package com.starsea.ai.chunking.indexing;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.ConfirmRequest;
import com.starsea.ai.chunking.api.ChunkingController;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.preview.ChunkCommandService;
import com.starsea.ai.chunking.preview.ChunkPreviewService;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChunkVectorServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID FILE_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FIRST_PUBLIC_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_PUBLIC_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    @BeforeEach
    void setAuth() {
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 7L, TENANT_ID, "tenant_admin", "jti"));
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void exposes_exact_confirm_and_single_reindex_routes() throws Exception {
        ChunkPreviewService previewService = mock(ChunkPreviewService.class);
        ChunkCommandService commandService = mock(ChunkCommandService.class);
        ChunkVectorService vectorService = mock(ChunkVectorService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ChunkingController(previewService, commandService, vectorService)).build();

        mvc.perform(post("/knowledge/10/files/20/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"overlapEnabled":true,"overlapTokens":40,"lockVersion":3}
                                """))
                .andExpect(status().isAccepted());
        mvc.perform(post("/knowledge/10/files/20/chunks/" + FIRST_PUBLIC_ID + "/reindex"))
                .andExpect(status().isAccepted());

        verify(vectorService).confirm(KNOWLEDGE_ID, FILE_ID,
                new ConfirmRequest(true, 40, 3));
        verify(vectorService).reindex(KNOWLEDGE_ID, FILE_ID, FIRST_PUBLIC_ID);
    }

    @Test
    void confirmation_hashes_physical_source_before_any_state_mutation() throws Exception {
        Fixture fixture = fixture(PipelineState.CHUNKED, 3, "stale-preview-hash",
                List.of(chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.DRAFT, 0, "edited body")));

        ChunkingException exception = assertThrows(ChunkingException.class,
                () -> fixture.service.confirm(KNOWLEDGE_ID, FILE_ID,
                        new ConfirmRequest(true, 40, 3)));

        assertEquals(409, exception.status().value());
        verify(fixture.stateService, never()).transition(
                anyLong(), anyLong(), any(), any(), anyInt());
        verify(fixture.processingMapper, never()).update(any(), any(Wrapper.class));
        verify(fixture.chunkMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void single_reindex_hashes_physical_source_before_any_state_mutation() throws Exception {
        Fixture fixture = fixture(PipelineState.ADJUSTING, 3, "stale-preview-hash",
                List.of(chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.DRAFT, 0, "edited body")));

        ChunkingException exception = assertThrows(ChunkingException.class,
                () -> fixture.service.reindex(KNOWLEDGE_ID, FILE_ID, FIRST_PUBLIC_ID));

        assertEquals(409, exception.status().value());
        assertEquals(1, fixture.transactionManager.begins());
        assertEquals(0, fixture.transactionManager.commits());
        assertEquals(1, fixture.transactionManager.rollbacks());
        verify(fixture.stateService, never()).transition(
                anyLong(), anyLong(), any(), any(), anyInt());
        verify(fixture.processingMapper, never()).update(any(), any(Wrapper.class));
        verify(fixture.chunkMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void confirmation_commits_context_and_indexing_before_dispatch() throws Exception {
        DocumentChunk first = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.DRAFT, 0, "first edited body");
        DocumentChunk second = chunk(2L, SECOND_PUBLIC_ID, ChunkStatus.ACTIVE, 4, "second body");
        Fixture fixture = fixture(PipelineState.ADJUSTING, 3, sourceHash(), List.of(first, second));
        when(fixture.stateService.transition(KNOWLEDGE_ID, FILE_ID, PipelineState.ADJUSTING,
                PipelineState.CONFIRMED, 3)).thenReturn(new FileProcessingService.Transition(
                KNOWLEDGE_ID, FILE_ID, PipelineState.ADJUSTING, PipelineState.CONFIRMED,
                4, 100, null, null));
        when(fixture.stateService.transition(KNOWLEDGE_ID, FILE_ID, PipelineState.CONFIRMED,
                PipelineState.VECTORIZING, 4)).thenReturn(new FileProcessingService.Transition(
                KNOWLEDGE_ID, FILE_ID, PipelineState.CONFIRMED, PipelineState.VECTORIZING,
                5, 0, null, null));

        fixture.service.confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(true, 40, 3));

        assertEquals(1, fixture.transactionManager.commits());
        var processingCaptor = org.mockito.ArgumentCaptor.forClass(FileProcessing.class);
        verify(fixture.processingMapper).update(processingCaptor.capture(), any(Wrapper.class));
        assertEquals(Map.of("overlapEnabled", true, "overlapTokens", 40),
                processingCaptor.getValue().getContextPolicy());
        var chunkCaptor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(fixture.chunkMapper, times(2)).update(chunkCaptor.capture(), any(Wrapper.class));
        assertTrue(chunkCaptor.getAllValues().stream()
                .allMatch(patch -> patch.getStatus() == ChunkStatus.INDEXING.code()));
        assertTrue(chunkCaptor.getAllValues().stream().allMatch(patch -> patch.getContent() == null));
        var orderedStates = inOrder(fixture.stateService);
        orderedStates.verify(fixture.stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.ADJUSTING, PipelineState.CONFIRMED, 3);
        orderedStates.verify(fixture.stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.CONFIRMED, PipelineState.VECTORIZING, 4);
        assertTrue(fixture.dispatched.get() != null);
        verify(fixture.worker, never()).vectorizeBatch(any());

        fixture.dispatched.get().run();
        verify(fixture.worker).vectorizeBatch(any());
    }

    @Test
    void failed_confirmation_retry_requires_failed_from_vectorizing() throws Exception {
        Fixture fixture = fixture(PipelineState.FAILED, 3, sourceHash(),
                List.of(chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.DRAFT, 0, "body")));
        fixture.processing.setFailedFromState(PipelineState.CHUNKING.code());

        assertThrows(ChunkingException.class, () -> fixture.service.confirm(
                KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(false, 40, 3)));

        verify(fixture.stateService, never()).transition(
                anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void failed_from_vectorizing_retries_directly_to_vectorizing() throws Exception {
        DocumentChunk draft = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.DRAFT, 2, "body");
        Fixture fixture = fixture(PipelineState.FAILED, 3, sourceHash(), List.of(draft));
        fixture.processing.setFailedFromState(PipelineState.VECTORIZING.code());
        when(fixture.stateService.transition(KNOWLEDGE_ID, FILE_ID, PipelineState.FAILED,
                PipelineState.VECTORIZING, 3)).thenReturn(new FileProcessingService.Transition(
                KNOWLEDGE_ID, FILE_ID, PipelineState.FAILED, PipelineState.VECTORIZING,
                4, 0, PipelineState.VECTORIZING.code(), "old failure"));

        fixture.service.confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(false, 40, 3));

        verify(fixture.stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.FAILED, PipelineState.VECTORIZING, 3);
        verify(fixture.stateService, never()).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.FAILED, PipelineState.CONFIRMED, 3);
        assertTrue(fixture.dispatched.get() != null);
    }

    @Test
    void single_reindex_moves_completed_file_to_vectorizing_and_rejects_indexing() throws Exception {
        DocumentChunk active = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.ACTIVE, 2, "body");
        Fixture fixture = fixture(PipelineState.COMPLETED, 7, sourceHash(), List.of(active));
        when(fixture.stateService.transition(KNOWLEDGE_ID, FILE_ID, PipelineState.COMPLETED,
                PipelineState.VECTORIZING, 7)).thenReturn(new FileProcessingService.Transition(
                KNOWLEDGE_ID, FILE_ID, PipelineState.COMPLETED, PipelineState.VECTORIZING,
                8, 100, null, null));

        fixture.service.reindex(KNOWLEDGE_ID, FILE_ID, FIRST_PUBLIC_ID);

        var patchCaptor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(fixture.chunkMapper).update(patchCaptor.capture(), any(Wrapper.class));
        assertEquals(ChunkStatus.INDEXING.code(), patchCaptor.getValue().getStatus());
        verify(fixture.stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.COMPLETED, PipelineState.VECTORIZING, 7);
        assertTrue(fixture.dispatched.get() != null);

        active.setStatus(ChunkStatus.INDEXING.code());
        assertThrows(ChunkingException.class,
                () -> fixture.service.reindex(KNOWLEDGE_ID, FILE_ID, FIRST_PUBLIC_ID));
    }

    @Test
    void worker_keeps_external_io_outside_transactions_and_persists_exact_success() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        FileProcessing processing = processing(PipelineState.VECTORIZING, 5, "hash");
        processing.setContextPolicy(Map.of("overlapEnabled", true, "overlapTokens", 40));
        DocumentChunk first = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.INDEXING, 1, "body");
        first.setSectionPath(List.of("Guide"));
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(first));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(tempDir.resolve("source.md")));
        when(enricher.enrich(any(), eq(new ContextPolicy(true, 40)), eq(512)))
                .thenReturn(List.of(new EnrichedChunk(first, null, "previous sentence.",
                        3, "标题：Guide\n上文：previous sentence.\n\nbody")));
        when(tokenCounter.count(any())).thenReturn(20);
        when(chunkMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(stateService.transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.COMPLETED, 5)).thenReturn(new FileProcessingService.Transition(
                KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING, PipelineState.COMPLETED,
                6, 0, null, null));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(transactionManager.active());
            return null;
        }).when(gateway).deleteAll(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(transactionManager.active());
            return null;
        }).when(gateway).delete(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(transactionManager.active());
            return null;
        }).when(gateway).add(any());
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokenCounter, gateway, transactions);

        worker.vectorizeBatch(batchJob(first, 5, new ContextPolicy(true, 40)));

        assertEquals(1, transactionManager.commits());
        var patchCaptor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).update(patchCaptor.capture(), any(Wrapper.class));
        DocumentChunk patch = patchCaptor.getValue();
        assertEquals(ChunkStatus.ACTIVE.code(), patch.getStatus());
        assertEquals("previous sentence.", patch.getOverlapContent());
        assertEquals(3, patch.getOverlapTokenCount());
        assertEquals("标题：Guide\n上文：previous sentence.\n\nbody", patch.getIndexContent());
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.COMPLETED, 5);
    }

    @Test
    void batch_add_failure_best_effort_deletes_every_id_and_restores_drafts_without_body_loss() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        FileProcessing processing = processing(PipelineState.VECTORIZING, 5, "hash");
        DocumentChunk first = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.INDEXING, 1, "edited first");
        DocumentChunk second = chunk(2L, SECOND_PUBLIC_ID, ChunkStatus.INDEXING, 1, "edited second");
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(first, second));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(tempDir.resolve("source.md")));
        when(enricher.enrich(any(), any(), eq(512))).thenReturn(List.of(
                new EnrichedChunk(first, null, null, 0, "edited first"),
                new EnrichedChunk(second, null, null, 0, "edited second")));
        when(tokenCounter.count(any())).thenReturn(10);
        when(chunkMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        doThrow(new IllegalStateException("embedding unavailable")).when(gateway).add(any());
        doThrow(new IllegalStateException("first cleanup failed")).when(gateway).delete(FIRST_PUBLIC_ID);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokenCounter, gateway, transactions);

        worker.vectorizeBatch(batchJob(List.of(first, second), 5, ContextPolicy.defaults()));

        verify(gateway).delete(FIRST_PUBLIC_ID);
        verify(gateway).delete(SECOND_PUBLIC_ID);
        var patchCaptor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, times(2)).update(patchCaptor.capture(), any(Wrapper.class));
        assertTrue(patchCaptor.getAllValues().stream()
                .allMatch(patch -> patch.getStatus() == ChunkStatus.DRAFT.code()));
        assertTrue(patchCaptor.getAllValues().stream().allMatch(patch -> patch.getContent() == null));
        verify(stateService).fail(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                5, 0, "embedding unavailable");
        assertEquals(1, transactionManager.commits());
    }

    @Test
    void exact_token_recheck_prevents_add_and_single_failure_leaves_file_adjusting() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        FileProcessing processing = processing(PipelineState.VECTORIZING, 8, "hash");
        processing.setPolicySnapshot(Map.of("maxTokens", 400));
        processing.setContextPolicy(Map.of("overlapEnabled", false, "overlapTokens", 40));
        DocumentChunk first = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.INDEXING, 3, "edited body");
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(first));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(tempDir.resolve("source.md")));
        when(enricher.enrich(any(), eq(ContextPolicy.defaults()), eq(400)))
                .thenReturn(List.of(new EnrichedChunk(first, null, null, 0, "too many tokens")));
        when(tokenCounter.count("too many tokens")).thenReturn(401);
        when(chunkMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokenCounter, gateway, transactions);

        worker.vectorizeSingle(singleJob(first, 8, ContextPolicy.defaults(), 400));

        verify(gateway, never()).add(any());
        var patchCaptor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).update(patchCaptor.capture(), any(Wrapper.class));
        assertEquals(ChunkStatus.DRAFT.code(), patchCaptor.getValue().getStatus());
        assertNull(patchCaptor.getValue().getContent());
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.ADJUSTING, 8);
        verify(stateService, never()).fail(anyLong(), anyLong(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void single_success_activates_the_exact_chunk_and_completes_when_all_are_active() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        FileProcessing processing = processing(PipelineState.VECTORIZING, 8, "hash");
        DocumentChunk first = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.INDEXING, 3, "body");
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(first));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(tempDir.resolve("source.md")));
        when(enricher.enrich(any(), eq(ContextPolicy.defaults()), eq(512)))
                .thenReturn(List.of(new EnrichedChunk(first, null, null, 0, "body")));
        when(tokenCounter.count("body")).thenReturn(2);
        when(chunkMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokenCounter, gateway, transactions);

        worker.vectorizeSingle(singleJob(first, 8, ContextPolicy.defaults()));

        var patchCaptor = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).update(patchCaptor.capture(), any(Wrapper.class));
        assertEquals(ChunkStatus.ACTIVE.code(), patchCaptor.getValue().getStatus());
        assertEquals("body", patchCaptor.getValue().getIndexContent());
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.COMPLETED, 8);
        assertEquals(1, transactionManager.commits());
    }

    @Test
    void single_success_returns_to_adjusting_when_another_chunk_is_still_draft() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        FileProcessing processing = processing(PipelineState.VECTORIZING, 8, "hash");
        DocumentChunk target = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.INDEXING, 3, "body");
        DocumentChunk draft = chunk(2L, SECOND_PUBLIC_ID, ChunkStatus.DRAFT, 4, "draft body");
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(target, draft));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(tempDir.resolve("source.md")));
        when(enricher.enrich(any(), eq(ContextPolicy.defaults()), eq(512)))
                .thenReturn(List.of(new EnrichedChunk(target, null, null, 0, "body")));
        when(tokenCounter.count("body")).thenReturn(2);
        when(chunkMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokenCounter, gateway, transactions);
        ChunkVectorWorker.ChunkSnapshot targetSnapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(target);
        ChunkVectorWorker.SingleJob job = new ChunkVectorWorker.SingleJob(
                TENANT_ID, KNOWLEDGE_ID, FILE_ID, 8, "hash", ContextPolicy.defaults(), 512,
                new ChunkVectorWorker.FileSnapshot(
                        FILE_PUBLIC_ID, tempDir.resolve("source.md").toString(), "md"),
                List.of(targetSnapshot, ChunkVectorWorker.ChunkSnapshot.current(draft)),
                targetSnapshot);

        worker.vectorizeSingle(job);

        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.ADJUSTING, 8);
        verify(stateService, never()).transition(KNOWLEDGE_ID, FILE_ID, PipelineState.VECTORIZING,
                PipelineState.COMPLETED, 8);
        assertEquals(1, transactionManager.commits());
    }

    @Test
    void combined_confirm_hash_enrich_and_vector_io_are_outside_exactly_two_transactions()
            throws Exception {
        TopologyFixture fixture = topologyFixture(true);

        fixture.service.confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(true, 40, 3));

        assertEquals(2, fixture.transactionManager.begins());
        assertEquals(2, fixture.transactionManager.commits());
        assertEquals(0, fixture.transactionManager.rollbacks());
        assertEquals(PipelineState.COMPLETED.code(), fixture.processing.getPipelineState());
        assertEquals(ChunkStatus.ACTIVE.code(), fixture.chunk.getStatus());
        assertEquals("标题：Captured\n\ncaptured body", fixture.chunk.getIndexContent());
        assertEquals(1, fixture.sourceReads()[0]);
        assertEquals(1, fixture.enrichCalls()[0]);
        assertEquals(1, fixture.vectorAdds()[0]);
    }

    @Test
    void combined_single_reindex_enrich_and_vector_io_are_outside_exactly_two_transactions()
            throws Exception {
        TopologyFixture fixture = topologyFixture(true);

        fixture.service.reindex(KNOWLEDGE_ID, FILE_ID, FIRST_PUBLIC_ID);

        assertEquals(2, fixture.transactionManager.begins());
        assertEquals(2, fixture.transactionManager.commits());
        assertEquals(0, fixture.transactionManager.rollbacks());
        assertEquals(PipelineState.COMPLETED.code(), fixture.processing.getPipelineState());
        assertEquals(ChunkStatus.ACTIVE.code(), fixture.chunk.getStatus());
        assertEquals("标题：Captured\n\ncaptured body", fixture.chunk.getIndexContent());
        assertEquals(1, fixture.sourceReads()[0]);
        assertEquals(1, fixture.enrichCalls()[0]);
        assertEquals(1, fixture.vectorAdds()[0]);
    }

    @Test
    void phase_one_job_deeply_captures_body_and_metadata_before_backing_entities_mutate()
            throws Exception {
        TopologyFixture fixture = topologyFixture(false);

        fixture.chunk.setOverlapContent("stale overlap");
        fixture.chunk.setOverlapSourceChunkId(77L);
        fixture.chunk.setOverlapTokenCount(9);
        fixture.chunk.setIndexContent("stale index");
        fixture.chunk.setLastError("stale error");
        fixture.service.confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(true, 40, 3));
        fixture.chunk.setContent("mutated backing body");
        fixture.chunk.getSectionPath().set(0, "Mutated");
        fixture.dispatched.get().run();

        assertEquals("captured body", fixture.enrichedBody.get());
        assertEquals(List.of("Captured"), fixture.enrichedSectionPath.get());
        assertEquals("标题：Captured\n\ncaptured body", fixture.addedDocument.get().indexContent());
        assertEquals("md", fixture.addedDocument.get().fileType());
        assertEquals(2, fixture.transactionManager.commits());
    }

    @Test
    void batch_phase_three_snapshot_mismatch_compensates_without_stranding_state()
            throws Exception {
        TopologyFixture fixture = topologyFixture(false);

        fixture.service.confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(true, 40, 3));
        fixture.chunk.setLockVersion(99);
        fixture.chunk.setContent("concurrent batch edit");
        fixture.dispatched.get().run();

        assertEquals(ChunkStatus.DRAFT.code(), fixture.chunk.getStatus());
        assertEquals("concurrent batch edit", fixture.chunk.getContent());
        assertEquals("hash-1", fixture.chunk.getContentHash());
        assertNull(fixture.chunk.getOverlapContent());
        assertNull(fixture.chunk.getOverlapSourceChunkId());
        assertEquals(0, fixture.chunk.getOverlapTokenCount());
        assertNull(fixture.chunk.getIndexContent());
        assertNull(fixture.chunk.getLastError());
        assertEquals(PipelineState.FAILED.code(), fixture.processing.getPipelineState());
        assertEquals(PipelineState.VECTORIZING.code(), fixture.processing.getFailedFromState());
        assertEquals(1, fixture.transactionManager.rollbacks());
        assertEquals(2, fixture.transactionManager.commits());
        verify(fixture.gateway).delete(FIRST_PUBLIC_ID);
        verify(fixture.stateService, never()).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.VECTORIZING, PipelineState.COMPLETED, 5);
    }

    @Test
    void single_phase_three_snapshot_mismatch_compensates_to_adjusting_without_losing_edits()
            throws Exception {
        TopologyFixture fixture = topologyFixture(false);

        fixture.service.reindex(KNOWLEDGE_ID, FILE_ID, FIRST_PUBLIC_ID);
        fixture.chunk.setContent("concurrent single edit");
        fixture.chunk.setContentHash("concurrent-single-hash");
        fixture.dispatched.get().run();

        assertEquals(ChunkStatus.DRAFT.code(), fixture.chunk.getStatus());
        assertEquals("concurrent single edit", fixture.chunk.getContent());
        assertEquals("concurrent-single-hash", fixture.chunk.getContentHash());
        assertEquals(PipelineState.ADJUSTING.code(), fixture.processing.getPipelineState());
        assertEquals(1, fixture.transactionManager.rollbacks());
        assertEquals(2, fixture.transactionManager.commits());
        verify(fixture.gateway).delete(FIRST_PUBLIC_ID);
        verify(fixture.stateService, never()).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.VECTORIZING, PipelineState.COMPLETED, 4);
    }

    @Test
    void compensation_leaves_non_indexing_target_untouched_but_marks_batch_failed()
            throws Exception {
        TopologyFixture fixture = topologyFixture(false);

        fixture.service.confirm(KNOWLEDGE_ID, FILE_ID, new ConfirmRequest(true, 40, 3));
        fixture.chunk.setStatus(ChunkStatus.ACTIVE.code());
        fixture.chunk.setLockVersion(99);
        fixture.chunk.setContent("concurrent active body");
        fixture.dispatched.get().run();

        assertEquals(ChunkStatus.ACTIVE.code(), fixture.chunk.getStatus());
        assertEquals("concurrent active body", fixture.chunk.getContent());
        assertEquals(PipelineState.FAILED.code(), fixture.processing.getPipelineState());
        verify(fixture.gateway).delete(FIRST_PUBLIC_ID);
    }

    private TopologyFixture topologyFixture(boolean directExecution) throws Exception {
        Path source = tempDir.resolve("topology.md");
        Files.writeString(source, "# Exact source\n\nBody.\n");
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokenCounter = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        FileProcessing processing = processing(PipelineState.ADJUSTING, 3, sourceHash());
        File file = file(source);
        DocumentChunk chunk = chunk(1L, FIRST_PUBLIC_ID, ChunkStatus.DRAFT, 0, "captured body");
        chunk.setSectionPath(new ArrayList<>(List.of("Captured")));
        AtomicReference<Runnable> dispatched = new AtomicReference<>();
        AtomicReference<String> enrichedBody = new AtomicReference<>();
        AtomicReference<List<String>> enrichedSectionPath = new AtomicReference<>();
        AtomicReference<ChunkVectorGateway.VectorDocument> addedDocument = new AtomicReference<>();
        int[] sourceReads = {0};
        int[] enrichCalls = {0};
        int[] vectorAdds = {0};

        when(processingMapper.selectById(FILE_ID)).thenReturn(processing);
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(chunk));
        when(processingMapper.update(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            FileProcessing patch = invocation.getArgument(0);
            processing.setContextPolicy(patch.getContextPolicy());
            return 1;
        });
        when(chunkMapper.update(any(), any(Wrapper.class))).thenAnswer(invocation -> {
            DocumentChunk patch = invocation.getArgument(0);
            UpdateWrapper<DocumentChunk> update = invocation.getArgument(1);
            if (patch.getStatus() != null) {
                chunk.setStatus(patch.getStatus());
            }
            if (patch.getOverlapContent() != null) {
                chunk.setOverlapContent(patch.getOverlapContent());
            }
            if (patch.getOverlapSourceChunkId() != null) {
                chunk.setOverlapSourceChunkId(patch.getOverlapSourceChunkId());
            }
            if (patch.getOverlapTokenCount() != null) {
                chunk.setOverlapTokenCount(patch.getOverlapTokenCount());
            }
            if (patch.getIndexContent() != null) {
                chunk.setIndexContent(patch.getIndexContent());
            }
            if (patch.getLastError() != null) {
                chunk.setLastError(patch.getLastError());
            }
            String sqlSet = update.getSqlSet();
            if (sqlSet != null && sqlSet.contains("overlap_content")) {
                chunk.setOverlapContent(null);
            }
            if (sqlSet != null && sqlSet.contains("overlap_source_chunk_id")) {
                chunk.setOverlapSourceChunkId(null);
            }
            if (sqlSet != null && sqlSet.contains("overlap_token_count")) {
                chunk.setOverlapTokenCount(0);
            }
            if (sqlSet != null && sqlSet.contains("index_content")) {
                chunk.setIndexContent(null);
            }
            if (sqlSet != null && sqlSet.contains("last_error")) {
                chunk.setLastError(null);
            }
            chunk.setLockVersion(chunk.getLockVersion() + 1);
            return 1;
        });
        when(stateService.transition(anyLong(), anyLong(), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    PipelineState expected = invocation.getArgument(2);
                    PipelineState target = invocation.getArgument(3);
                    int lockVersion = invocation.getArgument(4);
                    assertEquals(expected.code(), processing.getPipelineState());
                    assertEquals(lockVersion, processing.getLockVersion());
                    processing.setPipelineState(target.code());
                    processing.setLockVersion(lockVersion + 1);
                    return new FileProcessingService.Transition(KNOWLEDGE_ID, FILE_ID,
                            expected, target, lockVersion + 1, processing.getProgress(), null, null);
                });
        when(stateService.fail(anyLong(), anyLong(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    PipelineState expected = invocation.getArgument(2);
                    int lockVersion = invocation.getArgument(3);
                    String error = invocation.getArgument(5);
                    assertEquals(PipelineState.VECTORIZING, expected);
                    assertEquals(expected.code(), processing.getPipelineState());
                    assertEquals(lockVersion, processing.getLockVersion());
                    processing.setPipelineState(PipelineState.FAILED.code());
                    processing.setLockVersion(lockVersion + 1);
                    processing.setFailedFromState(expected.code());
                    processing.setLastError(error);
                    return new FileProcessingService.Transition(KNOWLEDGE_ID, FILE_ID,
                            expected, PipelineState.FAILED, lockVersion + 1,
                            processing.getProgress(), null, null);
                });
        when(enricher.enrich(any(), any(), anyInt())).thenAnswer(invocation -> {
            assertFalse(transactionManager.active());
            assertEquals(PipelineState.VECTORIZING.code(), processing.getPipelineState());
            enrichCalls[0]++;
            List<DocumentChunk> detached = invocation.getArgument(0);
            DocumentChunk captured = detached.get(0);
            enrichedBody.set(captured.getContent());
            enrichedSectionPath.set(List.copyOf(captured.getSectionPath()));
            String indexContent = new ChunkIndexContentBuilder().build(
                    captured.getSectionPath(), null, captured.getContent());
            return List.of(new EnrichedChunk(captured, null, null, 0, indexContent));
        });
        when(tokenCounter.count(any())).thenAnswer(invocation -> {
            assertFalse(transactionManager.active());
            return 10;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(transactionManager.active());
            return null;
        }).when(gateway).deleteAll(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertFalse(transactionManager.active());
            vectorAdds[0]++;
            List<ChunkVectorGateway.VectorDocument> documents = invocation.getArgument(0);
            addedDocument.set(documents.get(0));
            return null;
        }).when(gateway).add(any());

        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokenCounter, gateway, transactions);
        Executor executor = directExecution ? Runnable::run : dispatched::set;
        ChunkVectorService service = new ChunkVectorService(processingMapper, fileMapper, chunkMapper,
                stateService, worker, transactions, executor, path -> {
                    assertFalse(transactionManager.active());
                    sourceReads[0]++;
                    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path)));
                });
        return new TopologyFixture(service, processingMapper, chunkMapper, stateService,
                gateway, processing, file, chunk, transactionManager, dispatched, enrichedBody,
                enrichedSectionPath, addedDocument, sourceReads, enrichCalls, vectorAdds);
    }

    private Fixture fixture(PipelineState state, int lockVersion, String sourceHash,
                            List<DocumentChunk> chunks) throws Exception {
        Path source = tempDir.resolve("source.md");
        Files.writeString(source, "# Exact source\n\nBody.\n");
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkVectorWorker worker = mock(ChunkVectorWorker.class);
        FileProcessing processing = processing(state, lockVersion, sourceHash);
        when(processingMapper.selectById(FILE_ID)).thenReturn(processing);
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file(source));
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(chunks);
        for (DocumentChunk chunk : chunks) {
            when(chunkMapper.findScopedByPublicIdForUpdate(
                    FILE_ID, TENANT_ID, KNOWLEDGE_ID, chunk.getPublicId())).thenReturn(chunk);
        }
        when(processingMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(chunkMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AtomicReference<Runnable> dispatched = new AtomicReference<>();
        Executor executor = dispatched::set;
        ChunkVectorService service = new ChunkVectorService(processingMapper, fileMapper, chunkMapper,
                stateService, worker, new TransactionTemplate(transactionManager), executor);
        return new Fixture(service, processingMapper, chunkMapper, stateService, worker,
                processing, transactionManager, dispatched);
    }

    private String sourceHash() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("# Exact source\n\nBody.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static FileProcessing processing(PipelineState state, int lockVersion, String sourceHash) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(FILE_ID);
        processing.setTenantId(TENANT_ID);
        processing.setKnowledgeId(KNOWLEDGE_ID);
        processing.setPipelineState(state.code());
        processing.setLockVersion(lockVersion);
        processing.setProgress(state == PipelineState.COMPLETED || state == PipelineState.ADJUSTING ? 100 : 0);
        processing.setSourceHash(sourceHash);
        processing.setPolicySnapshot(Map.of("maxTokens", 512));
        processing.setContextPolicy(Map.of("overlapEnabled", false, "overlapTokens", 40));
        return processing;
    }

    private static File file(Path source) {
        File file = new File();
        file.setId(FILE_ID);
        file.setPublicId(FILE_PUBLIC_ID);
        file.setPath(source.toString());
        file.setType("md");
        return file;
    }

    private static DocumentChunk chunk(long id, UUID publicId, ChunkStatus status,
                                       int lockVersion, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setPublicId(publicId);
        chunk.setTenantId(TENANT_ID);
        chunk.setKnowledgeId(KNOWLEDGE_ID);
        chunk.setFileId(FILE_ID);
        chunk.setPosition((int) id - 1);
        chunk.setContent(content);
        chunk.setContentHash("hash-" + id);
        chunk.setSectionPath(List.of());
        chunk.setStatus(status.code());
        chunk.setLockVersion(lockVersion);
        return chunk;
    }

    private ChunkVectorWorker.BatchJob batchJob(DocumentChunk chunk, int fileLockVersion,
                                                 ContextPolicy policy) {
        return batchJob(List.of(chunk), fileLockVersion, policy);
    }

    private ChunkVectorWorker.BatchJob batchJob(List<DocumentChunk> chunks,
                                                 int fileLockVersion,
                                                 ContextPolicy policy) {
        List<ChunkVectorWorker.ChunkSnapshot> snapshots = chunks.stream()
                .map(ChunkVectorWorker.ChunkSnapshot::fromIndexing).toList();
        return new ChunkVectorWorker.BatchJob(TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                fileLockVersion, "hash", policy, 512,
                new ChunkVectorWorker.FileSnapshot(
                        FILE_PUBLIC_ID, tempDir.resolve("source.md").toString(), "md"),
                snapshots, snapshots);
    }

    private ChunkVectorWorker.SingleJob singleJob(DocumentChunk chunk,
                                                   int fileLockVersion,
                                                   ContextPolicy policy) {
        return singleJob(chunk, fileLockVersion, policy, 512);
    }

    private ChunkVectorWorker.SingleJob singleJob(DocumentChunk chunk,
                                                   int fileLockVersion,
                                                   ContextPolicy policy,
                                                   int maxTokens) {
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(chunk);
        return new ChunkVectorWorker.SingleJob(TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                fileLockVersion, "hash", policy, maxTokens,
                new ChunkVectorWorker.FileSnapshot(
                        FILE_PUBLIC_ID, tempDir.resolve("source.md").toString(), "md"),
                List.of(snapshot), snapshot);
    }

    private record Fixture(
            ChunkVectorService service,
            FileProcessingMapper processingMapper,
            DocumentChunkMapper chunkMapper,
            FileProcessingService stateService,
            ChunkVectorWorker worker,
            FileProcessing processing,
            RecordingTransactionManager transactionManager,
            AtomicReference<Runnable> dispatched) {
    }

    private record TopologyFixture(
            ChunkVectorService service,
            FileProcessingMapper processingMapper,
            DocumentChunkMapper chunkMapper,
            FileProcessingService stateService,
            ChunkVectorGateway gateway,
            FileProcessing processing,
            File file,
            DocumentChunk chunk,
            RecordingTransactionManager transactionManager,
            AtomicReference<Runnable> dispatched,
            AtomicReference<String> enrichedBody,
            AtomicReference<List<String>> enrichedSectionPath,
            AtomicReference<ChunkVectorGateway.VectorDocument> addedDocument,
            int[] sourceReads,
            int[] enrichCalls,
            int[] vectorAdds) {
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active.set(true);
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.set(false);
        }

        boolean active() {
            return active.get();
        }

        int commits() {
            return commits;
        }

        int begins() {
            return begins;
        }

        int rollbacks() {
            return rollbacks;
        }
    }
}
