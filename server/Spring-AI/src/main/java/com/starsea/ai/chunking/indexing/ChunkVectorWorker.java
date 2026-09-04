package com.starsea.ai.chunking.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.general.UnicodeText;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicyResolver;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Component
public class ChunkVectorWorker {

    private static final Logger log = LoggerFactory.getLogger(ChunkVectorWorker.class);

    private final FileProcessingMapper processingMapper;
    private final FileMapper fileMapper;
    private final DocumentChunkMapper chunkMapper;
    private final FileProcessingService stateService;
    private final ChunkContextEnricher enricher;
    private final TokenCounter tokenCounter;
    private final ChunkVectorGateway gateway;
    private final TransactionTemplate transactions;
    private final ChunkRuntimePolicyResolver runtimePolicyResolver;
    private final SourceHashReader sourceHashReader;
    private final ChunkVectorLifecycle vectorLifecycle;

    @Autowired
    public ChunkVectorWorker(FileProcessingMapper processingMapper,
                             FileMapper fileMapper,
                             DocumentChunkMapper chunkMapper,
                             FileProcessingService stateService,
                             ChunkContextEnricher enricher,
                             TokenCounter tokenCounter,
                             ChunkVectorGateway gateway,
                             TransactionTemplate transactions,
                             ChunkRuntimePolicyResolver runtimePolicyResolver,
                             DurableChunkVectorLifecycle vectorLifecycle) {
        this(processingMapper, fileMapper, chunkMapper, stateService, enricher,
                tokenCounter, gateway, transactions, runtimePolicyResolver,
                SourceHashing::sha256, vectorLifecycle);
    }

    public ChunkVectorWorker(FileProcessingMapper processingMapper,
                             FileMapper fileMapper,
                             DocumentChunkMapper chunkMapper,
                             FileProcessingService stateService,
                             ChunkContextEnricher enricher,
                             TokenCounter tokenCounter,
                             ChunkVectorGateway gateway,
                             TransactionTemplate transactions,
                             ChunkRuntimePolicyResolver runtimePolicyResolver) {
        this(processingMapper, fileMapper, chunkMapper, stateService, enricher,
                tokenCounter, gateway, transactions, runtimePolicyResolver,
                SourceHashing::sha256, directLifecycle(gateway));
    }

    ChunkVectorWorker(FileProcessingMapper processingMapper,
                      FileMapper fileMapper,
                      DocumentChunkMapper chunkMapper,
                      FileProcessingService stateService,
                      ChunkContextEnricher enricher,
                      TokenCounter tokenCounter,
                      ChunkVectorGateway gateway,
                      TransactionTemplate transactions,
                      ChunkRuntimePolicyResolver runtimePolicyResolver,
                      SourceHashReader sourceHashReader) {
        this(processingMapper, fileMapper, chunkMapper, stateService, enricher,
                tokenCounter, gateway, transactions, runtimePolicyResolver,
                sourceHashReader, directLifecycle(gateway));
    }

    ChunkVectorWorker(FileProcessingMapper processingMapper,
                      FileMapper fileMapper,
                      DocumentChunkMapper chunkMapper,
                      FileProcessingService stateService,
                      ChunkContextEnricher enricher,
                      TokenCounter tokenCounter,
                      ChunkVectorGateway gateway,
                      TransactionTemplate transactions,
                      ChunkRuntimePolicyResolver runtimePolicyResolver,
                      SourceHashReader sourceHashReader,
                      ChunkVectorLifecycle vectorLifecycle) {
        this.processingMapper = processingMapper;
        this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper;
        this.stateService = stateService;
        this.enricher = enricher;
        this.tokenCounter = tokenCounter;
        this.gateway = gateway;
        this.transactions = transactions;
        this.runtimePolicyResolver = Objects.requireNonNull(runtimePolicyResolver, "runtimePolicyResolver");
        this.sourceHashReader = Objects.requireNonNull(sourceHashReader, "sourceHashReader");
        this.vectorLifecycle = Objects.requireNonNull(vectorLifecycle, "vectorLifecycle");
    }

    public ChunkVectorWorker(FileProcessingMapper processingMapper,
                             FileMapper fileMapper,
                             DocumentChunkMapper chunkMapper,
                             FileProcessingService stateService,
                             ChunkContextEnricher enricher,
                             TokenCounter tokenCounter,
                             ChunkVectorGateway gateway,
                             TransactionTemplate transactions) {
        this(processingMapper, fileMapper, chunkMapper, stateService, enricher,
                tokenCounter, gateway, transactions, new ChunkRuntimePolicyResolver());
    }

    public void vectorizeBatch(BatchJob job) {
        java.util.concurrent.atomic.AtomicBoolean vectorMutationStarted =
                new java.util.concurrent.atomic.AtomicBoolean();
        vectorLifecycle.writerStarted(job.obligations());
        try {
            PreparedBatch prepared = prepared(job.file(), job.maxTokens(), job.processing(),
                    job.prepared(), job.allChunks(), job.chunks());
            validatePrepared(prepared);
            validateBatchBeforeIo(job);
            validateSourceBytes(job.file(), job.sourceHash(), job.processing());
            vectorLifecycle.withWriterFence(job.obligations(), () -> {
                writeVectors(prepared, job.vectorIds(), vectorMutationStarted);
                validateSourceBytes(job.file(), job.sourceHash(), job.processing());
            });
            transactions.executeWithoutResult(status -> completeBatch(job, prepared));
            vectorLifecycle.drain();
        } catch (RuntimeException failure) {
            if (!vectorMutationStarted.get()
                    || enqueueFailed(job.obligations(), failure)) {
                restoreBatch(job, failure);
            }
            drainAfterFailure(failure);
        } finally {
            finishWriter(job.obligations());
        }
    }

