package com.starsea.ai.chunking.preview;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChunkPreviewPersistenceService {

    private final DocumentChunkMapper chunkMapper;
    private final FileProcessingMapper processingMapper;
    private final FileProcessingService processingService;

    public ChunkPreviewPersistenceService(DocumentChunkMapper chunkMapper,
                                          FileProcessingMapper processingMapper,
                                          FileProcessingService processingService) {
        this.chunkMapper = chunkMapper;
        this.processingMapper = processingMapper;
        this.processingService = processingService;
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

        for (int position = 0; position < drafts.size(); position++) {
            if (chunkMapper.insert(toEntity(tenantId, job, position, drafts.get(position))) != 1) {
                throw new IllegalStateException("Unable to persist the complete DRAFT set");
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
}
