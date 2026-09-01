package com.starsea.ai.chunking.preview;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.ChunkResponse;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns tenant-scoped, optimistic chunk read and mutation commands. */
@Service
public class ChunkCommandService {

    private static final Logger log = LoggerFactory.getLogger(ChunkCommandService.class);
    private static final long[] RETRY_DELAYS_MILLIS = {100L, 300L, 900L};

    private final DocumentChunkMapper chunkMapper;
    private final FileProcessingMapper processingMapper;
    private final FileProcessingService stateService;
    private final TokenCounter tokenCounter;
    private final ChunkIndexContentBuilder contentBuilder;
    private final ChunkVectorGateway vectorGateway;
    private final RetrySleeper retrySleeper;

    @Autowired
    public ChunkCommandService(DocumentChunkMapper chunkMapper,
                               FileProcessingMapper processingMapper,
                               FileProcessingService stateService,
                               TokenCounter tokenCounter,
                               ChunkIndexContentBuilder contentBuilder,
                               ChunkVectorGateway vectorGateway) {
        this(chunkMapper, processingMapper, stateService, tokenCounter,
                contentBuilder, vectorGateway, Thread::sleep);
    }

    ChunkCommandService(DocumentChunkMapper chunkMapper,
                        FileProcessingMapper processingMapper,
                        FileProcessingService stateService,
                        TokenCounter tokenCounter,
                        ChunkIndexContentBuilder contentBuilder,
                        ChunkVectorGateway vectorGateway,
                        RetrySleeper retrySleeper) {
        this.chunkMapper = chunkMapper;
        this.processingMapper = processingMapper;
        this.stateService = stateService;
        this.tokenCounter = tokenCounter;
        this.contentBuilder = contentBuilder;
        this.vectorGateway = vectorGateway;
        this.retrySleeper = retrySleeper;
    }

