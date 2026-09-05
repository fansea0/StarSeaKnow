package com.starsea.ai.chunking.preview;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlan;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PlannedChunk;
import com.starsea.ai.chunking.context.StrategyAwareChunkContextEnricher;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChunkPreviewPersistenceService {

    private final DocumentChunkMapper chunkMapper;
    private final FileProcessingMapper processingMapper;
    private final FileProcessingService processingService;
    private final ChunkContextEnricher contextEnricher;

    @Autowired
    public ChunkPreviewPersistenceService(DocumentChunkMapper chunkMapper,
                                          FileProcessingMapper processingMapper,
                                          FileProcessingService processingService,
                                          ChunkContextEnricher contextEnricher) {
        this.chunkMapper = chunkMapper;
        this.processingMapper = processingMapper;
        this.processingService = processingService;
        this.contextEnricher = contextEnricher;
    }

    /** Compatibility constructor used by isolated persistence tests. */
    public ChunkPreviewPersistenceService(DocumentChunkMapper chunkMapper,
                                          FileProcessingMapper processingMapper,
                                          FileProcessingService processingService) {
        this(chunkMapper, processingMapper, processingService,
                new StrategyAwareChunkContextEnricher(new CodePointTokenCounter()));
    }

    @Transactional
    public void replace(ChunkPreviewWorker.Job job, String sourceHash, String plannerVersion,
                        Map<String, Object> policySnapshot, List<ChunkDraft> drafts) {
        ContextConfig context = job.contextConfig();
        replace(job, sourceHash, plannerVersion, policySnapshot,
                Map.of("enabled", context.enabled(), "mode", context.mode().name(),
                        "limit", context.limit(), "unit", context.unit().name()),
                Map.of(), Map.of(), drafts);
    }

    @Transactional
    public void replace(ChunkPreviewWorker.Job job, String sourceHash, String plannerVersion,
                        Map<String, Object> policySnapshot, Map<String, Object> contextPolicy,
                        Map<String, Object> executionMetadata, Map<String, Object> previewSummary,
                        List<ChunkDraft> drafts) {
        long tenantId = requireTenantId();
        FileProcessing lockedProcessing = processingMapper.findScopedForUpdate(
                job.fileId(), tenantId, job.knowledgeId());
        if (lockedProcessing == null
                || !Integer.valueOf(PipelineState.CHUNKING.code()).equals(lockedProcessing.getPipelineState())
                || !Integer.valueOf(job.lockVersion()).equals(lockedProcessing.getLockVersion())) {
            throw ChunkingException.conflict("Pipeline state or lock version changed before preview persistence");
        }
        List<DocumentChunk> existing = chunkMapper.findByFileForUpdate(
                job.fileId(), tenantId, job.knowledgeId());
        List<ChunkPreviewWorker.ExistingChunkSnapshot> lockedSnapshot = existing.stream()
                .map(ChunkPreviewWorker.ExistingChunkSnapshot::from)
                .toList();
        if (!job.existingChunks().equals(lockedSnapshot)) {
            throw ChunkingException.conflict("The current chunk set changed after preview confirmation");
        }
        for (DocumentChunk chunk : existing) {
            ChunkStatus status = ChunkStatus.fromCode(chunk.getStatus());
            if (status != ChunkStatus.DRAFT) {
                throw ChunkingException.conflict("Only DRAFT chunks can be replaced by preview generation");
            }
            if (Boolean.TRUE.equals(chunk.getIsModified()) && !job.replaceEditedDrafts()) {
                throw ChunkingException.conflict("Edited DRAFT chunks require explicit replacement confirmation");
            }
        }
        int deleted = chunkMapper.deleteReplaceableDrafts(
                job.fileId(), tenantId, job.knowledgeId(), job.replaceEditedDrafts());
        if (deleted != existing.size()) {
            throw ChunkingException.conflict("The current DRAFT set changed during replacement");
        }

        java.util.ArrayList<DocumentChunk> inserted = new java.util.ArrayList<>(drafts.size());
        for (int position = 0; position < drafts.size(); position++) {
            DocumentChunk entity = toEntity(tenantId, job, position, drafts.get(position));
            if (chunkMapper.insert(entity) != 1) {
                throw new IllegalStateException("Unable to persist the complete DRAFT set");
            }
            inserted.add(entity);
        }
        if ("GENERAL".equalsIgnoreCase(job.strategyCode())) {
            ChunkRuntimePolicy runtimePolicy = new ChunkRuntimePolicy(
                    job.strategyCode(), job.strategyConfig(), job.contextConfig(),
                    job.maxIndexTokens(),
                    executionMetadata.get("tokenizerId") instanceof String tokenizerId ? tokenizerId : null);
            List<EnrichedChunk> enriched = contextEnricher.enrich(inserted, runtimePolicy);
            if (enriched.size() != inserted.size()) {
                throw new IllegalStateException("Context enrichment omitted a preview chunk");
            }
            for (EnrichedChunk value : enriched) {
                applyDerived(value.chunk(), value);
                if (chunkMapper.update(value.chunk(), previewScope(value.chunk())) != 1) {
                    throw new IllegalStateException("Unable to persist enriched preview context");
                }
            }
        }

        FileProcessing metadata = new FileProcessing();
        metadata.setSourceHash(sourceHash);
        metadata.setStrategyCode(job.strategyCode());
        metadata.setPlannerVersion(plannerVersion);
        metadata.setPolicySnapshot(Map.copyOf(policySnapshot));
        metadata.setContextPolicy(Map.copyOf(contextPolicy));
        metadata.setExecutionMetadata(Map.copyOf(executionMetadata));
        metadata.setPreviewSummary(Map.copyOf(previewSummary));
        int updated = processingMapper.update(metadata, Wrappers.<FileProcessing>lambdaUpdate()
                .eq(FileProcessing::getFileId, job.fileId())
                .eq(FileProcessing::getTenantId, tenantId)
                .eq(FileProcessing::getKnowledgeId, job.knowledgeId())
                .eq(FileProcessing::getPipelineState, PipelineState.CHUNKING.code())
                .eq(FileProcessing::getLockVersion, job.lockVersion()));
        if (updated != 1) {
            throw ChunkingException.conflict("Pipeline state or lock version changed during preview persistence");
        }
        processingService.transition(job.knowledgeId(), job.fileId(), PipelineState.CHUNKING,
                PipelineState.CHUNKED, job.lockVersion());
    }

    @Transactional
    public void replacePlan(ChunkPreviewWorker.Job job, String sourceHash, String plannerVersion,
                        Map<String, Object> policySnapshot, Map<String, Object> contextPolicy,
                        Map<String, Object> executionMetadata, Map<String, Object> previewSummary,
                        ChunkPlan plan) {
        long tenantId = requireTenantId();
        FileProcessing lockedProcessing = processingMapper.findScopedForUpdate(
                job.fileId(), tenantId, job.knowledgeId());
        if (lockedProcessing == null
                || !Integer.valueOf(PipelineState.CHUNKING.code()).equals(lockedProcessing.getPipelineState())
                || !Integer.valueOf(job.lockVersion()).equals(lockedProcessing.getLockVersion())) {
            throw ChunkingException.conflict("Pipeline state or lock version changed before preview persistence");
        }
        List<DocumentChunk> existing = chunkMapper.findByFileForUpdate(
                job.fileId(), tenantId, job.knowledgeId());
        if (!job.existingChunks().equals(existing.stream()
                .map(ChunkPreviewWorker.ExistingChunkSnapshot::from).toList())) {
            throw ChunkingException.conflict("The current chunk set changed after preview confirmation");
        }
        for (DocumentChunk chunk : existing) {
            if (ChunkStatus.fromCode(chunk.getStatus()) != ChunkStatus.DRAFT) {
                throw ChunkingException.conflict("Only DRAFT chunks can be replaced by preview generation");
            }
            if (Boolean.TRUE.equals(chunk.getIsModified()) && !job.replaceEditedDrafts()) {
                throw ChunkingException.conflict("Edited DRAFT chunks require explicit replacement confirmation");
            }
        }
        int deleted = chunkMapper.deleteReplaceableDrafts(
                job.fileId(), tenantId, job.knowledgeId(), job.replaceEditedDrafts());
        if (deleted != existing.size()) {
            throw ChunkingException.conflict("The current DRAFT set changed during replacement");
        }

        Map<String, DocumentChunk> parentsByKey = new HashMap<>();
        java.util.ArrayList<DocumentChunk> inserted = new java.util.ArrayList<>(plan.chunks().size());
        int position = 0;
        for (PlannedChunk planned : plan.chunks()) {
            DocumentChunk parent = planned.type() == ChunkType.CHILD
                    ? parentsByKey.get(planned.parentKey()) : null;
            if (planned.type() == ChunkType.CHILD && (parent == null || parent.getId() == null)) {
                throw new IllegalArgumentException("The planned child references an unknown parent");
            }
            DocumentChunk entity = toEntity(tenantId, job, position++, planned, parent);
            if (chunkMapper.insert(entity) != 1) {
                throw new IllegalStateException("Unable to persist the complete DRAFT set");
            }
            inserted.add(entity);
            if (planned.type() == ChunkType.PARENT) {
                if (entity.getId() == null) {
                    throw new IllegalStateException("The persisted parent has no database identifier");
                }
                parentsByKey.put(planned.key(), entity);
            }
        }

        FileProcessing metadata = new FileProcessing();
        metadata.setSourceHash(sourceHash);
        metadata.setStrategyCode(job.strategyCode());
        metadata.setPlannerVersion(plannerVersion);
        metadata.setPolicySnapshot(Map.copyOf(policySnapshot));
        metadata.setContextPolicy(Map.copyOf(contextPolicy));
        metadata.setExecutionMetadata(Map.copyOf(executionMetadata));
        metadata.setPreviewSummary(Map.copyOf(previewSummary));
        int updated = processingMapper.update(metadata, Wrappers.<FileProcessing>lambdaUpdate()
                .eq(FileProcessing::getFileId, job.fileId())
                .eq(FileProcessing::getTenantId, tenantId)
                .eq(FileProcessing::getKnowledgeId, job.knowledgeId())
                .eq(FileProcessing::getPipelineState, PipelineState.CHUNKING.code())
                .eq(FileProcessing::getLockVersion, job.lockVersion()));
        if (updated != 1) {
            throw ChunkingException.conflict("Pipeline state or lock version changed during preview persistence");
        }
        processingService.transition(job.knowledgeId(), job.fileId(), PipelineState.CHUNKING,
                PipelineState.CHUNKED, job.lockVersion());
    }

    private DocumentChunk toEntity(long tenantId, ChunkPreviewWorker.Job job,
                                   int position, ChunkDraft draft) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setPublicId(UUID.randomUUID());
        chunk.setTenantId(tenantId);
        chunk.setKnowledgeId(job.knowledgeId());
        chunk.setFileId(job.fileId());
        chunk.setPosition(position);
        chunk.setContent(draft.content());
        ContextConfig context = job.contextConfig();
        chunk.setOverlapEnabled(context.enabled());
        chunk.setOverlapLimit(context.limit());
        chunk.setOverlapUnit(context.unit());
        chunk.setOverlapContent(null);
        chunk.setOverlapSourceChunkId(null);
        chunk.setOverlapTokenCount(0);
        chunk.setOverlapCharacterCount(0);
        chunk.setOverlapReductionReason(null);
        chunk.setIndexContent(null);
        chunk.setSectionPath(draft.sectionPath());
        chunk.setSourceLocator(sourceLocator(draft.sourceLocator()));
        chunk.setTokenCount(draft.tokenCount());
        chunk.setContentHash(sha256(draft.content()));
        chunk.setBoundaryReason(draft.boundaryReason());
        chunk.setStatus(ChunkStatus.DRAFT.code());
        chunk.setIsModified(false);
        chunk.setLastError(null);
        chunk.setLockVersion(0);
        return chunk;
    }

    private DocumentChunk toEntity(long tenantId, ChunkPreviewWorker.Job job, int position,
                                   PlannedChunk planned, DocumentChunk parent) {
        DocumentChunk chunk = toEntity(tenantId, job, position, planned.draft());
        chunk.setChunkType(planned.type().code());
        chunk.setParentChunkId(parent == null ? null : parent.getId());
        chunk.setParentPublicId(parent == null ? null : parent.getPublicId());
        chunk.setSiblingPosition(planned.siblingPosition());
        chunk.setOverlapEnabled(planned.overlapEnabled());
        chunk.setOverlapLimit(planned.overlapTokenLimit() <= 0 ? 40 : planned.overlapTokenLimit());
        chunk.setOverlapUnit(com.starsea.ai.chunking.model.OverlapUnit.TOKENS);
        return chunk;
    }

    private void applyDerived(DocumentChunk chunk, EnrichedChunk enriched) {
        chunk.setOverlapContent(enriched.overlapContent());
        chunk.setOverlapSourceChunkId(enriched.overlapSourceChunkId());
        chunk.setOverlapTokenCount(enriched.overlapTokenCount());
        chunk.setOverlapCharacterCount(enriched.overlapCharacterCount());
        chunk.setOverlapReductionReason(enriched.overlapReductionReason());
        chunk.setIndexContent(enriched.indexContent());
    }

    private com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DocumentChunk> previewScope(
            DocumentChunk chunk) {
        return new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DocumentChunk>()
                .eq("tenant_id", chunk.getTenantId())
                .eq("knowledge_id", chunk.getKnowledgeId())
                .eq("file_id", chunk.getFileId())
                .eq("public_id", chunk.getPublicId())
                .eq("position", chunk.getPosition())
                .eq("status", ChunkStatus.DRAFT.code())
                .eq("lock_version", 0);
    }

    private Map<String, Object> sourceLocator(SourceLocator source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        putIfPresent(value, "type", source.type());
        value.put("blockIds", source.blockIds());
        putIfPresent(value, "startOffset", source.startOffset());
        putIfPresent(value, "endOffset", source.endOffset());
        putIfPresent(value, "startLine", source.startLine());
        putIfPresent(value, "endLine", source.endLine());
        putIfPresent(value, "startPage", source.startPage());
        putIfPresent(value, "endPage", source.endPage());
        value.put("regions", source.regions());
        return Map.copyOf(value);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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

    private long requireTenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) {
            throw ChunkingException.notFound("A tenant context is required");
        }
        return context.getTenantId();
    }

    private static final class CodePointTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }

        @Override
        public String id() {
            return "preview-test-code-point";
        }
    }
}
