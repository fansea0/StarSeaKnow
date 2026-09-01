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
import java.util.List;
import java.util.Map;
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

    public ChunkVectorService(FileProcessingMapper processingMapper,
                              FileMapper fileMapper,
                              DocumentChunkMapper chunkMapper,
                              FileProcessingService stateService,
                              ChunkVectorWorker worker,
                              TransactionTemplate transactions,
                              @Qualifier("chunkingTaskExecutor") Executor executor) {
        this.processingMapper = processingMapper;
        this.fileMapper = fileMapper;
        this.chunkMapper = chunkMapper;
        this.stateService = stateService;
        this.worker = worker;
        this.transactions = transactions;
        this.executor = executor;
    }

    public void confirm(long knowledgeId, long fileId, ConfirmRequest request) {
        if (request == null) {
            throw ChunkingException.unprocessable("Confirmation settings are required");
        }
        ContextPolicy policy = contextPolicy(request.overlapEnabled(), request.overlapTokens());
        long tenantId = requireTenantId();
        ChunkVectorWorker.BatchJob job = transactions.execute(status -> prepareBatch(
                tenantId, knowledgeId, fileId, request.lockVersion(), policy));
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
        ChunkVectorWorker.SingleJob job = transactions.execute(status -> prepareSingle(
                tenantId, knowledgeId, fileId, chunkPublicId));
        if (job == null) {
            throw new IllegalStateException("Chunk reindex preparation returned no job");
        }
        dispatch(() -> worker.vectorizeSingle(job), failure -> worker.failSingleDispatch(job, failure));
    }

    private ChunkVectorWorker.BatchJob prepareBatch(long tenantId, long knowledgeId, long fileId,
                                                     int lockVersion, ContextPolicy policy) {
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

        File file = requireFile(fileId);
        requireCurrentSourceHash(file, processing.getSourceHash());
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
                        .eq("lock_version", lockVersion));
        if (contextUpdated != 1) {
            throw ChunkingException.conflict("Pipeline state or lock version changed concurrently");
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
        return new ChunkVectorWorker.BatchJob(tenantId, knowledgeId, fileId,
                vectorizingLockVersion, policy, snapshots);
    }

    private ChunkVectorWorker.SingleJob prepareSingle(long tenantId, long knowledgeId, long fileId,
                                                       UUID chunkPublicId) {
        FileProcessing processing = requireLockedProcessing(tenantId, knowledgeId, fileId);
        PipelineState current = pipelineState(processing);
        if (current != PipelineState.ADJUSTING && current != PipelineState.COMPLETED) {
            throw ChunkingException.conflict("Only an ADJUSTING or COMPLETED file can reindex one chunk");
        }
        DocumentChunk chunk = chunkMapper.findScopedByPublicIdForUpdate(
                fileId, tenantId, knowledgeId, chunkPublicId);
        if (chunk == null) {
            throw ChunkingException.notFound("Chunk was not found in the requested file");
        }
        ChunkStatus status = chunkStatus(chunk);
        if (status == ChunkStatus.INDEXING) {
            throw ChunkingException.conflict("An INDEXING chunk cannot be reindexed again");
        }
        if (status != ChunkStatus.DRAFT && status != ChunkStatus.ACTIVE) {
            throw ChunkingException.conflict("Only a DRAFT or ACTIVE chunk can be reindexed");
        }
        requireStableChunk(chunk);

        int adjustingLockVersion = value(processing.getLockVersion());
        if (current == PipelineState.COMPLETED) {
            adjustingLockVersion = stateService.transition(knowledgeId, fileId,
                    PipelineState.COMPLETED, PipelineState.ADJUSTING,
                    adjustingLockVersion).lockVersion();
        }
        ChunkVectorWorker.ChunkSnapshot snapshot = markIndexing(
                chunk, tenantId, knowledgeId, fileId);
        return new ChunkVectorWorker.SingleJob(tenantId, knowledgeId, fileId,
                adjustingLockVersion, readContextPolicy(processing), snapshot);
    }

    private ChunkVectorWorker.ChunkSnapshot markIndexing(DocumentChunk chunk, long tenantId,
                                                          long knowledgeId, long fileId) {
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
        return ChunkVectorWorker.ChunkSnapshot.afterMarking(chunk);
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

    private void requireCurrentSourceHash(File file, String previewHash) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(file.getPath()));
        } catch (IOException | InvalidPathException | NullPointerException exception) {
            throw ChunkingException.unprocessable("The source document cannot be read");
        }
        if (previewHash == null || !previewHash.equals(sha256(bytes))) {
            throw ChunkingException.conflict(
                    "The physical source changed after the chunk preview was generated");
        }
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
        if (chunk.getId() == null || chunk.getPublicId() == null || chunk.getLockVersion() == null) {
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

    private String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }
}