    @Transactional(readOnly = true)
    public List<ChunkResponse> list(long knowledgeId, long fileId) {
        long tenantId = requireTenantId();
        requireScopedProcessing(knowledgeId, fileId, tenantId);
        return chunkMapper.findByFile(fileId, tenantId, knowledgeId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ChunkResponse edit(long knowledgeId, long fileId, UUID chunkPublicId,
                              EditChunkRequest request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw ChunkingException.unprocessable("Chunk content must not be blank");
        }
        if (request.lockVersion() == null) {
            throw ChunkingException.unprocessable("Chunk lockVersion is required");
        }
        long tenantId = requireTenantId();
        FileProcessing processing = lockMutableProcessing(knowledgeId, fileId, tenantId);
        DocumentChunk target = requireLockedChunk(
                knowledgeId, fileId, tenantId, chunkPublicId, request.lockVersion());
        TokenBudget budget = tokenBudget(processing, target.getSectionPath(), request.content());
        if (budget.total() > budget.maximum()) {
            throw ChunkingException.unprocessable("Edited chunk exceeds the token budget", Map.of(
                    "titleTokenCount", budget.title(),
                    "bodyTokenCount", budget.body(),
                    "totalTokenCount", budget.total(),
                    "maxTokens", budget.maximum()));
        }

        DocumentChunk dependent = lockMutableDependent(target, tenantId);
        int updated = chunkMapper.updateContent(fileId, tenantId, knowledgeId, chunkPublicId,
                request.content(), budget.body(), sha256(request.content()), request.lockVersion());
        if (updated != 1) {
            throw ChunkingException.conflict("Chunk state or lock version changed concurrently");
        }
        invalidateDependent(dependent, target, tenantId);
        int adjustingLockVersion = moveToAdjustingIfNeeded(processing, knowledgeId, fileId);
        scheduleVectorCleanup(tenantId, knowledgeId, fileId, adjustingLockVersion,
                vectorIds(target, dependent));

        return new ChunkResponse(target.getPublicId(), value(target.getPosition()), request.content(),
                target.getSectionPath(), target.getSourceLocator(), budget.body(),
                ChunkStatus.DRAFT.code(), true, request.lockVersion() + 1);
    }

    @Transactional
    public void delete(long knowledgeId, long fileId, UUID chunkPublicId, int lockVersion) {
        long tenantId = requireTenantId();
        FileProcessing processing = lockMutableProcessing(knowledgeId, fileId, tenantId);
        DocumentChunk target = requireLockedChunk(
                knowledgeId, fileId, tenantId, chunkPublicId, lockVersion);
        DocumentChunk dependent = lockMutableDependent(target, tenantId);
        invalidateDependent(dependent, target, tenantId);
        int deleted = chunkMapper.deleteScoped(
                fileId, tenantId, knowledgeId, chunkPublicId, lockVersion);
        if (deleted != 1) {
            throw ChunkingException.conflict("Chunk state or lock version changed concurrently");
        }
        int adjustingLockVersion = moveToAdjustingIfNeeded(processing, knowledgeId, fileId);
        scheduleVectorCleanup(tenantId, knowledgeId, fileId, adjustingLockVersion,
                vectorIds(target, dependent));
    }

    /** Task 9 calls this before confirmation so an empty set can never be vectorized. */
    @Transactional(readOnly = true)
    public void requireConfirmable(long knowledgeId, long fileId) {
        long tenantId = requireTenantId();
        requireScopedProcessing(knowledgeId, fileId, tenantId);
        if (chunkMapper.findByFile(fileId, tenantId, knowledgeId).isEmpty()) {
            throw ChunkingException.unprocessable("Confirmation requires at least one chunk");
        }
    }

    /** Task 9 can reconcile the file after each activation without completing early. */
    @Transactional
    public boolean completeIfAllActive(long knowledgeId, long fileId) {
        long tenantId = requireTenantId();
        FileProcessing processing = processingMapper.findScopedForUpdate(
                fileId, tenantId, knowledgeId);
        if (processing == null) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        if (pipelineState(processing) != PipelineState.ADJUSTING) {
            throw ChunkingException.conflict(
                    "Only an ADJUSTING file can reconcile chunk completion");
        }
        List<DocumentChunk> chunks = chunkMapper.findByFileForUpdate(
                fileId, tenantId, knowledgeId);
        if (chunks.isEmpty()) {
            throw ChunkingException.unprocessable("Completion requires at least one chunk");
        }
        boolean allActive = chunks.stream()
                .allMatch(chunk -> chunkStatus(chunk) == ChunkStatus.ACTIVE);
        if (!allActive) {
            return false;
        }
        stateService.transition(knowledgeId, fileId, PipelineState.ADJUSTING,
                PipelineState.COMPLETED, value(processing.getLockVersion()));
        return true;
    }

    private FileProcessing lockMutableProcessing(long knowledgeId, long fileId, long tenantId) {
        FileProcessing processing = processingMapper.findScopedForUpdate(fileId, tenantId, knowledgeId);
        if (processing == null) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        PipelineState state = pipelineState(processing);
        boolean recoverableFailure = state == PipelineState.FAILED
                && (Integer.valueOf(PipelineState.VECTORIZING.code()).equals(processing.getFailedFromState())
                || Integer.valueOf(PipelineState.ADJUSTING.code()).equals(processing.getFailedFromState()));
        if (state != PipelineState.CHUNKED && state != PipelineState.ADJUSTING
                && state != PipelineState.COMPLETED && !recoverableFailure) {
            throw ChunkingException.conflict("The current processing state cannot mutate chunks");
        }
        return processing;
    }

    private FileProcessing requireScopedProcessing(long knowledgeId, long fileId, long tenantId) {
        FileProcessing processing = processingMapper.selectById(fileId);
        if (processing == null
                || !Long.valueOf(fileId).equals(processing.getFileId())
                || !Long.valueOf(tenantId).equals(processing.getTenantId())
                || !Long.valueOf(knowledgeId).equals(processing.getKnowledgeId())) {
            throw ChunkingException.notFound(
                    "File was not found in the current tenant and knowledge base");
        }
        return processing;
    }

    private DocumentChunk requireLockedChunk(long knowledgeId, long fileId, long tenantId,
                                             UUID chunkPublicId, int lockVersion) {
        if (chunkPublicId == null) {
            throw ChunkingException.notFound("Chunk was not found in the requested file");
        }
        DocumentChunk chunk = chunkMapper.findScopedByPublicIdForUpdate(
                fileId, tenantId, knowledgeId, chunkPublicId);
        if (chunk == null) {
            throw ChunkingException.notFound("Chunk was not found in the requested file");
        }
        ChunkStatus status = chunkStatus(chunk);
        if (status == ChunkStatus.INDEXING) {
            throw ChunkingException.conflict("An INDEXING chunk cannot be edited or deleted");
        }
        if (!Integer.valueOf(lockVersion).equals(chunk.getLockVersion())) {
            throw ChunkingException.conflict("Chunk state or lock version is stale");
        }
        return chunk;
    }

    private DocumentChunk lockMutableDependent(DocumentChunk target, long tenantId) {
        if (target.getId() == null || target.getPosition() == null) {
            return null;
        }
        DocumentChunk dependent = chunkMapper.findNextDependentForUpdate(
                target.getFileId(), tenantId, target.getKnowledgeId(),
                target.getPosition() + 1, target.getId());
        if (dependent != null && chunkStatus(dependent) == ChunkStatus.INDEXING) {
            throw ChunkingException.conflict(
                    "The dependent INDEXING chunk cannot be invalidated");
        }
        return dependent;
    }

    private void invalidateDependent(DocumentChunk dependent, DocumentChunk target, long tenantId) {
        if (dependent == null) {
            return;
        }
        int updated = chunkMapper.invalidateDependent(
                target.getFileId(), tenantId, target.getKnowledgeId(), dependent.getId(),
                target.getId(), dependent.getLockVersion());
        if (updated != 1) {
            throw ChunkingException.conflict(
                    "Dependent chunk state or lock version changed concurrently");
        }
    }

    private int moveToAdjustingIfNeeded(FileProcessing processing,
                                        long knowledgeId, long fileId) {
        PipelineState current = pipelineState(processing);
        if (current == PipelineState.CHUNKED || current == PipelineState.COMPLETED) {
            FileProcessingService.Transition transition = stateService.transition(
                    knowledgeId, fileId, current, PipelineState.ADJUSTING,
                    value(processing.getLockVersion()));
            return transition == null ? value(processing.getLockVersion()) + 1 : transition.lockVersion();
        }
        if (current == PipelineState.FAILED) {
            FileProcessingService.Transition transition = stateService.recoverFailedDraftMutation(
                    knowledgeId, fileId, value(processing.getLockVersion()));
            return transition == null ? value(processing.getLockVersion()) + 1 : transition.lockVersion();
        }
        return value(processing.getLockVersion());
    }

    private TokenBudget tokenBudget(FileProcessing processing, List<String> sectionPath, String body) {
        int maximum = configuredMaximum(processing.getPolicySnapshot());
        String titleText = contentBuilder.title(sectionPath);
        String fullText = contentBuilder.build(sectionPath, null, body);
        return new TokenBudget(tokenCounter.count(titleText), tokenCounter.count(body),
                tokenCounter.count(fullText), maximum);
    }

    private int configuredMaximum(Map<String, Object> policySnapshot) {
        Object configured = policySnapshot == null ? null : policySnapshot.get("maxTokens");
        if (configured instanceof Number number) {
            int maximum = number.intValue();
            if (maximum > 0 && maximum <= ChunkPolicy.MAX_ALLOWED_TOKENS) {
                return maximum;
            }
        }
        return ChunkPolicy.MAX_ALLOWED_TOKENS;
    }

    private List<UUID> vectorIds(DocumentChunk target, DocumentChunk dependent) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (target.getPublicId() != null) {
            ids.add(target.getPublicId());
        }
        if (dependent != null && dependent.getPublicId() != null) {
            ids.add(dependent.getPublicId());
        }
        return List.copyOf(ids);
    }

