package com.starsea.ai.chunking.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.domain.FileTextExtraction;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileTextExtractionMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Durable recovery journal for cache generations and file-deletion quarantines. */
@Component
public class DurableCleanupJournal {
    private static final Logger log = LoggerFactory.getLogger(DurableCleanupJournal.class);
    private static final int MAX_RECORD_BYTES = 64 * 1024;
    private static final int STARTUP_RETRY_LIMIT = 32;
    private static final long MAX_RETRY_DELAY_MILLIS = 60_000;
    private static final String DIRECTORY_NAME = ".cleanup-journal";

    private final ObjectMapper objectMapper;
    private final Path cacheRoot;
    private final Path uploadRoot;
    private final OwnerResolver ownerResolver;
    private final TransactionTemplate ownerTransaction;
    private final ReentrantLock journalLock = new ReentrantLock();
    private final AtomicLong dueSequence = new AtomicLong();
    private final java.util.Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService retryExecutor;

    @Autowired
    public DurableCleanupJournal(
            ObjectMapper objectMapper,
            @Value("${chunking.extraction.cache-root:${user.dir}/data/extraction-cache}") String cacheRoot,
            @Value("${file.uploadPath:${user.dir}/src/main/resources/file/}") String uploadRoot,
            FileMapper fileMapper,
            FileTextExtractionMapper extractionMapper,
            PlatformTransactionManager transactionManager) {
        this(objectMapper, Path.of(cacheRoot), Path.of(uploadRoot),
                databaseResolver(fileMapper, extractionMapper),
                new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager")));
    }

    DurableCleanupJournal(ObjectMapper objectMapper, Path cacheRoot, Path uploadRoot) {
        this(objectMapper, cacheRoot, uploadRoot, (tenantId, fileId) -> OwnerSnapshot.absent());
    }

    DurableCleanupJournal(ObjectMapper objectMapper, Path cacheRoot, Path uploadRoot,
                          OwnerResolver ownerResolver) {
        this(objectMapper, cacheRoot, uploadRoot, ownerResolver, null);
    }

    private DurableCleanupJournal(ObjectMapper objectMapper, Path cacheRoot, Path uploadRoot,
                                  OwnerResolver ownerResolver,
                                  TransactionTemplate ownerTransaction) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.cacheRoot = normalizedRoot(cacheRoot);
        this.uploadRoot = normalizedRoot(uploadRoot);
        this.ownerResolver = Objects.requireNonNull(ownerResolver, "ownerResolver");
        this.ownerTransaction = ownerTransaction;
    }

    private static OwnerResolver databaseResolver(FileMapper fileMapper,
                                                  FileTextExtractionMapper extractionMapper) {
        Objects.requireNonNull(fileMapper, "fileMapper");
        Objects.requireNonNull(extractionMapper, "extractionMapper");
        return (tenantId, fileId) -> {
            com.starsea.ai.domain.File owner = fileMapper.selectScopedForUpdate(tenantId, fileId);
            if (owner == null) return OwnerSnapshot.absent();
            FileTextExtraction extraction = extractionMapper.findScoped(tenantId, fileId);
            return new OwnerSnapshot(owner.getPath(),
                    extraction == null ? null : extraction.getManagedTextPath(),
                    extraction == null ? null : extraction.getSourceMapPath());
        };
    }

