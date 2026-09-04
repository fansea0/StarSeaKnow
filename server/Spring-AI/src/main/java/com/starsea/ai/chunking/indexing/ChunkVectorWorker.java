package com.starsea.ai.chunking.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ChunkType;
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
            PreparedBatch prepared = enrich(job.allChunks(), job.chunks(),
                    job.maxTokens(), job.file());
            writeVectors(prepared);
            transactions.executeWithoutResult(status -> completeBatch(job, prepared));
        } catch (RuntimeException failure) {
            cleanupEvery(job.chunks(), failure);
            restoreBatch(job, failure);
        }
    }

    public void vectorizeSingle(SingleJob job) {
        try {
            PreparedBatch prepared = enrich(job.allChunks(), List.of(job.chunk()),
                    job.maxTokens(), job.file());
            writeVectors(prepared);
            transactions.executeWithoutResult(status -> completeSingle(job, prepared));
        } catch (RuntimeException failure) {
            cleanupEvery(List.of(job.chunk()), failure);
            restoreSingle(job, failure);
            throw failure;
        }
    }

    public void failBatchDispatch(BatchJob job, RuntimeException failure) {
        cleanupEvery(job.chunks(), failure);
        restoreBatch(job, failure);
    }

    public void failSingleDispatch(SingleJob job, RuntimeException failure) {
        cleanupEvery(List.of(job.chunk()), failure);
        restoreSingle(job, failure);
    }

    private PreparedBatch enrich(List<ChunkSnapshot> allSnapshots,
                                 List<ChunkSnapshot> targets,
                                 int maxTokens,
                                 FileSnapshot file) {
        List<DocumentChunk> detachedChunks = allSnapshots.stream()
                .filter(ChunkSnapshot::vectorizable)
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
                    prepared.file().publicId(),
                    value(chunk.getPosition()),
                    prepared.file().fileType(),
                    chunk.getSectionPath(),
                    chunkType(chunk),
                    chunk.getParentPublicId()));
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
        FileProcessing processing = requireLockedProcessing(job.tenantId(), job.knowledgeId(),
                job.fileId(), PipelineState.VECTORIZING, job.fileLockVersion());
        requireJobSnapshot(processing, job.sourceHash(), job.maxTokens());
        requireFileSnapshot(job.fileId(), job.file());
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                job.fileId(), job.tenantId(), job.knowledgeId());
        Map<Long, DocumentChunk> currentById = byId(chunks);
        Map<Long, EnrichedChunk> enrichedById = prepared.chunks().stream()
                .collect(Collectors.toMap(value -> value.chunk().getId(), value -> value));
        for (ChunkSnapshot snapshot : job.allChunks()) {
            DocumentChunk current = currentById.get(snapshot.id());
            requireSnapshot(current, snapshot);
            if (snapshot.vectorizable()) {
                EnrichedChunk enriched = enrichedById.get(snapshot.id());
                if (enriched == null) {
                    throw ChunkingException.conflict(
                            "The context enhancer omitted an indexing chunk");
                }
                activate(current, snapshot, enriched);
            } else {
                activateParent(current, snapshot);
            }
        }
        stateService.transition(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                PipelineState.COMPLETED, job.fileLockVersion());
    }

    private void completeSingle(SingleJob job, PreparedBatch prepared) {
        FileProcessing processing = requireLockedProcessing(job.tenantId(), job.knowledgeId(),
                job.fileId(), PipelineState.VECTORIZING, job.fileLockVersion());
        requireJobSnapshot(processing, job.sourceHash(), job.maxTokens());
        requireFileSnapshot(job.fileId(), job.file());
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                job.fileId(), job.tenantId(), job.knowledgeId());
        Map<Long, DocumentChunk> currentById = byId(chunks);
        DocumentChunk current = currentById.get(job.chunk().id());
        requireSnapshot(current, job.chunk());
        activate(current, job.chunk(), prepared.chunks().get(0));
        boolean allActive = chunks.stream().allMatch(chunk ->
                Objects.equals(chunk.getId(), job.chunk().id())
                        || chunkStatus(chunk) == ChunkStatus.ACTIVE);
        PipelineState targetState = allActive ? PipelineState.COMPLETED : PipelineState.ADJUSTING;
        stateService.transition(job.knowledgeId(), job.fileId(), PipelineState.VECTORIZING,
                targetState, job.fileLockVersion());
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

    private void activateParent(DocumentChunk current, ChunkSnapshot snapshot) {
        DocumentChunk patch = new DocumentChunk();
        patch.setStatus(ChunkStatus.ACTIVE.code());
        UpdateWrapper<DocumentChunk> update = chunkScope(current, snapshot)
                .set("last_error", null)
                .setSql("lock_version = lock_version + 1");
        if (chunkMapper.update(patch, update) != 1) {
            throw ChunkingException.conflict("Parent chunk changed before activation");
        }
    }

    private void restoreBatch(BatchJob job, RuntimeException original) {
        try {
            transactions.executeWithoutResult(status -> {
                FileProcessing processing = requireCompensatableProcessing(
                        job.tenantId(), job.knowledgeId(), job.fileId());
                restoreIndexingTargets(job.tenantId(), job.knowledgeId(), job.fileId(),
                        job.allChunks(), null);
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
                        job.tenantId(), job.knowledgeId(), job.fileId());
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
        Map<UUID, DocumentChunk> currentByPublicId = chunks.stream()
                .filter(chunk -> chunk.getPublicId() != null)
                .collect(Collectors.toMap(DocumentChunk::getPublicId, chunk -> chunk));
        for (ChunkSnapshot snapshot : snapshots) {
            DocumentChunk current = currentByPublicId.get(snapshot.publicId());
            if (current == null
                    || !Integer.valueOf(ChunkStatus.INDEXING.code()).equals(current.getStatus())) {
                continue;
            }
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
                    .eq("lock_version", current.getLockVersion())
                    .set("overlap_content", null)
                    .set("overlap_source_chunk_id", null)
                    .set("overlap_token_count", 0)
                    .set("index_content", null)
                    .setSql("lock_version = lock_version + 1");
            if (lastError == null) {
                update.set("last_error", null);
            }
            if (chunkMapper.update(patch, update) != 1) {
                throw ChunkingException.conflict("Chunk changed before failure restoration");
            }
        }
    }

    private void cleanupEvery(List<ChunkSnapshot> snapshots, RuntimeException original) {
        for (ChunkSnapshot snapshot : snapshots) {
            try {
                gateway.delete(snapshot.publicId());
            } catch (RuntimeException cleanupFailure) {
                addSuppressedUnlessSame(original, cleanupFailure);
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

    private void addSuppressedUnlessSame(RuntimeException original,
                                         RuntimeException secondary) {
        if (secondary != original) {
            original.addSuppressed(secondary);
        }
    }

    private FileProcessing requireCompensatableProcessing(long tenantId, long knowledgeId,
                                                           long fileId) {
        FileProcessing processing = processingMapper.findScopedForUpdate(
                fileId, tenantId, knowledgeId);
        if (processing == null) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        if (pipelineState(processing) != PipelineState.VECTORIZING) {
            throw ChunkingException.conflict(
                    "File left VECTORIZING before vector failure could be compensated");
        }
        return processing;
    }

    private void requireJobSnapshot(FileProcessing processing, String sourceHash, int maxTokens) {
        if (!Objects.equals(processing.getSourceHash(), sourceHash)
                || configuredMaximum(processing.getPolicySnapshot()) != maxTokens) {
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
                || !Objects.equals(current.getLockVersion(), expected.lockVersion())
                || !Objects.equals(current.getContentHash(), expected.contentHash())
                || !Objects.equals(current.getOverlapEnabled(), expected.overlapEnabled())
                || !Objects.equals(current.getOverlapTokenLimit(), expected.overlapTokenLimit())
                || chunkType(current) != expected.chunkType()
                || !Objects.equals(current.getParentChunkId(), expected.parentChunkId())
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

    static int configuredMaximum(Map<String, Object> policySnapshot) {
        Object configured = policySnapshot == null ? null
                : policySnapshot.getOrDefault("childMaxTokens", policySnapshot.get("maxTokens"));
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

    private ChunkType chunkType(DocumentChunk chunk) {
        try {
            return chunk.getChunkType() == null
                    ? ChunkType.SINGLE : ChunkType.fromCode(chunk.getChunkType());
        } catch (IllegalArgumentException exception) {
            throw ChunkingException.conflict("The current chunk type is invalid");
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
            Integer overlapTokenLimit,
            List<String> sectionPath,
            Map<String, Object> sourceLocator,
            Map<String, Object> boundaryReason,
            ChunkType chunkType,
            Long parentChunkId,
            UUID parentChunkPublicId,
            int lockVersion) {

        public ChunkSnapshot {
            Objects.requireNonNull(publicId, "publicId");
            content = content == null ? "" : content;
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            sourceLocator = immutableMap(sourceLocator);
            boundaryReason = immutableMap(boundaryReason);
            chunkType = chunkType == null ? ChunkType.SINGLE : chunkType;
        }

        public static ChunkSnapshot afterMarking(DocumentChunk chunk) {
            return afterMarking(chunk, chunk.getParentPublicId());
        }

        public static ChunkSnapshot afterMarking(DocumentChunk chunk, UUID parentChunkPublicId) {
            return from(chunk, parentChunkPublicId, chunk.getLockVersion() + 1);
        }

        public static ChunkSnapshot current(DocumentChunk chunk) {
            return current(chunk, chunk.getParentPublicId());
        }

        public static ChunkSnapshot current(DocumentChunk chunk, UUID parentChunkPublicId) {
            return from(chunk, parentChunkPublicId, chunk.getLockVersion());
        }

        public static ChunkSnapshot fromIndexing(DocumentChunk chunk) {
            return current(chunk);
        }

        private static ChunkSnapshot from(DocumentChunk chunk, UUID parentChunkPublicId,
                                          int lockVersion) {
            return new ChunkSnapshot(
                    chunk.getId(), chunk.getPublicId(), chunk.getTenantId(), chunk.getKnowledgeId(),
                    chunk.getFileId(), chunk.getPosition(), chunk.getContent(), chunk.getContentHash(),
                    chunk.getOverlapEnabled(), chunk.getOverlapTokenLimit(),
                    chunk.getSectionPath(), chunk.getSourceLocator(), chunk.getBoundaryReason(),
                    chunkType(chunk), chunk.getParentChunkId(), parentChunkPublicId, lockVersion);
        }

        public boolean vectorizable() {
            return chunkType != ChunkType.PARENT;
        }

        DocumentChunk detached() {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setId(id);
            chunk.setPublicId(publicId);
            chunk.setTenantId(tenantId);
            chunk.setKnowledgeId(knowledgeId);
            chunk.setFileId(fileId);
            chunk.setPosition(position);
            chunk.setChunkType(chunkType.code());
            chunk.setParentChunkId(parentChunkId);
            chunk.setParentPublicId(parentChunkPublicId);
            chunk.setContent(content);
            chunk.setContentHash(contentHash);
            chunk.setOverlapEnabled(overlapEnabled);
            chunk.setOverlapTokenLimit(overlapTokenLimit);
            chunk.setSectionPath(sectionPath);
            chunk.setSourceLocator(sourceLocator);
            chunk.setBoundaryReason(boundaryReason);
            chunk.setStatus(ChunkStatus.INDEXING.code());
            chunk.setLockVersion(lockVersion);
            return chunk;
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

        private static ChunkType chunkType(DocumentChunk chunk) {
            return chunk.getChunkType() == null
                    ? ChunkType.SINGLE : ChunkType.fromCode(chunk.getChunkType());
        }
    }

    public record BatchJob(long tenantId, long knowledgeId, long fileId,
                           int fileLockVersion, String sourceHash,
                           int maxTokens, FileSnapshot file,
                           List<ChunkSnapshot> allChunks, List<ChunkSnapshot> chunks) {

        public BatchJob {
            sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
            file = Objects.requireNonNull(file, "file");
            allChunks = List.copyOf(allChunks);
            chunks = List.copyOf(chunks);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Batch vectorization requires at least one chunk");
            }
        }

    }

    public record SingleJob(long tenantId, long knowledgeId, long fileId,
                            int fileLockVersion, String sourceHash,
                            int maxTokens, FileSnapshot file,
                            List<ChunkSnapshot> allChunks, ChunkSnapshot chunk) {

        public SingleJob {
            sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
            file = Objects.requireNonNull(file, "file");
            allChunks = List.copyOf(allChunks);
            chunk = Objects.requireNonNull(chunk, "chunk");
        }
    }

    private record PreparedBatch(FileSnapshot file, int maximum, List<EnrichedChunk> chunks) {
    }
}
