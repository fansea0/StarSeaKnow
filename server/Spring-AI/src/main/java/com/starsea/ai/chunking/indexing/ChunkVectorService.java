package com.starsea.ai.chunking.indexing;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.ConfirmRequest;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class ChunkVectorService {

    private final FileProcessingMapper processingMapper;
    private final FileMapper fileMapper;
    private final DocumentChunkMapper chunkMapper;
    private final FileProcessingService stateService;
    private final ChunkVectorWorker worker;
    private final TransactionTemplate transactions;
    private final Executor executor;
    private final SourceHashReader sourceHashReader;

    @Autowired
    public ChunkVectorService(FileProcessingMapper processingMapper,
                              FileMapper fileMapper,
                              DocumentChunkMapper chunkMapper,
                              FileProcessingService stateService,
                              ChunkVectorWorker worker,
                              TransactionTemplate transactions,
                              @Qualifier("chunkingTaskExecutor") Executor executor) {
        this(processingMapper, fileMapper, chunkMapper, stateService, worker,
                transactions, executor, ChunkVectorService::sha256File);
    }

    ChunkVectorService(FileProcessingMapper processingMapper,
                       FileMapper fileMapper,
                       DocumentChunkMapper chunkMapper,
                       FileProcessingService stateService,
                       ChunkVectorWorker worker,
                       TransactionTemplate transactions,
                       Executor executor,
                       SourceHashReader sourceHashReader) {
        this.processingMapper = processingMapper;
        this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper;
        this.stateService = stateService;
        this.worker = worker;
        this.transactions = transactions;
        this.executor = executor;
        this.sourceHashReader = sourceHashReader;
    }

    public void confirm(long knowledgeId, long fileId, ConfirmRequest request) {
        if (request == null) {
            throw ChunkingException.unprocessable("Confirmation settings are required");
        }
        ContextPolicy policy = contextPolicy(request.overlapEnabled(), request.overlapTokens());
        long tenantId = requireTenantId();
        SourceSnapshot source = readSourceOutsideTransaction(tenantId, knowledgeId, fileId);
        ChunkVectorWorker.BatchJob job = transactions.execute(status -> prepareBatch(
                tenantId, knowledgeId, fileId, request.lockVersion(), policy, source));
        if (job == null) {
            throw new IllegalStateException("Vectorization preparation returned no job");
        }
        dispatch(() -> worker.vectorizeBatch(job), failure -> worker.failBatchDispatch(job, failure));
    }

    public void reindex(long knowledgeId, long fileId, UUID chunkPublicId) {
        if (chunkPublicId == null) {
            throw ChunkingException.notFound("Chunk was not found in the requested file");
        }
        long tenantId = requireTenantId();
        SourceSnapshot source = readSourceOutsideTransaction(tenantId, knowledgeId, fileId);
        ChunkVectorWorker.SingleJob job = transactions.execute(status -> prepareSingle(
                tenantId, knowledgeId, fileId, chunkPublicId, source));
        if (job == null) {
            throw new IllegalStateException("Chunk reindex preparation returned no job");
        }
        dispatch(() -> worker.vectorizeSingle(job), failure -> worker.failSingleDispatch(job, failure));
    }

    private SourceSnapshot readSourceOutsideTransaction(long tenantId, long knowledgeId, long fileId) {
        FileProcessing processing = processingMapper.selectById(fileId);
        if (!isScoped(processing, tenantId, knowledgeId, fileId)) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        File file = requireFile(fileId);
        final Path path;
        final String hash;
        try {
            path = Path.of(file.getPath());
            hash = sourceHashReader.hash(path);
        } catch (InvalidPathException | NullPointerException exception) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        } catch (Exception exception) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        }
        return new SourceSnapshot(ChunkVectorWorker.FileSnapshot.from(file), hash);
    }

    private ChunkVectorWorker.BatchJob prepareBatch(long tenantId, long knowledgeId, long fileId,
                                                     int lockVersion, ContextPolicy policy,
                                                     SourceSnapshot source) {
        FileProcessing processing = requireLockedProcessing(tenantId, knowledgeId, fileId);
        PipelineState current = pipelineState(processing);
        if (!Integer.valueOf(lockVersion).equals(processing.getLockVersion())) {
            throw ChunkingException.conflict("Pipeline state or lock version is stale");
        }
        if (current != PipelineState.CHUNKED && current != PipelineState.ADJUSTING
                && !(current == PipelineState.FAILED
                && Integer.valueOf(PipelineState.VECTORIZING.code())
                .equals(processing.getFailedFromState()))) {
            throw ChunkingException.conflict("The current processing state cannot confirm vectorization");
        }
        if (!Objects.equals(processing.getSourceHash(), source.hash())) {
            throw ChunkingException.sourceChanged();
        }
        File file = requireFile(fileId);
        requireSameFile(source.file(), file);
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId);
        if (chunks.isEmpty()) {
            throw ChunkingException.unprocessable("Confirmation requires at least one chunk");
        }
        for (DocumentChunk chunk : chunks) {
            ChunkStatus status = chunkStatus(chunk);
            if (status == ChunkStatus.INDEXING) {
                throw ChunkingException.conflict("An INDEXING chunk cannot be confirmed again");
            }
            if (status != ChunkStatus.DRAFT && status != ChunkStatus.ACTIVE) {
                throw ChunkingException.conflict("Only DRAFT or ACTIVE chunks can be confirmed");
            }
            requireStableChunk(chunk);
        }

        FileProcessing contextPatch = new FileProcessing();
        contextPatch.setContextPolicy(contextPolicyMap(policy));
        int contextUpdated = processingMapper.update(contextPatch,
                processingScope(fileId, tenantId, knowledgeId)
                        .eq("pipeline_state", current.code())
                        .eq("lock_version", lockVersion)
                        .eq("source_hash", source.hash()));
        if (contextUpdated != 1) {
            throw ChunkingException.conflict("Pipeline state or source snapshot changed concurrently");
        }

        int vectorizingLockVersion;
        if (current == PipelineState.FAILED) {
            vectorizingLockVersion = stateService.transition(knowledgeId, fileId,
                    PipelineState.FAILED, PipelineState.VECTORIZING, lockVersion).lockVersion();
        } else {
            int confirmedLockVersion = stateService.transition(knowledgeId, fileId, current,
                    PipelineState.CONFIRMED, lockVersion).lockVersion();
            vectorizingLockVersion = stateService.transition(knowledgeId, fileId,
                    PipelineState.CONFIRMED, PipelineState.VECTORIZING,
                    confirmedLockVersion).lockVersion();
        }

        List<ChunkVectorWorker.ChunkSnapshot> snapshots = chunks.stream()
                .map(chunk -> markIndexing(chunk, tenantId, knowledgeId, fileId))
                .toList();
        int maxTokens = ChunkVectorWorker.configuredMaximum(processing.getPolicySnapshot());
        return new ChunkVectorWorker.BatchJob(tenantId, knowledgeId, fileId,
                vectorizingLockVersion, source.hash(), policy, maxTokens,
                source.file(), snapshots, snapshots);
    }

    private ChunkVectorWorker.SingleJob prepareSingle(long tenantId, long knowledgeId, long fileId,
                                                       UUID chunkPublicId, SourceSnapshot source) {
        FileProcessing processing = requireLockedProcessing(tenantId, knowledgeId, fileId);
        PipelineState current = pipelineState(processing);
        if (current != PipelineState.ADJUSTING && current != PipelineState.COMPLETED) {
            throw ChunkingException.conflict("Only an ADJUSTING or COMPLETED file can reindex one chunk");
        }
        if (!Objects.equals(processing.getSourceHash(), source.hash())) {
            throw ChunkingException.sourceChanged();
        }
        File file = requireFile(fileId);
        requireSameFile(source.file(), file);
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(fileId, tenantId, knowledgeId);
        DocumentChunk target = chunks.stream()
                .filter(chunk -> Objects.equals(chunk.getPublicId(), chunkPublicId))
                .findFirst()
                .orElseThrow(() -> ChunkingException.notFound(
                        "Chunk was not found in the requested file"));
        ChunkStatus status = chunkStatus(target);
        if (status == ChunkStatus.INDEXING) {
            throw ChunkingException.conflict("An INDEXING chunk cannot be reindexed again");
        }
        if (status != ChunkStatus.DRAFT && status != ChunkStatus.ACTIVE) {
            throw ChunkingException.conflict("Only a DRAFT or ACTIVE chunk can be reindexed");
        }
        chunks.forEach(this::requireStableChunk);

        int adjustingLockVersion = value(processing.getLockVersion());
        if (current == PipelineState.COMPLETED) {
            adjustingLockVersion = stateService.transition(knowledgeId, fileId,
                    PipelineState.COMPLETED, PipelineState.ADJUSTING,
                    adjustingLockVersion).lockVersion();
        }
        int vectorizingLockVersion = stateService.transition(knowledgeId, fileId,
                PipelineState.ADJUSTING, PipelineState.VECTORIZING,
                adjustingLockVersion).lockVersion();
        ChunkVectorWorker.ChunkSnapshot targetSnapshot = markIndexing(
                target, tenantId, knowledgeId, fileId);
        List<ChunkVectorWorker.ChunkSnapshot> allSnapshots = chunks.stream()
                .map(chunk -> Objects.equals(chunk.getId(), target.getId())
                        ? targetSnapshot
                        : ChunkVectorWorker.ChunkSnapshot.current(chunk))
                .toList();
        return new ChunkVectorWorker.SingleJob(tenantId, knowledgeId, fileId,
                vectorizingLockVersion, source.hash(), readContextPolicy(processing),
                ChunkVectorWorker.configuredMaximum(processing.getPolicySnapshot()),
                source.file(), allSnapshots, targetSnapshot);
    }

    private ChunkVectorWorker.ChunkSnapshot markIndexing(DocumentChunk chunk, long tenantId,
                                                          long knowledgeId, long fileId) {
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.afterMarking(chunk);
        DocumentChunk patch = new DocumentChunk();
        patch.setStatus(ChunkStatus.INDEXING.code());
        int updated = chunkMapper.update(patch, new UpdateWrapper<DocumentChunk>()
                .eq("id", chunk.getId())
                .eq("tenant_id", tenantId)
                .eq("knowledge_id", knowledgeId)
                .eq("file_id", fileId)
                .eq("public_id", chunk.getPublicId())
                .eq("status", chunk.getStatus())
                .eq("lock_version", chunk.getLockVersion())
                .set("last_error", null)
                .setSql("lock_version = lock_version + 1"));
        if (updated != 1) {
            throw ChunkingException.conflict("Chunk state or lock version changed concurrently");
        }
        return snapshot;
    }

    private void dispatch(Runnable task, java.util.function.Consumer<RuntimeException> onRejected) {
        AuthContext context = AuthContext.current();
        try {
            executor.execute(() -> {
                AuthContext.set(context);
                try {
                    task.run();
                } finally {
                    AuthContext.clear();
                }
            });
        } catch (RejectedExecutionException rejected) {
            try {
                onRejected.accept(rejected);
            } catch (RuntimeException compensationFailure) {
                rejected.addSuppressed(compensationFailure);
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Vectorization service is temporarily unavailable", rejected);
        }
    }

    private FileProcessing requireLockedProcessing(long tenantId, long knowledgeId, long fileId) {
        FileProcessing processing = processingMapper.findScopedForUpdate(fileId, tenantId, knowledgeId);
        if (processing == null) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        return processing;
    }

    private File requireFile(long fileId) {
        File file = fileMapper.selectById(fileId);
        if (file == null || file.getPublicId() == null || file.getPath() == null
                || file.getType() == null || file.getType().isBlank()) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        }
        return file;
    }

    private void requireSameFile(ChunkVectorWorker.FileSnapshot expected, File current) {
        if (!Objects.equals(expected.publicId(), current.getPublicId())
                || !Objects.equals(expected.path(), current.getPath())
                || !Objects.equals(expected.fileType(), current.getType())) {
            throw ChunkingException.conflict("The source file metadata changed before vectorization");
        }
    }

    private boolean isScoped(FileProcessing processing, long tenantId, long knowledgeId, long fileId) {
        return processing != null
                && Long.valueOf(fileId).equals(processing.getFileId())
                && Long.valueOf(tenantId).equals(processing.getTenantId())
                && Long.valueOf(knowledgeId).equals(processing.getKnowledgeId());
    }

    private ContextPolicy contextPolicy(boolean enabled, int overlapTokens) {
        try {
            return new ContextPolicy(enabled, overlapTokens);
        } catch (IllegalArgumentException exception) {
            throw ChunkingException.unprocessable(exception.getMessage());
        }
    }

    private ContextPolicy readContextPolicy(FileProcessing processing) {
        Map<String, Object> values = processing.getContextPolicy();
        boolean enabled = values != null && Boolean.TRUE.equals(values.get("overlapEnabled"));
        Object configured = values == null ? null : values.get("overlapTokens");
        int overlapTokens = configured instanceof Number number ? number.intValue() : 40;
        return contextPolicy(enabled, overlapTokens);
    }

    private Map<String, Object> contextPolicyMap(ContextPolicy policy) {
        return Map.of("overlapEnabled", policy.enabled(), "overlapTokens", policy.overlapTokens());
    }

    private UpdateWrapper<FileProcessing> processingScope(long fileId, long tenantId,
                                                           long knowledgeId) {
        return new UpdateWrapper<FileProcessing>()
                .eq("file_id", fileId)
                .eq("tenant_id", tenantId)
                .eq("knowledge_id", knowledgeId);
    }

    private void requireStableChunk(DocumentChunk chunk) {
        if (chunk.getId() == null || chunk.getPublicId() == null || chunk.getLockVersion() == null
                || chunk.getTenantId() == null || chunk.getKnowledgeId() == null
                || chunk.getFileId() == null) {
            throw ChunkingException.conflict("Chunk identity or lock version is invalid");
        }
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

    private long requireTenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) {
            throw ChunkingException.notFound("A tenant context is required");
        }
        return context.getTenantId();
    }

    private static String sha256File(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private record SourceSnapshot(ChunkVectorWorker.FileSnapshot file, String hash) {
    }

    @FunctionalInterface
    interface SourceHashReader {
        String hash(Path path) throws Exception;
    }
}