    @PostConstruct
    void startRetryWorker() {
        retryPending(STARTUP_RETRY_LIMIT);
        retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "extraction-cleanup-journal");
            thread.setDaemon(true);
            return thread;
        });
        retryExecutor.scheduleWithFixedDelay(() -> retryPending(STARTUP_RETRY_LIMIT),
                30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopRetryWorker() {
        if (retryExecutor != null) retryExecutor.shutdownNow();
    }

    public CleanupTarget cacheTarget(Path path) { return managedTarget(RootKind.CACHE, path, false); }
    public CleanupTarget sourceTarget(Path path) { return managedTarget(RootKind.UPLOAD, path, false); }

    public UUID persist(long tenantId, long fileId, List<CleanupTarget> targets) {
        return persist(new JournalRecord(UUID.randomUUID(), tenantId, fileId, Action.DELETE,
                validateTargets(targets), List.of(), 0, nextDue()));
    }

    public UUID prepareGeneration(long tenantId, long fileId, List<CleanupTarget> targets) {
        return persist(new JournalRecord(UUID.randomUUID(), tenantId, fileId, Action.GENERATION,
                validateTargets(targets), List.of(), 0, nextDue()));
    }

    public UUID prepareFileDeletion(long tenantId, long fileId, List<ManagedMove> moves) {
        if (moves == null || moves.isEmpty() || moves.size() > 8) {
            throw new IllegalArgumentException("Prepared deletion requires managed moves");
        }
        List<ManagedMove> validated = moves.stream().map(this::validateMove).distinct().toList();
        return persist(new JournalRecord(UUID.randomUUID(), tenantId, fileId, Action.FILE_DELETION,
                validated.stream().map(ManagedMove::quarantined).toList(), validated, 0, nextDue()));
    }

    public ManagedMove sourceMove(Path original, Path quarantined, long tenantId, long fileId) {
        CleanupTarget originalTarget = managedTarget(RootKind.UPLOAD, original, true);
        CleanupTarget quarantineTarget = managedTarget(RootKind.UPLOAD, quarantined, false);
        requireUploadQuarantineScope(quarantineTarget, tenantId, fileId);
        OwnerSnapshot owner = ownerResolver.resolve(tenantId, fileId);
        if (owner.sourcePath() != null && !samePath(resolve(originalTarget), owner.sourcePath())) {
            throw new IllegalArgumentException("Cleanup source owner does not match");
        }
        return new ManagedMove(originalTarget, quarantineTarget);
    }

    public ManagedMove cacheMove(Path original, Path quarantined) {
        return new ManagedMove(managedTarget(RootKind.CACHE, original, true),
                managedTarget(RootKind.CACHE, quarantined, false));
    }

    public void movePrepared(Path original, Path quarantined, RootKind kind) throws IOException {
        secureMove(kind, relative(kind, original), relative(kind, quarantined));
    }

    public void deleteManaged(Path target, RootKind kind) throws IOException {
        secureDelete(kind, relative(kind, target));
    }

    public void complete(UUID obligationId) {
        Objects.requireNonNull(obligationId, "obligationId");
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            Files.deleteIfExists(directory.resolve(fileName(obligationId)));
            syncDirectoryIfSupported(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("Cleanup obligation could not be completed", failure);
        } finally {
            journalLock.unlock();
        }
    }

    /** Runs at most {@code limit} due obligations; failures are deferred for fair progress. */
    public void retryPending(int limit) {
        if (limit < 1) return;
        List<Pending> selected = new ArrayList<>();
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            List<Pending> pending = new ArrayList<>();
            try (var entries = Files.list(directory)) {
                for (Path path : entries.filter(item -> isPendingName(item.getFileName().toString())).toList()) {
                    Pending parsed = readPending(path, directory);
                    if (parsed != null) pending.add(parsed);
                }
            }
            long now = System.currentTimeMillis();
            pending.stream()
                    .filter(item -> item.record().nextAttemptAtMillis() <= now)
                    .sorted(Comparator.comparingLong((Pending item) -> item.record().nextAttemptAtMillis())
                            .thenComparing(item -> item.record().obligationId()))
                    .filter(item -> inFlight.add(item.record().obligationId()))
                    .limit(limit)
                    .forEach(selected::add);
        } catch (IOException | RuntimeException failure) {
            log.warn("Cleanup journal retry could not enumerate pending obligations");
        } finally {
            journalLock.unlock();
        }
        for (Pending pending : selected) {
            try { replay(pending); }
            finally { inFlight.remove(pending.record().obligationId()); }
        }
    }

    /** Operation-triggered recovery for one owner key, independent of global retry ordering. */
    public boolean recoverKey(long tenantId, long fileId) {
        List<Pending> matching = new ArrayList<>();
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "cleanup-*.json")) {
                for (Path path : entries) {
                    Pending parsed = readPending(path, directory);
                    if (parsed != null && parsed.record().tenantId() == tenantId
                            && parsed.record().fileId() == fileId) {
                        if (inFlight.add(parsed.record().obligationId())) matching.add(parsed);
                        if (matching.size() == STARTUP_RETRY_LIMIT) break;
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Cleanup recovery remains pending for tenant {} file {}", tenantId, fileId);
            return false;
        } finally {
            journalLock.unlock();
        }
        matching.sort(Comparator.comparing(item -> item.record().obligationId()));
        for (Pending pending : matching) {
            try { replay(pending); }
            finally { inFlight.remove(pending.record().obligationId()); }
        }
        return !hasPendingKey(tenantId, fileId);
    }

    private boolean hasPendingKey(long tenantId, long fileId) {
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "cleanup-*.json")) {
                for (Path path : entries) {
                    Pending parsed = readPending(path, directory);
                    if (parsed != null && parsed.record().tenantId() == tenantId
                            && parsed.record().fileId() == fileId) return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException failure) {
            return true;
        } finally {
            journalLock.unlock();
        }
    }

    Path journalDirectory() { return cacheRoot.resolve(DIRECTORY_NAME); }

    protected void deleteTarget(Path target) throws IOException {
        RootKind kind = target.toAbsolutePath().normalize().startsWith(cacheRoot)
                ? RootKind.CACHE : RootKind.UPLOAD;
        secureDelete(kind, relative(kind, target));
    }

    private UUID persist(JournalRecord record) {
        if (record.tenantId() <= 0 || record.fileId() <= 0) {
            throw new IllegalArgumentException("cleanup identifiers must be positive");
        }
        validateOwnership(record.targets(), record.tenantId(), record.fileId());
        record.moves().forEach(this::validateMove);
        journalLock.lock();
        try {
            writeRecord(record, secureJournalDirectory());
            return record.obligationId();
        } catch (IOException failure) {
            throw new IllegalStateException("Cleanup obligation could not be persisted", failure);
        } finally {
            journalLock.unlock();
        }
    }

    private void writeRecord(JournalRecord record, Path directory) throws IOException {
        byte[] serialized = objectMapper.writeValueAsBytes(record);
        if (serialized.length > MAX_RECORD_BYTES) throw new IOException("Cleanup obligation too large");
        Path temporary = Files.createTempFile(directory, ".cleanup-", ".tmp");
        Path destination = directory.resolve(fileName(record.obligationId()));
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
    }

    private Pending readPending(Path journalFile, Path directory) {
        UUID obligationId = obligationIdFromName(journalFile.getFileName().toString());
        try {
            if (Files.isSymbolicLink(journalFile)
                    || !Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(journalFile) > MAX_RECORD_BYTES) throw new IOException("Invalid record");
            JournalRecord record = objectMapper.readValue(journalFile.toFile(), JournalRecord.class);
            Action action = record.action() == null ? Action.DELETE : record.action();
            List<CleanupTarget> targets = validateTargets(record.targets());
            List<ManagedMove> moves = record.moves() == null ? List.of()
                    : record.moves().stream().map(this::validateMove).toList();
            JournalRecord normalized = new JournalRecord(record.obligationId(), record.tenantId(),
                    record.fileId(), action, targets, moves, record.attempts(),
                    record.nextAttemptAtMillis());
            if (!obligationId.equals(normalized.obligationId())
                    || normalized.tenantId() <= 0 || normalized.fileId() <= 0
                    || action == Action.FILE_DELETION && moves.isEmpty()) {
                throw new IllegalArgumentException("Invalid cleanup obligation");
            }
            validateOwnership(targets, normalized.tenantId(), normalized.fileId());
            return new Pending(journalFile, normalized);
        } catch (IOException | RuntimeException invalid) {
            try { reject(journalFile, directory, obligationId); }
            catch (IOException ignored) { log.warn("Cleanup obligation {} could not be rejected", obligationId); }
            return null;
        }
    }

    private void replay(Pending pending) {
        JournalRecord record = pending.record();
        try {
            if (ownerTransaction != null && record.action() != Action.DELETE) {
                ownerTransaction.executeWithoutResult(status -> {
                    try {
                        replayAction(record);
                    } catch (IOException failure) {
                        throw new ReplayFailure(failure);
                    }
                });
            } else {
                replayAction(record);
            }
            complete(record.obligationId());
            log.info("Cleanup obligation {} completed for tenant {} file {}",
                    record.obligationId(), record.tenantId(), record.fileId());
        } catch (IOException | RuntimeException failure) {
            defer(record);
            log.warn("Cleanup obligation {} remains pending for tenant {} file {}",
                    record.obligationId(), record.tenantId(), record.fileId());
        }
    }

    private void replayAction(JournalRecord record) throws IOException {
        switch (record.action()) {
            case DELETE -> deleteTargets(record.targets());
            case GENERATION -> replayGeneration(record);
            case FILE_DELETION -> replayFileDeletion(record);
        }
    }

    private void replayGeneration(JournalRecord record) throws IOException {
        OwnerSnapshot owner = ownerResolver.resolve(record.tenantId(), record.fileId());
        boolean textReferenced = record.targets().stream()
                .anyMatch(target -> samePath(resolve(target), owner.managedTextPath()));
        boolean mapReferenced = record.targets().stream()
                .anyMatch(target -> samePath(resolve(target), owner.sourceMapPath()));
        if (record.targets().size() == 2 && textReferenced && mapReferenced) return;
        deleteTargets(record.targets());
    }

    private void replayFileDeletion(JournalRecord record) throws IOException {
        OwnerSnapshot owner = ownerResolver.resolve(record.tenantId(), record.fileId());
        if (!owner.exists()) {
            deleteTargets(record.targets());
            return;
        }
        for (ManagedMove move : record.moves()) {
            Path original = resolve(move.original());
            if (move.original().root() == RootKind.UPLOAD) {
                if (!samePath(original, owner.sourcePath())) throw new IOException("Source owner mismatch");
            } else if (!samePath(original, owner.managedTextPath())
                    && !samePath(original, owner.sourceMapPath())) {
                throw new IOException("Cache owner mismatch");
            }
            Path quarantined = resolve(move.quarantined());
            if (Files.exists(quarantined, LinkOption.NOFOLLOW_LINKS)) {
                secureMove(move.quarantined().root(), Path.of(move.quarantined().relativePath()),
                        Path.of(move.original().relativePath()));
            }
        }
    }

    private void deleteTargets(List<CleanupTarget> targets) throws IOException {
        for (CleanupTarget target : targets) deleteTarget(resolve(target));
    }

    private void defer(JournalRecord record) {
        int attempts = Math.min(30, record.attempts() + 1);
        long delay = Math.min(MAX_RETRY_DELAY_MILLIS, 1L << Math.min(attempts - 1, 15));
        JournalRecord deferred = new JournalRecord(record.obligationId(), record.tenantId(),
                record.fileId(), record.action(), record.targets(), record.moves(), attempts,
                System.currentTimeMillis() + delay);
        journalLock.lock();
        try {
            Path directory = secureJournalDirectory();
            if (Files.exists(directory.resolve(fileName(record.obligationId())),
                    LinkOption.NOFOLLOW_LINKS)) {
                writeRecord(deferred, directory);
            }
        } catch (IOException ignored) {
            log.warn("Cleanup obligation {} retry state could not be persisted", record.obligationId());
        } finally {
            journalLock.unlock();
        }
    }

    private CleanupTarget managedTarget(RootKind kind, Path path, boolean original) {
        return validateTarget(new CleanupTarget(kind, relative(kind, path).toString()), original);
    }

    private Path relative(RootKind kind, Path path) {
        Objects.requireNonNull(path, "path");
        Path root = rootFor(kind);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalArgumentException("Cleanup target is outside its managed root");
        }
        return root.relativize(normalized);
    }

    private List<CleanupTarget> validateTargets(List<CleanupTarget> targets) {
        if (targets == null || targets.isEmpty() || targets.size() > 8) {
            throw new IllegalArgumentException("Cleanup obligation has an invalid target count");
        }
        return targets.stream().map(target -> validateTarget(target, false)).distinct().toList();
    }

    private ManagedMove validateMove(ManagedMove move) {
        if (move == null || move.original() == null || move.quarantined() == null
                || move.original().root() != move.quarantined().root()) {
            throw new IllegalArgumentException("Cleanup move is incomplete");
        }
        return new ManagedMove(validateTarget(move.original(), true),
                validateTarget(move.quarantined(), false));
    }

    private CleanupTarget validateTarget(CleanupTarget target, boolean original) {
        if (target == null || target.root() == null || target.relativePath() == null
                || target.relativePath().isBlank()) throw new IllegalArgumentException("Incomplete target");
        Path relative;
        try { relative = Path.of(target.relativePath()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid target"); }
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || !relative.normalize().equals(relative)) throw new IllegalArgumentException("Invalid target");
        if (!original) {
            String filename = relative.getFileName().toString();
            int marker = filename.lastIndexOf(".deleting-");
            if (marker >= 1) requireUuid(filename.substring(marker + 10));
            else if (target.root() == RootKind.CACHE) requireCacheGenerationName(filename);
            else throw new IllegalArgumentException("Source target is not quarantined");
        }
        return new CleanupTarget(target.root(), relative.toString());
    }

    private void validateOwnership(List<CleanupTarget> targets, long tenantId, long fileId) {
        for (CleanupTarget target : targets) {
            Path relative = Path.of(target.relativePath());
            int scope = target.root() == RootKind.UPLOAD && relative.getNameCount() > 0
                    && ".deleting".equals(relative.getName(0).toString()) ? 1 : 0;
            if (relative.getNameCount() <= scope
                    || !Long.toString(tenantId).equals(relative.getName(scope).toString())) {
                throw new IllegalArgumentException("Cleanup target has the wrong tenant scope");
            }
            if (target.root() == RootKind.CACHE
                    && (relative.getNameCount() != 3
                    || !Long.toString(fileId).equals(relative.getName(1).toString()))) {
                throw new IllegalArgumentException("Cleanup cache target has the wrong file scope");
            }
            if (target.root() == RootKind.UPLOAD && scope == 1) {
                requireUploadQuarantineScope(target, tenantId, fileId);
            }
        }
    }

    private void requireUploadQuarantineScope(CleanupTarget target, long tenantId, long fileId) {
        Path relative = Path.of(target.relativePath());
        if (relative.getNameCount() < 4 || !".deleting".equals(relative.getName(0).toString())
                || !Long.toString(tenantId).equals(relative.getName(1).toString())
                || !Long.toString(fileId).equals(relative.getName(2).toString())) {
            throw new IllegalArgumentException("Cleanup source target has the wrong file scope");
        }
    }

    private void requireCacheGenerationName(String filename) {
        if (filename.startsWith("text-") && filename.endsWith(".txt")) {
            requireUuid(filename.substring(5, filename.length() - 4));
        } else if (filename.startsWith("source-map-") && filename.endsWith(".json")) {
            requireUuid(filename.substring(11, filename.length() - 5));
        } else throw new IllegalArgumentException("Cache target is not a generation");
    }

    private void requireUuid(String value) {
        try { UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid cleanup id"); }
    }

    private Path resolve(CleanupTarget target) {
        Path root = rootFor(target.root());
        Path result = root.resolve(target.relativePath()).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw new IllegalArgumentException("Escaped root");
        return result;
    }

    private boolean samePath(Path actual, String expected) {
        if (expected == null) return false;
        try { return actual.equals(Path.of(expected).toAbsolutePath().normalize()); }
        catch (RuntimeException invalid) { return false; }
    }

    private void secureDelete(RootKind kind, Path relative) throws IOException {
        try (SecureHandle parent = secureParent(kind, relative)) {
            if (parent == null) return;
            try { parent.directory().deleteFile(relative.getFileName()); }
            catch (java.nio.file.NoSuchFileException ignored) { }
        } catch (SecureHandlesUnavailable unavailable) {
            safeFallbackDelete(kind, relative);
        }
    }

    private void secureMove(RootKind kind, Path source, Path destination) throws IOException {
        createManagedParents(kind, destination.getParent());
        try (SecureHandle from = secureParent(kind, source);
             SecureHandle to = secureParent(kind, destination)) {
            if (from == null || to == null) throw new IOException("Managed move endpoint is absent");
            from.directory().move(source.getFileName(), to.directory(), destination.getFileName());
        } catch (SecureHandlesUnavailable unavailable) {
            safeFallbackMove(kind, source, destination);
        }
    }

    private void safeFallbackDelete(RootKind kind, Path relative) throws IOException {
        Path target = safeFallbackPath(kind, relative);
        if (Files.isSymbolicLink(target)) throw new IOException("Managed target is unsafe");
        Files.deleteIfExists(target);
    }

    private void safeFallbackMove(RootKind kind, Path source, Path destination) throws IOException {
        Path sourcePath = safeFallbackPath(kind, source);
        Path destinationPath = safeFallbackPath(kind, destination);
        if (Files.isSymbolicLink(sourcePath) || Files.isSymbolicLink(destinationPath)) {
            throw new IOException("Managed target is unsafe");
        }
        moveManagedAtomically(sourcePath, destinationPath);
    }

    /**
     * Platforms without SecureDirectoryStream use only owner-controlled, non-writable managed
     * parents and an atomic leaf operation. This removes the parent-swap capability required for
     * a traversal race rather than relying on a check of the leaf alone.
     */
    private Path safeFallbackPath(RootKind kind, Path relative) throws IOException {
        Path root = rootFor(kind);
        Path target = root.resolve(relative).normalize();
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        requireOwnerControlled(root);
        Path current = root;
        for (Path name : relative.getParent()) {
            current = current.resolve(name.toString());
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                    || !current.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realRoot)) {
                throw new IOException("Managed directory is unsafe");
            }
            requireOwnerControlled(current);
        }
        return target;
    }

    private void requireOwnerControlled(Path directory) throws IOException {
        try {
            var permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) {
                throw new IOException("Managed directory is not owner controlled");
            }
        } catch (UnsupportedOperationException ignored) {
            throw new IOException("Secure managed-directory operations are unavailable");
        }
    }

    private void createManagedParents(RootKind kind, Path relativeParent) throws IOException {
        ensureSafeRoot(rootFor(kind));
        Path current = rootFor(kind);
        if (relativeParent == null) return;
        for (Path name : relativeParent) {
            current = current.resolve(name.toString());
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(current);
            if (Files.isSymbolicLink(current)
                    || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Managed directory is unsafe");
            }
        }
    }

    private SecureHandle secureParent(RootKind kind, Path relative) throws IOException {
        if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.getParent() == null) {
            throw new IOException("Managed target is invalid");
        }
        Path root = rootFor(kind);
        ensureSafeRoot(root);
        DirectoryStream<Path> opened = Files.newDirectoryStream(root);
        if (!(opened instanceof SecureDirectoryStream<Path> secure)) {
            opened.close();
            throw new SecureHandlesUnavailable();
        }
        List<SecureDirectoryStream<Path>> handles = new ArrayList<>();
        handles.add(secure);
        SecureDirectoryStream<Path> current = secure;
        try {
            for (Path name : relative.getParent()) {
                current = current.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS);
                handles.add(current);
            }
            return new SecureHandle(current, handles);
        } catch (java.nio.file.NoSuchFileException absent) {
            closeHandles(handles);
            return null;
        } catch (IOException failure) {
            closeHandles(handles);
            throw failure;
        }
    }

    private void closeHandles(List<SecureDirectoryStream<Path>> handles) {
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).close(); } catch (IOException ignored) { }
        }
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
                throw new IOException("Cleanup journal directory is unsafe");
            }
            return directory;
        } catch (IOException failure) {
            throw new IllegalStateException("Cleanup journal storage cannot be secured", failure);
        }
    }

    private void ensureSafeRoot(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(root);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cleanup root is unsafe");
        }
    }

    private void reject(Path journalFile, Path directory, UUID obligationId) throws IOException {
        moveAtomically(journalFile, directory.resolve("cleanup-" + obligationId + ".rejected"));
        syncDirectoryIfSupported(directory);
        log.warn("Cleanup obligation {} was rejected", obligationId);
    }

    private void moveAtomically(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("Cleanup journal requires atomic replacement", failure);
        }
    }

    private void moveManagedAtomically(Path source, Path destination) throws IOException {
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("Managed storage requires atomic moves", failure);
        }
    }

    protected void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException ignored) {
            // Explicitly unsupported directory fsync may be downgraded.
        }
    }

    private void syncDirectoryIfSupported(Path directory) throws IOException { syncDirectory(directory); }
    private Path rootFor(RootKind kind) { return kind == RootKind.CACHE ? cacheRoot : uploadRoot; }
    private static Path normalizedRoot(Path root) {
        return Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }
    private static String fileName(UUID id) { return "cleanup-" + id + ".json"; }
    private static boolean isPendingName(String name) {
        try { obligationIdFromName(name); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static UUID obligationIdFromName(String name) {
        if (!name.startsWith("cleanup-") || !name.endsWith(".json")) {
            throw new IllegalArgumentException("Not a cleanup obligation name");
        }
        return UUID.fromString(name.substring(8, name.length() - 5));
    }

    private long nextDue() {
        return dueSequence.updateAndGet(previous -> Math.max(System.currentTimeMillis(), previous + 1));
    }

    public enum RootKind { CACHE, UPLOAD }
    public enum Action { DELETE, FILE_DELETION, GENERATION }
    public record CleanupTarget(RootKind root, String relativePath) {}
    public record ManagedMove(CleanupTarget original, CleanupTarget quarantined) {}
    @FunctionalInterface interface OwnerResolver { OwnerSnapshot resolve(long tenantId, long fileId); }
    public record OwnerSnapshot(String sourcePath, String managedTextPath, String sourceMapPath) {
        public static OwnerSnapshot absent() { return new OwnerSnapshot(null, null, null); }
        boolean exists() { return sourcePath != null; }
    }
    public record JournalRecord(UUID obligationId, long tenantId, long fileId, Action action,
                                List<CleanupTarget> targets, List<ManagedMove> moves,
                                int attempts, long nextAttemptAtMillis) {}
    private record Pending(Path path, JournalRecord record) {}
    private static final class SecureHandlesUnavailable extends IOException {}
    private static final class ReplayFailure extends RuntimeException {
        private ReplayFailure(IOException cause) { super(cause); }
    }

    private final class SecureHandle implements AutoCloseable {
        private final SecureDirectoryStream<Path> directory;
        private final List<SecureDirectoryStream<Path>> handles;
        private SecureHandle(SecureDirectoryStream<Path> directory,
                             List<SecureDirectoryStream<Path>> handles) {
            this.directory = directory;
            this.handles = handles;
        }
        private SecureDirectoryStream<Path> directory() { return directory; }
        @Override public void close() { closeHandles(handles); }
    }
}
