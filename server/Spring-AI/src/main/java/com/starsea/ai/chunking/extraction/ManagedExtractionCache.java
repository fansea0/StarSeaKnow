package com.starsea.ai.chunking.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.domain.FileTextExtraction;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileTextExtractionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ManagedExtractionCache {

    private static final Logger log = LoggerFactory.getLogger(ManagedExtractionCache.class);
    private final FileTextExtractionMapper mapper;
    private final DocumentTextExtractorRegistry registry;
    private final ObjectMapper objectMapper;
    private final Path root;
    private final DurableCleanupJournal cleanupJournal;
    private final FileMapper fileMapper;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrentHashMap<CacheKey, LockEntry> keyLocks = new ConcurrentHashMap<>();

    @Autowired
    public ManagedExtractionCache(FileTextExtractionMapper mapper,
                                  DocumentTextExtractorRegistry registry,
                                  ObjectMapper objectMapper,
                                  @Value("${chunking.extraction.cache-root:${user.dir}/data/extraction-cache}") String root,
                                  DurableCleanupJournal cleanupJournal,
                                  FileMapper fileMapper,
                                  PlatformTransactionManager transactionManager) {
        this(mapper, registry, objectMapper, Path.of(root), cleanupJournal, fileMapper,
                new TransactionTemplate(transactionManager));
    }

    public ManagedExtractionCache(FileTextExtractionMapper mapper,
                                  DocumentTextExtractorRegistry registry,
                                  ObjectMapper objectMapper,
                                  Path root) {
        this(mapper, registry, objectMapper, root,
                new DurableCleanupJournal(objectMapper, root, root), null, null);
    }

    ManagedExtractionCache(FileTextExtractionMapper mapper,
                           DocumentTextExtractorRegistry registry,
                           ObjectMapper objectMapper,
                           Path root,
                           DurableCleanupJournal cleanupJournal) {
        this(mapper, registry, objectMapper, root, cleanupJournal, null, null);
    }

    ManagedExtractionCache(FileTextExtractionMapper mapper,
                           DocumentTextExtractorRegistry registry,
                           ObjectMapper objectMapper,
                           Path root,
                           DurableCleanupJournal cleanupJournal,
                           FileMapper fileMapper,
                           TransactionTemplate transactionTemplate) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.cleanupJournal = Objects.requireNonNull(cleanupJournal, "cleanupJournal");
        this.fileMapper = fileMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ExtractedText getOrExtract(long tenantId, long fileId, String sourceHash,
                                      Path source, String suppliedType) {
        requirePositive(tenantId, "tenantId");
        requirePositive(fileId, "fileId");
        if (sourceHash == null || !sourceHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sourceHash must be a SHA-256 hex digest");
        }
        requireRecoveredKey(tenantId, fileId);
        try (KeyLease ignored = acquire(new CacheKey(tenantId, fileId))) {
            ExtractionOutcome outcome = transactionTemplate == null
                    ? getOrExtractLocked(tenantId, fileId, sourceHash, source, suppliedType)
                    : transactionTemplate.execute(status -> {
                        requireDatabaseOwner(tenantId, fileId);
                        return getOrExtractLocked(tenantId, fileId, sourceHash, source, suppliedType);
                    });
            if (outcome == null) throw new IllegalStateException("Extraction transaction returned no result");
            finishCommittedOutcome(outcome, tenantId, fileId);
            return outcome.extracted();
        }
    }

    private ExtractionOutcome getOrExtractLocked(long tenantId, long fileId, String sourceHash,
                                                  Path source, String suppliedType) {
        ExtractionCapability capability = registry.probe(source, suppliedType);
        if (!capability.available()) {
            throw new DocumentTextExtractor.ExtractionException(
                    DocumentTextExtractor.FailureReason.UNSUPPORTED, capability.reason());
        }
        FileTextExtraction existing = mapper.findScoped(tenantId, fileId);
        if (existing != null) {
            validateManagedPaths(existing, tenantId, fileId);
        }
        ExtractedText hit = readValid(existing, tenantId, fileId, sourceHash, capability);
        if (hit != null) {
            return new ExtractionOutcome(hit, null, null, null);
        }

        ExtractedText extracted = registry.extract(source, capability);
        Path directory = scopedDirectory(tenantId, fileId);
        String generation = UUID.randomUUID().toString();
        Path textPath = checkedManagedPath(
                directory.resolve("text-" + generation + ".txt"), directory);
        Path mapPath = checkedManagedPath(
                directory.resolve("source-map-" + generation + ".json"), directory);
        UUID generationObligation = cleanupJournal.prepareGeneration(tenantId, fileId, List.of(
                cleanupJournal.cacheTarget(textPath), cleanupJournal.cacheTarget(mapPath)));
        try {
            writePairAtomically(directory, textPath, mapPath, extracted, sourceHash);
        } catch (RuntimeException failure) {
            cleanupNewPair(textPath, mapPath, generationObligation, failure);
            throw failure;
        }

        FileTextExtraction replacement = metadata(tenantId, fileId, sourceHash, extracted,
                textPath, mapPath);
        UUID replacedObligation = null;
        try {
            if (existing != null) {
                replacedObligation = cleanupJournal.prepareGeneration(tenantId, fileId, List.of(
                        cleanupJournal.cacheTarget(Path.of(existing.getManagedTextPath())),
                        cleanupJournal.cacheTarget(Path.of(existing.getSourceMapPath()))));
            }
            int changed = existing == null ? mapper.insert(replacement) : mapper.updateScoped(replacement);
            if (changed != 1) {
                throw new IllegalStateException("Extraction cache metadata was not saved");
            }
        } catch (RuntimeException failure) {
            cleanupNewPair(textPath, mapPath, generationObligation, failure);
            if (replacedObligation != null) {
                try { cleanupJournal.complete(replacedObligation); }
                catch (RuntimeException pending) { failure.addSuppressed(pending); }
            }
            throw failure;
        }
        return new ExtractionOutcome(extracted, generationObligation, existing, replacedObligation);
    }

    private void requireDatabaseOwner(long tenantId, long fileId) {
        if (fileMapper == null || fileMapper.selectScopedForUpdate(tenantId, fileId) == null) {
            throw new DocumentTextExtractor.ExtractionException(
                    DocumentTextExtractor.FailureReason.CORRUPT,
                    "Source file is no longer available");
        }
    }

    private void finishCommittedOutcome(ExtractionOutcome outcome, long tenantId, long fileId) {
        if (outcome.generationObligation() != null) {
            try {
                cleanupJournal.complete(outcome.generationObligation());
            } catch (RuntimeException pending) {
                log.warn("Cleanup obligation {} retained for tenant {} file {}",
                        outcome.generationObligation(), tenantId, fileId);
            }
        }
        if (outcome.replaced() != null) {
            deleteExistingPair(outcome.replaced(), tenantId, fileId, outcome.replacedObligation());
        }
    }

    public void deleteManagedFiles(long tenantId, long fileId) {
        requireRecoveredKey(tenantId, fileId);
        if (transactionTemplate == null) {
            quarantineManagedFiles(tenantId, fileId).commit();
            return;
        }
        ManagedFileQuarantine quarantine = transactionTemplate.execute(status -> {
            requireDatabaseOwner(tenantId, fileId);
            return quarantineManagedFiles(tenantId, fileId);
        });
        if (quarantine != null) quarantine.commit();
    }

    public ManagedFileQuarantine quarantineManagedFiles(long tenantId, long fileId) {
        requirePositive(tenantId, "tenantId");
        requirePositive(fileId, "fileId");
        KeyLease lease = acquire(new CacheKey(tenantId, fileId));
        List<QuarantinedFile> moved = new ArrayList<>();
        try {
            FileTextExtraction existing = mapper.findScoped(tenantId, fileId);
            if (existing == null) {
                return new Quarantine(List.of(), tenantId, fileId, lease);
            }
            Path directory = scopedDirectory(tenantId, fileId, false);
            Path textPath = checkedManagedPath(Path.of(existing.getManagedTextPath()), directory);
            Path mapPath = checkedManagedPath(Path.of(existing.getSourceMapPath()), directory);
            planQuarantineIfPresent(textPath, moved);
            planQuarantineIfPresent(mapPath, moved);
            UUID obligationId = moved.isEmpty() ? null : cleanupJournal.prepareFileDeletion(
                    tenantId, fileId, moved.stream()
                            .map(file -> cleanupJournal.cacheMove(file.original(), file.quarantined()))
                            .toList());
            try {
                for (QuarantinedFile file : moved) {
                    cleanupJournal.movePrepared(file.original(), file.quarantined(),
                            DurableCleanupJournal.RootKind.CACHE);
                    file.markMoved();
                }
            } catch (IOException failure) {
                IllegalStateException wrapped = new IllegalStateException(
                        "Managed extraction files could not be quarantined", failure);
                restoreMoved(moved, wrapped);
                if (obligationId != null && wrapped.getSuppressed().length == 0) {
                    cleanupJournal.complete(obligationId);
                }
                throw wrapped;
            }
            return new Quarantine(List.copyOf(moved), tenantId, fileId, lease, obligationId);
        } catch (RuntimeException failure) {
            restoreMoved(moved, failure);
            lease.close();
            throw failure;
        }
    }

    public UUID persistSourceCleanup(long tenantId, long fileId, Path sourceQuarantine) {
        return cleanupJournal.persist(tenantId, fileId,
                List.of(cleanupJournal.sourceTarget(sourceQuarantine)));
    }

    private void requireRecoveredKey(long tenantId, long fileId) {
        if (!cleanupJournal.recoverKey(tenantId, fileId)) {
            throw new IllegalStateException("Managed cleanup for this file is still pending");
        }
    }

    public PreparedSourceDeletion prepareSourceDeletion(long tenantId, long fileId,
                                                        Path source, Path quarantine) {
        DurableCleanupJournal.ManagedMove move = cleanupJournal.sourceMove(
                source, quarantine, tenantId, fileId);
        UUID obligationId = cleanupJournal.prepareFileDeletion(tenantId, fileId, List.of(move));
        return new PreparedSourceDeletion(obligationId, source, quarantine);
    }

    public void movePreparedSource(PreparedSourceDeletion deletion) throws IOException {
        cleanupJournal.movePrepared(deletion.source(), deletion.quarantine(),
                DurableCleanupJournal.RootKind.UPLOAD);
    }

    public void restorePreparedSource(PreparedSourceDeletion deletion) throws IOException {
        cleanupJournal.movePrepared(deletion.quarantine(), deletion.source(),
                DurableCleanupJournal.RootKind.UPLOAD);
    }

    public void deletePreparedSource(PreparedSourceDeletion deletion) throws IOException {
        cleanupJournal.deleteManaged(deletion.quarantine(), DurableCleanupJournal.RootKind.UPLOAD);
    }

    public void deletePreparedSourcePath(Path quarantine) throws IOException {
        cleanupJournal.deleteManaged(quarantine, DurableCleanupJournal.RootKind.UPLOAD);
    }

    public void deleteUploadedSource(Path source) throws IOException {
        cleanupJournal.deleteManaged(source, DurableCleanupJournal.RootKind.UPLOAD);
    }

    public void completeCleanup(UUID obligationId) {
        cleanupJournal.complete(obligationId);
    }

    public void recoverManagedFiles(long tenantId, long fileId) {
        requireRecoveredKey(tenantId, fileId);
    }

    private KeyLease acquire(CacheKey key) {
        LockEntry entry = keyLocks.compute(key, (ignored, current) -> {
            LockEntry selected = current == null ? new LockEntry() : current;
            selected.users++;
            return selected;
        });
        entry.lock.lock();
        return new KeyLease(key, entry);
    }

    private void release(CacheKey key, LockEntry entry) {
        entry.lock.unlock();
        keyLocks.compute(key, (ignored, current) -> {
            if (current != entry) return current;
            entry.users--;
            return entry.users == 0 ? null : entry;
        });
    }

    private ExtractedText readValid(FileTextExtraction row, long tenantId, long fileId,
                                    String hash, ExtractionCapability capability) {
        if (row == null || !Objects.equals(row.getTenantId(), tenantId)
                || !Objects.equals(row.getFileId(), fileId)
                || !hash.equals(row.getSourceHash())
                || !capability.extractorId().equals(row.getExtractorId())
                || !capability.extractorVersion().equals(row.getExtractorVersion())
                || !capability.detectedMediaType().equals(row.getMediaType())) {
            return null;
        }
        Path directory = scopedDirectory(tenantId, fileId, false);
        Path textPath = checkedManagedPath(Path.of(row.getManagedTextPath()), directory);
        Path mapPath = checkedManagedPath(Path.of(row.getSourceMapPath()), directory);
        if (!Files.isRegularFile(textPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(mapPath, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            long maximumUtf8Bytes = Math.multiplyExact(row.getCharacterCount(), 4L);
            if (Files.size(textPath) > maximumUtf8Bytes) {
                return null;
            }
            String text = Files.readString(textPath, StandardCharsets.UTF_8);
            long count = text.codePointCount(0, text.length());
            if (!Objects.equals(row.getCharacterCount(), count)) {
                return null;
            }
            CacheSidecar sidecar = objectMapper.readValue(mapPath.toFile(), CacheSidecar.class);
            if (!row.getSourceHash().equals(sidecar.sourceHash())
                    || !row.getExtractorId().equals(sidecar.extractorId())
                    || !row.getExtractorVersion().equals(sidecar.extractorVersion())
                    || !row.getMediaType().equals(sidecar.mediaType())
                    || !sha256(text).equals(sidecar.textHash())) {
                return null;
            }
            if (sidecar.sourceSpans().stream().anyMatch(span -> span.textEnd() > text.length())) {
                return null;
            }
            return new ExtractedText(text, row.getMediaType(), row.getExtractorId(),
                    row.getExtractorVersion(), sidecar.sourceSpans(), sidecar.metadata());
        } catch (IOException exception) {
            return null;
        }
    }

    private void writePairAtomically(Path directory, Path textPath, Path mapPath,
                                     ExtractedText extracted, String sourceHash) {
        Path textTemp = null;
        Path mapTemp = null;
        boolean textCommitted = false;
        boolean mapCommitted = false;
        try {
            textTemp = Files.createTempFile(directory, ".text-", ".tmp");
            mapTemp = Files.createTempFile(directory, ".source-map-", ".tmp");
            Files.writeString(textTemp, extracted.text(), StandardCharsets.UTF_8);
            objectMapper.writeValue(mapTemp.toFile(),
                    new CacheSidecar(sourceHash, extracted.extractorId(), extracted.extractorVersion(),
                            extracted.mediaType(), sha256(extracted.text()),
                            extracted.sourceSpans(), extracted.metadata()));
            moveAtomically(textTemp, textPath);
            textTemp = null;
            textCommitted = true;
            moveAtomically(mapTemp, mapPath);
            mapTemp = null;
            mapCommitted = true;
        } catch (IOException exception) {
            if (textCommitted || mapCommitted) {
                try {
                    cleanupJournal.deleteManaged(textPath, DurableCleanupJournal.RootKind.CACHE);
                    cleanupJournal.deleteManaged(mapPath, DurableCleanupJournal.RootKind.CACHE);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw new IllegalStateException("Extraction cache files could not be saved", exception);
        } finally {
            deleteTemporary(textTemp);
            deleteTemporary(mapTemp);
        }
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Managed extraction storage does not support atomic replacement", exception);
        }
    }

    private void deleteExistingPair(FileTextExtraction row, long tenantId, long fileId,
                                    UUID obligationId) {
        Path directory = scopedDirectory(tenantId, fileId, false);
        Path textPath = checkedManagedPath(Path.of(row.getManagedTextPath()), directory);
        Path mapPath = checkedManagedPath(Path.of(row.getSourceMapPath()), directory);
        boolean cleanupFailed = false;
        try {
            deleteReplacedManagedFile(textPath);
        } catch (IOException ignored) {
            cleanupFailed = true;
        }
        try {
            deleteReplacedManagedFile(mapPath);
        } catch (IOException ignored) {
            cleanupFailed = true;
        }
        if (cleanupFailed) {
            log.warn("Cleanup obligation {} retained for tenant {} file {}",
                    obligationId, tenantId, fileId);
        } else {
            cleanupJournal.complete(obligationId);
        }
    }

    protected void deleteReplacedManagedFile(Path path) throws IOException {
        cleanupJournal.deleteManaged(path, DurableCleanupJournal.RootKind.CACHE);
    }

    private void validateManagedPaths(FileTextExtraction row, long tenantId, long fileId) {
        Path directory = scopedDirectory(tenantId, fileId, false);
        checkedManagedPath(Path.of(row.getManagedTextPath()), directory);
        checkedManagedPath(Path.of(row.getSourceMapPath()), directory);
    }

    private void cleanupNewPair(Path textPath, Path mapPath, UUID obligationId,
                                RuntimeException failure) {
        boolean cleanupFailed = false;
        try {
            deleteNewManagedFile(textPath);
        } catch (IOException ignored) {
            cleanupFailed = true;
        }
        try {
            deleteNewManagedFile(mapPath);
        } catch (IOException ignored) {
            cleanupFailed = true;
        }
        if (cleanupFailed) {
            CleanupPendingException pending = new CleanupPendingException(obligationId);
            failure.addSuppressed(pending);
        } else {
            try {
                cleanupJournal.complete(obligationId);
            } catch (RuntimeException pending) {
                failure.addSuppressed(new CleanupPendingException(obligationId));
            }
        }
    }

    protected void deleteNewManagedFile(Path path) throws IOException {
        cleanupJournal.deleteManaged(path, DurableCleanupJournal.RootKind.CACHE);
    }

    private void deleteTemporary(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Unable to remove an extraction cache temporary file");
        }
    }

    private Path scopedDirectory(long tenantId, long fileId) {
        return scopedDirectory(tenantId, fileId, true);
    }

    private Path scopedDirectory(long tenantId, long fileId, boolean create) {
        Path realRoot = secureRoot();
        Path tenantDirectory = secureDirectory(root.resolve(Long.toString(tenantId)), realRoot, create);
        return secureDirectory(tenantDirectory.resolve(Long.toString(fileId)), realRoot, create);
    }

    private Path secureRoot() {
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(root);
            } else {
                Files.createDirectories(root);
                requireSafeDirectory(root);
            }
            return root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Managed extraction root cannot be secured", exception);
        }
    }

    private Path secureDirectory(Path directory, Path realRoot, boolean create) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) {
                    return directory.toAbsolutePath().normalize();
                }
                Files.createDirectory(directory);
            }
            requireSafeDirectory(directory);
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(realRoot)) {
                throw new IllegalStateException("Managed extraction directory escapes its root");
            }
            return directory.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new IllegalStateException("Managed extraction directory cannot be secured", exception);
        }
    }

    private void requireSafeDirectory(Path directory) {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Managed extraction directory is not a safe directory");
        }
    }

    private Path checkedManagedPath(Path candidate, Path requiredParent) {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path parent = requiredParent.toAbsolutePath().normalize();
        if (!normalized.startsWith(parent)) {
            throw new IllegalStateException("Managed extraction path escapes its tenant/file directory");
        }
        Path realRoot = secureRoot();
        Path securedParent = secureDirectory(parent, realRoot, false);
        if (!Files.exists(securedParent, LinkOption.NOFOLLOW_LINKS)) {
            return normalized;
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IllegalStateException("Managed extraction file cannot be a symbolic link");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Path realFile = normalized.toRealPath();
                Path realParent = securedParent.toRealPath();
                if (!realFile.startsWith(realRoot) || !realFile.startsWith(realParent)) {
                    throw new IllegalStateException("Managed extraction file escapes its root");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Managed extraction file cannot be secured", exception);
            }
        }
        return normalized;
    }

    private void planQuarantineIfPresent(Path original, List<QuarantinedFile> moved) {
        if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path quarantined = original.resolveSibling(original.getFileName()
                + ".deleting-" + UUID.randomUUID());
        moved.add(new QuarantinedFile(original, quarantined));
    }

    private void restoreMoved(List<QuarantinedFile> moved, Throwable failure) {
        for (int index = moved.size() - 1; index >= 0; index--) {
            QuarantinedFile file = moved.get(index);
            if (!file.moved()) continue;
            try {
                cleanupJournal.movePrepared(file.quarantined(), file.original(),
                        DurableCleanupJournal.RootKind.CACHE);
            } catch (IOException restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    public interface ManagedFileQuarantine {
        void commit();
        void restore();
    }

    private final class Quarantine implements ManagedFileQuarantine {
        private final List<QuarantinedFile> files;
        private final long tenantId;
        private final long fileId;
        private final KeyLease lease;
        private boolean completed;

        private final UUID obligationId;

        private Quarantine(List<QuarantinedFile> files, long tenantId, long fileId, KeyLease lease) {
            this(files, tenantId, fileId, lease, null);
        }

        private Quarantine(List<QuarantinedFile> files, long tenantId, long fileId, KeyLease lease,
                           UUID obligationId) {
            this.files = files;
            this.tenantId = tenantId;
            this.fileId = fileId;
            this.lease = lease;
            this.obligationId = obligationId;
        }

        @Override
        public synchronized void commit() {
            if (completed) return;
            RuntimeException failure = null;
            try {
                for (QuarantinedFile file : files) {
                    try {
                        deleteQuarantined(file.quarantined());
                    } catch (IOException exception) {
                        if (failure == null) {
                            failure = new IllegalStateException(
                                    "Managed extraction quarantine could not be removed", exception);
                        } else {
                            failure.addSuppressed(exception);
                        }
                    }
                }
                if (failure != null) {
                    if (obligationId != null) throw new CleanupPendingException(obligationId);
                } else if (obligationId != null) {
                    cleanupJournal.complete(obligationId);
                }
            } finally {
                completed = true;
                lease.close();
            }
        }

        @Override
        public synchronized void restore() {
            if (completed) return;
            RuntimeException failure = new IllegalStateException(
                    "Managed extraction quarantine could not be restored");
            try {
                restoreMoved(new ArrayList<>(files), failure);
                if (failure.getSuppressed().length > 0) {
                    log.warn("Cleanup obligation {} remains pending for tenant {} file {}",
                            obligationId, tenantId, fileId);
                    throw failure;
                }
                if (obligationId != null) cleanupJournal.complete(obligationId);
            } finally {
                completed = true;
                lease.close();
            }
        }
    }

    private static final class QuarantinedFile {
        private final Path original;
        private final Path quarantined;
        private boolean moved;
        private QuarantinedFile(Path original, Path quarantined) {
            this.original = original;
            this.quarantined = quarantined;
        }
        private Path original() { return original; }
        private Path quarantined() { return quarantined; }
        private boolean moved() { return moved; }
        private void markMoved() { moved = true; }
    }

    public record PreparedSourceDeletion(UUID obligationId, Path source, Path quarantine) {}

    private record ExtractionOutcome(ExtractedText extracted, UUID generationObligation,
                                     FileTextExtraction replaced, UUID replacedObligation) {}

    protected void deleteQuarantined(Path path) throws IOException {
        cleanupJournal.deleteManaged(path, DurableCleanupJournal.RootKind.CACHE);
    }

    public static final class CleanupPendingException extends IllegalStateException {
        private final UUID obligationId;

        public CleanupPendingException(UUID obligationId) {
            super("Managed extraction cleanup obligation " + obligationId + " is pending");
            this.obligationId = obligationId;
        }

        public UUID obligationId() {
            return obligationId;
        }
    }

    private record CacheKey(long tenantId, long fileId) {
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private final class KeyLease implements AutoCloseable {
        private final CacheKey key;
        private final LockEntry entry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private KeyLease(CacheKey key, LockEntry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(key, entry);
            }
        }
    }

    private FileTextExtraction metadata(long tenantId, long fileId, String sourceHash,
                                        ExtractedText extracted, Path textPath, Path mapPath) {
        FileTextExtraction row = new FileTextExtraction();
        row.setTenantId(tenantId);
        row.setFileId(fileId);
        row.setSourceHash(sourceHash);
        row.setExtractorId(extracted.extractorId());
        row.setExtractorVersion(extracted.extractorVersion());
        row.setMediaType(extracted.mediaType());
        row.setManagedTextPath(textPath.toString());
        row.setSourceMapPath(mapPath.toString());
        row.setCharacterCount((long) extracted.text().codePointCount(0, extracted.text().length()));
        return row;
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CacheSidecar(String sourceHash, String extractorId, String extractorVersion,
                                String mediaType, String textHash,
                                List<SourceSpan> sourceSpans, Map<String, Object> metadata) {
        private CacheSidecar {
            sourceSpans = sourceSpans == null ? List.of() : List.copyOf(sourceSpans);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
