package com.starsea.ai.chunking.preview;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.ChunkResponse;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.context.StrategyAwareChunkContextEnricher;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicyResolver;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
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
import java.util.Objects;
import java.util.UUID;

import static com.starsea.ai.chunking.api.ChunkingApiModels.NO_AVAILABLE_OVERLAP_REASON_CODE;

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
    private final ChunkContextEnricher contextEnricher;
    private final ChunkVectorGateway vectorGateway;
    private final RetrySleeper retrySleeper;
    private final ChunkRuntimePolicyResolver runtimePolicyResolver;

    @Autowired
    public ChunkCommandService(DocumentChunkMapper chunkMapper,
                               FileProcessingMapper processingMapper,
                               FileProcessingService stateService,
                               TokenCounter tokenCounter,
                               ChunkIndexContentBuilder contentBuilder,
                               ChunkVectorGateway vectorGateway,
                               ChunkContextEnricher contextEnricher,
                               ChunkRuntimePolicyResolver runtimePolicyResolver) {
        this(chunkMapper, processingMapper, stateService, tokenCounter,
                contentBuilder, vectorGateway, contextEnricher, Thread::sleep, runtimePolicyResolver);
    }

    public ChunkCommandService(DocumentChunkMapper chunkMapper,
                               FileProcessingMapper processingMapper,
                               FileProcessingService stateService,
                               TokenCounter tokenCounter,
                               ChunkIndexContentBuilder contentBuilder,
                               ChunkVectorGateway vectorGateway,
                               ChunkContextEnricher contextEnricher) {
        this(chunkMapper, processingMapper, stateService, tokenCounter,
                contentBuilder, vectorGateway, contextEnricher, Thread::sleep,
                new ChunkRuntimePolicyResolver());
    }

    public ChunkCommandService(DocumentChunkMapper chunkMapper,
                               FileProcessingMapper processingMapper,
                               FileProcessingService stateService,
                               TokenCounter tokenCounter,
                               ChunkIndexContentBuilder contentBuilder,
                               ChunkVectorGateway vectorGateway) {
        this(chunkMapper, processingMapper, stateService, tokenCounter, contentBuilder,
                vectorGateway, new StrategyAwareChunkContextEnricher(tokenCounter), Thread::sleep,
                new ChunkRuntimePolicyResolver());
    }

    ChunkCommandService(DocumentChunkMapper chunkMapper,
                        FileProcessingMapper processingMapper,
                        FileProcessingService stateService,
                        TokenCounter tokenCounter,
                        ChunkIndexContentBuilder contentBuilder,
                        ChunkVectorGateway vectorGateway,
                        RetrySleeper retrySleeper) {
        this(chunkMapper, processingMapper, stateService, tokenCounter, contentBuilder,
                vectorGateway, new StrategyAwareChunkContextEnricher(tokenCounter), retrySleeper,
                new ChunkRuntimePolicyResolver());
    }

    ChunkCommandService(DocumentChunkMapper chunkMapper,
                        FileProcessingMapper processingMapper,
                        FileProcessingService stateService,
                        TokenCounter tokenCounter,
                        ChunkIndexContentBuilder contentBuilder,
                        ChunkVectorGateway vectorGateway,
                        ChunkContextEnricher contextEnricher,
                        RetrySleeper retrySleeper) {
        this(chunkMapper, processingMapper, stateService, tokenCounter, contentBuilder,
                vectorGateway, contextEnricher, retrySleeper, new ChunkRuntimePolicyResolver());
    }

    ChunkCommandService(DocumentChunkMapper chunkMapper,
                        FileProcessingMapper processingMapper,
                        FileProcessingService stateService,
                        TokenCounter tokenCounter,
                        ChunkIndexContentBuilder contentBuilder,
                        ChunkVectorGateway vectorGateway,
                        ChunkContextEnricher contextEnricher,
                        RetrySleeper retrySleeper,
                        ChunkRuntimePolicyResolver runtimePolicyResolver) {
        this.chunkMapper = chunkMapper;
        this.processingMapper = processingMapper;
        this.stateService = stateService;
        this.tokenCounter = tokenCounter;
        this.contentBuilder = contentBuilder;
        this.contextEnricher = contextEnricher;
        this.vectorGateway = vectorGateway;
        this.retrySleeper = retrySleeper;
        this.runtimePolicyResolver = Objects.requireNonNull(runtimePolicyResolver, "runtimePolicyResolver");
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
        if (request.overlapEnabled() == null) {
            throw ChunkingException.unprocessable("Chunk overlapEnabled is required");
        }
        long tenantId = requireTenantId();
        FileProcessing processing = lockMutableProcessing(knowledgeId, fileId, tenantId);
        ChunkRuntimePolicy runtimePolicy = runtimePolicyResolver.resolve(processing);
        DocumentChunk target = requireLockedChunk(
                knowledgeId, fileId, tenantId, chunkPublicId, request.lockVersion());
        int overlapLimit = requireOverlapLimit(request, target);
        com.starsea.ai.chunking.model.OverlapUnit overlapUnit = target.getOverlapUnit() == null
                ? com.starsea.ai.chunking.model.OverlapUnit.TOKENS : target.getOverlapUnit();
        TokenBudget budget = tokenBudget(runtimePolicy, target.getSectionPath(), request.content());
        if (budget.total() > budget.maximum()) {
            throw tokenBudgetFailure(budget, runtimePolicy, request.content());
        }
        if (runtimePolicy.strategyConfig() instanceof com.starsea.ai.chunking.model.GeneralChunkConfig general
                && codePoints(request.content()) > general.maxCharacters()) {
            throw generalBudgetFailure(request.content(), budget.total(), general.maxCharacters(),
                    runtimePolicy.maxIndexTokens());
        }

        DocumentChunk previous = findPreviousChunk(fileId, tenantId, knowledgeId, target);
        DocumentChunk dependentCandidate = lockOverlapDependent(
                fileId, tenantId, knowledgeId, target);
        DocumentChunk edited = copyForContext(target);
        edited.setContent(request.content());
        edited.setTokenCount(budget.body());
        edited.setContentHash(sha256(request.content()));
        edited.setOverlapEnabled(request.overlapEnabled());
        edited.setOverlapLimit(overlapLimit);
        edited.setOverlapUnit(overlapUnit);
        EnrichedChunk editedContext = enrich(previous, edited, runtimePolicy);
        applyDerivedContext(edited, editedContext);
        DocumentChunk dependent = changedOverlapDependent(
                target, edited, dependentCandidate, runtimePolicy);
        requireMutableDependent(dependent);
        edited.setStatus(ChunkStatus.DRAFT.code());
        edited.setIsModified(true);
        edited.setLastError(null);
        edited.setLockVersion(request.lockVersion() + 1);
        int updated = updateEdited(edited, request.lockVersion());
        if (updated != 1) {
            throw ChunkingException.conflict("Chunk state or lock version changed concurrently");
        }
        if (dependent != null) {
            recalculateDependent(dependent, dependent.getLockVersion());
        }
        int adjustingLockVersion = moveToAdjustingIfNeeded(processing, knowledgeId, fileId);
        scheduleVectorCleanup(tenantId, knowledgeId, fileId, adjustingLockVersion,
                vectorIds(target, dependent));

        return toResponse(edited);
    }

    @Transactional
    public void delete(long knowledgeId, long fileId, UUID chunkPublicId, int lockVersion) {
        long tenantId = requireTenantId();
        FileProcessing processing = lockMutableProcessing(knowledgeId, fileId, tenantId);
        ChunkRuntimePolicy runtimePolicy = runtimePolicyResolver.resolve(processing);
        DocumentChunk target = requireLockedChunk(
                knowledgeId, fileId, tenantId, chunkPublicId, lockVersion);
        DocumentChunk dependentCandidate = lockOverlapDependent(
                fileId, tenantId, knowledgeId, target);
        DocumentChunk dependent = changedOverlapAfterSourceDeletion(
                dependentCandidate, runtimePolicy);
        requireMutableDependent(dependent);
        if (dependent != null) {
            recalculateDependent(dependent, dependentCandidate.getLockVersion());
        }
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

    private void requireMutableDependent(DocumentChunk dependent) {
        if (dependent != null && chunkStatus(dependent) == ChunkStatus.INDEXING) {
            throw ChunkingException.conflict(
                    "The dependent INDEXING chunk cannot be invalidated");
        }
    }

    private void recalculateDependent(DocumentChunk dependent, int expectedLockVersion) {
        dependent.setStatus(ChunkStatus.DRAFT.code());
        dependent.setLastError(null);
        dependent.setLockVersion(expectedLockVersion + 1);
        int updated = updateDerived(dependent, expectedLockVersion);
        if (updated != 1) {
            throw ChunkingException.conflict(
                    "Dependent chunk state or lock version changed concurrently");
        }
    }

    private int updateEdited(DocumentChunk chunk, int expectedLockVersion) {
        DocumentChunk patch = new DocumentChunk();
        patch.setOverlapEnabled(chunk.getOverlapEnabled());
        patch.setOverlapLimit(chunk.getOverlapLimit());
        patch.setOverlapUnit(chunk.getOverlapUnit());
        patch.setContent(chunk.getContent());
        patch.setTokenCount(chunk.getTokenCount());
        patch.setContentHash(chunk.getContentHash());
        patch.setOverlapContent(chunk.getOverlapContent());
        patch.setOverlapSourceChunkId(chunk.getOverlapSourceChunkId());
        patch.setOverlapTokenCount(chunk.getOverlapTokenCount());
        patch.setOverlapCharacterCount(chunk.getOverlapCharacterCount());
        patch.setOverlapReductionReason(chunk.getOverlapReductionReason());
        patch.setIndexContent(chunk.getIndexContent());
        patch.setStatus(ChunkStatus.DRAFT.code());
        patch.setIsModified(true);
        patch.setLastError(null);
        UpdateWrapper<DocumentChunk> update =
                chunkScope(chunk, expectedLockVersion)
                        .set("last_error", null)
                        .setSql("lock_version = lock_version + 1");
        if (chunk.getOverlapContent() == null) {
            update.set("overlap_content", null);
        }
        if (chunk.getOverlapSourceChunkId() == null) {
            update.set("overlap_source_chunk_id", null);
        }
        if (chunk.getOverlapReductionReason() == null) {
            update.set("overlap_reduction_reason", null);
        }
        return chunkMapper.update(patch, update);
    }

    private int updateDerived(DocumentChunk chunk, int expectedLockVersion) {
        DocumentChunk patch = new DocumentChunk();
        patch.setStatus(ChunkStatus.DRAFT.code());
        patch.setOverlapContent(chunk.getOverlapContent());
        patch.setOverlapSourceChunkId(chunk.getOverlapSourceChunkId());
        patch.setOverlapTokenCount(chunk.getOverlapTokenCount());
        patch.setOverlapCharacterCount(chunk.getOverlapCharacterCount());
        patch.setOverlapReductionReason(chunk.getOverlapReductionReason());
        patch.setIndexContent(chunk.getIndexContent());
        UpdateWrapper<DocumentChunk> update =
                chunkScope(chunk, expectedLockVersion)
                        .set("last_error", null)
                        .setSql("lock_version = lock_version + 1");
        if (chunk.getOverlapContent() == null) {
            update.set("overlap_content", null);
        }
        if (chunk.getOverlapSourceChunkId() == null) {
            update.set("overlap_source_chunk_id", null);
        }
        if (chunk.getOverlapReductionReason() == null) {
            update.set("overlap_reduction_reason", null);
        }
        return chunkMapper.update(patch, update);
    }

    private UpdateWrapper<DocumentChunk> chunkScope(
            DocumentChunk chunk, int expectedLockVersion) {
        return new UpdateWrapper<DocumentChunk>()
                .eq("id", chunk.getId())
                .eq("tenant_id", chunk.getTenantId())
                .eq("knowledge_id", chunk.getKnowledgeId())
                .eq("file_id", chunk.getFileId())
                .eq("public_id", chunk.getPublicId())
                .ne("status", ChunkStatus.INDEXING.code())
                .eq("lock_version", expectedLockVersion);
    }

    private DocumentChunk findPreviousChunk(
            long fileId, long tenantId, long knowledgeId, DocumentChunk target) {
        if (target.getPosition() == null) {
            return null;
        }
        return chunkMapper.findScopedByPosition(
                fileId, tenantId, knowledgeId, target.getPosition() - 1);
    }

    private DocumentChunk lockOverlapDependent(
            long fileId, long tenantId, long knowledgeId, DocumentChunk target) {
        if (target.getPosition() == null) {
            return null;
        }
        int expectedPosition = target.getPosition() + 1;
        DocumentChunk dependent = chunkMapper.findNextDependentForUpdate(
                fileId, tenantId, knowledgeId, expectedPosition);
        return dependent != null
                && Integer.valueOf(expectedPosition).equals(dependent.getPosition())
                && Boolean.TRUE.equals(dependent.getOverlapEnabled())
                ? dependent : null;
    }

    private DocumentChunk changedOverlapDependent(
            DocumentChunk target, DocumentChunk edited, DocumentChunk candidate,
            ChunkRuntimePolicy runtimePolicy) {
        if (candidate == null) {
            return null;
        }
        DocumentChunk recalculated = copyForContext(candidate);
        applyDerivedContext(recalculated, enrich(edited, recalculated, runtimePolicy));
        boolean referencesTarget = Objects.equals(
                candidate.getOverlapSourceChunkId(), target.getId())
                || Objects.equals(recalculated.getOverlapSourceChunkId(), target.getId());
        if (!referencesTarget || sameDerivedContext(candidate, recalculated)) {
            return null;
        }
        return recalculated;
    }

    private DocumentChunk changedOverlapAfterSourceDeletion(
            DocumentChunk candidate, ChunkRuntimePolicy runtimePolicy) {
        if (candidate == null) {
            return null;
        }
        DocumentChunk recalculated = copyForContext(candidate);
        applyDerivedContext(recalculated, enrich(null, recalculated, runtimePolicy));
        return sameDerivedContext(candidate, recalculated) ? null : recalculated;
    }

    private boolean sameDerivedContext(DocumentChunk left, DocumentChunk right) {
        return Objects.equals(left.getOverlapContent(), right.getOverlapContent())
                && Objects.equals(left.getOverlapSourceChunkId(), right.getOverlapSourceChunkId())
                && value(left.getOverlapTokenCount()) == value(right.getOverlapTokenCount())
                && value(left.getOverlapCharacterCount()) == value(right.getOverlapCharacterCount())
                && Objects.equals(left.getOverlapReductionReason(), right.getOverlapReductionReason())
                && Objects.equals(left.getIndexContent(), right.getIndexContent());
    }

    private EnrichedChunk enrich(DocumentChunk previous, DocumentChunk current,
                                 ChunkRuntimePolicy runtimePolicy) {
        List<DocumentChunk> input = previous == null
                ? List.of(current) : List.of(previous, current);
        return contextEnricher.enrich(input, runtimePolicy).stream()
                .filter(value -> Objects.equals(value.chunk().getId(), current.getId()))
                .findFirst()
                .orElseThrow(() -> ChunkingException.conflict(
                        "The context enhancer omitted a mutable chunk"));
    }

    private void applyDerivedContext(DocumentChunk chunk, EnrichedChunk enriched) {
        chunk.setOverlapContent(enriched.overlapContent());
        chunk.setOverlapSourceChunkId(enriched.overlapSourceChunkId());
        chunk.setOverlapTokenCount(enriched.overlapTokenCount());
        chunk.setOverlapCharacterCount(enriched.overlapCharacterCount());
        chunk.setOverlapReductionReason(enriched.overlapReductionReason());
        chunk.setIndexContent(enriched.indexContent());
    }

    private DocumentChunk copyForContext(DocumentChunk source) {
        DocumentChunk copy = new DocumentChunk();
        copy.setId(source.getId());
        copy.setPublicId(source.getPublicId());
        copy.setTenantId(source.getTenantId());
        copy.setKnowledgeId(source.getKnowledgeId());
        copy.setFileId(source.getFileId());
        copy.setPosition(source.getPosition());
        copy.setContent(source.getContent());
        copy.setOverlapEnabled(source.getOverlapEnabled());
        copy.setOverlapLimit(source.getOverlapLimit());
        copy.setOverlapUnit(source.getOverlapUnit());
        copy.setOverlapContent(source.getOverlapContent());
        copy.setOverlapSourceChunkId(source.getOverlapSourceChunkId());
        copy.setOverlapTokenCount(source.getOverlapTokenCount());
        copy.setOverlapCharacterCount(source.getOverlapCharacterCount());
        copy.setOverlapReductionReason(source.getOverlapReductionReason());
        copy.setIndexContent(source.getIndexContent());
        copy.setSectionPath(source.getSectionPath());
        copy.setSourceLocator(source.getSourceLocator());
        copy.setTokenCount(source.getTokenCount());
        copy.setContentHash(source.getContentHash());
        copy.setBoundaryReason(source.getBoundaryReason());
        copy.setStatus(source.getStatus());
        copy.setIsModified(source.getIsModified());
        copy.setLockVersion(source.getLockVersion());
        return copy;
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

    private TokenBudget tokenBudget(ChunkRuntimePolicy runtimePolicy,
                                    List<String> sectionPath, String body) {
        int maximum = runtimePolicy.maxIndexTokens();
        String titleText = contentBuilder.title(sectionPath);
        String fullText = contentBuilder.build(sectionPath, null, body);
        return new TokenBudget(tokenCounter.count(titleText), tokenCounter.count(body),
                tokenCounter.count(fullText), maximum);
    }

    private ChunkingException tokenBudgetFailure(TokenBudget budget,
                                                 ChunkRuntimePolicy policy, String body) {
        if (policy.strategyConfig() instanceof com.starsea.ai.chunking.model.GeneralChunkConfig general) {
            return generalBudgetFailure(body, budget.total(), general.maxCharacters(), budget.maximum());
        }
        return ChunkingException.unprocessable("Edited chunk exceeds the token budget", Map.of(
                "titleTokenCount", budget.title(),
                "bodyTokenCount", budget.body(),
                "totalTokenCount", budget.total(),
                "maxTokens", budget.maximum()));
    }

    private ChunkingException generalBudgetFailure(String body, int tokens,
                                                   int maxCharacters, int maxTokens) {
        return ChunkingException.unprocessable("Edited GENERAL chunk exceeds its budget", Map.of(
                "actualCharacterCount", codePoints(body),
                "actualTokenCount", tokens,
                "maxCharacters", maxCharacters,
                "maxIndexTokens", maxTokens));
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
        com.starsea.ai.chunking.model.OverlapUnit unit = chunk.getOverlapUnit() == null
                ? com.starsea.ai.chunking.model.OverlapUnit.TOKENS : chunk.getOverlapUnit();
        int bodyLength = unit == com.starsea.ai.chunking.model.OverlapUnit.CHARACTERS
                ? codePoints(chunk.getContent()) : value(chunk.getTokenCount());
        Integer indexLength = chunk.getIndexContent() == null ? null
                : unit == com.starsea.ai.chunking.model.OverlapUnit.CHARACTERS
                ? codePoints(chunk.getIndexContent()) : tokenCounter.count(chunk.getIndexContent());
        int actualLength = unit == com.starsea.ai.chunking.model.OverlapUnit.CHARACTERS
                ? value(chunk.getOverlapCharacterCount()) : value(chunk.getOverlapTokenCount());
        return new ChunkResponse(chunk.getPublicId(), value(chunk.getPosition()), chunk.getContent(),
                chunk.getSectionPath(), chunk.getSourceLocator(), value(chunk.getTokenCount()),
                value(chunk.getStatus()), Boolean.TRUE.equals(chunk.getIsModified()),
                value(chunk.getLockVersion()), Boolean.TRUE.equals(chunk.getOverlapEnabled()),
                overlapLimit(chunk), unit, chunk.getOverlapContent(), value(chunk.getOverlapTokenCount()),
                value(chunk.getOverlapCharacterCount()), chunk.getOverlapReductionReason(),
                overlapUnavailableReason(chunk), unit.name(), bodyLength, indexLength,
                actualLength, chunk.getBoundaryReason());
    }

    private int overlapLimit(DocumentChunk chunk) {
        Integer limit = chunk.getOverlapLimit();
        return limit == null ? 40 : limit;
    }

    private int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private int requireOverlapLimit(EditChunkRequest request, DocumentChunk target) {
        Integer limit = request.resolvedOverlapLimit();
        com.starsea.ai.chunking.model.OverlapUnit expected = target.getOverlapUnit() == null
                ? com.starsea.ai.chunking.model.OverlapUnit.TOKENS : target.getOverlapUnit();
        com.starsea.ai.chunking.model.OverlapUnit requested = request.overlapUnit();
        if (request.overlapTokenLimit() != null) {
            if (requested != null && requested != com.starsea.ai.chunking.model.OverlapUnit.TOKENS) {
                throw ChunkingException.unprocessable(
                        "Legacy overlapTokenLimit always uses TOKENS");
            }
            requested = com.starsea.ai.chunking.model.OverlapUnit.TOKENS;
        } else if (request.overlapLimit() != null && requested == null) {
            throw ChunkingException.unprocessable("Chunk overlapUnit is required with overlapLimit");
        }
        if (requested != null && requested != expected) {
            throw ChunkingException.unprocessable("Chunk overlapUnit does not match its strategy");
        }
        int maximum = expected == com.starsea.ai.chunking.model.OverlapUnit.TOKENS ? 512 : 1000;
        if (limit == null || limit < 0 || limit > maximum
                || Boolean.TRUE.equals(request.overlapEnabled()) && limit == 0) {
            throw ChunkingException.unprocessable(
                    "Chunk overlapLimit is invalid for " + expected.name());
        }
        return limit;
    }

    private String overlapUnavailableReason(DocumentChunk chunk) {
        return Boolean.TRUE.equals(chunk.getOverlapEnabled())
                && (chunk.getOverlapContent() == null || chunk.getOverlapContent().isBlank())
                ? NO_AVAILABLE_OVERLAP_REASON_CODE : null;
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
