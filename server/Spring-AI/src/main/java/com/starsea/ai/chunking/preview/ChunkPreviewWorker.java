package com.starsea.ai.chunking.preview;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlan;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.chunking.model.PlannedChunk;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class ChunkPreviewWorker {

    private static final Logger log = LoggerFactory.getLogger(ChunkPreviewWorker.class);

    private final FileMapper fileMapper;
    private final FileProcessingMapper processingMapper;
    private final DocumentStructureParserRegistry parserRegistry;
    private final ChunkStrategyRegistry strategyRegistry;
    private final TokenCounter tokenCounter;
    private final ChunkPreviewPersistenceService persistence;
    private final FileProcessingService processingService;

    public ChunkPreviewWorker(FileMapper fileMapper,
                              FileProcessingMapper processingMapper,
                              DocumentStructureParserRegistry parserRegistry,
                              ChunkStrategyRegistry strategyRegistry,
                              TokenCounter tokenCounter,
                              ChunkPreviewPersistenceService persistence,
                              FileProcessingService processingService) {
        this.fileMapper = fileMapper;
        this.processingMapper = processingMapper;
        this.parserRegistry = parserRegistry;
        this.strategyRegistry = strategyRegistry;
        this.tokenCounter = tokenCounter;
        this.persistence = persistence;
        this.processingService = processingService;
    }

    public void generate(Job job) {
        Objects.requireNonNull(job, "job");
        Path snapshotPath = null;
        try {
            ScopedSource source = requireScopedSource(job);
            byte[] exactSource = Files.readAllBytes(source.path());
            if (exactSource.length == 0 || new String(exactSource, StandardCharsets.UTF_8).isBlank()) {
                throw new IllegalArgumentException("The source document is empty");
            }
            String sourceHash = sha256(exactSource);
            snapshotPath = createSnapshot(job.fileId(), source.file().getType(), exactSource);
            DocumentStructureParser parser = parserRegistry.require(source.file().getType());
            ChunkPlanningStrategy planner = strategyRegistry.require(job.strategyCode(), source.file().getType());
            FileResource resource = new FileResource(
                    source.processing().getTenantId(),
                    job.knowledgeId(),
                    job.fileId(),
                    source.file().getPublicId(),
                    source.file().getFileName(),
                    source.file().getType(),
                    snapshotPath);
            ParsedStructure structure = parser.parse(resource);
            ChunkPlan plan = planner.planConfigured(structure, job.strategyConfig());
            List<ChunkDraft> drafts = validateDrafts(plan);
            persistence.replace(job, sourceHash, planner.plannerVersion(),
                    policySnapshot(job.strategyConfig()), drafts);
        } catch (Exception exception) {
            markFailed(job, exception);
        } finally {
            deleteSnapshot(snapshotPath, job.fileId());
        }
    }

    private Path createSnapshot(long fileId, String fileType, byte[] exactSource) throws IOException {
        String normalizedType = fileType == null ? "" : fileType.replaceAll("[^A-Za-z0-9]", "");
        String suffix = normalizedType.isBlank() ? ".snapshot" : "." + normalizedType;
        Path snapshot = Files.createTempFile("chunk-preview-" + fileId + "-", suffix);
        try {
            Files.write(snapshot, exactSource);
            return snapshot;
        } catch (IOException writeFailure) {
            try {
                Files.deleteIfExists(snapshot);
            } catch (IOException cleanupFailure) {
                writeFailure.addSuppressed(cleanupFailure);
            }
            throw writeFailure;
        }
    }

    private void deleteSnapshot(Path snapshotPath, long fileId) {
        if (snapshotPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshotPath);
        } catch (IOException cleanupFailure) {
            log.error("Unable to delete chunk preview snapshot for file {}", fileId, cleanupFailure);
        }
    }

    private ScopedSource requireScopedSource(Job job) {
        AuthContext context = AuthContext.current();
        FileProcessing processing = processingMapper.selectById(job.fileId());
        if (context == null || context.getTenantId() == null || processing == null
                || !context.getTenantId().equals(processing.getTenantId())
                || !Long.valueOf(job.knowledgeId()).equals(processing.getKnowledgeId())
                || !Long.valueOf(job.fileId()).equals(processing.getFileId())
                || !Integer.valueOf(PipelineState.CHUNKING.code()).equals(processing.getPipelineState())
                || !Integer.valueOf(job.lockVersion()).equals(processing.getLockVersion())) {
            throw new FileProcessingService.StateConflictException(
                    "Chunk preview ownership, state, or lock version changed");
        }
        File file = fileMapper.selectById(job.fileId());
        if (file == null || file.getPath() == null) {
            throw new IllegalArgumentException("The source document cannot be read");
        }
        return new ScopedSource(processing, file, Path.of(file.getPath()));
    }

    private List<ChunkDraft> validateDrafts(ChunkPlan plan) {
        if (plan == null || plan.chunks().isEmpty()) {
            throw new IllegalArgumentException("The source document produced no chunks");
        }
        boolean hasVectorizableChunk = false;
        List<ChunkDraft> normalized = new java.util.ArrayList<>(plan.chunks().size());
        for (PlannedChunk planned : plan.chunks()) {
            ChunkDraft draft = planned.draft();
            if (draft == null || draft.content() == null || draft.content().isBlank()) {
                throw new IllegalArgumentException("The planner produced an invalid chunk");
            }
            int bodyTokens = tokenCounter.count(draft.content());
            int totalTokens = tokenCounter.count(
                    ChunkIndexContentBuilder.preview(draft.sectionPath(), draft.content()));
            if (bodyTokens < 0 || totalTokens < 0) {
                throw new IllegalArgumentException("The planner produced an invalid chunk");
            }
            if (planned.type() != ChunkType.PARENT && (totalTokens > plan.indexMaxTokens()
                    || totalTokens > ChunkPolicy.MAX_ALLOWED_TOKENS)) {
                throw new IllegalArgumentException(
                        "The planner produced a chunk that exceeds the token budget");
            }
            hasVectorizableChunk |= planned.type() == ChunkType.SINGLE || planned.type() == ChunkType.CHILD;
            normalized.add(new ChunkDraft(draft.sectionPath(), draft.content(),
                    draft.sourceLocator(), bodyTokens, draft.boundaryReason()));
        }
        if (!hasVectorizableChunk) {
            throw new IllegalArgumentException("The source document produced no vectorizable chunks");
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> policySnapshot(Map<String, Object> strategyConfig) {
        Map<String, Object> snapshot = new LinkedHashMap<>(strategyConfig);
        snapshot.put("tokenizer", tokenCounter.id());
        return Map.copyOf(snapshot);
    }

    private void markFailed(Job job, Exception exception) {
        String message = failureSummary(exception);
        try {
            processingService.fail(job.knowledgeId(), job.fileId(), PipelineState.CHUNKING,
                    job.lockVersion(), 0, message);
        } catch (RuntimeException failureUpdate) {
            log.error("Unable to mark chunk preview as FAILED for file {}", job.fileId(), failureUpdate);
        }
    }

    private String failureSummary(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Job(long knowledgeId, long fileId, String strategyCode, Map<String, Object> strategyConfig,
                      boolean replaceEditedDrafts, int lockVersion,
                      List<ExistingChunkSnapshot> existingChunks) {
        public Job {
            Objects.requireNonNull(strategyCode, "strategyCode");
            strategyConfig = Map.copyOf(Objects.requireNonNull(strategyConfig, "strategyConfig"));
            existingChunks = existingChunks == null ? List.of() : List.copyOf(existingChunks);
        }

        public Job(long knowledgeId, long fileId, String strategyCode, ChunkPolicy policy,
                   boolean replaceEditedDrafts, int lockVersion,
                   List<ExistingChunkSnapshot> existingChunks) {
            this(knowledgeId, fileId, strategyCode, Map.of(
                    "minTokens", policy.minTokens(),
                    "targetTokens", policy.targetTokens(),
                    "maxTokens", policy.maxTokens()), replaceEditedDrafts, lockVersion, existingChunks);
        }

        public ChunkPolicy policy() {
            return new ChunkPolicy(
                    ((Number) strategyConfig.get("minTokens")).intValue(),
                    ((Number) strategyConfig.get("targetTokens")).intValue(),
                    ((Number) strategyConfig.get("maxTokens")).intValue());
        }
    }

    public record ExistingChunkSnapshot(
            Long id,
            UUID publicId,
            Integer status,
            Boolean isModified,
            OffsetDateTime updateTime,
            String contentHash,
            Integer position) {

        public static ExistingChunkSnapshot from(com.starsea.ai.domain.DocumentChunk chunk) {
            Objects.requireNonNull(chunk, "chunk");
            return new ExistingChunkSnapshot(
                    chunk.getId(),
                    chunk.getPublicId(),
                    chunk.getStatus(),
                    chunk.getIsModified(),
                    chunk.getUpdateTime(),
                    chunk.getContentHash(),
                    chunk.getPosition());
        }
    }

    private record ScopedSource(FileProcessing processing, File file, Path path) {
    }
}
