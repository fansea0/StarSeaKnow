package com.starsea.ai.chunking.preview;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.registry.ChunkStrategyRegistry;
import com.starsea.ai.chunking.registry.DocumentStructureParserRegistry;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.config.TenantLineHandlerImpl;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkPreviewWorkerTest {

    @TempDir
    Path tempDir;

    private FileMapper fileMapper;
    private FileProcessingMapper processingMapper;
    private DocumentStructureParser parser;
    private ChunkPlanningStrategy planner;
    private ChunkPreviewPersistenceService persistence;
    private FileProcessingService processingService;
    private ChunkPreviewWorker worker;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 1L, "tenant_admin", "jti"));
        source = tempDir.resolve("source.md");
        Files.writeString(source, "# Title\n\nExact source bytes.\n");
        fileMapper = mock(FileMapper.class);
        processingMapper = mock(FileProcessingMapper.class);
        parser = mock(DocumentStructureParser.class);
        planner = mock(ChunkPlanningStrategy.class);
        persistence = mock(ChunkPreviewPersistenceService.class);
        processingService = mock(FileProcessingService.class);
        when(parser.supportedFileTypes()).thenReturn(Set.of("md"));
        when(planner.code()).thenReturn("MARKDOWN_OPTIMIZED");
        when(planner.supportedFileTypes()).thenReturn(Set.of("md"));
        when(planner.plannerVersion()).thenReturn("markdown-adaptive-v1");
        when(fileMapper.selectById(20L)).thenReturn(file(source));
        when(processingMapper.selectById(20L)).thenReturn(processing());
        TokenCounter tokenCounter = mock(TokenCounter.class);
        when(tokenCounter.id()).thenReturn("BAAI/bge-base-zh-v1.5@test-sha");
        when(tokenCounter.count(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).codePointCount(0,
                        ((String) invocation.getArgument(0)).length()));
        worker = new ChunkPreviewWorker(
                fileMapper,
                processingMapper,
                new DocumentStructureParserRegistry(List.of(parser)),
                new ChunkStrategyRegistry(List.of(planner)),
                tokenCounter,
                persistence,
                processingService);
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void hashes_exact_source_selects_parser_and_planner_independently_and_persists_snapshot() {
        FileResource resource = new FileResource(1L, 10L, 20L, null, "source.md", "md", source);
        ParsedStructure structure = new ParsedStructure(resource, List.of());
        ChunkDraft draft = draft("Body", 3);
        when(parser.parse(any(FileResource.class))).thenReturn(structure);
        when(planner.plan(structure, new ChunkPolicy(100, 400, 512))).thenReturn(List.of(draft));

        worker.generate(job());

        verify(parser).parse(any(FileResource.class));
        verify(planner).plan(structure, new ChunkPolicy(100, 400, 512));
        verify(persistence).replace(
                eq(job()),
                eq("cafc00b3438fefaa916b121d6475c4466514fdf3b67d2d33a340017e0a341619"),
                eq("markdown-adaptive-v1"),
                eq(Map.of(
                        "minTokens", 100,
                        "targetTokens", 400,
                        "maxTokens", 512,
                        "tokenizer", "BAAI/bge-base-zh-v1.5@test-sha")),
                eq(List.of(draft("Body", 4))));
    }

    @Test
    void rejects_a_malicious_planner_that_underreports_title_and_body_tokens() {
        FileResource resource = new FileResource(1L, 10L, 20L, null, "source.md", "md", source);
        ParsedStructure structure = new ParsedStructure(resource, List.of());
        String oversized = "x".repeat(510);
        ChunkDraft dishonest = new ChunkDraft(
                List.of("Title"), oversized,
                new SourceLocator("markdown", List.of("b1"), 0, oversized.length(),
                        1, 1, null, null, List.of()),
                1, Map.of());
        when(parser.parse(any(FileResource.class))).thenReturn(structure);
        when(planner.plan(structure, new ChunkPolicy(100, 400, 512)))
                .thenReturn(List.of(dishonest));

        worker.generate(job());

        verify(persistence, never()).replace(any(), any(), any(), any(), any());
        verify(processingService).fail(eq(10L), eq(20L), eq(PipelineState.CHUNKING),
                eq(1), eq(0), org.mockito.ArgumentMatchers.contains("token budget"));
    }

    @Test
    void parser_failure_writes_failed_from_chunking_without_persisting_chunks() {
        when(parser.parse(any(FileResource.class))).thenThrow(new IllegalStateException("broken markdown"));

        worker.generate(job());

        verify(persistence, never()).replace(any(), any(), any(), any(), any());
        verify(processingService).fail(10L, 20L, PipelineState.CHUNKING, 1, 0, "broken markdown");
    }

    @Test
    void parser_reads_the_same_immutable_snapshot_that_was_hashed() throws Exception {
        String capturedContent = Files.readString(source);
        String changedContent = "# Changed concurrently\n";
        AtomicReference<Path> parserPath = new AtomicReference<>();
        AtomicReference<String> parserContent = new AtomicReference<>();
        when(parser.parse(any(FileResource.class))).thenAnswer(invocation -> {
            FileResource resource = invocation.getArgument(0);
            parserPath.set(resource.path());
            Files.writeString(source, changedContent);
            parserContent.set(Files.readString(resource.path()));
            return new ParsedStructure(resource, List.of());
        });
        when(planner.plan(any(ParsedStructure.class), eq(job().policy())))
                .thenReturn(List.of(draft("Body", 3)));

        worker.generate(job());

        assertEquals(changedContent, Files.readString(source));
        assertEquals(capturedContent, parserContent.get());
        assertFalse(source.equals(parserPath.get()));
        assertFalse(Files.exists(parserPath.get()));
        verify(persistence).replace(eq(job()),
                eq("cafc00b3438fefaa916b121d6475c4466514fdf3b67d2d33a340017e0a341619"),
                eq("markdown-adaptive-v1"), any(), any());
    }

    @Test
    void parser_failure_deletes_the_exact_immutable_snapshot() {
        AtomicReference<Path> parserPath = new AtomicReference<>();
        when(parser.parse(any(FileResource.class))).thenAnswer(invocation -> {
            FileResource resource = invocation.getArgument(0);
            parserPath.set(resource.path());
            throw new IllegalStateException("broken snapshot");
        });

        worker.generate(job());

        assertTrue(parserPath.get() != null);
        assertFalse(Files.exists(parserPath.get()));
        assertTrue(Files.exists(source));
        verify(processingService).fail(10L, 20L, PipelineState.CHUNKING, 1, 0, "broken snapshot");
    }

    @Test
    void persistence_failure_is_reported_by_worker() {
        ParsedStructure structure = new ParsedStructure(
                new FileResource(1L, 10L, 20L, null, "source.md", "md", source), List.of());
        when(parser.parse(any(FileResource.class))).thenReturn(structure);
        when(planner.plan(eq(structure), any(ChunkPolicy.class))).thenReturn(List.of(draft("Body", 3)));
        doThrow(new IllegalStateException("database unavailable"))
                .when(persistence).replace(any(), any(), any(), any(), any());

        worker.generate(job());

        verify(processingService).fail(10L, 20L, PipelineState.CHUNKING, 1, 0, "database unavailable");
    }

    @Test
    void transactional_replacement_saves_drafts_then_moves_chunking_to_chunked() {
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService realProcessingService = mock(FileProcessingService.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        when(processingMapper.findScopedForUpdate(20L, 1L, 10L)).thenReturn(processing());
        when(chunkMapper.findByFileForUpdate(20L, 1L, 10L)).thenReturn(List.of());
        when(processingMapper.update(any(FileProcessing.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(chunkMapper.insert(any(DocumentChunk.class))).thenReturn(1);
        ChunkPreviewPersistenceService service = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, realProcessingService);

        service.replace(job(), "source-hash", "planner-v1",
                Map.of("tokenizer", "exact-tokenizer"), List.of(draft("First", 2), draft("Second", 3)));

        var captor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<DocumentChunk> saved = captor.getAllValues();
        assertEquals(List.of(0, 1), saved.stream().map(DocumentChunk::getPosition).toList());
        assertEquals(List.of(ChunkStatus.DRAFT.code(), ChunkStatus.DRAFT.code()),
                saved.stream().map(DocumentChunk::getStatus).toList());
        assertTrue(saved.stream().allMatch(chunk -> !chunk.getContent().isBlank()));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getPublicId() != null));
        assertTrue(saved.stream().noneMatch(chunk -> Boolean.TRUE.equals(chunk.getOverlapEnabled())));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getOverlapTokenLimit() == 40));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getOverlapTokenCount() == 0));
        assertTrue(saved.stream().allMatch(chunk -> chunk.getIndexContent() == null));

        var metadata = org.mockito.ArgumentCaptor.forClass(FileProcessing.class);
        verify(processingMapper).update(metadata.capture(), any(LambdaUpdateWrapper.class));
        assertEquals("source-hash", metadata.getValue().getSourceHash());
        assertEquals("MARKDOWN_OPTIMIZED", metadata.getValue().getStrategyCode());
        assertEquals("planner-v1", metadata.getValue().getPlannerVersion());
        assertEquals(Map.of("tokenizer", "exact-tokenizer"), metadata.getValue().getPolicySnapshot());
        verify(realProcessingService).transition(
                10L, 20L, PipelineState.CHUNKING, PipelineState.CHUNKED, 1);
    }

    @Test
    void conditional_delete_count_mismatch_rejects_a_concurrent_status_or_edit_race() {
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        DocumentChunk oldDraft = persistedChunk(99L, ChunkStatus.DRAFT, false, "Old draft");
        when(processingMapper.findScopedForUpdate(20L, 1L, 10L)).thenReturn(processing());
        when(chunkMapper.findByFileForUpdate(20L, 1L, 10L)).thenReturn(List.of(oldDraft));
        when(chunkMapper.deleteReplaceableDrafts(20L, 1L, 10L, false)).thenReturn(0);
        ChunkPreviewPersistenceService service = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, stateService);

        assertThrows(ChunkingException.class, () -> service.replace(
                job(List.of(ChunkPreviewWorker.ExistingChunkSnapshot.from(oldDraft))),
                "source-hash", "planner-v1", Map.of("tokenizer", "exact"),
                List.of(draft("Replacement", 3))));

        verify(chunkMapper, never()).insert(any(DocumentChunk.class));
        verify(processingMapper, never()).update(any(FileProcessing.class), any(LambdaUpdateWrapper.class));
        verify(stateService, never()).transition(anyLong(), anyLong(), any(), any(), anyInt());
        verify(chunkMapper, never()).deleteBatchIds(any());
    }

    @Test
    void real_transaction_proxy_restores_old_drafts_after_partial_replacement_failure() {
        List<DocumentChunk> database = new java.util.ArrayList<>();
        database.add(persistedChunk(99L, ChunkStatus.DRAFT, false, "Old draft"));
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        when(processingMapper.findScopedForUpdate(20L, 1L, 10L)).thenReturn(processing());
        when(chunkMapper.findByFileForUpdate(20L, 1L, 10L))
                .thenAnswer(invocation -> List.copyOf(database));
        when(chunkMapper.deleteReplaceableDrafts(20L, 1L, 10L, false)).thenAnswer(invocation -> {
            int before = database.size();
            database.removeIf(chunk -> chunk.getTenantId() == 1L
                    && chunk.getKnowledgeId() == 10L
                    && chunk.getFileId() == 20L
                    && chunk.getStatus() == ChunkStatus.DRAFT.code()
                    && !Boolean.TRUE.equals(chunk.getIsModified()));
            return before - database.size();
        });
        when(chunkMapper.insert(any(DocumentChunk.class))).thenAnswer(invocation -> {
            database.add(invocation.getArgument(0));
            throw new IllegalStateException("insert failed after partial mutation");
        });
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkPreviewPersistenceService target = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, stateService);
        StateTransactionManager transactionManager = new StateTransactionManager(database);
        ChunkPreviewPersistenceService proxy = transactionalProxy(target, transactionManager);

        assertThrows(IllegalStateException.class, () -> proxy.replace(
                job(List.of(ChunkPreviewWorker.ExistingChunkSnapshot.from(database.get(0)))),
                "source-hash", "planner-v1", Map.of("tokenizer", "exact"),
                List.of(draft("Replacement", 3))));

        assertEquals(1, transactionManager.rollbacks());
        assertEquals(1, database.size());
        assertEquals(99L, database.get(0).getId());
        assertEquals("Old draft", database.get(0).getContent());
        verify(chunkMapper).deleteReplaceableDrafts(20L, 1L, 10L, false);
        verify(chunkMapper, times(1)).insert(any(DocumentChunk.class));
        verify(processingMapper, never()).update(any(FileProcessing.class), any(LambdaUpdateWrapper.class));
        verify(stateService, never()).transition(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void conditional_delete_sql_is_fully_scoped_and_never_deletes_non_drafts_or_unconfirmed_edits()
            throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("mapper/DocumentChunkMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DocumentChunkMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        MappedStatement statement = configuration.getMappedStatement(
                "com.starsea.ai.mapper.DocumentChunkMapper.deleteReplaceableDrafts");
        BoundSql boundSql = statement.getBoundSql(Map.of(
                "fileId", 20L,
                "tenantId", 1L,
                "knowledgeId", 10L,
                "replaceEditedDrafts", false));
        String sql = boundSql.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(sql.contains("file_id = ?"));
        assertTrue(sql.contains("tenant_id = ?"));
        assertTrue(sql.contains("knowledge_id = ?"));
        assertTrue(sql.contains("status = 0"));
        assertTrue(sql.contains("is_modified = FALSE"));
    }

    @Test
    void confirmed_edited_draft_change_after_dispatch_is_rejected_before_delete() {
        DocumentChunk confirmed = persistedChunk(99L, ChunkStatus.DRAFT, true, "Confirmed edit");
        confirmed.setContentHash("confirmed-hash");
        confirmed.setUpdateTime(OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        DocumentChunk changed = persistedChunk(99L, ChunkStatus.DRAFT, true, "Changed again");
        changed.setContentHash("changed-hash");
        changed.setUpdateTime(OffsetDateTime.parse("2026-09-01T10:00:01+08:00"));
        DocumentChunkMapper chunkMapper = lockedChunks(List.of(changed));
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkPreviewPersistenceService service = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, stateService);

        assertThrows(ChunkingException.class, () -> service.replace(
                job(true, List.of(ChunkPreviewWorker.ExistingChunkSnapshot.from(confirmed))),
                "source-hash", "planner-v1", Map.of("tokenizer", "exact"),
                List.of(draft("Replacement", 3))));

        assertNoReplacementWrites(chunkMapper, stateService);
    }

    @Test
    void same_count_identity_replacement_after_dispatch_is_rejected_before_delete() {
        DocumentChunk confirmed = persistedChunk(99L, ChunkStatus.DRAFT, false, "Old");
        DocumentChunk different = persistedChunk(100L, ChunkStatus.DRAFT, false, "Different");
        DocumentChunkMapper chunkMapper = lockedChunks(List.of(different));
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkPreviewPersistenceService service = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, stateService);

        assertThrows(ChunkingException.class, () -> service.replace(
                job(List.of(ChunkPreviewWorker.ExistingChunkSnapshot.from(confirmed))),
                "source-hash", "planner-v1", Map.of("tokenizer", "exact"),
                List.of(draft("Replacement", 3))));

        assertNoReplacementWrites(chunkMapper, stateService);
    }

    @Test
    void empty_confirmed_snapshot_that_gains_a_row_is_rejected_before_delete() {
        DocumentChunk unexpected = persistedChunk(100L, ChunkStatus.DRAFT, false, "Unexpected");
        DocumentChunkMapper chunkMapper = lockedChunks(List.of(unexpected));
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkPreviewPersistenceService service = new ChunkPreviewPersistenceService(
                chunkMapper, processingMapper, stateService);

        assertThrows(ChunkingException.class, () -> service.replace(
                job(List.of()), "source-hash", "planner-v1", Map.of("tokenizer", "exact"),
                List.of(draft("Replacement", 3))));

        assertNoReplacementWrites(chunkMapper, stateService);
    }

    @Test
    void locking_sql_reads_processing_before_scoped_chunks_for_update() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream processing = getClass().getClassLoader()
                .getResourceAsStream("mapper/FileProcessingMapper.xml");
             InputStream chunks = getClass().getClassLoader()
                     .getResourceAsStream("mapper/DocumentChunkMapper.xml")) {
            new XMLMapperBuilder(processing, configuration, "mapper/FileProcessingMapper.xml",
                    configuration.getSqlFragments()).parse();
            new XMLMapperBuilder(chunks, configuration, "mapper/DocumentChunkMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        String processingSql = sql(configuration,
                "com.starsea.ai.mapper.FileProcessingMapper.findScopedForUpdate");
        String chunksSql = sql(configuration,
                "com.starsea.ai.mapper.DocumentChunkMapper.findByFileForUpdate");
        MappedStatement chunksStatement = configuration.getMappedStatement(
                "com.starsea.ai.mapper.DocumentChunkMapper.findByFileForUpdate");
        BoundSql interceptedChunks = chunksStatement.getBoundSql(Map.of(
                "fileId", 20L, "tenantId", 1L, "knowledgeId", 10L));
        InterceptorIgnoreHelper.initSqlParserInfoCache(null,
                DocumentChunkMapper.class.getName(),
                DocumentChunkMapper.class.getMethod("findByFileForUpdate",
                        long.class, long.class, long.class));
        new TenantLineInnerInterceptor(new TenantLineHandlerImpl()).beforeQuery(
                null, chunksStatement, Map.of(), RowBounds.DEFAULT, null, interceptedChunks);
        String finalChunksSql = interceptedChunks.getSql().replaceAll("\\s+", " ").trim();

        assertTrue(processingSql.contains("tenant_id = ?"));
        assertTrue(processingSql.endsWith("FOR UPDATE"));
        assertTrue(chunksSql.contains("tenant_id = ?"));
        assertTrue(chunksSql.contains("knowledge_id = ?"));
        assertTrue(chunksSql.endsWith("FOR UPDATE"));
        assertTrue(finalChunksSql.endsWith("ORDER BY position FOR UPDATE"), finalChunksSql);
    }

    private static ChunkPreviewWorker.Job job() {
        return job(List.of());
    }

    private static ChunkPreviewWorker.Job job(List<ChunkPreviewWorker.ExistingChunkSnapshot> existingChunks) {
        return job(false, existingChunks);
    }

    private static ChunkPreviewWorker.Job job(
            boolean replaceEditedDrafts,
            List<ChunkPreviewWorker.ExistingChunkSnapshot> existingChunks) {
        return new ChunkPreviewWorker.Job(
                10L, 20L, "MARKDOWN_OPTIMIZED", new ChunkPolicy(100, 400, 512),
                replaceEditedDrafts, 1, existingChunks);
    }

    private static ChunkDraft draft(String content, int tokens) {
        return new ChunkDraft(
                List.of("Title"),
                content,
                new SourceLocator("markdown", List.of("b1"), 0, content.length(),
                        1, 1, null, null, List.of()),
                tokens,
                Map.of("end", "PARAGRAPH_END"));
    }

    private static File file(Path path) {
        File file = new File();
        file.setId(20L);
        file.setFileName("source.md");
        file.setType("md");
        file.setPath(path.toString());
        return file;
    }

    private static FileProcessing processing() {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(20L);
        processing.setTenantId(1L);
        processing.setKnowledgeId(10L);
        processing.setPipelineState(PipelineState.CHUNKING.code());
        processing.setLockVersion(1);
        return processing;
    }

    private static DocumentChunk persistedChunk(long id, ChunkStatus status,
                                                 boolean modified, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setTenantId(1L);
        chunk.setKnowledgeId(10L);
        chunk.setFileId(20L);
        chunk.setPosition(0);
        chunk.setContent(content);
        chunk.setPublicId(new UUID(0L, id));
        chunk.setContentHash("hash-" + id);
        chunk.setStatus(status.code());
        chunk.setIsModified(modified);
        chunk.setUpdateTime(OffsetDateTime.parse("2026-09-01T10:00:00+08:00"));
        return chunk;
    }

    private DocumentChunkMapper lockedChunks(List<DocumentChunk> chunks) {
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        when(processingMapper.findScopedForUpdate(20L, 1L, 10L)).thenReturn(processing());
        when(mapper.findByFileForUpdate(20L, 1L, 10L)).thenReturn(chunks);
        return mapper;
    }

    private void assertNoReplacementWrites(DocumentChunkMapper mapper,
                                            FileProcessingService stateService) {
        verify(mapper, never()).deleteReplaceableDrafts(anyLong(), anyLong(), anyLong(), anyBoolean());
        verify(mapper, never()).insert(any(DocumentChunk.class));
        verify(processingMapper, never()).update(any(FileProcessing.class), any(LambdaUpdateWrapper.class));
        verify(stateService, never()).transition(anyLong(), anyLong(), any(), any(), anyInt());
    }

    private static String sql(Configuration configuration, String statementId) {
        BoundSql boundSql = configuration.getMappedStatement(statementId).getBoundSql(Map.of(
                "fileId", 20L, "tenantId", 1L, "knowledgeId", 10L));
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private static ChunkPreviewPersistenceService transactionalProxy(
            ChunkPreviewPersistenceService target, StateTransactionManager transactionManager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (ChunkPreviewPersistenceService) factory.getProxy();
    }

    private static final class StateTransactionManager extends AbstractPlatformTransactionManager {
        private final List<DocumentChunk> database;
        private List<DocumentChunk> snapshot = List.of();
        private int rollbacks;

        private StateTransactionManager(List<DocumentChunk> database) {
            this.database = database;
        }

        int rollbacks() {
            return rollbacks;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            snapshot = List.copyOf(database);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            database.clear();
            database.addAll(snapshot);
        }
    }
}
