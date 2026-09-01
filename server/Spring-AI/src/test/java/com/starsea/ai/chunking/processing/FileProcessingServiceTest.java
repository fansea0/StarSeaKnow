package com.starsea.ai.chunking.processing;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileProcessingServiceTest {

    private FileProcessingMapper mapper;
    private FileProcessingService service;

    @BeforeEach
    void setUp() {
        mapper = mock(FileProcessingMapper.class);
        service = new FileProcessingService(mapper);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 1L, "tenant_admin", "jti-1"));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void transitions_legal_state_with_full_scope_and_optimistic_version() {
        when(mapper.selectById(20L)).thenReturn(processing(1L, 10L, 20L, PipelineState.UPLOADED, 0));
        when(mapper.transition(20L, 1L, 10L, 0, 1, 0, 0, null, null)).thenReturn(1);

        FileProcessingService.Transition transition = service.transition(
                10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 0);

        assertEquals(PipelineState.UPLOADED, transition.previous());
        assertEquals(PipelineState.CHUNKING, transition.current());
        assertEquals(1, transition.lockVersion());
        verify(mapper).transition(20L, 1L, 10L, 0, 1, 0, 0, null, null);
    }

    @Test
    void rejects_illegal_uploaded_to_completed_transition_without_writing() {
        when(mapper.selectById(20L)).thenReturn(processing(1L, 10L, 20L, PipelineState.UPLOADED, 0));

        assertThrows(FileProcessingService.StateConflictException.class, () -> service.transition(
                10L, 20L, PipelineState.UPLOADED, PipelineState.COMPLETED, 0));

        verify(mapper, never()).transition(20L, 1L, 10L, 0, 6, 100, 0, null, null);
    }

    @Test
    void rejects_stale_lock_version_without_writing() {
        when(mapper.selectById(20L)).thenReturn(processing(1L, 10L, 20L, PipelineState.UPLOADED, 2));

        assertThrows(FileProcessingService.StateConflictException.class, () -> service.transition(
                10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 1));

        verify(mapper, never()).transition(20L, 1L, 10L, 0, 1, 0, 1, null, null);
    }

    @Test
    void rejects_cross_tenant_or_wrong_knowledge_scope_without_writing() {
        when(mapper.selectById(20L)).thenReturn(processing(2L, 11L, 20L, PipelineState.UPLOADED, 0));

        assertThrows(FileProcessingService.OwnershipException.class, () -> service.transition(
                10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 0));

        verify(mapper, never()).transition(20L, 1L, 10L, 0, 1, 0, 0, null, null);
    }

    @Test
    void dispatcher_restores_captured_auth_context_and_clears_worker_thread() {
        FileProcessingService processingService = mock(FileProcessingService.class);
        Executor executor = mock(Executor.class);
        when(processingService.transition(10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 0))
                .thenReturn(new FileProcessingService.Transition(
                        10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 1,
                        0, null, null));
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            submitted.set(invocation.getArgument(0));
            return null;
        }).when(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        ChunkTaskDispatcher dispatcher = new ChunkTaskDispatcher(processingService, executor);
        AtomicReference<AuthContext> workerContext = new AtomicReference<>();

        dispatcher.dispatch(10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 0,
                () -> workerContext.set(AuthContext.current()));

        var order = inOrder(processingService, executor);
        order.verify(processingService).transition(
                10L, 20L, PipelineState.UPLOADED, PipelineState.CHUNKING, 0);
        order.verify(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        AuthContext.clear();
        submitted.get().run();
        assertEquals(AuthContext.Kind.BUSINESS, workerContext.get().getKind());
        assertEquals(7L, workerContext.get().getUserId());
        assertEquals(1L, workerContext.get().getTenantId());
        assertEquals("tenant_admin", workerContext.get().getRole());
        assertEquals("jti-1", workerContext.get().getJti());
        assertNull(AuthContext.current());
    }

    @Test
    void dispatcher_conditionally_restores_complete_prior_state_and_reports_503_when_pool_rejects() {
        FileProcessingService processingService = mock(FileProcessingService.class);
        Executor executor = command -> { throw new RejectedExecutionException("full"); };
        when(processingService.transition(10L, 20L, PipelineState.FAILED, PipelineState.CHUNKING, 3))
                .thenReturn(new FileProcessingService.Transition(
                        10L, 20L, PipelineState.FAILED, PipelineState.CHUNKING, 4,
                        45, PipelineState.CHUNKING.code(), "parser failed"));
        ChunkTaskDispatcher dispatcher = new ChunkTaskDispatcher(processingService, executor);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> dispatcher.dispatch(10L, 20L, PipelineState.FAILED,
                        PipelineState.CHUNKING, 3, () -> { }));

        assertEquals(503, exception.getStatusCode().value());
        assertTrue(exception.getReason().contains("temporarily unavailable"));
        verify(processingService).restoreAfterRejectedDispatch(
                10L, 20L, PipelineState.CHUNKING, PipelineState.FAILED, 4,
                45, PipelineState.CHUNKING.code(), "parser failed");
    }

    private static FileProcessing processing(long tenantId, long knowledgeId, long fileId,
                                             PipelineState state, int lockVersion) {
        FileProcessing processing = new FileProcessing();
        processing.setTenantId(tenantId);
        processing.setKnowledgeId(knowledgeId);
        processing.setFileId(fileId);
        processing.setPipelineState(state.code());
        processing.setProgress(0);
        processing.setLockVersion(lockVersion);
        return processing;
    }
}
