package com.starsea.ai.chunking.preview;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.general.UnicodeText;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkInputResult;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ChunkPlanningResult;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.model.PreviewSummary;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkInputProviderRegistry;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final long DEFAULT_MAX_SOURCE_BYTES = 52_428_800L;

    private final FileMapper fileMapper;
    private final FileProcessingMapper processingMapper;
    private final DocumentStructureParserRegistry parserRegistry;
    private final ChunkInputProviderRegistry inputProviderRegistry;
    private final ChunkStrategyRegistry strategyRegistry;
    private final TokenCounter tokenCounter;
    private final ChunkPreviewPersistenceService persistence;
    private final FileProcessingService processingService;
    private final long maxSourceBytes;

    @Autowired
    public ChunkPreviewWorker(FileMapper fileMapper,
                              FileProcessingMapper processingMapper,
                              DocumentStructureParserRegistry parserRegistry,
                              ChunkInputProviderRegistry inputProviderRegistry,
                              ChunkStrategyRegistry strategyRegistry,
                              TokenCounter tokenCounter,
                              ChunkPreviewPersistenceService persistence,
                              FileProcessingService processingService,
                              @Value("${chunking.extraction.max-source-bytes:52428800}") long maxSourceBytes) {
        this.fileMapper = fileMapper;
        this.processingMapper = processingMapper;
        this.parserRegistry = parserRegistry;
        this.inputProviderRegistry = inputProviderRegistry;
        this.strategyRegistry = strategyRegistry;
        this.tokenCounter = tokenCounter;
        this.persistence = persistence;
        this.processingService = processingService;
        if (maxSourceBytes < 1) throw new IllegalArgumentException("maxSourceBytes must be positive");
        this.maxSourceBytes = maxSourceBytes;
    }

    public ChunkPreviewWorker(FileMapper fileMapper,
                              FileProcessingMapper processingMapper,
                              DocumentStructureParserRegistry parserRegistry,
                              ChunkInputProviderRegistry inputProviderRegistry,
                              ChunkStrategyRegistry strategyRegistry,
                              TokenCounter tokenCounter,
                              ChunkPreviewPersistenceService persistence,
                              FileProcessingService processingService) {
        this(fileMapper, processingMapper, parserRegistry, inputProviderRegistry, strategyRegistry,
                tokenCounter, persistence, processingService, DEFAULT_MAX_SOURCE_BYTES);
    }

    /** Compatibility constructor for isolated Markdown tests. */
    public ChunkPreviewWorker(FileMapper fileMapper,
                              FileProcessingMapper processingMapper,
                              DocumentStructureParserRegistry parserRegistry,
                              ChunkStrategyRegistry strategyRegistry,
                              TokenCounter tokenCounter,
                              ChunkPreviewPersistenceService persistence,
                              FileProcessingService processingService) {
        this(fileMapper, processingMapper, parserRegistry, null, strategyRegistry,
                tokenCounter, persistence, processingService, DEFAULT_MAX_SOURCE_BYTES);
    }

    public void generate(Job job) {
        Objects.requireNonNull(job, "job");
        Path snapshotPath = null;
        try {
            ScopedSource source = requireScopedSource(job);
            SourceSnapshot snapshot = createSnapshot(
                    job.fileId(), source.file().getType(), source.path());
            snapshotPath = snapshot.path();
            String sourceHash = snapshot.sha256();
            ChunkPlanningStrategy planner = strategyRegistry.require(job.strategyCode(), source.file().getType());
            if (!planner.plannerVersion().equals(job.plannerVersion())) {
                throw new FileProcessingService.StateConflictException("Chunk planner version changed");
            }
            FileResource resource = new FileResource(
                    source.processing().getTenantId(),
                    job.knowledgeId(),
                    job.fileId(),
                    source.file().getPublicId(),
                    source.file().getFileName(),
                    source.file().getType(),
                    snapshotPath);
            if (inputProviderRegistry == null) {
                generateLegacyMarkdown(job, sourceHash, planner, resource);
            } else {
                ChunkInputProvider provider = inputProviderRegistry.require(
                        job.strategyCode(), source.file().getType());
                ChunkInputResult input = provider.provide(resource, sourceHash, job.strategyConfig());
                ChunkPlanningResult planning = planner.plan(new ChunkPlanningRequest(
                        input.structure(), job.strategyConfig(), job.contextConfig(), job.maxIndexTokens()));
                List<ChunkDraft> drafts = validateDrafts(
                        planning.drafts(), job.strategyConfig(), job.maxIndexTokens());
                verifyUnchangedSource(source.path(), sourceHash);
                Map<String, Object> executionMetadata = new LinkedHashMap<>(input.extractorMetadata());
                executionMetadata.put("tokenizerId", tokenCounter.id());
                executionMetadata.put("tokenHardLimit", job.maxIndexTokens());
                PreviewSummary summary = new PreviewSummary(input.preprocessingSummary(),
                        input.delimiterMatched(), planning.forcedSplitCount(),
                        planning.tokenLimitedSplitCount());
                persistence.replace(job, sourceHash, job.plannerVersion(),
                        policySnapshot(job.strategyConfig()), contextSnapshot(job.contextConfig()),
                        Map.copyOf(executionMetadata), summary.toMap(), drafts);
            }
        } catch (Exception exception) {
            markFailed(job, exception);
        } finally {
            deleteSnapshot(snapshotPath, job.fileId());
        }
    }

    private void generateLegacyMarkdown(Job job, String sourceHash, ChunkPlanningStrategy planner,
                                        FileResource resource) {
        DocumentStructureParser parser = parserRegistry.require(resource.fileType());
        ParsedStructure structure = parser.parse(resource);
        ChunkPolicy policy = job.policy();
        List<ChunkDraft> drafts = validateDrafts(planner.plan(structure, policy),
                policy, job.maxIndexTokens());
        persistence.replace(job, sourceHash, planner.plannerVersion(), policySnapshot(policy), drafts);
    }

    private SourceSnapshot createSnapshot(long fileId, String fileType, Path source) throws IOException {
        requireSourceSize(source);
        String normalizedType = fileType == null ? "" : fileType.replaceAll("[^A-Za-z0-9]", "");
        String suffix = normalizedType.isBlank() ? ".snapshot" : "." + normalizedType;
        Path snapshot = Files.createTempFile("chunk-preview-" + fileId + "-", suffix);
        try {
            MessageDigest digest = sha256Digest();
            long copied = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = openSource(source);
                 OutputStream output = Files.newOutputStream(snapshot,
                         StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                while (true) {
                    long remaining = maxSourceBytes - copied;
                    int requested = remaining >= buffer.length
                            ? buffer.length : Math.toIntExact(remaining + 1);
                    int read = input.read(buffer, 0, requested);
                    if (read < 0) break;
                    if (read == 0) continue;
                    if (read > remaining) {
                        throw sourceTooLarge();
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    copied += read;
                }
            }
            if (copied == 0) {
                throw new IllegalArgumentException("The source document is empty");
            }
            return new SourceSnapshot(snapshot, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException writeFailure) {
            try {
                Files.deleteIfExists(snapshot);
            } catch (IOException cleanupFailure) {
                writeFailure.addSuppressed(cleanupFailure);
            }
            throw writeFailure;
        }
    }

    protected InputStream openSource(Path path) throws IOException {
        return Files.newInputStream(path);
    }

    private void requireSourceSize(Path source) throws IOException {
        if (Files.size(source) > maxSourceBytes) {
            throw sourceTooLarge();
        }
    }

    private com.starsea.ai.chunking.extraction.DocumentTextExtractor.ExtractionException sourceTooLarge() {
        return new com.starsea.ai.chunking.extraction.DocumentTextExtractor.ExtractionException(
                com.starsea.ai.chunking.extraction.DocumentTextExtractor.FailureReason.SOURCE_TOO_LARGE,
                "Source document exceeds the extraction size limit");
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

    private List<ChunkDraft> validateDrafts(List<ChunkDraft> drafts,
                                            ChunkStrategyConfig config, int maxIndexTokens) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("The source document produced no chunks");
        }
        List<ChunkDraft> normalized = new java.util.ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            if (draft == null || draft.content() == null || UnicodeText.isBlank(draft.content())) {
                throw new IllegalArgumentException("The planner produced an invalid chunk");
            }
            int bodyTokens = tokenCounter.count(draft.content());
            int totalTokens = tokenCounter.count(ChunkIndexContentBuilder.preview(
                    draft.sectionPath(), draft.content()));
            int strategyTokenLimit = config instanceof ChunkPolicy policy
                    ? policy.maxTokens() : maxIndexTokens;
            int characterLimit = config instanceof GeneralChunkConfig general
                    ? general.maxCharacters() : Integer.MAX_VALUE;
            if (bodyTokens < 0 || totalTokens < 0
                    || totalTokens > strategyTokenLimit || totalTokens > maxIndexTokens
                    || draft.content().codePointCount(0, draft.content().length()) > characterLimit) {
                throw new IllegalArgumentException(
                        "The planner produced a chunk that exceeds the token budget");
            }
            normalized.add(new ChunkDraft(draft.sectionPath(), draft.content(),
                    draft.sourceLocator(), bodyTokens, draft.boundaryReason()));
        }
        return List.copyOf(normalized);
    }

    private Map<String, Object> policySnapshot(ChunkStrategyConfig config) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (config instanceof ChunkPolicy policy) {
            snapshot.put("minTokens", policy.minTokens());
            snapshot.put("targetTokens", policy.targetTokens());
            snapshot.put("maxTokens", policy.maxTokens());
            if (inputProviderRegistry == null) snapshot.put("tokenizer", tokenCounter.id());
        } else if (config instanceof GeneralChunkConfig general) {
            snapshot.put("delimiter", general.delimiter());
            snapshot.put("delimiterMode", general.delimiterMode().name());
            snapshot.put("maxCharacters", general.maxCharacters());
            snapshot.put("collapseWhitespace", general.collapseWhitespace());
            snapshot.put("removeUrls", general.removeUrls());
            snapshot.put("removeEmails", general.removeEmails());
        } else {
            throw new IllegalArgumentException("Unsupported chunk strategy config");
        }
        return Map.copyOf(snapshot);
    }

    private Map<String, Object> contextSnapshot(ContextConfig context) {
        return Map.of("enabled", context.enabled(), "mode", context.mode().name(),
                "limit", context.limit(), "unit", context.unit().name());
    }

    private void verifyUnchangedSource(Path source, String expectedHash) throws IOException {
        requireSourceSize(source);
        MessageDigest digest = sha256Digest();
        long readTotal = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = openSource(source)) {
            while (true) {
                long remaining = maxSourceBytes - readTotal;
                int requested = remaining >= buffer.length
                        ? buffer.length : Math.toIntExact(remaining + 1);
                int read = input.read(buffer, 0, requested);
                if (read < 0) break;
                if (read == 0) continue;
                if (read > remaining) throw sourceTooLarge();
                digest.update(buffer, 0, read);
                readTotal += read;
            }
        }
        if (!expectedHash.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw com.starsea.ai.chunking.api.ChunkingException.sourceChanged();
        }
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
        if (exception instanceof com.starsea.ai.chunking.extraction.DocumentTextExtractor.ExtractionException
                extraction) {
            return extraction.reason().name() + ": " + extraction.getMessage();
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Job(long knowledgeId, long fileId, String strategyCode,
                      ChunkStrategyConfig strategyConfig, ContextConfig contextConfig,
                      String plannerVersion, int maxIndexTokens,
                      boolean replaceEditedDrafts, int lockVersion,
                      List<ExistingChunkSnapshot> existingChunks) {
        public Job {
            Objects.requireNonNull(strategyCode, "strategyCode");
            Objects.requireNonNull(strategyConfig, "strategyConfig");
            Objects.requireNonNull(contextConfig, "contextConfig");
            Objects.requireNonNull(plannerVersion, "plannerVersion");
            existingChunks = existingChunks == null ? List.of() : List.copyOf(existingChunks);
        }

        public Job(long knowledgeId, long fileId, String strategyCode, ChunkPolicy policy,
                   boolean replaceEditedDrafts, int lockVersion,
                   List<ExistingChunkSnapshot> existingChunks) {
            this(knowledgeId, fileId, strategyCode, policy, ContextConfig.markdownDefaults(),
                    "markdown-adaptive-v1", ChunkPolicy.MAX_ALLOWED_TOKENS,
                    replaceEditedDrafts, lockVersion, existingChunks);
        }

        public ChunkPolicy policy() {
            return (ChunkPolicy) strategyConfig;
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

    private record SourceSnapshot(Path path, String sha256) {
    }
}