    private void scheduleVectorCleanup(long tenantId, long knowledgeId, long fileId,
                                       int adjustingLockVersion, List<UUID> publicIds) {
        if (publicIds.isEmpty()) {
            return;
        }
        Runnable cleanup = () -> publicIds.forEach(id -> deleteVectorWithRetry(
                tenantId, knowledgeId, fileId, adjustingLockVersion, id));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private void deleteVectorWithRetry(long tenantId, long knowledgeId, long fileId,
                                       int adjustingLockVersion, UUID publicId) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < RETRY_DELAYS_MILLIS.length; attempt++) {
            try {
                retrySleeper.sleep(RETRY_DELAYS_MILLIS[attempt]);
                vectorGateway.delete(publicId);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastFailure = new IllegalStateException(
                        "Vector cleanup retry was interrupted", interrupted);
                break;
            } catch (RuntimeException failure) {
                lastFailure = failure;
            }
        }
        log.error("chunk_vector_delete_failed tenant_id={} file_id={} chunk_public_id={}",
                tenantId, fileId, publicId, lastFailure);
        String detail = lastFailure == null || lastFailure.getMessage() == null
                ? "unknown error" : lastFailure.getMessage();
        try {
            stateService.fail(knowledgeId, fileId, PipelineState.ADJUSTING,
                    adjustingLockVersion, 100, "Vector cleanup failed: " + detail);
        } catch (RuntimeException stateFailure) {
            log.error("Unable to mark vector cleanup failure for file {}", fileId, stateFailure);
        }
    }

    private ChunkResponse toResponse(DocumentChunk chunk) {
        return new ChunkResponse(chunk.getPublicId(), value(chunk.getPosition()), chunk.getContent(),
                chunk.getSectionPath(), chunk.getSourceLocator(), value(chunk.getTokenCount()),
                value(chunk.getStatus()), Boolean.TRUE.equals(chunk.getIsModified()),
                value(chunk.getLockVersion()));
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

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private record TokenBudget(int title, int body, int total, int maximum) {
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
