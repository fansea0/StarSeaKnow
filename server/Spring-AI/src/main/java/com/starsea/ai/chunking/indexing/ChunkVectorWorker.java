package com.starsea.ai.chunking.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

    public ChunkVectorWorker(FileProcessingMapper processingMapper,
                             FileMapper fileMapper,
                             DocumentChunkMapper chunkMapper,
                             FileProcessingService stateService,
                             ChunkContextEnricher enricher,
                             TokenCounter tokenCounter,
                             ChunkVectorGateway gateway,
                             TransactionTemplate transactions) {
        this.processingMapper = processingMapper;
        this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper;
        this.stateService = stateService;
        this.enricher = enricher;
        this.tokenCounter = tokenCounter;
        this.gateway = gateway;
        this.transactions = transactions;
    }

    public void vectorizeBatch(BatchJob job) {
        try {
            PreparedBatch prepared = transactions.execute(status -> prepare(
                    job.tenantId(), job.knowledgeId(), job.fileId(), job.fileLockVersion(),
                    PipelineState.VECTORIZING, job.policy(), job.chunks()));
            if (prepared == null) {
                throw new IllegalStateException("Vectorization snapshot preparation returned no result");
            }
            writeVectors(prepared);
            transactions.executeWithoutResult(status -> completeBatch(job, prepared));
        } catch (RuntimeException failure) {
            cleanupEvery(job.chunks());
            restoreBatch(job, failure);
        }
    }

    public void vectorizeSingle(SingleJob job) {
        try {
            PreparedBatch prepared = transactions.execute(status -> prepare(
                    job.tenantId(), job.knowledgeId(), job.fileId(), job.fileLockVersion(),
                    PipelineState.ADJUSTING, job.policy(), List.of(job.chunk())));
            if (prepared == null) {
                throw new IllegalStateException("Chunk reindex snapshot preparation returned no result");
            }
            writeVectors(prepared);
            transactions.executeWithoutResult(status -> completeSingle(job, prepared));
        } catch (RuntimeException failure) {
            cleanupEvery(List.of(job.chunk()));
            restoreSingle(job, failure);
        }
    }

    public void failBatchDispatch(BatchJob job, RuntimeException failure) {
        cleanupEvery(job.chunks());
        restoreBatch(job, failure);
    }

    public void failSingleDispatch(SingleJob job, RuntimeException failure) {
        cleanupEvery(List.of(job.chunk()));
        restoreSingle(job, failure);
    }

    private PreparedBatch prepare(long tenantId, long knowledgeId, long fileId,
                                  int fileLockVersion, PipelineState expectedState,
                                  ContextPolicy policy, List<ChunkSnapshot> targets) {
        FileProcessing processing = requireLockedProcessing(
                tenantId, knowledgeId, fileId, expectedState, fileLockVersion);
        requireContextPolicy(processing, policy);
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId);
        Map<Long, ChunkSnapshot> targetById = targets.stream().collect(Collectors.toMap(
                ChunkSnapshot::id, snapshot -> snapshot, (first, ignored) -> first,
                LinkedHashMap::new));
        Map<Long, DocumentChunk> currentById = chunks.stream()
                .filter(chunk -> chunk.getId() != null)
                .collect(Collectors.toMap(DocumentChunk::getId, chunk -> chunk));
        if (currentById.keySet().stream().filter(targetById::containsKey).count() != targets.size()) {
            throw ChunkingException.conflict("The vectorization chunk snapshot changed");
        }
        for (ChunkSnapshot snapshot : targets) {
            requireSnapshot(currentById.get(snapshot.id()), snapshot);
        }

        File file = fileMapper.selectById(fileId);
        if (file == null || file.getPublicId() == null || file.getType() == null
                || file.getType().isBlank()) {
            throw ChunkingException.conflict("The indexed file metadata changed");
        }
        int maximum = configuredMaximum(processing.getPolicySnapshot());
        List<EnrichedChunk> enriched = enricher.enrich(chunks, policy, maximum);
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
        return new PreparedBatch(file, maximum, List.copyOf(selected));
    }

    private void writeVectors(PreparedBatch prepared) {
        List<ChunkVectorGateway.VectorDocument> documents = new ArrayList<>(prepared.chunks().size());
        for (EnrichedChunk enriched : prepared.chunks()) {
            DocumentChunk chunk = enriched.chunk();
            documents.add(new ChunkVectorGateway.VectorDocument(
                    chunk.getPublicId(),
                    enriched.indexContent(),
                    chunk.getTenantId(),
                    chunk.getKnowledgeId(),
                    chunk.getFileId(),
                    prepared.file().getPublicId(),
                    value(chunk.getPosition()),
                    prepared.file().getType(),
                    chunk.getSectionPath()));
        }
        List<UUID> publicIds = documents.stream()
                .map(ChunkVectorGateway.VectorDocument::publicId)
                .toList();
        gateway.deleteAll(publicIds);
        for (ChunkVectorGateway.VectorDocument document : documents) {
            int exactTokens = tokenCounter.count(document.indexContent());
            if (exactTokens > prepared.maximum() || exactTokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
                throw ChunkingException.unprocessable("Final index text exceeds the token budget",
                        Map.of("tokenCount", exactTokens, "maxTokens", prepared.maximum()));
            }
        }
        gateway.add(List.copyOf(documents));
    }

    private void completeBatch(BatchJob job, PreparedBatch prepared) {
        requireLockedProcessing(job.tenantId(), job.knowledgeId(), job.fileId(),
                PipelineState.VECTORIZING, job.fileLockVersion());
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                job.fileId(), job.tenantId(), job.knowledgeId());
        Map<Long, DocumentChunk> currentById = byId(chunks);
        for (EnrichedChunk enriched : prepared.chunks()) {
            ChunkSnapshot snapshot = job.snapshot(enriched.chunk().getId());
            DocumentChunk current = currentById.get(snapshot.id());
            requireSnapshot(current, snapshot);
            activate(current, snapshot, enriched);
        }
        stateService.transition(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                PipelineState.COMPLETED, job.fileLockVersion());
    }

    private void completeSingle(SingleJob job, PreparedBatch prepared) {
        requireLockedProcessing(job.tenantId(), job.knowledgeId(), job.fileId(),
                PipelineState.ADJUSTING, job.fileLockVersion());
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                job.fileId(), job.tenantId(), job.knowledgeId());
        Map<Long, DocumentChunk> currentById = byId(chunks);
        DocumentChunk current = currentById.get(job.chunk().id());
        requireSnapshot(current, job.chunk());
        activate(current, job.chunk(), prepared.chunks().get(0));
        boolean allActive = chunks.stream().allMatch(chunk ->
                Objects.equals(chunk.getId(), job.chunk().id())
                        || chunkStatus(chunk) == ChunkStatus.ACTIVE);
        if (allActive) {
            stateService.transition(job.knowledgeId(), job.fileId(), PipelineState.ADJUSTING,
                    PipelineState.COMPLETED, job.fileLockVersion());
        }
    }

    private void activate(DocumentChunk current, ChunkSnapshot snapshot, EnrichedChunk enriched) {
        DocumentChunk patch = new DocumentChunk();
        patch.setStatus(ChunkStatus.ACTIVE.code());
        patch.setOverlapContent(enriched.overlapContent());
        patch.setOverlapSourceChunkId(enriched.overlapSourceChunkId());
        patch.setOverlapTokenCount(enriched.overlapTokenCount());
        patch.setIndexContent(enriched.indexContent());
        UpdateWrapper<DocumentChunk> update = chunkScope(current, snapshot)
                .set("last_error", null)
                .setSql("lock_version = lock_version + 1");
        if (enriched.overlapContent() == null) {
            update.set("overlap_content", null);
        }
        if (enriched.overlapSourceChunkId() == null) {
            update.set("overlap_source_chunk_id", null);
        }
        if (chunkMapper.update(patch, update) != 1) {
            throw ChunkingException.conflict("Chunk changed before vector activation");
        }
    }

    private void restoreBatch(BatchJob job, RuntimeException original) {
        try {
            transactions.executeWithoutResult(status -> {
                requireLockedProcessing(job.tenantId(), job.knowledgeId(), job.fileId(),
                        PipelineState.VECTORIZING, job.fileLockVersion());
                restoreDrafts(job.tenantId(), job.knowledgeId(), job.fileId(), job.chunks(), original);
                stateService.fail(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                        job.fileLockVersion(), 0, failureSummary(original));
            });
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
            log.error("Unable to restore failed batch vectorization for file {}",
                    job.fileId(), original);
        }
    }

    private void restoreSingle(SingleJob job, RuntimeException original) {
        try {
            transactions.executeWithoutResult(status -> {
                requireLockedProcessing(job.tenantId(), job.knowledgeId(), job.fileId(),
                        PipelineState.ADJUSTING, job.fileLockVersion());
                restoreDrafts(job.tenantId(), job.knowledgeId(), job.fileId(),
                        List.of(job.chunk()), original);
            });
        } catch (RuntimeException compensationFailure) {
            original.addSuppressed(compensationFailure);
            log.error("Unable to restore failed chunk reindex for file {} chunk {}",
                    job.fileId(), job.chunk().publicId(), original);
        }
    }

    private void restoreDrafts(long tenantId, long knowledgeId, long fileId,
                               List<ChunkSnapshot> snapshots, RuntimeException original) {
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId);
        Map<Long, DocumentChunk> currentById = byId(chunks);
        for (ChunkSnapshot snapshot : snapshots) {
            DocumentChunk current = currentById.get(snapshot.id());
            requireSnapshot(current, snapshot);
            DocumentChunk patch = new DocumentChunk();
            patch.setStatus(ChunkStatus.DRAFT.code());
            patch.setLastError(failureSummary(original));
            UpdateWrapper<DocumentChunk> update = chunkScope(current, snapshot)
                    .setSql("lock_version = lock_version + 1");
            if (chunkMapper.update(patch, update) != 1) {
                throw ChunkingException.conflict("Chunk changed before failure restoration");
            }
        }
    }

    private void cleanupEvery(List<ChunkSnapshot> snapshots) {
        for (ChunkSnapshot snapshot : snapshots) {
            try {
                gateway.delete(snapshot.publicId());
            } catch (RuntimeException cleanupFailure) {
                log.error("Unable to delete vector after indexing failure for chunk {}",
                        snapshot.publicId(), cleanupFailure);
            }
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

    private void requireContextPolicy(FileProcessing processing, ContextPolicy expected) {
        Map<String, Object> values = processing.getContextPolicy();
        boolean enabled = values != null && Boolean.TRUE.equals(values.get("overlapEnabled"));
        Object configured = values == null ? null : values.get("overlapTokens");
        int overlapTokens = configured instanceof Number number ? number.intValue() : 40;
        if (enabled != expected.enabled() || overlapTokens != expected.overlapTokens()) {
            throw ChunkingException.conflict("Context policy changed during vectorization");
        }
    }

    private void requireSnapshot(DocumentChunk current, ChunkSnapshot expected) {
        if (current == null
                || !Objects.equals(current.getPublicId(), expected.publicId())
                || !Objects.equals(current.getLockVersion(), expected.lockVersion())
                || !Objects.equals(current.getContentHash(), expected.contentHash())
                || chunkStatus(current) != ChunkStatus.INDEXING) {
            throw ChunkingException.conflict("Chunk state or content snapshot changed during vectorization");
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
                .eq("lock_version", snapshot.lockVersion());
        return snapshot.contentHash() == null
                ? update.isNull("content_hash")
                : update.eq("content_hash", snapshot.contentHash());
    }

    private Map<Long, DocumentChunk> byId(List<DocumentChunk> chunks) {
        return chunks.stream().filter(chunk -> chunk.getId() != null)
                .collect(Collectors.toMap(DocumentChunk::getId, chunk -> chunk));
    }

    private int configuredMaximum(Map<String, Object> policySnapshot) {
        Object configured = policySnapshot == null ? null : policySnapshot.get("maxTokens");
        if (configured instanceof Number number) {
            int value = number.intValue();
            if (value > 0 && value <= ChunkPolicy.MAX_ALLOWED_TOKENS) {
                return value;
            }
        }
        return ChunkPolicy.MAX_ALLOWED_TOKENS;
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

    public record ChunkSnapshot(long id, UUID publicId, int lockVersion, String contentHash) {

        public static ChunkSnapshot afterMarking(DocumentChunk chunk) {
            return new ChunkSnapshot(chunk.getId(), chunk.getPublicId(),
                    chunk.getLockVersion() + 1, chunk.getContentHash());
        }

        public static ChunkSnapshot fromIndexing(DocumentChunk chunk) {
            return new ChunkSnapshot(chunk.getId(), chunk.getPublicId(),
                    chunk.getLockVersion(), chunk.getContentHash());
        }
    }

    public record BatchJob(long tenantId, long knowledgeId, long fileId,
                           int fileLockVersion, ContextPolicy policy,
                           List<ChunkSnapshot> chunks) {

        public BatchJob {
            policy = Objects.requireNonNull(policy, "policy");
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Batch vectorization requires at least one chunk");
            }
        }

        ChunkSnapshot snapshot(long id) {
            return chunks.stream().filter(value -> value.id() == id).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing chunk snapshot"));
        }
    }

    public record SingleJob(long tenantId, long knowledgeId, long fileId,
                            int fileLockVersion, ContextPolicy policy,
                            ChunkSnapshot chunk) {

        public SingleJob {
            policy = Objects.requireNonNull(policy, "policy");
            chunk = Objects.requireNonNull(chunk, "chunk");
        }
    }

    private record PreparedBatch(File file, int maximum, List<EnrichedChunk> chunks) {
    }
}
