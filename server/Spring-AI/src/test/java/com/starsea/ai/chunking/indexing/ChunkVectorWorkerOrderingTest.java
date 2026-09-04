package com.starsea.ai.chunking.indexing;

import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicyResolver;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import com.starsea.ai.chunking.processing.FileProcessingService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ChunkVectorWorkerOrderingTest {

    @Test
    void invalid_replacement_is_rejected_before_any_old_vector_delete() {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokens = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        DocumentChunk chunk = chunk();
        when(enricher.enrich(any(), org.mockito.ArgumentMatchers.eq(512)))
                .thenReturn(List.of(new EnrichedChunk(chunk, null, null, 0,
                        "replacement that violates the model budget")));
        when(tokens.count(any())).thenReturn(513);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokens, gateway, transactions);
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(chunk);
        ChunkVectorWorker.SingleJob job = new ChunkVectorWorker.SingleJob(
                1L, 10L, 20L, 5, "hash", 512,
                new ChunkVectorWorker.FileSnapshot(UUID.randomUUID(), "/source.txt", "txt"),
                List.of(snapshot), snapshot);

        assertThrows(RuntimeException.class, () -> worker.vectorizeSingle(job));

        verify(gateway, never()).deleteAll(any());
        verify(gateway, never()).delete(any());
        verify(gateway, never()).add(any());
    }

    @Test
    void planner_or_policy_drift_is_rejected_before_any_vector_io() {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokens = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                    invocation.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        DocumentChunk chunk = chunk();
        FileProcessing processing = markdownProcessing("planner-v1");
        ChunkRuntimePolicyResolver resolver = new ChunkRuntimePolicyResolver();
        ChunkVectorWorker.ProcessingSnapshot expected =
                ChunkVectorWorker.ProcessingSnapshot.from(processing, resolver);
        processing.setPlannerVersion("planner-v2");
        when(processingMapper.findScopedForUpdate(20L, 1L, 10L)).thenReturn(processing);
        when(chunkMapper.findByFileForUpdate(20L, 1L, 10L)).thenReturn(List.of(chunk));
        when(chunkMapper.update(any(), any())).thenReturn(1);
        when(tokens.count(any())).thenReturn(4);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokens, gateway, transactions, resolver, path -> "hash");
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(chunk);
        ChunkVectorWorker.PreparedChunk prepared = ChunkVectorWorker.PreparedChunk.from(
                new EnrichedChunk(chunk, null, null, 0, "body"));
        ChunkVectorWorker.SingleJob job = new ChunkVectorWorker.SingleJob(
                1L, 10L, 20L, 5, "hash", 512,
                new ChunkVectorWorker.FileSnapshot(UUID.randomUUID(), "/source.md", "md"),
                List.of(snapshot), snapshot, expected, List.of(prepared));

        assertThrows(RuntimeException.class, () -> worker.vectorizeSingle(job));

        verify(gateway, never()).deleteAll(any());
        verify(gateway, never()).delete(any());
        verify(gateway, never()).add(any());
    }

    @Test
    void single_job_rejects_an_extra_chunk_outside_the_captured_id_set_before_vector_io() {
        UnexpectedChunkFixture fixture = unexpectedChunkFixture();
        ChunkVectorWorker.SingleJob job = new ChunkVectorWorker.SingleJob(
                1L, 10L, 20L, 5, "hash", 512, fixture.fileSnapshot(),
                List.of(fixture.snapshot()), fixture.snapshot(), fixture.processingSnapshot(),
                List.of(fixture.prepared()));

        assertThrows(RuntimeException.class, () -> fixture.worker().vectorizeSingle(job));

        verify(fixture.gateway(), never()).deleteAll(any());
        verify(fixture.gateway(), never()).delete(any());
        verify(fixture.gateway(), never()).add(any());
    }

    @Test
    void batch_job_rejects_an_extra_chunk_outside_the_captured_id_set_before_vector_io() {
        UnexpectedChunkFixture fixture = unexpectedChunkFixture();
        ChunkVectorWorker.BatchJob job = new ChunkVectorWorker.BatchJob(
                1L, 10L, 20L, 5, "hash", 512, fixture.fileSnapshot(),
                List.of(fixture.snapshot()), List.of(fixture.snapshot()),
                fixture.processingSnapshot(), List.of(fixture.prepared()));

        fixture.worker().vectorizeBatch(job);

        verify(fixture.gateway(), never()).deleteAll(any());
        verify(fixture.gateway(), never()).delete(any());
        verify(fixture.gateway(), never()).add(any());
    }

    @Test
    void single_job_rechecks_the_complete_chunk_set_in_the_completion_transaction() {
        UnexpectedChunkFixture fixture = unexpectedChunkFixture(true);
        ChunkVectorWorker.SingleJob job = new ChunkVectorWorker.SingleJob(
                1L, 10L, 20L, 5, "hash", 512, fixture.fileSnapshot(),
                List.of(fixture.snapshot()), fixture.snapshot(), fixture.processingSnapshot(),
                List.of(fixture.prepared()));

        assertThrows(RuntimeException.class, () -> fixture.worker().vectorizeSingle(job));

        verify(fixture.gateway()).deleteAll(any());
        ArgumentCaptor<List<ChunkVectorGateway.VectorDocument>> documents =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.gateway()).add(documents.capture());
        verify(fixture.gateway()).delete(documents.getValue().get(0).vectorId());
        verify(fixture.stateService(), never()).transition(10L, 20L,
                PipelineState.VECTORIZING, PipelineState.COMPLETED, 5);
        verify(fixture.stateService(), never()).transition(10L, 20L,
                PipelineState.VECTORIZING, PipelineState.ADJUSTING, 5);
    }

    @Test
    void batch_job_rechecks_the_complete_chunk_set_in_the_completion_transaction() {
        UnexpectedChunkFixture fixture = unexpectedChunkFixture(true);
        ChunkVectorWorker.BatchJob job = new ChunkVectorWorker.BatchJob(
                1L, 10L, 20L, 5, "hash", 512, fixture.fileSnapshot(),
                List.of(fixture.snapshot()), List.of(fixture.snapshot()),
                fixture.processingSnapshot(), List.of(fixture.prepared()));

        fixture.worker().vectorizeBatch(job);

        verify(fixture.gateway()).deleteAll(any());
        ArgumentCaptor<List<ChunkVectorGateway.VectorDocument>> documents =
                ArgumentCaptor.forClass(List.class);
        verify(fixture.gateway()).add(documents.capture());
        verify(fixture.gateway()).delete(documents.getValue().get(0).vectorId());
        verify(fixture.stateService(), never()).transition(10L, 20L,
                PipelineState.VECTORIZING, PipelineState.COMPLETED, 5);
    }

    private static UnexpectedChunkFixture unexpectedChunkFixture() {
        return unexpectedChunkFixture(false);
    }

    private static UnexpectedChunkFixture unexpectedChunkFixture(boolean driftAtCompletion) {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        FileMapper fileMapper = mock(FileMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessingService stateService = mock(FileProcessingService.class);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        TokenCounter tokens = mock(TokenCounter.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action =
                    invocation.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());

        FileProcessing processing = markdownProcessing("planner-v1");
        ChunkRuntimePolicyResolver resolver = new ChunkRuntimePolicyResolver();
        ChunkVectorWorker.ProcessingSnapshot processingSnapshot =
                ChunkVectorWorker.ProcessingSnapshot.from(processing, resolver);
        DocumentChunk target = chunk();
        ChunkVectorWorker.ChunkSnapshot snapshot =
                ChunkVectorWorker.ChunkSnapshot.fromIndexing(target);
        ChunkVectorWorker.PreparedChunk prepared = ChunkVectorWorker.PreparedChunk.from(
                new EnrichedChunk(target, null, null, 0, "body"));
        DocumentChunk extra = chunk();
        extra.setId(2L);
        extra.setPublicId(UUID.randomUUID());
        extra.setPosition(1);
        extra.setStatus(com.starsea.ai.chunking.model.ChunkStatus.ACTIVE.code());
        extra.setLockVersion(4);
        File file = new File();
        UUID filePublicId = UUID.randomUUID();
        file.setId(20L);
        file.setPublicId(filePublicId);
        file.setPath("/source.md");
        file.setType("md");
        ChunkVectorWorker.FileSnapshot fileSnapshot =
                new ChunkVectorWorker.FileSnapshot(filePublicId, "/source.md", "md");

        when(processingMapper.findScopedForUpdate(20L, 1L, 10L)).thenReturn(processing);
        when(fileMapper.selectById(20L)).thenReturn(file);
        if (driftAtCompletion) {
            when(chunkMapper.findByFileForUpdate(20L, 1L, 10L))
                    .thenReturn(List.of(target), List.of(target, extra), List.of(target, extra));
        } else {
            when(chunkMapper.findByFileForUpdate(20L, 1L, 10L))
                    .thenReturn(List.of(target, extra));
        }
        when(chunkMapper.update(any(), any())).thenReturn(1);
        when(tokens.count(any())).thenReturn(4);
        ChunkVectorWorker worker = new ChunkVectorWorker(processingMapper, fileMapper, chunkMapper,
                stateService, enricher, tokens, gateway, transactions, resolver, path -> "hash");
        return new UnexpectedChunkFixture(worker, gateway, stateService, snapshot, processingSnapshot,
                prepared, fileSnapshot);
    }

    private record UnexpectedChunkFixture(ChunkVectorWorker worker,
                                          ChunkVectorGateway gateway,
                                          FileProcessingService stateService,
                                          ChunkVectorWorker.ChunkSnapshot snapshot,
                                          ChunkVectorWorker.ProcessingSnapshot processingSnapshot,
                                          ChunkVectorWorker.PreparedChunk prepared,
                                          ChunkVectorWorker.FileSnapshot fileSnapshot) {
    }

    private static FileProcessing markdownProcessing(String plannerVersion) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(20L);
        processing.setTenantId(1L);
        processing.setKnowledgeId(10L);
        processing.setPipelineState(PipelineState.VECTORIZING.code());
        processing.setLockVersion(5);
        processing.setSourceHash("hash");
        processing.setStrategyCode("MARKDOWN_OPTIMIZED");
        processing.setPlannerVersion(plannerVersion);
        processing.setPolicySnapshot(java.util.Map.of("maxTokens", 512));
        processing.setContextPolicy(java.util.Map.of("overlapEnabled", false,
                "overlapTokens", 40));
        processing.setExecutionMetadata(java.util.Map.of());
        return processing;
    }

    private static DocumentChunk chunk() {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setPublicId(UUID.randomUUID());
        chunk.setTenantId(1L);
        chunk.setKnowledgeId(10L);
        chunk.setFileId(20L);
        chunk.setPosition(0);
        chunk.setContent("body");
        chunk.setContentHash("hash-1");
        chunk.setOverlapEnabled(true);
        chunk.setOverlapLimit(40);
        chunk.setOverlapUnit(OverlapUnit.CHARACTERS);
        chunk.setStatus(1);
        chunk.setLockVersion(2);
        return chunk;
    }
}
