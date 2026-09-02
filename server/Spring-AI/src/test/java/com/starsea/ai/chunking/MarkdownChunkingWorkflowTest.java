package com.starsea.ai.chunking;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.ConfirmRequest;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.chunking.api.ChunkingApiModels.PreviewRequest;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.context.DefaultChunkContextEnricher;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.indexing.ChunkVectorService;
import com.starsea.ai.chunking.indexing.ChunkVectorWorker;
import com.starsea.ai.chunking.indexing.SpringAiChunkVectorGateway;
import com.starsea.ai.chunking.markdown.MarkdownChunkPlanningStrategy;
import com.starsea.ai.chunking.markdown.MarkdownStructureParser;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewPersistenceService;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.chunking.preview.ChunkPreviewWorker;
import com.starsea.ai.chunking.processing.ChunkTaskDispatcher;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.chunking.token.HuggingFaceTokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.FileService;
import com.starsea.ai.service.impl.PgVectorRagServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarkdownChunkingWorkflowTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID FILE_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @TempDir
    Path tempDir;

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void real_services_preserve_adjustments_overlap_source_and_original_q1_lines()
            throws IOException {
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 7L, TENANT_ID, "tenant_admin", "jti"));
        Path uploadedSource = uploadedSampleWithContinuousSection();

        try (ExactCounter exact = exactCounter()) {
            TokenCounter counter = exact.counter();
            WorkflowRepository repository = new WorkflowRepository(uploadedSource);
            WorkflowServices services = workflowServices(repository, counter);

            services.preview().startPreview(KNOWLEDGE_ID, FILE_ID, new PreviewRequest(
                    "MARKDOWN_OPTIMIZED", new ChunkPolicy(20, 70, 140), false, 0));

            assertEquals(PipelineState.CHUNKED.code(), repository.processing.getPipelineState(),
                    repository.processing.getLastError());
            DocumentChunk q1 = repository.chunks.stream()
                    .filter(chunk -> chunk.getContent().contains("Q1"))
                    .findFirst()
                    .orElseThrow();
            assertQ1SourceRange(q1, uploadedSource);
            List<DocumentChunk> overlapPair = continuousPairs(repository.chunks).stream()
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "the fixture must produce adjacent chunks under one heading"));
            assertTrue(continuousPairs(repository.chunks).size() >= 1,
                    "the fixture must produce adjacent chunks under one heading");

            String editedBody = q1.getContent() + "\n\n自主招生咨询专线已经开通。";
            services.commands().edit(KNOWLEDGE_ID, FILE_ID, q1.getPublicId(),
                    new EditChunkRequest(editedBody, false, 40, q1.getLockVersion()));
            DocumentChunk overlapTarget = overlapPair.get(1);
            services.commands().edit(KNOWLEDGE_ID, FILE_ID, overlapTarget.getPublicId(),
                    new EditChunkRequest(overlapTarget.getContent(), true, 40,
                            overlapTarget.getLockVersion()));
            DocumentChunk deleted = repository.chunks.stream()
                    .filter(chunk -> chunk != q1)
                    .filter(chunk -> !chunk.getSectionPath().contains("上下文连续性测试"))
                    .max(Comparator.comparing(DocumentChunk::getPosition))
                    .orElseThrow();
            services.commands().delete(KNOWLEDGE_ID, FILE_ID,
                    deleted.getPublicId(), deleted.getLockVersion());

            assertEquals(PipelineState.ADJUSTING.code(), repository.processing.getPipelineState());
            assertEquals(editedBody, repository.byPublicId(q1.getPublicId()).getContent());
            assertFalse(repository.chunks.contains(deleted));

            services.vectors().confirm(KNOWLEDGE_ID, FILE_ID,
                    new ConfirmRequest(repository.processing.getLockVersion()));

            assertEquals(PipelineState.COMPLETED.code(), repository.processing.getPipelineState());
            assertEquals(6, repository.processing.getLockVersion(),
                    "six legal file transitions must each increment the lock version");
            assertEquals(Map.of(), repository.processing.getContextPolicy());
            assertTrue(repository.chunks.stream()
                    .allMatch(chunk -> chunk.getStatus() == ChunkStatus.ACTIVE.code()));
            repository.assertSuccessfulCasCoverage();
            DocumentChunk enriched = repository.chunks.stream()
                    .filter(chunk -> chunk.getOverlapContent() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("enabled overlap must enrich a continuous chunk"));
            DocumentChunk overlapSource = repository.byId(enriched.getOverlapSourceChunkId());
            assertEquals(overlapSource.getId(), enriched.getOverlapSourceChunkId());
            assertEquals(overlapSource.getSectionPath(), enriched.getSectionPath());
            assertEquals(overlapSource.getPosition() + 1, enriched.getPosition());
            assertTrue(overlapSource.getContent().contains(enriched.getOverlapContent()));
            int overlapStart = overlapSource.getContent().indexOf(enriched.getOverlapContent());
            assertTrue(overlapStart == 0 || isSentenceBoundaryBefore(
                    overlapSource.getContent().charAt(overlapStart - 1)),
                    "copied context must start at the source beginning or after a sentence boundary");
            assertTrue(enriched.getOverlapContent().matches("(?s).*[。！？.!?]$"),
                    "only a complete sentence may be copied");
            ChunkIndexContentBuilder contentBuilder = new ChunkIndexContentBuilder();
            int withoutOverlap = counter.count(contentBuilder.build(
                    enriched.getSectionPath(), null, enriched.getContent()));
            int withOverlap = counter.count(enriched.getIndexContent());
            int independentlyCountedOverlap = withOverlap - withoutOverlap;
            assertEquals(independentlyCountedOverlap, enriched.getOverlapTokenCount());
            assertTrue(independentlyCountedOverlap > 0 && independentlyCountedOverlap <= 40);
            assertTrue(enriched.getIndexContent()
                    .contains("上文：" + enriched.getOverlapContent()));

            repository.assertWrongCasIsRejected(q1.getPublicId());

            DocumentChunk savedQ1 = repository.byPublicId(q1.getPublicId());
            RetrievedChunk result = retrieve(repository, "自主招生咨询专线");
            assertEquals(savedQ1.getPublicId(), result.chunkId());
            assertEquals(savedQ1.getIndexContent(), result.content(),
                    "retrieval must use saved index_content instead of stale vector text");
            assertEquals(savedQ1.getSourceLocator(), result.sourceLocator());

            DocumentChunk last = repository.chunks.stream()
                    .max(Comparator.comparing(DocumentChunk::getPosition))
                    .orElseThrow();
            String reedited = last.getContent() + "\n\n补充说明。";
            services.commands().edit(KNOWLEDGE_ID, FILE_ID, last.getPublicId(),
                    new EditChunkRequest(reedited, last.getLockVersion()));
            assertEquals(PipelineState.ADJUSTING.code(), repository.processing.getPipelineState());
            assertEquals(ChunkStatus.DRAFT.code(), repository.byPublicId(last.getPublicId()).getStatus());

            services.vectors().reindex(KNOWLEDGE_ID, FILE_ID, last.getPublicId());

            assertEquals(PipelineState.COMPLETED.code(), repository.processing.getPipelineState());
            assertEquals(ChunkStatus.ACTIVE.code(), repository.byPublicId(last.getPublicId()).getStatus());
            assertTrue(repository.byPublicId(last.getPublicId()).getIndexContent().contains(reedited));
        }
    }

    private boolean isSentenceBoundaryBefore(char value) {
        return "。！？.!?\n\r".indexOf(value) >= 0;
    }

    private WorkflowServices workflowServices(WorkflowRepository repository, TokenCounter counter) {
        FileProcessingService states = new FileProcessingService(repository.processingMapper);
        ChunkStrategyRegistry strategies = new ChunkStrategyRegistry(
                List.of(new MarkdownChunkPlanningStrategy(counter)));
        DocumentStructureParserRegistry parsers = new DocumentStructureParserRegistry(
                List.of(new MarkdownStructureParser(counter)));
        ChunkPreviewPersistenceService persistence = new ChunkPreviewPersistenceService(
                repository.chunkMapper, repository.processingMapper, states);
        ChunkPreviewWorker previewWorker = new ChunkPreviewWorker(
                repository.fileMapper, repository.processingMapper, parsers, strategies,
                counter, persistence, states);
        Executor executor = command -> {
            AuthContext before = AuthContext.current();
            command.run();
            AuthContext.set(before);
        };
        ChunkPreviewService preview = new ChunkPreviewService(
                repository.processingMapper, repository.fileMapper, repository.chunkMapper,
                strategies, parsers, new ChunkTaskDispatcher(states, executor), previewWorker);
        ChunkVectorGateway gateway = new SpringAiChunkVectorGateway(
                repository.vectorStore, new ObjectMapper());
        ChunkCommandService commands = new ChunkCommandService(
                repository.chunkMapper, repository.processingMapper, states, counter,
                new ChunkIndexContentBuilder(), gateway);
        ChunkVectorWorker vectorWorker = new ChunkVectorWorker(
                repository.processingMapper, repository.fileMapper, repository.chunkMapper,
                states, new DefaultChunkContextEnricher(counter), counter, gateway,
                repository.transactions);
        ChunkVectorService vectors = new ChunkVectorService(
                repository.processingMapper, repository.fileMapper, repository.chunkMapper,
                states, vectorWorker, repository.transactions, executor);
        return new WorkflowServices(preview, commands, vectors);
    }

    private Path uploadedSampleWithContinuousSection() throws IOException {
        Path original = Path.of("src/main/resources/file/科大百事通.md")
                .toAbsolutePath().normalize();
        String continuous = """

                ### 上下文连续性测试

                校园服务中心每天早上八点开放。值班老师会先核对学生证件。材料齐全后可以现场办理。

                新生办理业务需要携带录取通知书。线上预约可以减少等待时间。特殊情况可联系值班老师。

                办理完成后系统会发送确认消息。学生应当妥善保存办理回执。后续查询可以使用回执编号。

                如果信息填写错误需要及时更正。更正完成后系统会再次发送通知。所有通知都应完整阅读。
                """;
        Path uploaded = tempDir.resolve("科大百事通.md");
        Files.writeString(uploaded, Files.readString(original) + continuous,
                StandardCharsets.UTF_8);
        return uploaded;
    }

    private List<List<DocumentChunk>> continuousPairs(List<DocumentChunk> chunks) {
        List<DocumentChunk> ordered = chunks.stream()
                .sorted(Comparator.comparing(DocumentChunk::getPosition))
                .toList();
        List<List<DocumentChunk>> pairs = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            DocumentChunk previous = ordered.get(index - 1);
            DocumentChunk current = ordered.get(index);
            if (previous.getSectionPath().contains("上下文连续性测试")
                    && previous.getSectionPath().equals(current.getSectionPath())
                    && current.getPosition() == previous.getPosition() + 1) {
                pairs.add(List.of(previous, current));
            }
        }
        return pairs;
    }

    private void assertQ1SourceRange(DocumentChunk q1, Path source) throws IOException {
        List<String> lines = Files.readAllLines(source);
        int questionLine = findLine(lines, "Q1：湖南科技大学是几本");
        int answerLine = findLine(lines, "A：湖南科技大学是湖南省属重点本科高校");
        int start = ((Number) q1.getSourceLocator().get("startLine")).intValue();
        int end = ((Number) q1.getSourceLocator().get("endLine")).intValue();
        assertTrue(start <= questionLine && end >= answerLine,
                "source range must cover the original Q1 question and answer lines");
        assertTrue(lines.get(questionLine - 1).contains("Q1：湖南科技大学是几本"));
        assertTrue(lines.get(answerLine - 1).contains("A：湖南科技大学是湖南省属重点本科高校"));
    }

    private int findLine(List<String> lines, String marker) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(marker)) {
                return index + 1;
            }
        }
        throw new AssertionError("missing fixture line: " + marker);
    }

    private RetrievedChunk retrieve(WorkflowRepository repository, String queryText) {
        FileService fileService = mock(FileService.class);
        when(fileService.listEnabledByKnowledgeIds(TENANT_ID, Set.of(KNOWLEDGE_ID)))
                .thenReturn(List.of(repository.file));
        PgVectorRagServiceImpl retrieval = new PgVectorRagServiceImpl(
                repository.vectorStore, fileService, repository.chunkMapper);
        List<RetrievedChunk> results = retrieval.retrieve(
                new RetrievalQuery(queryText, Set.of(KNOWLEDGE_ID), 1, 0.0));
        assertEquals(1, results.size());
        return results.get(0);
    }

    private ExactCounter exactCounter() throws IOException {
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(
                new ClassPathResource("tokenizer/bge-base-zh-v1.5-tokenizer.json")
                        .getInputStream(), Map.of());
        return new ExactCounter(new HuggingFaceTokenCounter(
                tokenizer, "BAAI/bge-base-zh-v1.5@7dfbf196"));
    }

    private record WorkflowServices(ChunkPreviewService preview,
                                    ChunkCommandService commands,
                                    ChunkVectorService vectors) {
    }

    private record ExactCounter(HuggingFaceTokenCounter counter) implements AutoCloseable {
        @Override
        public void close() {
            counter.close();
        }
    }

    /**
     * Stateful mapper fake: production services own every transition and mutation; this class only
     * applies the mapper contract to in-memory rows. External vector search remains the sole fake
     * outside the persistence boundary.
     */
    private static final class WorkflowRepository {
        private final File file = new File();
        private final FileProcessing processing = new FileProcessing();
        private final List<DocumentChunk> chunks = new ArrayList<>();
        private final FakeVectorStore vectorStore = new FakeVectorStore();
        private final FileMapper fileMapper = mock(FileMapper.class);
        private final FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        private final DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        private final TransactionTemplate transactions = mock(TransactionTemplate.class);
        private final List<CasAudit> processingCasAudits = new ArrayList<>();
        private final List<CasAudit> chunkCasAudits = new ArrayList<>();
        private long nextChunkId = 1;

        private WorkflowRepository(Path source) {
            Configuration configuration = new Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(configuration, "workflow-file-processing"),
                    FileProcessing.class);
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(configuration, "workflow-document-chunk"),
                    DocumentChunk.class);
            file.setId(FILE_ID);
            file.setPublicId(FILE_PUBLIC_ID);
            file.setFileName("科大百事通.md");
            file.setType("md");
            file.setPath(source.toString());
            file.setStatus(1);
            processing.setFileId(FILE_ID);
            processing.setTenantId(TENANT_ID);
            processing.setKnowledgeId(KNOWLEDGE_ID);
            processing.setPipelineState(PipelineState.UPLOADED.code());
            processing.setProgress(0);
            processing.setLockVersion(0);
            processing.setPolicySnapshot(Map.of());
            processing.setContextPolicy(Map.of());
            stubTransactions();
            stubFiles();
            stubProcessing();
            stubChunks();
        }

        private void stubTransactions() {
            when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Consumer<TransactionStatus> callback = invocation.getArgument(0);
                callback.accept(mock(TransactionStatus.class));
                return null;
            }).when(transactions).executeWithoutResult(any());
        }

        private void stubFiles() {
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        }

        private void stubProcessing() {
            when(processingMapper.selectById(FILE_ID)).thenReturn(processing);
            when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                    .thenReturn(processing);
            when(processingMapper.transition(eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID),
                    anyInt(), anyInt(), anyInt(), anyInt(), any(), any()))
                    .thenAnswer(invocation -> {
                        int expected = invocation.getArgument(3);
                        int target = invocation.getArgument(4);
                        int progress = invocation.getArgument(5);
                        int lockVersion = invocation.getArgument(6);
                        if (!Objects.equals(processing.getPipelineState(), expected)
                                || !Objects.equals(processing.getLockVersion(), lockVersion)) {
                            return 0;
                        }
                        processing.setPipelineState(target);
                        processing.setProgress(progress);
                        processing.setLockVersion(lockVersion + 1);
                        processing.setFailedFromState(invocation.getArgument(7));
                        processing.setLastError(invocation.getArgument(8));
                        return 1;
                    });
            when(processingMapper.update(any(FileProcessing.class), any(Wrapper.class)))
                    .thenAnswer(invocation -> {
                        FileProcessing patch = invocation.getArgument(0);
                        Wrapper<?> wrapper = invocation.getArgument(1);
                        boolean contextUpdate = patch.getContextPolicy() != null;
                        Map<String, Object> required = new LinkedHashMap<>();
                        required.put("file_id", processing.getFileId());
                        required.put("tenant_id", processing.getTenantId());
                        required.put("knowledge_id", processing.getKnowledgeId());
                        required.put("pipeline_state", processing.getPipelineState());
                        required.put("lock_version", processing.getLockVersion());
                        if (contextUpdate) required.put("source_hash", processing.getSourceHash());
                        boolean matched = matches(wrapper, required);
                        processingCasAudits.add(new CasAudit(
                                contextUpdate ? "CONTEXT_POLICY" : "PREVIEW_METADATA",
                                Map.copyOf(required), matched));
                        if (!matched) return 0;
                        if (patch.getSourceHash() != null) processing.setSourceHash(patch.getSourceHash());
                        if (patch.getStrategyCode() != null) processing.setStrategyCode(patch.getStrategyCode());
                        if (patch.getPlannerVersion() != null) processing.setPlannerVersion(patch.getPlannerVersion());
                        if (patch.getPolicySnapshot() != null) processing.setPolicySnapshot(patch.getPolicySnapshot());
                        if (patch.getContextPolicy() != null) processing.setContextPolicy(patch.getContextPolicy());
                        return 1;
                    });
        }

        private void stubChunks() {
            when(chunkMapper.findByFile(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                    .thenAnswer(invocation -> orderedChunks());
            when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                    .thenAnswer(invocation -> orderedChunks());
            when(chunkMapper.deleteReplaceableDrafts(FILE_ID, TENANT_ID, KNOWLEDGE_ID, false))
                    .thenAnswer(invocation -> removeReplaceable(false));
            when(chunkMapper.deleteReplaceableDrafts(FILE_ID, TENANT_ID, KNOWLEDGE_ID, true))
                    .thenAnswer(invocation -> removeReplaceable(true));
            when(chunkMapper.insert(any(DocumentChunk.class))).thenAnswer(invocation -> {
                DocumentChunk chunk = invocation.getArgument(0);
                chunk.setId(nextChunkId++);
                chunks.add(chunk);
                return 1;
            });
            when(chunkMapper.findScopedByPublicIdForUpdate(
                    eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID), any(UUID.class)))
                    .thenAnswer(invocation -> byPublicId(invocation.getArgument(3)));
            when(chunkMapper.findScopedByPosition(
                    eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID), anyInt()))
                    .thenAnswer(invocation -> chunks.stream()
                            .filter(chunk -> Objects.equals(
                                    chunk.getPosition(), invocation.getArgument(3)))
                            .findFirst().orElse(null));
            when(chunkMapper.findNextDependentForUpdate(
                    eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID), anyInt()))
                    .thenAnswer(invocation -> chunks.stream()
                            .filter(chunk -> Objects.equals(chunk.getPosition(), invocation.getArgument(3)))
                            .filter(chunk -> Boolean.TRUE.equals(chunk.getOverlapEnabled()))
                            .findFirst().orElse(null));
            when(chunkMapper.deleteScoped(eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID),
                    any(UUID.class), anyInt())).thenAnswer(invocation -> deleteChunk(invocation));
            when(chunkMapper.update(any(DocumentChunk.class), any(Wrapper.class)))
                    .thenAnswer(invocation -> applyChunkPatch(
                            invocation.getArgument(0), invocation.getArgument(1)));
            when(chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                    .thenAnswer(invocation -> {
                        @SuppressWarnings("unchecked")
                        List<UUID> ids = invocation.getArgument(2);
                        return chunks.stream()
                                .filter(chunk -> chunk.getStatus() == ChunkStatus.ACTIVE.code())
                                .filter(chunk -> ids.contains(chunk.getPublicId()))
                                .toList();
                    });
        }

        private int removeReplaceable(boolean includeModified) {
            int before = chunks.size();
            chunks.removeIf(chunk -> chunk.getStatus() == ChunkStatus.DRAFT.code()
                    && (includeModified || !Boolean.TRUE.equals(chunk.getIsModified())));
            return before - chunks.size();
        }

        private int deleteChunk(org.mockito.invocation.InvocationOnMock invocation) {
            UUID publicId = invocation.getArgument(3);
            int version = invocation.getArgument(4);
            DocumentChunk chunk = byPublicId(publicId);
            if (chunk == null || chunk.getLockVersion() != version
                    || chunk.getStatus() == ChunkStatus.INDEXING.code()) {
                return 0;
            }
            chunks.remove(chunk);
            return 1;
        }

        private int applyChunkPatch(DocumentChunk patch, Wrapper<?> wrapper) {
            boolean activation = patch.getStatus() == ChunkStatus.ACTIVE.code();
            boolean draftMutation = patch.getStatus() == ChunkStatus.DRAFT.code();
            DocumentChunk target = chunks.stream()
                    .filter(chunk -> draftMutation
                            ? chunk.getStatus() != ChunkStatus.INDEXING.code()
                            && matchesMutableChunkCas(wrapper, chunk)
                            : matchesChunkCas(wrapper, chunk, activation))
                    .findFirst().orElse(null);
            Map<String, Object> required = target == null
                    ? Map.of()
                    : chunkCasValues(target, activation);
            String stage = activation ? "ACTIVE"
                    : draftMutation ? "DRAFT" : "INDEXING";
            chunkCasAudits.add(new CasAudit(
                    stage, required, target != null));
            if (target == null) return 0;
            if (patch.getStatus() == ChunkStatus.INDEXING.code()) {
                target.setStatus(ChunkStatus.INDEXING.code());
                target.setLastError(null);
                target.setLockVersion(target.getLockVersion() + 1);
                return 1;
            }
            if (patch.getStatus() == ChunkStatus.ACTIVE.code()) {
                target.setStatus(ChunkStatus.ACTIVE.code());
                target.setOverlapContent(patch.getOverlapContent());
                target.setOverlapSourceChunkId(patch.getOverlapSourceChunkId());
                target.setOverlapTokenCount(patch.getOverlapTokenCount());
                target.setIndexContent(patch.getIndexContent());
                target.setLastError(null);
                target.setLockVersion(target.getLockVersion() + 1);
                target.setSourceDocumentPublicId(FILE_PUBLIC_ID);
                target.setSourceFileName(file.getFileName());
                target.setSourceFileType(file.getType());
                return 1;
            }
            if (draftMutation) {
                if (patch.getContent() != null) target.setContent(patch.getContent());
                if (patch.getTokenCount() != null) target.setTokenCount(patch.getTokenCount());
                if (patch.getContentHash() != null) target.setContentHash(patch.getContentHash());
                if (patch.getOverlapEnabled() != null) target.setOverlapEnabled(patch.getOverlapEnabled());
                if (patch.getOverlapTokenLimit() != null) {
                    target.setOverlapTokenLimit(patch.getOverlapTokenLimit());
                }
                target.setOverlapContent(patch.getOverlapContent());
                target.setOverlapSourceChunkId(patch.getOverlapSourceChunkId());
                target.setOverlapTokenCount(patch.getOverlapTokenCount());
                target.setIndexContent(patch.getIndexContent());
                target.setStatus(ChunkStatus.DRAFT.code());
                if (patch.getIsModified() != null) target.setIsModified(patch.getIsModified());
                target.setLastError(null);
                target.setLockVersion(target.getLockVersion() + 1);
                return 1;
            }
            return 0;
        }

        private boolean matchesMutableChunkCas(Wrapper<?> wrapper, DocumentChunk chunk) {
            Map<String, Object> required = new LinkedHashMap<>(chunkCasValues(chunk, false));
            required.remove("status");
            return matches(wrapper, required);
        }

        private boolean matchesChunkCas(Wrapper<?> wrapper, DocumentChunk chunk,
                                        boolean includeContentHash) {
            return matches(wrapper, chunkCasValues(chunk, includeContentHash));
        }

        private Map<String, Object> chunkCasValues(DocumentChunk chunk,
                                                    boolean includeContentHash) {
            Map<String, Object> required = new LinkedHashMap<>();
            required.put("id", chunk.getId());
            required.put("tenant_id", chunk.getTenantId());
            required.put("knowledge_id", chunk.getKnowledgeId());
            required.put("file_id", chunk.getFileId());
            required.put("public_id", chunk.getPublicId());
            required.put("status", chunk.getStatus());
            required.put("lock_version", chunk.getLockVersion());
            if (includeContentHash) required.put("content_hash", chunk.getContentHash());
            return Map.copyOf(required);
        }

        private boolean matches(Wrapper<?> wrapper, Map<String, Object> required) {
            if (!(wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper)) return false;
            String sql = wrapper.getSqlSegment();
            Map<String, Object> parameters = abstractWrapper.getParamNameValuePairs();
            for (Map.Entry<String, Object> condition : required.entrySet()) {
                String camelColumn = snakeToCamel(condition.getKey());
                Pattern pattern = Pattern.compile(
                        "(?i)(?:^|[^a-z0-9_])(?:" + Pattern.quote(condition.getKey())
                                + "|" + Pattern.quote(camelColumn) + ")"
                                + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)}");
                Matcher matcher = pattern.matcher(sql);
                if (!matcher.find()
                        || !Objects.equals(parameters.get(matcher.group(1)), condition.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private String snakeToCamel(String value) {
            StringBuilder converted = new StringBuilder();
            boolean uppercase = false;
            for (char character : value.toCharArray()) {
                if (character == '_') {
                    uppercase = true;
                } else if (uppercase) {
                    converted.append(Character.toUpperCase(character));
                    uppercase = false;
                } else {
                    converted.append(character);
                }
            }
            return converted.toString();
        }

        private void assertSuccessfulCasCoverage() {
            assertEquals(List.of("PREVIEW_METADATA"),
                    processingCasAudits.stream().map(CasAudit::stage).toList());
            assertTrue(processingCasAudits.stream().allMatch(CasAudit::matched));
            assertTrue(processingCasAudits.get(0).required().keySet().containsAll(Set.of(
                    "file_id", "tenant_id", "knowledge_id", "pipeline_state", "lock_version")));
            assertEquals(1, processingCasAudits.get(0).required().get("lock_version"));
            long indexing = chunkCasAudits.stream()
                    .filter(audit -> audit.stage().equals("INDEXING")).count();
            long active = chunkCasAudits.stream()
                    .filter(audit -> audit.stage().equals("ACTIVE")).count();
            assertEquals(chunks.size(), indexing);
            assertEquals(chunks.size(), active);
            assertTrue(chunkCasAudits.stream().allMatch(CasAudit::matched));
            chunkCasAudits.forEach(audit -> {
                assertTrue(audit.required().keySet().containsAll(Set.of(
                        "id", "public_id", "tenant_id", "knowledge_id", "file_id",
                        "status", "lock_version")));
                if (audit.stage().equals("ACTIVE")) {
                    assertTrue(audit.required().containsKey("content_hash"));
                }
            });
            for (DocumentChunk chunk : chunks) {
                CasAudit indexingAudit = chunkCasAudits.stream()
                        .filter(audit -> audit.stage().equals("INDEXING"))
                        .filter(audit -> Objects.equals(audit.required().get("id"), chunk.getId()))
                        .findFirst().orElseThrow();
                CasAudit activeAudit = chunkCasAudits.stream()
                        .filter(audit -> audit.stage().equals("ACTIVE"))
                        .filter(audit -> Objects.equals(audit.required().get("id"), chunk.getId()))
                        .findFirst().orElseThrow();
                int beforeIndexing = ((Number) indexingAudit.required().get("lock_version")).intValue();
                int beforeActive = ((Number) activeAudit.required().get("lock_version")).intValue();
                assertEquals(beforeIndexing + 1, beforeActive);
                assertEquals(beforeActive + 1, chunk.getLockVersion());
            }
        }

        private void assertWrongCasIsRejected(UUID publicId) {
            DocumentChunk chunk = byPublicId(publicId);
            int status = chunk.getStatus();
            int version = chunk.getLockVersion();
            DocumentChunk patch = new DocumentChunk();
            patch.setStatus(ChunkStatus.INDEXING.code());
            UpdateWrapper<DocumentChunk> wrong = new UpdateWrapper<DocumentChunk>()
                    .eq("id", chunk.getId())
                    .eq("tenant_id", chunk.getTenantId())
                    .eq("knowledge_id", chunk.getKnowledgeId())
                    .eq("file_id", chunk.getFileId())
                    .eq("public_id", chunk.getPublicId())
                    .eq("status", chunk.getStatus())
                    .eq("lock_version", version + 99);
            assertEquals(0, chunkMapper.update(patch, wrong));
            assertEquals(status, chunk.getStatus());
            assertEquals(version, chunk.getLockVersion());

            Map<String, Object> previousContext = processing.getContextPolicy();
            FileProcessing contextPatch = new FileProcessing();
            contextPatch.setContextPolicy(Map.of("overlapEnabled", false, "overlapTokens", 40));
            UpdateWrapper<FileProcessing> stale = new UpdateWrapper<FileProcessing>()
                    .eq("file_id", processing.getFileId())
                    .eq("tenant_id", processing.getTenantId())
                    .eq("knowledge_id", processing.getKnowledgeId())
                    .eq("pipeline_state", PipelineState.ADJUSTING.code())
                    .eq("lock_version", processing.getLockVersion() - 1)
                    .eq("source_hash", processing.getSourceHash());
            assertEquals(0, processingMapper.update(contextPatch, stale));
            assertEquals(previousContext, processing.getContextPolicy());
        }

        private List<DocumentChunk> orderedChunks() {
            return chunks.stream()
                    .sorted(Comparator.comparing(DocumentChunk::getPosition))
                    .toList();
        }

        private DocumentChunk byPublicId(UUID publicId) {
            return chunks.stream()
                    .filter(chunk -> chunk.getPublicId().equals(publicId))
                    .findFirst().orElse(null);
        }

        private DocumentChunk byId(Long id) {
            assertNotNull(id);
            return chunks.stream()
                    .filter(chunk -> chunk.getId().equals(id))
                    .findFirst().orElseThrow();
        }

        private record CasAudit(String stage, Map<String, Object> required, boolean matched) {
        }
    }

    private static final class FakeVectorStore implements VectorStore {
        private final Map<String, Document> documents = new LinkedHashMap<>();

        @Override
        public void add(List<Document> values) {
            values.forEach(value -> documents.put(value.getId(), value));
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(documents::remove);
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
            documents.clear();
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return documents.values().stream()
                    .filter(document -> document.getText().contains(request.getQuery()))
                    .limit(request.getTopK())
                    .map(document -> document.mutate()
                            .text("stale vector payload")
                            .score(0.97)
                            .build())
                    .toList();
        }
    }
}
