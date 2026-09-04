package com.starsea.ai.chunking.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Durable, bounded journal for physical files which remain after their owning database row has
 * committed its deletion. Journal records contain root-relative managed names only.
 */
@Component
public class DurableCleanupJournal {

    private static final Logger log = LoggerFactory.getLogger(DurableCleanupJournal.class);
    private static final int MAX_RECORD_BYTES = 64 * 1024;
    private static final int STARTUP_RETRY_LIMIT = 32;
    private static final String DIRECTORY_NAME = ".cleanup-journal";

    private final ObjectMapper objectMapper;
    private final Path cacheRoot;
    private final Path uploadRoot;
    private final ReentrantLock journalLock = new ReentrantLock();

    @Autowired
    public DurableCleanupJournal(
            ObjectMapper objectMapper,
            @Value("${chunking.extraction.cache-root:${user.dir}/data/extraction-cache}")
            String cacheRoot,
            @Value("${file.uploadPath:${user.dir}/src/main/resources/file/}")
            String uploadRoot) {
        this(objectMapper, Path.of(cacheRoot), Path.of(uploadRoot));
    }

    DurableCleanupJournal(ObjectMapper objectMapper, Path cacheRoot, Path uploadRoot) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.cacheRoot = normalizedRoot(cacheRoot);
        this.uploadRoot = normalizedRoot(uploadRoot);
    }

    @PostConstruct
    void retryAtStartup() {
        retryPending(STARTUP_RETRY_LIMIT);
    }

    public CleanupTarget cacheTarget(Path path) {
        return managedTarget(RootKind.CACHE, path);
    }

    public CleanupTarget sourceTarget(Path path) {
        return managedTarget(RootKind.UPLOAD, path);
    }

    public UUID persist(long tenantId, long fileId, List<CleanupTarget> targets) {
        if (tenantId <= 0 || fileId <= 0) {
            throw new IllegalArgumentException("cleanup identifiers must be positive");
        }
        List<CleanupTarget> validated = validateTargets(targets);
        validateOwnership(validated, tenantId, fileId);
        UUID obligationId = UUID.randomUUID();
        JournalRecord record = new JournalRecord(obligationId, tenantId, fileId, validated);
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            byte[] serialized = objectMapper.writeValueAsBytes(record);
            if (serialized.length > MAX_RECORD_BYTES) {
                throw new IllegalStateException("Cleanup obligation exceeds its storage limit");
            }
            Path temporary = Files.createTempFile(directory, ".cleanup-", ".tmp");
            Path destination = directory.resolve(fileName(obligationId));
            try {
                try (FileChannel channel = FileChannel.open(temporary,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer bytes = ByteBuffer.wrap(serialized);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                moveAtomically(temporary, destination);
                syncDirectoryIfSupported(directory);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return obligationId;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Cleanup obligation " + obligationId + " could not be persisted");
        } finally {
            journalLock.unlock();
        }
    }

    /** Runs at most {@code limit} obligations and never lets one bad record stop later retries. */
    public void retryPending(int limit) {
        if (limit < 1) return;
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            List<Path> pending;
            try (var entries = Files.list(directory)) {
                pending = entries
                        .filter(path -> isPendingName(path.getFileName().toString()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(limit)
                        .toList();
            }
            for (Path journalFile : pending) replay(journalFile, directory);
        } catch (IOException | RuntimeException failure) {
            log.warn("Cleanup journal retry could not enumerate pending obligations");
        } finally {
            journalLock.unlock();
        }
    }

    Path journalDirectory() {
        return cacheRoot.resolve(DIRECTORY_NAME);
    }

    protected void deleteTarget(Path target) throws IOException {
        Files.deleteIfExists(target);
    }

    private void replay(Path journalFile, Path directory) {
        UUID fileId = obligationIdFromName(journalFile.getFileName().toString());
        try {
            if (Files.isSymbolicLink(journalFile)
                    || !Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(journalFile) > MAX_RECORD_BYTES) {
                reject(journalFile, directory, fileId);
                return;
            }
            JournalRecord record = objectMapper.readValue(journalFile.toFile(), JournalRecord.class);
            if (record.obligationId() == null || !record.obligationId().equals(fileId)
                    || record.tenantId() <= 0 || record.fileId() <= 0) {
                reject(journalFile, directory, fileId);
                return;
            }
            List<CleanupTarget> targets;
            try {
                targets = validateTargets(record.targets());
                validateOwnership(targets, record.tenantId(), record.fileId());
            } catch (RuntimeException invalid) {
                reject(journalFile, directory, fileId);
                return;
            }
            for (CleanupTarget target : targets) {
                Path resolved = resolveForDeletion(target);
                deleteTarget(resolved);
            }
            Files.deleteIfExists(journalFile);
            syncDirectoryIfSupported(directory);
            log.info("Cleanup obligation {} completed for tenant {} file {}",
                    fileId, record.tenantId(), record.fileId());
        } catch (IOException | RuntimeException failure) {
            log.warn("Cleanup obligation {} remains pending", fileId);
        }
    }

    private CleanupTarget managedTarget(RootKind kind, Path path) {
        Objects.requireNonNull(path, "path");
        Path root = rootFor(kind);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalArgumentException("Cleanup target is outside its managed root");
        }
        CleanupTarget target = new CleanupTarget(kind, root.relativize(normalized).toString());
        validateTarget(target);
        return target;
    }

    private List<CleanupTarget> validateTargets(List<CleanupTarget> targets) {
        if (targets == null || targets.isEmpty() || targets.size() > 8) {
            throw new IllegalArgumentException("Cleanup obligation has an invalid target count");
        }
        return targets.stream().map(this::validateTarget).distinct().toList();
    }

    private CleanupTarget validateTarget(CleanupTarget target) {
        if (target == null || target.root() == null || target.relativePath() == null
                || target.relativePath().isBlank()) {
            throw new IllegalArgumentException("Cleanup target is incomplete");
        }
        Path relative;
        try {
            relative = Path.of(target.relativePath());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Cleanup target is invalid");
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || !relative.normalize().equals(relative)) {
            throw new IllegalArgumentException("Cleanup target is not a normalized relative path");
        }
        String filename = relative.getFileName().toString();
        int marker = filename.lastIndexOf(".deleting-");
        if (marker >= 1) {
            requireUuid(filename.substring(marker + ".deleting-".length()),
                    "Cleanup target quarantine identifier is invalid");
        } else if (target.root() == RootKind.CACHE) {
            requireCacheGenerationName(filename);
        } else {
            throw new IllegalArgumentException("Cleanup source target is not quarantined");
        }
        return new CleanupTarget(target.root(), relative.toString());
    }

    private void validateOwnership(List<CleanupTarget> targets, long tenantId, long fileId) {
        for (CleanupTarget target : targets) {
            Path relative = Path.of(target.relativePath());
            if (relative.getNameCount() < 2
                    || !relative.getName(0).toString().equals(Long.toString(tenantId))) {
                throw new IllegalArgumentException("Cleanup target has the wrong tenant scope");
            }
            if (target.root() == RootKind.CACHE
                    && (relative.getNameCount() != 3
                    || !relative.getName(1).toString().equals(Long.toString(fileId)))) {
                throw new IllegalArgumentException("Cleanup cache target has the wrong file scope");
            }
        }
    }

    private void requireCacheGenerationName(String filename) {
        if (filename.startsWith("text-") && filename.endsWith(".txt")) {
            requireUuid(filename.substring("text-".length(), filename.length() - ".txt".length()),
                    "Cleanup cache generation identifier is invalid");
            return;
        }
        if (filename.startsWith("source-map-") && filename.endsWith(".json")) {
            requireUuid(filename.substring("source-map-".length(), filename.length() - ".json".length()),
                    "Cleanup cache generation identifier is invalid");
            return;
        }
        throw new IllegalArgumentException("Cleanup cache target is not a managed generation");
    }

    private void requireUuid(String value, String message) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(message);
        }
    }

    private Path resolveForDeletion(CleanupTarget target) throws IOException {
        Path root = rootFor(target.root());
        ensureSafeRoot(root);
        Path resolved = root.resolve(target.relativePath()).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("Cleanup target escaped its managed root");
        }
        Path current = resolved.getParent();
        while (current != null && !current.equals(root)) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Cleanup target parent is not managed");
            }
            current = current.getParent();
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new IOException("Cleanup target is not a managed file");
        }
        return resolved;
    }

    private Path secureJournalDirectory() {
        try {
            ensureSafeRoot(cacheRoot);
            Path directory = journalDirectory();
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory);
                syncDirectoryIfSupported(cacheRoot);
            }
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cleanup journal directory is not managed");
            }
            return directory;
        } catch (IOException exception) {
            throw new IllegalStateException("Cleanup journal storage cannot be secured");
        }
    }

    private void ensureSafeRoot(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cleanup root is not managed");
        }
    }

    private void reject(Path journalFile, Path directory, UUID obligationId) throws IOException {
        Path rejected = directory.resolve("cleanup-" + obligationId + ".rejected");
        moveAtomically(journalFile, rejected);
        syncDirectoryIfSupported(directory);
        log.warn("Cleanup obligation {} was rejected", obligationId);
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Cleanup journal requires atomic replacement");
        }
    }

    private void syncDirectoryIfSupported(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Some supported platforms do not permit opening directory handles for fsync.
        }
    }

    private Path rootFor(RootKind rootKind) {
        return rootKind == RootKind.CACHE ? cacheRoot : uploadRoot;
    }

    private static Path normalizedRoot(Path root) {
        return Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    private static String fileName(UUID obligationId) {
        return "cleanup-" + obligationId + ".json";
    }

    private static boolean isPendingName(String name) {
        try {
            obligationIdFromName(name);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static UUID obligationIdFromName(String name) {
        if (!name.startsWith("cleanup-") || !name.endsWith(".json")) {
            throw new IllegalArgumentException("Not a cleanup obligation name");
        }
        return UUID.fromString(name.substring("cleanup-".length(), name.length() - ".json".length()));
    }

    public enum RootKind { CACHE, UPLOAD }

    public record CleanupTarget(RootKind root, String relativePath) {}

    public record JournalRecord(UUID obligationId, long tenantId, long fileId,
                                List<CleanupTarget> targets) {}
}