    public void vectorizeSingle(SingleJob job) {
        java.util.concurrent.atomic.AtomicBoolean vectorMutationStarted =
                new java.util.concurrent.atomic.AtomicBoolean();
        vectorLifecycle.writerStarted(job.obligations());
        try {
            PreparedBatch prepared = prepared(job.file(), job.maxTokens(), job.processing(),
                    job.prepared(), job.allChunks(), List.of(job.chunk()));
            validatePrepared(prepared);
            validateSingleBeforeIo(job);
            validateSourceBytes(job.file(), job.sourceHash(), job.processing());
            vectorLifecycle.withWriterFence(job.obligations(), () -> {
                writeVectors(prepared, job.vectorIds(), vectorMutationStarted);
                validateSourceBytes(job.file(), job.sourceHash(), job.processing());
            });
            transactions.executeWithoutResult(status -> completeSingle(job, prepared));
            vectorLifecycle.drain();
        } catch (RuntimeException failure) {
            if (!vectorMutationStarted.get()
                    || enqueueFailed(job.obligations(), failure)) {
                restoreSingle(job, failure);
            }
            drainAfterFailure(failure);
            throw failure;
        } finally {
            finishWriter(job.obligations());
        }
    }

    private void finishWriter(Collection<ChunkVectorLifecycle.CleanupObligation> obligations) {
        try {
            vectorLifecycle.writerFinished(obligations);
        } catch (RuntimeException failure) {
            log.error("Unable to acknowledge completed vector writer", failure);
        }
    }

    public void failBatchDispatch(BatchJob job, RuntimeException failure) {
        restoreBatch(job, failure);
        drainAfterFailure(failure);
    }

    public void failSingleDispatch(SingleJob job, RuntimeException failure) {
        restoreSingle(job, failure);
        drainAfterFailure(failure);
    }

    private PreparedBatch enrich(List<ChunkSnapshot> allSnapshots,
                                 List<ChunkSnapshot> targets,
                                 int maxTokens,
                                 FileSnapshot file) {
        List<DocumentChunk> detachedChunks = allSnapshots.stream()
                .map(ChunkSnapshot::detached)
                .toList();
        List<EnrichedChunk> enriched = enricher.enrich(detachedChunks, maxTokens);
        Map<Long, EnrichedChunk> enrichedById = enriched.stream()
                .filter(value -> value != null && value.chunk() != null && value.chunk().getId() != null)
                .collect(Collectors.toMap(value -> value.chunk().getId(), value -> value));
        List<EnrichedChunk> selected = new ArrayList<>(targets.size());
        for (ChunkSnapshot target : targets) {
            EnrichedChunk value = enrichedById.get(target.id());
            if (value == null) {
                throw ChunkingException.conflict("The context enhancer omitted an indexing chunk");
            }
            selected.add(value);
        }
        selected.sort(Comparator.comparing(value -> value.chunk().getPosition(),
                Comparator.nullsLast(Integer::compareTo)));
        return new PreparedBatch(file, maxTokens, List.copyOf(selected));
    }

    private PreparedBatch prepared(FileSnapshot file, int maxTokens,
                                   ProcessingSnapshot processing,
                                   List<PreparedChunk> supplied,
                                   List<ChunkSnapshot> allSnapshots,
                                   List<ChunkSnapshot> targets) {
        if (supplied == null || supplied.isEmpty()) {
            return enrich(allSnapshots, targets, maxTokens, file);
        }
        Map<Long, ChunkSnapshot> targetsById = targets.stream()
                .collect(Collectors.toMap(ChunkSnapshot::id, value -> value));
        Map<Long, ChunkSnapshot> remainingTargets = new LinkedHashMap<>(targetsById);
        for (PreparedChunk value : supplied) {
            ChunkSnapshot target = remainingTargets.remove(value.id());
            if (target == null
                    || !Objects.equals(value.publicId(), target.publicId())
                    || value.tenantId() != target.tenantId()
                    || value.knowledgeId() != target.knowledgeId()
                    || value.fileId() != target.fileId()
                    || !Objects.equals(value.position(), target.position())
                    || !Objects.equals(value.sectionPath(), target.sectionPath())) {
                throw ChunkingException.conflict("Prepared index text does not match its chunk snapshot");
            }
        }
        if (!remainingTargets.isEmpty() || supplied.size() != targets.size()) {
            throw ChunkingException.conflict("Prepared index batch does not match its chunk snapshots");
        }
        Integer maxCharacters = processing != null
                && processing.runtimePolicy().strategyConfig() instanceof GeneralChunkConfig general
                ? general.maxCharacters() : null;
        return new PreparedBatch(file, maxTokens, maxCharacters,
                supplied.stream().map(value -> {
                            EnrichedChunk enriched = value.enriched();
                            enriched.chunk().setContent(targetsById.get(value.id()).content());
                            return enriched;
                        })
                        .sorted(Comparator.comparing(value -> value.chunk().getPosition(),
                                Comparator.nullsLast(Integer::compareTo)))
                        .toList());
    }

    private void validateBatchBeforeIo(BatchJob job) {
        if (job.processing() == null) return;
        transactions.executeWithoutResult(status -> validateJob(
                job.tenantId(), job.knowledgeId(), job.fileId(), job.fileLockVersion(),
                job.sourceHash(), job.file(), job.processing(), job.allChunks(),
                job.chunks()));
    }

    private void validateSingleBeforeIo(SingleJob job) {
        if (job.processing() == null) return;
        transactions.executeWithoutResult(status -> validateJob(
                job.tenantId(), job.knowledgeId(), job.fileId(), job.fileLockVersion(),
                job.sourceHash(), job.file(), job.processing(), job.allChunks(),
                List.of(job.chunk())));
    }

    private void validateJob(long tenantId, long knowledgeId, long fileId, int fileLockVersion,
                             String sourceHash, FileSnapshot file,
                             ProcessingSnapshot expectedProcessing,
                             List<ChunkSnapshot> allSnapshots,
                             List<ChunkSnapshot> targets) {
        FileProcessing processing = requireLockedProcessing(tenantId, knowledgeId, fileId,
                PipelineState.VECTORIZING, fileLockVersion);
        requireJobSnapshot(processing, sourceHash, expectedProcessing);
        requireFileSnapshot(fileId, file);
        Map<Long, DocumentChunk> currentById = byId(
                chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId));
        requireExactChunkSet(currentById, allSnapshots);
        Set<Long> targetIds = targets.stream().map(ChunkSnapshot::id).collect(Collectors.toSet());
        for (ChunkSnapshot snapshot : allSnapshots) {
            DocumentChunk current = currentById.get(snapshot.id());
            if (targetIds.contains(snapshot.id())) {
                requireSnapshot(current, snapshot);
            } else {
                requireRelatedSnapshot(current, snapshot);
            }
        }
    }

    private void writeVectors(PreparedBatch prepared, Map<Long, UUID> vectorIds,
                              java.util.concurrent.atomic.AtomicBoolean mutationStarted) {
        List<ChunkVectorGateway.VectorDocument> documents = new ArrayList<>(prepared.chunks().size());
        for (EnrichedChunk enriched : prepared.chunks()) {
            DocumentChunk chunk = enriched.chunk();
            if (!UnicodeText.isBlank(chunk.getContent())) {
                documents.add(new ChunkVectorGateway.VectorDocument(
                        Objects.requireNonNull(vectorIds.get(chunk.getId()), "vectorId"),
                        chunk.getPublicId(),
                        enriched.indexContent(),
                        chunk.getTenantId(),
                        chunk.getKnowledgeId(),
                        chunk.getFileId(),
                        prepared.file().publicId(),
                        value(chunk.getPosition()),
                        prepared.file().fileType(),
                        chunk.getSectionPath()));
            }
        }
        for (ChunkVectorGateway.VectorDocument document : documents) {
            int exactTokens = tokenCounter.count(document.indexContent());
            if (exactTokens > prepared.maximum() || exactTokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
                throw ChunkingException.unprocessable("Final index text exceeds the token budget",
                        Map.of("tokenCount", exactTokens, "maxTokens", prepared.maximum()));
            }
        }
        if (prepared.maxCharacters() != null) {
            for (ChunkVectorGateway.VectorDocument document : documents) {
                int characters = document.indexContent().codePointCount(0, document.indexContent().length());
                if (characters > prepared.maxCharacters()) {
                    throw ChunkingException.unprocessable("Final index text exceeds the character budget",
                            Map.of("characterCount", characters,
                                    "maxCharacters", prepared.maxCharacters()));
                }
            }
        }
        List<UUID> vectorIdsToReplace = prepared.chunks().stream()
                .map(enriched -> Objects.requireNonNull(
                        vectorIds.get(enriched.chunk().getId()), "vectorId"))
                .toList();
        mutationStarted.set(true);
        gateway.deleteAll(vectorIdsToReplace);
        if (!documents.isEmpty()) {
            gateway.add(List.copyOf(documents));
        }
    }

    private void validatePrepared(PreparedBatch prepared) {
        if (prepared.chunks().isEmpty()) {
            throw ChunkingException.conflict("Prepared vector batch is empty");
        }
        for (EnrichedChunk enriched : prepared.chunks()) {
            if (enriched.indexContent() == null
                    || (!UnicodeText.isBlank(enriched.chunk().getContent())
                    && UnicodeText.isBlank(enriched.indexContent()))) {
                throw ChunkingException.unprocessable("Prepared index text must not be blank");
            }
            int tokens = tokenCounter.count(enriched.indexContent());
            if (tokens > prepared.maximum() || tokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
                throw ChunkingException.unprocessable("Final index text exceeds the token budget",
                        Map.of("tokenCount", tokens, "maxTokens", prepared.maximum()));
            }
            if (prepared.maxCharacters() != null
                    && enriched.indexContent().codePointCount(0, enriched.indexContent().length())
                    > prepared.maxCharacters()) {
                throw ChunkingException.unprocessable("Final index text exceeds the character budget");
            }
        }
    }

    private void validateSourceBytes(FileSnapshot file, String expectedHash,
                                     ProcessingSnapshot processing) {
        if (processing == null) {
            return;
        }
        final String actualHash;
        try {
            actualHash = sourceHashReader.hash(Path.of(file.path()));
        } catch (InvalidPathException | NullPointerException exception) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        } catch (Exception exception) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        }
        if (!Objects.equals(expectedHash, actualHash)) {
            throw ChunkingException.sourceChanged();
        }
    }

    private void completeBatch(BatchJob job, PreparedBatch prepared) {
        FileProcessing processing = requireLockedProcessing(job.tenantId(), job.knowledgeId(),
                job.fileId(), PipelineState.VECTORIZING, job.fileLockVersion());
        requireJobSnapshot(processing, job.sourceHash(), job.processing(), job.maxTokens());
        requireFileSnapshot(job.fileId(), job.file());
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                job.fileId(), job.tenantId(), job.knowledgeId());
        Map<Long, DocumentChunk> currentById = byId(chunks);
        requireExactChunkSet(currentById, job.allChunks());
        for (EnrichedChunk enriched : prepared.chunks()) {
            ChunkSnapshot snapshot = job.snapshot(enriched.chunk().getId());
            DocumentChunk current = currentById.get(snapshot.id());
            requireSnapshot(current, snapshot);
            activate(current, snapshot, enriched, job.vectorId(snapshot.id()));
        }
        stateService.transition(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                PipelineState.COMPLETED, job.fileLockVersion());
    }

    private void completeSingle(SingleJob job, PreparedBatch prepared) {
        FileProcessing processing = requireLockedProcessing(job.tenantId(), job.knowledgeId(),
                job.fileId(), PipelineState.VECTORIZING, job.fileLockVersion());
        requireJobSnapshot(processing, job.sourceHash(), job.processing(), job.maxTokens());
        requireFileSnapshot(job.fileId(), job.file());
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                job.fileId(), job.tenantId(), job.knowledgeId());
        Map<Long, DocumentChunk> currentById = byId(chunks);
        requireExactChunkSet(currentById, job.allChunks());
        for (ChunkSnapshot snapshot : job.allChunks()) {
            if (!Objects.equals(snapshot.id(), job.chunk().id())) {
                requireRelatedSnapshot(currentById.get(snapshot.id()), snapshot);
            }
        }
        DocumentChunk current = currentById.get(job.chunk().id());
        requireSnapshot(current, job.chunk());
        activate(current, job.chunk(), prepared.chunks().get(0), job.vectorId(job.chunk().id()));
        boolean allActive = chunks.stream().allMatch(chunk ->
                Objects.equals(chunk.getId(), job.chunk().id())
                        || chunkStatus(chunk) == ChunkStatus.ACTIVE);
        PipelineState targetState = allActive ? PipelineState.COMPLETED : PipelineState.ADJUSTING;
        stateService.transition(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                targetState, job.fileLockVersion());
    }

    private void activate(DocumentChunk current, ChunkSnapshot snapshot, EnrichedChunk enriched,
                          UUID vectorId) {
        DocumentChunk patch = new DocumentChunk();
        patch.setStatus(ChunkStatus.ACTIVE.code());
        patch.setVectorId(vectorId);
        patch.setOverlapContent(enriched.overlapContent());
        patch.setOverlapSourceChunkId(enriched.overlapSourceChunkId());
        patch.setOverlapTokenCount(enriched.overlapTokenCount());
        patch.setOverlapCharacterCount(enriched.overlapCharacterCount());
        patch.setOverlapReductionReason(enriched.overlapReductionReason());
        patch.setIndexContent(enriched.indexContent());
        UpdateWrapper<DocumentChunk> update = chunkScope(current, snapshot)
                .set("last_error", null)
                .set("pending_vector_id", null)
                .set("indexing_lock_version", null)
                .setSql("lock_version = lock_version + 1");
        if (enriched.overlapContent() == null) {
            update.set("overlap_content", null);
        }
        if (enriched.overlapSourceChunkId() == null) {
            update.set("overlap_source_chunk_id", null);
        }
        if (enriched.overlapReductionReason() == null) {
            update.set("overlap_reduction_reason", null);
        }
        if (chunkMapper.update(patch, update) != 1) {
            throw ChunkingException.conflict("Chunk changed before vector activation");
        }
        if (snapshot.vectorId() != null && !snapshot.vectorId().equals(vectorId)) {
            vectorLifecycle.enqueue(List.of(new ChunkVectorLifecycle.CleanupObligation(
                    snapshot.vectorId(), snapshot.tenantId(), snapshot.knowledgeId(),
                    snapshot.fileId(), snapshot.publicId())));
        }
        current.setVectorId(vectorId);
        current.setPendingVectorId(null);
        current.setIndexingLockVersion(null);
    }

    private void restoreBatch(BatchJob job, RuntimeException original) {
        try {
            transactions.executeWithoutResult(status -> {
                FileProcessing processing = requireCompensatableProcessing(
                        job.tenantId(), job.knowledgeId(), job.fileId(), job.fileLockVersion());
                requireJobSnapshot(processing, job.sourceHash(), job.processing(), job.maxTokens());
                requireExactJobChunks(job.tenantId(), job.knowledgeId(), job.fileId(),
                        job.allChunks(), job.chunks());
                restoreIndexingTargets(job.tenantId(), job.knowledgeId(), job.fileId(),
                        job.chunks(), null);
                stateService.fail(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                        value(processing.getLockVersion()), 0, failureSummary(original));
            });
        } catch (RuntimeException compensationFailure) {
            addSuppressedUnlessSame(original, compensationFailure);
            log.error("Unable to restore failed batch vectorization for file {}",
                    job.fileId(), original);
        }
    }

    private void restoreSingle(SingleJob job, RuntimeException original) {
        try {
            String error = failureSummary(original);
            transactions.executeWithoutResult(status -> {
                FileProcessing processing = requireCompensatableProcessing(
                        job.tenantId(), job.knowledgeId(), job.fileId(), job.fileLockVersion());
                requireJobSnapshot(processing, job.sourceHash(), job.processing(), job.maxTokens());
                requireExactJobChunks(job.tenantId(), job.knowledgeId(), job.fileId(),
                        job.allChunks(), List.of(job.chunk()));
                restoreIndexingTargets(job.tenantId(), job.knowledgeId(), job.fileId(),
                        List.of(job.chunk()), error);
                stateService.recoverSingleVectorizationFailure(job.knowledgeId(), job.fileId(),
                        value(processing.getLockVersion()), error);
            });
        } catch (RuntimeException compensationFailure) {
            addSuppressedUnlessSame(original, compensationFailure);
            log.error("Unable to restore failed chunk reindex for file {} chunk {}",
                    job.fileId(), job.chunk().publicId(), original);
        }
    }

    private void restoreIndexingTargets(long tenantId, long knowledgeId, long fileId,
                                        List<ChunkSnapshot> snapshots, String lastError) {
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId);
        Map<Long, DocumentChunk> currentById = byId(chunks);
        for (ChunkSnapshot snapshot : snapshots) {
            DocumentChunk current = currentById.get(snapshot.id());
            requireSnapshot(current, snapshot);
            DocumentChunk patch = new DocumentChunk();
            patch.setStatus(ChunkStatus.DRAFT.code());
            patch.setLastError(lastError);
            UpdateWrapper<DocumentChunk> update = new UpdateWrapper<DocumentChunk>()
                    .eq("id", current.getId())
                    .eq("tenant_id", tenantId)
                    .eq("knowledge_id", knowledgeId)
                    .eq("file_id", fileId)
                    .eq("public_id", snapshot.publicId())
                    .eq("status", ChunkStatus.INDEXING.code())
                    .eq("lock_version", snapshot.lockVersion())
                    .eq("indexing_lock_version", snapshot.indexingLockVersion())
                    .set("overlap_content", null)
                    .set("overlap_source_chunk_id", null)
                    .set("overlap_token_count", 0)
                    .set("overlap_character_count", 0)
                    .set("overlap_reduction_reason", null)
                    .set("index_content", null)
                    .set("pending_vector_id", null)
                    .set("indexing_lock_version", null)
                    .setSql("lock_version = lock_version + 1");
            if (lastError == null) {
                update.set("last_error", null);
            }
            if (chunkMapper.update(patch, update) != 1) {
                throw ChunkingException.conflict("Chunk changed before failure restoration");
            }
            current.setPendingVectorId(null);
            current.setIndexingLockVersion(null);
        }
    }

    private boolean enqueueFailed(
            Collection<ChunkVectorLifecycle.CleanupObligation> obligations,
            RuntimeException original) {
        try {
            vectorLifecycle.enqueue(obligations);
            return true;
        } catch (RuntimeException enqueueFailure) {
            addSuppressedUnlessSame(original, enqueueFailure);
            log.error("Unable to persist vector cleanup obligations", enqueueFailure);
            return false;
        }
    }

    private void drainAfterFailure(RuntimeException original) {
        try {
            vectorLifecycle.drain();
        } catch (RuntimeException cleanupFailure) {
            addSuppressedUnlessSame(original, cleanupFailure);
            log.error("Unable to drain persisted vector cleanup obligations", cleanupFailure);
        }
    }

    private FileProcessing requireLockedProcessing(long tenantId, long knowledgeId, long fileId,
                                                    PipelineState expected, int lockVersion) {
        FileProcessing processing = processingMapper.findScopedForUpdate(fileId, tenantId, knowledgeId);
        if (processing == null) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        if (pipelineState(processing) != expected
                || !Integer.valueOf(lockVersion).equals(processing.getLockVersion())) {
            throw ChunkingException.conflict("Pipeline state or lock version changed during vectorization");
        }
        return processing;
    }

    private void addSuppressedUnlessSame(RuntimeException original,
                                         RuntimeException secondary) {
        if (secondary != original) {
            original.addSuppressed(secondary);
        }
    }

    private FileProcessing requireCompensatableProcessing(long tenantId, long knowledgeId,
                                                           long fileId, int fileLockVersion) {
        FileProcessing processing = processingMapper.findScopedForUpdate(
                fileId, tenantId, knowledgeId);
        if (processing == null) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        if (pipelineState(processing) != PipelineState.VECTORIZING
                || !Integer.valueOf(fileLockVersion).equals(processing.getLockVersion())) {
            throw ChunkingException.conflict(
                    "File ownership changed before vector failure could be compensated");
        }
        return processing;
    }

    private void requireExactJobChunks(long tenantId, long knowledgeId, long fileId,
                                       List<ChunkSnapshot> allSnapshots,
                                       List<ChunkSnapshot> targets) {
        Map<Long, DocumentChunk> currentById = byId(
                chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId));
        requireExactChunkSet(currentById, allSnapshots);
        Set<Long> targetIds = targets.stream().map(ChunkSnapshot::id).collect(Collectors.toSet());
        for (ChunkSnapshot snapshot : allSnapshots) {
            DocumentChunk current = currentById.get(snapshot.id());
            if (targetIds.contains(snapshot.id())) {
                requireSnapshot(current, snapshot);
            } else {
                requireRelatedSnapshot(current, snapshot);
            }
        }
    }

    private void requireJobSnapshot(FileProcessing processing, String sourceHash,
                                    ProcessingSnapshot expected) {
        if (!Objects.equals(processing.getSourceHash(), sourceHash)
                || !expected.matches(processing, runtimePolicyResolver)) {
            throw ChunkingException.conflict("File indexing policy snapshot changed");
        }
    }

    private void requireJobSnapshot(FileProcessing processing, String sourceHash,
                                    ProcessingSnapshot expected, int maxTokens) {
        if (expected != null) {
            requireJobSnapshot(processing, sourceHash, expected);
            return;
        }
        if (!Objects.equals(processing.getSourceHash(), sourceHash)
                || runtimePolicyResolver.resolve(processing).maxIndexTokens() != maxTokens) {
            throw ChunkingException.conflict("File indexing policy snapshot changed");
        }
    }

    private void requireFileSnapshot(long fileId, FileSnapshot expected) {
        File file = fileMapper.selectById(fileId);
        if (file == null
                || !Objects.equals(file.getPublicId(), expected.publicId())
                || !Objects.equals(file.getPath(), expected.path())
                || !Objects.equals(file.getType(), expected.fileType())) {
            throw ChunkingException.conflict("The indexed file metadata changed");
        }
    }

    private void requireSnapshot(DocumentChunk current, ChunkSnapshot expected) {
        if (current == null
                || !Objects.equals(current.getPublicId(), expected.publicId())
                || !Objects.equals(current.getTenantId(), expected.tenantId())
                || !Objects.equals(current.getKnowledgeId(), expected.knowledgeId())
                || !Objects.equals(current.getFileId(), expected.fileId())
                || !Objects.equals(current.getPosition(), expected.position())
                || !Objects.equals(current.getContent(), expected.content())
                || !Objects.equals(current.getLockVersion(), expected.lockVersion())
                || !Objects.equals(current.getContentHash(), expected.contentHash())
                || !Objects.equals(current.getOverlapEnabled(), expected.overlapEnabled())
                || !Objects.equals(current.getOverlapLimit(), expected.overlapLimit())
                || !Objects.equals(current.getOverlapUnit(), expected.overlapUnit())
                || !Objects.equals(current.getSectionPath() == null ? List.of() : current.getSectionPath(),
                expected.sectionPath())
                || !Objects.equals(ChunkSnapshot.immutableMap(current.getSourceLocator()),
                expected.sourceLocator())
                || !Objects.equals(ChunkSnapshot.immutableMap(current.getBoundaryReason()),
                expected.boundaryReason())
                || !Objects.equals(current.getTokenCount(), expected.tokenCount())
                || !Objects.equals(current.getIsModified(), expected.isModified())
                || !Objects.equals(current.getVectorId(), expected.vectorId())
                || !Objects.equals(current.getPendingVectorId(), expected.pendingVectorId())
                || !Objects.equals(current.getIndexingLockVersion(), expected.indexingLockVersion())
                || !Objects.equals(current.getStatus(), expected.status())) {
            throw ChunkingException.conflict("Chunk state or content snapshot changed during vectorization");
        }
    }

    private void requireRelatedSnapshot(DocumentChunk current, ChunkSnapshot expected) {
        if (current == null
                || !Objects.equals(current.getPublicId(), expected.publicId())
                || !Objects.equals(current.getLockVersion(), expected.lockVersion())
                || !Objects.equals(current.getContentHash(), expected.contentHash())
                || !Objects.equals(current.getOverlapEnabled(), expected.overlapEnabled())
                || !Objects.equals(current.getOverlapLimit(), expected.overlapLimit())
                || !Objects.equals(current.getOverlapUnit(), expected.overlapUnit())
                || !Objects.equals(current.getTenantId(), expected.tenantId())
                || !Objects.equals(current.getKnowledgeId(), expected.knowledgeId())
                || !Objects.equals(current.getFileId(), expected.fileId())
                || !Objects.equals(current.getPosition(), expected.position())
                || !Objects.equals(current.getContent(), expected.content())
                || !Objects.equals(current.getSectionPath() == null ? List.of() : current.getSectionPath(),
                expected.sectionPath())
                || !Objects.equals(ChunkSnapshot.immutableMap(current.getSourceLocator()),
                expected.sourceLocator())
                || !Objects.equals(ChunkSnapshot.immutableMap(current.getBoundaryReason()),
                expected.boundaryReason())
                || !Objects.equals(current.getTokenCount(), expected.tokenCount())
                || !Objects.equals(current.getIsModified(), expected.isModified())
                || !Objects.equals(current.getVectorId(), expected.vectorId())
                || !Objects.equals(current.getPendingVectorId(), expected.pendingVectorId())
                || !Objects.equals(current.getIndexingLockVersion(), expected.indexingLockVersion())
                || !Objects.equals(current.getStatus(), expected.status())) {
            throw ChunkingException.conflict(
                    "Related chunk snapshot changed during vectorization");
        }
    }

    private void requireExactChunkSet(Map<Long, DocumentChunk> currentById,
                                      List<ChunkSnapshot> expected) {
        Set<Long> expectedIds = expected.stream().map(ChunkSnapshot::id)
                .collect(Collectors.toSet());
        if (expectedIds.size() != expected.size()
                || currentById.size() != expectedIds.size()
                || !currentById.keySet().equals(expectedIds)) {
            throw ChunkingException.conflict(
                    "Chunk set changed during vectorization");
        }
    }

    private UpdateWrapper<DocumentChunk> chunkScope(DocumentChunk current,
                                                     ChunkSnapshot snapshot) {
        UpdateWrapper<DocumentChunk> update = new UpdateWrapper<DocumentChunk>()
                .eq("id", snapshot.id())
                .eq("tenant_id", current.getTenantId())
                .eq("knowledge_id", current.getKnowledgeId())
                .eq("file_id", current.getFileId())
                .eq("public_id", snapshot.publicId())
                .eq("status", ChunkStatus.INDEXING.code())
                .eq("lock_version", snapshot.lockVersion())
                .eq("indexing_lock_version", snapshot.indexingLockVersion());
        update = snapshot.vectorId() == null
                ? update.isNull("vector_id") : update.eq("vector_id", snapshot.vectorId());
        update = snapshot.pendingVectorId() == null
                ? update.isNull("pending_vector_id")
                : update.eq("pending_vector_id", snapshot.pendingVectorId());
        return snapshot.contentHash() == null
                ? update.isNull("content_hash")
                : update.eq("content_hash", snapshot.contentHash());
    }

    private Map<Long, DocumentChunk> byId(List<DocumentChunk> chunks) {
        return chunks.stream().filter(chunk -> chunk.getId() != null)
                .collect(Collectors.toMap(DocumentChunk::getId, chunk -> chunk));
    }

    private PipelineState pipelineState(FileProcessing processing) {
        try {
            return PipelineState.fromCode(processing.getPipelineState());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ChunkingException.conflict("The current processing state is invalid");
        }
    }

    private ChunkStatus chunkStatus(DocumentChunk chunk) {
        try {
            return ChunkStatus.fromCode(chunk.getStatus());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ChunkingException.conflict("The current chunk state is invalid");
        }
    }

    private String failureSummary(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    public record FileSnapshot(UUID publicId, String path, String fileType) {

        public FileSnapshot {
            Objects.requireNonNull(publicId, "publicId");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(fileType, "fileType");
        }

        public static FileSnapshot from(File file) {
            return new FileSnapshot(file.getPublicId(), file.getPath(), file.getType());
        }
    }

    public record ChunkSnapshot(
            long id,
            UUID publicId,
            long tenantId,
            long knowledgeId,
            long fileId,
            Integer position,
            String content,
            String contentHash,
            Boolean overlapEnabled,
            Integer overlapLimit,
            OverlapUnit overlapUnit,
            List<String> sectionPath,
            Map<String, Object> sourceLocator,
            Map<String, Object> boundaryReason,
            Integer tokenCount,
            Integer status,
            Boolean isModified,
            UUID vectorId,
            UUID pendingVectorId,
            Integer indexingLockVersion,
            int lockVersion) {

        public ChunkSnapshot {
            Objects.requireNonNull(publicId, "publicId");
            content = content == null ? "" : content;
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            sourceLocator = immutableMap(sourceLocator);
            boundaryReason = immutableMap(boundaryReason);
        }

        public static ChunkSnapshot afterMarking(DocumentChunk chunk, int indexingLockVersion,
                                                 UUID pendingVectorId) {
            return from(chunk, chunk.getLockVersion() + 1,
                    ChunkStatus.INDEXING.code(), indexingLockVersion, pendingVectorId);
        }

        public static ChunkSnapshot afterMarking(DocumentChunk chunk, int indexingLockVersion) {
            UUID pendingVectorId = vectorGenerationId(chunk.getTenantId(), chunk.getKnowledgeId(),
                    chunk.getFileId(), indexingLockVersion, chunk.getPublicId(),
                    chunk.getLockVersion() + 1);
            return afterMarking(chunk, indexingLockVersion, pendingVectorId);
        }

        public static ChunkSnapshot current(DocumentChunk chunk) {
            return from(chunk, chunk.getLockVersion(), chunk.getStatus(),
                    chunk.getIndexingLockVersion(), chunk.getPendingVectorId());
        }

        public static ChunkSnapshot fromIndexing(DocumentChunk chunk) {
            return current(chunk);
        }

        private static ChunkSnapshot from(DocumentChunk chunk, int lockVersion,
                                          Integer status, Integer indexingLockVersion,
                                          UUID pendingVectorId) {
            return new ChunkSnapshot(
                    chunk.getId(), chunk.getPublicId(), chunk.getTenantId(), chunk.getKnowledgeId(),
                    chunk.getFileId(), chunk.getPosition(), chunk.getContent(), chunk.getContentHash(),
                    chunk.getOverlapEnabled(), chunk.getOverlapLimit(), chunk.getOverlapUnit(),
                    chunk.getSectionPath(), chunk.getSourceLocator(), chunk.getBoundaryReason(),
                    chunk.getTokenCount(), status, chunk.getIsModified(), chunk.getVectorId(),
                    pendingVectorId, indexingLockVersion, lockVersion);
        }

        DocumentChunk detached() {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(id);
            chunk.setPublicId(publicId);
            chunk.setTenantId(tenantId);
            chunk.setKnowledgeId(knowledgeId);
            chunk.setFileId(fileId);
            chunk.setPosition(position);
            chunk.setContent(content);
            chunk.setContentHash(contentHash);
            chunk.setOverlapEnabled(overlapEnabled);
            chunk.setOverlapLimit(overlapLimit);
            chunk.setOverlapUnit(overlapUnit);
            chunk.setSectionPath(sectionPath);
            chunk.setSourceLocator(sourceLocator);
            chunk.setBoundaryReason(boundaryReason);
            chunk.setTokenCount(tokenCount);
            chunk.setStatus(status);
            chunk.setIsModified(isModified);
            chunk.setVectorId(vectorId);
            chunk.setPendingVectorId(pendingVectorId);
            chunk.setIndexingLockVersion(indexingLockVersion);
            chunk.setLockVersion(lockVersion);
            return chunk;
        }

        /** Legacy accessor retained for token-only callers for one release. */
        public Integer overlapTokenLimit() {
            return overlapLimit;
        }

        private static Map<String, Object> immutableMap(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, immutableValue(value)));
            return Collections.unmodifiableMap(copy);
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(key, immutableValue(item)));
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                return list.stream().map(ChunkSnapshot::immutableValue).toList();
            }
            if (value instanceof Set<?> set) {
                Set<Object> copy = new LinkedHashSet<>();
                set.forEach(item -> copy.add(immutableValue(item)));
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Collection<?> collection) {
                return collection.stream().map(ChunkSnapshot::immutableValue).toList();
            }
            return value;
        }
    }

    public record BatchJob(long tenantId, long knowledgeId, long fileId,
                           int fileLockVersion, String sourceHash,
                           int maxTokens, FileSnapshot file,
                           List<ChunkSnapshot> allChunks, List<ChunkSnapshot> chunks,
                           ProcessingSnapshot processing, List<PreparedChunk> prepared) {

        public BatchJob {
            sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
            file = Objects.requireNonNull(file, "file");
            allChunks = List.copyOf(allChunks);
            chunks = List.copyOf(chunks);
            prepared = prepared == null ? List.of() : List.copyOf(prepared);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Batch vectorization requires at least one chunk");
            }
        }

        public BatchJob(long tenantId, long knowledgeId, long fileId,
                        int fileLockVersion, String sourceHash, int maxTokens,
                        FileSnapshot file, List<ChunkSnapshot> allChunks,
                        List<ChunkSnapshot> chunks) {
            this(tenantId, knowledgeId, fileId, fileLockVersion, sourceHash, maxTokens,
                    file, allChunks, chunks, null, List.of());
        }

        ChunkSnapshot snapshot(long id) {
            return chunks.stream().filter(value -> value.id() == id).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing chunk snapshot"));
        }

        Map<Long, UUID> vectorIds() {
            return chunks.stream().collect(Collectors.toUnmodifiableMap(
                    ChunkSnapshot::id, snapshot -> snapshot.pendingVectorId() == null
                            ? vectorGenerationId(tenantId, knowledgeId, fileId, fileLockVersion,
                            snapshot.publicId(), snapshot.lockVersion())
                            : snapshot.pendingVectorId()));
        }

        UUID vectorId(long id) {
            return Objects.requireNonNull(vectorIds().get(id), "vectorId");
        }

        List<ChunkVectorLifecycle.CleanupObligation> obligations() {
            Map<Long, UUID> ids = vectorIds();
            return chunks.stream().map(snapshot -> new ChunkVectorLifecycle.CleanupObligation(
                    ids.get(snapshot.id()), tenantId, knowledgeId, fileId, snapshot.publicId()))
                    .toList();
        }
    }

    public record SingleJob(long tenantId, long knowledgeId, long fileId,
                            int fileLockVersion, String sourceHash,
                            int maxTokens, FileSnapshot file,
                            List<ChunkSnapshot> allChunks, ChunkSnapshot chunk,
                            ProcessingSnapshot processing, List<PreparedChunk> prepared) {

        public SingleJob {
            sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
            file = Objects.requireNonNull(file, "file");
            allChunks = List.copyOf(allChunks);
            chunk = Objects.requireNonNull(chunk, "chunk");
            prepared = prepared == null ? List.of() : List.copyOf(prepared);
        }

        public SingleJob(long tenantId, long knowledgeId, long fileId,
                         int fileLockVersion, String sourceHash, int maxTokens,
                         FileSnapshot file, List<ChunkSnapshot> allChunks,
                         ChunkSnapshot chunk) {
            this(tenantId, knowledgeId, fileId, fileLockVersion, sourceHash, maxTokens,
                    file, allChunks, chunk, null, List.of());
        }

        Map<Long, UUID> vectorIds() {
            return Map.of(chunk.id(), chunk.pendingVectorId() == null
                    ? vectorGenerationId(tenantId, knowledgeId, fileId, fileLockVersion,
                    chunk.publicId(), chunk.lockVersion()) : chunk.pendingVectorId());
        }

        UUID vectorId(long id) {
            if (id != chunk.id()) {
                throw new IllegalStateException("Missing chunk vector generation");
            }
            return vectorIds().get(id);
        }

        List<ChunkVectorLifecycle.CleanupObligation> obligations() {
            return List.of(new ChunkVectorLifecycle.CleanupObligation(vectorId(chunk.id()),
                    tenantId, knowledgeId, fileId, chunk.publicId()));
        }
    }

    static UUID vectorGenerationId(long tenantId, long knowledgeId, long fileId,
                                   int fileLockVersion, UUID publicId, int chunkLockVersion) {
        String identity = "starseaknow-vector-v1|" + tenantId + '|' + knowledgeId + '|'
                + fileId + '|' + publicId + '|' + fileLockVersion + '|'
                + chunkLockVersion;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static ChunkVectorLifecycle directLifecycle(ChunkVectorGateway gateway) {
        return new ChunkVectorLifecycle() {
            private final Set<CleanupObligation> queued =
                    java.util.concurrent.ConcurrentHashMap.newKeySet();

            @Override
            public void enqueue(Collection<CleanupObligation> obligations) {
                queued.addAll(obligations);
            }

            @Override
            public void enqueuePendingOwner(long tenantId, long knowledgeId, long fileId,
                                            int indexingLockVersion) {
            }

            @Override
            public void resetAbandonedClaims() {
            }

            @Override
            public void drain() {
                RuntimeException firstFailure = null;
                for (CleanupObligation obligation : List.copyOf(queued)) {
                    try {
                        gateway.delete(obligation.vectorId());
                        queued.remove(obligation);
                    } catch (RuntimeException failure) {
                        if (firstFailure == null) {
                            firstFailure = failure;
                        } else if (firstFailure != failure) {
                            firstFailure.addSuppressed(failure);
                        }
                    }
                }
                if (firstFailure != null) {
                    throw firstFailure;
                }
            }
        };
    }

    public record ProcessingSnapshot(String strategyCode, String plannerVersion,
                                     Map<String, Object> policySnapshot,
                                     Map<String, Object> contextPolicy,
                                     Map<String, Object> executionMetadata,
                                     ChunkRuntimePolicy runtimePolicy) {
        public ProcessingSnapshot {
            Objects.requireNonNull(strategyCode, "strategyCode");
            policySnapshot = ChunkSnapshot.immutableMap(policySnapshot);
            contextPolicy = ChunkSnapshot.immutableMap(contextPolicy);
            executionMetadata = ChunkSnapshot.immutableMap(executionMetadata);
            Objects.requireNonNull(runtimePolicy, "runtimePolicy");
        }

        public static ProcessingSnapshot from(FileProcessing processing,
                                              ChunkRuntimePolicyResolver resolver) {
            return new ProcessingSnapshot(processing.getStrategyCode(),
                    processing.getPlannerVersion(), processing.getPolicySnapshot(),
                    processing.getContextPolicy(), processing.getExecutionMetadata(),
                    resolver.resolve(processing));
        }

        boolean matches(FileProcessing processing, ChunkRuntimePolicyResolver resolver) {
            return Objects.equals(strategyCode, processing.getStrategyCode())
                    && Objects.equals(plannerVersion, processing.getPlannerVersion())
                    && Objects.equals(policySnapshot, processing.getPolicySnapshot())
                    && Objects.equals(contextPolicy, processing.getContextPolicy())
                    && Objects.equals(executionMetadata, processing.getExecutionMetadata())
                    && Objects.equals(runtimePolicy, resolver.resolve(processing));
        }
    }

    public record PreparedChunk(long id, UUID publicId, long tenantId, long knowledgeId,
                                long fileId, Integer position, List<String> sectionPath,
                                Long overlapSourceChunkId, String overlapContent,
                                int overlapTokenCount, int overlapCharacterCount,
                                String overlapReductionReason, String indexContent) {
        public PreparedChunk {
            Objects.requireNonNull(publicId, "publicId");
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            Objects.requireNonNull(indexContent, "indexContent");
        }

        public static PreparedChunk from(EnrichedChunk value) {
            DocumentChunk chunk = value.chunk();
            return new PreparedChunk(chunk.getId(), chunk.getPublicId(), chunk.getTenantId(),
                    chunk.getKnowledgeId(), chunk.getFileId(), chunk.getPosition(),
                    chunk.getSectionPath(), value.overlapSourceChunkId(), value.overlapContent(),
                    value.overlapTokenCount(), value.overlapCharacterCount(),
                    value.overlapReductionReason(), value.indexContent());
        }

        EnrichedChunk enriched() {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(id);
            chunk.setPublicId(publicId);
            chunk.setTenantId(tenantId);
            chunk.setKnowledgeId(knowledgeId);
            chunk.setFileId(fileId);
            chunk.setPosition(position);
            chunk.setSectionPath(sectionPath);
            return new EnrichedChunk(chunk, overlapSourceChunkId, overlapContent,
                    overlapTokenCount, overlapCharacterCount, overlapReductionReason, indexContent);
        }
    }

    private record PreparedBatch(FileSnapshot file, int maximum,
                                 Integer maxCharacters, List<EnrichedChunk> chunks) {
        private PreparedBatch(FileSnapshot file, int maximum, List<EnrichedChunk> chunks) {
            this(file, maximum, null, chunks);
        }
    }
}
