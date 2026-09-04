package com.starsea.ai.chunking.preview;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkCommandServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID CHUNK_ID = UUID.fromString("10000000-0000-0000-0000-000000000021");
    private static final UUID NEXT_ID = UUID.fromString("10000000-0000-0000-0000-000000000022");

    private DocumentChunkMapper chunkMapper;
    private FileProcessingMapper processingMapper;
    private FileProcessingService stateService;
    private ChunkVectorGateway vectorGateway;
    private ChunkCommandService service;

    @BeforeEach
    void setUp() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, TENANT_ID,
                "tenant_admin", "jti"));
        chunkMapper = mock(DocumentChunkMapper.class);
        processingMapper = mock(FileProcessingMapper.class);
        stateService = mock(FileProcessingService.class);
        vectorGateway = mock(ChunkVectorGateway.class);
        TokenCounter counter = new CharacterTokenCounter();
        service = new ChunkCommandService(chunkMapper, processingMapper, stateService,
                counter, new ChunkIndexContentBuilder(), vectorGateway, millis -> { });
        when(processingMapper.selectById(FILE_ID)).thenReturn(processing(PipelineState.CHUNKED, 5));
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.CHUNKED, 5));
        when(chunkMapper.update(any(DocumentChunk.class), any())).thenReturn(1);
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void list_maps_entities_to_visible_responses_without_hidden_index_context() {
        DocumentChunk chunk = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Body");
        chunk.setOverlapContent("hidden overlap");
        chunk.setIndexContent("hidden indexed text");
        chunk.setSectionPath(List.of("Title"));
        chunk.setSourceLocator(Map.of("startLine", 8));
        chunk.setIsModified(true);
        when(chunkMapper.findByFile(FILE_ID, TENANT_ID, KNOWLEDGE_ID)).thenReturn(List.of(chunk));

        var response = service.list(KNOWLEDGE_ID, FILE_ID).get(0);

        assertEquals(CHUNK_ID, response.publicId());
        assertEquals(4, response.position());
        assertEquals("Body", response.content());
        assertEquals(List.of("Title"), response.sectionPath());
        assertEquals(Map.of("startLine", 8), response.sourceLocator());
        assertEquals(4, response.tokenCount());
        assertEquals(ChunkStatus.DRAFT.code(), response.status());
        assertTrue(response.isModified());
        assertEquals(2, response.lockVersion());
    }

    @Test
    void list_returns_the_stable_reason_code_when_enabled_overlap_is_unavailable() {
        DocumentChunk chunk = chunk(31L, CHUNK_ID, 0, ChunkStatus.DRAFT, 2, "Body");
        chunk.setOverlapEnabled(true);
        chunk.setOverlapContent(null);
        when(chunkMapper.findByFile(FILE_ID, TENANT_ID, KNOWLEDGE_ID)).thenReturn(List.of(chunk));

        var response = service.list(KNOWLEDGE_ID, FILE_ID).get(0);

        assertEquals(ChunkingApiModels.NO_AVAILABLE_OVERLAP_REASON_CODE,
                response.overlapUnavailableReason());
    }

    @Test
    void edit_persists_body_and_per_chunk_settings_with_fresh_read_only_overlap() {
        DocumentChunk previous = chunk(30L, UUID.randomUUID(), 3,
                ChunkStatus.ACTIVE, 1, "Source sentence.");
        previous.setOverlapContent("must not be chained");
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findScopedByPosition(FILE_ID, TENANT_ID, KNOWLEDGE_ID, 3))
                .thenReturn(previous);
        when(chunkMapper.update(any(DocumentChunk.class), any())).thenReturn(1);

        var response = service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", true, 40, 2));

        assertTrue(response.overlapEnabled());
        assertEquals(40, response.overlapTokenLimit());
        assertEquals("Source sentence.", response.overlapContent());
        assertTrue(response.overlapTokenCount() > 0);
        assertEquals(null, response.overlapUnavailableReason());
        var patch = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).update(patch.capture(), any());
        assertEquals("Edited", patch.getValue().getContent());
        assertEquals("Source sentence.", patch.getValue().getOverlapContent());
        assertEquals("上文：Source sentence.\n\nEdited", patch.getValue().getIndexContent());
    }

    @Test
    void source_edit_recalculates_enabled_next_chunk_even_without_an_existing_source_id() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        DocumentChunk dependent = chunk(32L, NEXT_ID, 5, ChunkStatus.ACTIVE, 7, "Next");
        dependent.setOverlapEnabled(true);
        dependent.setOverlapTokenLimit(40);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenReturn(dependent);
        when(chunkMapper.update(any(DocumentChunk.class), any())).thenReturn(1);

        service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited source.", false, 40, 2));

        var patches = org.mockito.ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, times(2)).update(patches.capture(), any());
        verify(chunkMapper, never()).findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        DocumentChunk dependentPatch = patches.getAllValues().get(1);
        assertEquals("Edited source.", dependentPatch.getOverlapContent());
        assertEquals("上文：Edited source.\n\nNext", dependentPatch.getIndexContent());
    }

    @Test
    void source_edit_does_not_invalidate_enabled_neighbor_when_derived_context_is_unchanged() {
        DocumentChunk target = chunk(
                31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Discarded sentence. Stable.");
        DocumentChunk dependent = chunk(32L, NEXT_ID, 5, ChunkStatus.ACTIVE, 7, "Next");
        dependent.setOverlapEnabled(true);
        dependent.setOverlapTokenLimit(12);
        dependent.setOverlapSourceChunkId(31L);
        dependent.setOverlapContent("Stable.");
        dependent.setOverlapTokenCount(12);
        dependent.setIndexContent("上文：Stable.\n\nNext");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenReturn(dependent);

        service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Changed sentence. Stable.", 2));

        verify(chunkMapper, times(1)).update(any(DocumentChunk.class), any());
        verify(chunkMapper, never()).findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway, never()).delete(NEXT_ID);
    }

    @Test
    void edit_rejects_blank_content_before_any_mutation() {
        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("  \n", 2)));

        assertEquals(422, failure.status().value());
        verify(processingMapper, never()).findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
    }

    @Test
    void edit_reports_independent_title_body_and_exact_total_budget_counts() {
        DocumentChunk chunk = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Body");
        chunk.setSectionPath(List.of("Long"));
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5, Map.of("maxTokens", 12)));
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(chunk);

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("abcdef", 2)));

        assertEquals(422, failure.status().value());
        assertEquals(Map.of(
                "titleTokenCount", 7,
                "bodyTokenCount", 6,
                "totalTokenCount", 15,
                "maxTokens", 12), failure.details());
        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
    }

    @Test
    void edit_active_atomically_invalidates_target_and_dependent_before_vector_cleanup() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        target.setSectionPath(List.of());
        DocumentChunk dependent = chunk(32L, NEXT_ID, 5, ChunkStatus.ACTIVE, 7, "Next");
        dependent.setOverlapEnabled(true);
        dependent.setOverlapTokenLimit(40);
        dependent.setOverlapSourceChunkId(31L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenReturn(dependent);
        var response = service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2));

        assertEquals("Edited", response.content());
        assertEquals(ChunkStatus.DRAFT.code(), response.status());
        assertEquals(3, response.lockVersion());
        verify(chunkMapper, times(2)).update(any(DocumentChunk.class), any());
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.CHUNKED, PipelineState.ADJUSTING, 5);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway).delete(NEXT_ID);
    }

    @Test
    void failed_vectorization_draft_can_be_edited_and_returns_file_to_adjusting() {
        FileProcessing failed = processing(PipelineState.FAILED, 5);
        failed.setFailedFromState(PipelineState.VECTORIZING.code());
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(failed);
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        target.setSectionPath(List.of());
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        var response = service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Recovered", 2));

        assertEquals(ChunkStatus.DRAFT.code(), response.status());
        verify(stateService).recoverFailedDraftMutation(KNOWLEDGE_ID, FILE_ID, 5);
    }

    @Test
    void edit_never_mutates_an_indexing_chunk() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.INDEXING, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("Edited", 2)));

        assertEquals(409, failure.status().value());
        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
        verify(vectorGateway, never()).delete(CHUNK_ID);
    }

    @Test
    void edit_ignores_disabled_indexing_right_neighbor() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        DocumentChunk rightNeighbor = chunk(
                32L, NEXT_ID, 5, ChunkStatus.INDEXING, 7, "Unrelated");
        rightNeighbor.setOverlapEnabled(false);
        rightNeighbor.setOverlapSourceChunkId(31L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenAnswer(invocation ->
                        Boolean.TRUE.equals(rightNeighbor.getOverlapEnabled())
                                ? rightNeighbor : null);

        assertDoesNotThrow(() -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2)));

        verify(chunkMapper, times(1)).update(any(DocumentChunk.class), any());
        verify(chunkMapper).findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5);
        verify(chunkMapper, never()).findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway, never()).delete(NEXT_ID);
    }

    @Test
    void affected_row_mismatch_is_an_optimistic_lock_conflict_without_vector_cleanup() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.update(any(DocumentChunk.class), any())).thenReturn(0);

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("Edited", 2)));

        assertEquals(409, failure.status().value());
        verify(vectorGateway, never()).delete(CHUNK_ID);
        verify(stateService, never()).transition(eq(KNOWLEDGE_ID), eq(FILE_ID),
                eq(PipelineState.CHUNKED), eq(PipelineState.ADJUSTING), anyInt());
    }

    @Test
    void delete_is_physical_scoped_optimistic_and_invalidates_the_next_overlap_dependent() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        DocumentChunk dependent = chunk(32L, NEXT_ID, 5, ChunkStatus.ACTIVE, 7, "Next");
        dependent.setOverlapEnabled(true);
        dependent.setOverlapTokenLimit(40);
        dependent.setOverlapSourceChunkId(31L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenReturn(dependent);
        when(chunkMapper.deleteScoped(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2)).thenReturn(1);

        service.delete(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, 2);

        verify(chunkMapper).deleteScoped(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2);
        verify(chunkMapper, never()).findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        verify(chunkMapper, never()).deleteById(31L);
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.CHUNKED, PipelineState.ADJUSTING, 5);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway).delete(NEXT_ID);
    }

    @Test
    void delete_ignores_disabled_indexing_right_neighbor() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        DocumentChunk rightNeighbor = chunk(
                32L, NEXT_ID, 5, ChunkStatus.INDEXING, 7, "Unrelated");
        rightNeighbor.setOverlapEnabled(false);
        rightNeighbor.setOverlapSourceChunkId(31L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenAnswer(invocation ->
                        Boolean.TRUE.equals(rightNeighbor.getOverlapEnabled())
                                ? rightNeighbor : null);
        when(chunkMapper.deleteScoped(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2)).thenReturn(1);

        assertDoesNotThrow(() -> service.delete(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, 2));

        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
        verify(chunkMapper).findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5);
        verify(chunkMapper, never()).findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway, never()).delete(NEXT_ID);
    }

    @Test
    void delete_ignores_source_free_indexing_neighbor_across_title_and_structure_boundaries() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Incomplete source");
        target.setSectionPath(List.of("Previous section"));
        target.setBoundaryReason(Map.of("end", "H2_SECTION"));
        DocumentChunk rightNeighbor = chunk(
                32L, NEXT_ID, 5, ChunkStatus.INDEXING, 7, "Unrelated");
        rightNeighbor.setSectionPath(List.of("Next section"));
        rightNeighbor.setBoundaryReason(Map.of("start", "H2_SECTION"));
        rightNeighbor.setOverlapEnabled(true);
        rightNeighbor.setOverlapTokenLimit(40);
        rightNeighbor.setOverlapContent(null);
        rightNeighbor.setOverlapSourceChunkId(null);
        rightNeighbor.setOverlapTokenCount(0);
        rightNeighbor.setIndexContent("标题：Next section\n\nUnrelated");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5)).thenReturn(rightNeighbor);
        when(chunkMapper.deleteScoped(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2)).thenReturn(1);

        assertDoesNotThrow(() -> service.delete(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, 2));

        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
        verify(chunkMapper).deleteScoped(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway, never()).delete(NEXT_ID);
    }

    @Test
    void final_chunk_deletion_makes_the_file_explicitly_unconfirmable() {
        when(processingMapper.selectById(FILE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5));
        when(chunkMapper.findByFile(FILE_ID, TENANT_ID, KNOWLEDGE_ID)).thenReturn(List.of());

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.requireConfirmable(KNOWLEDGE_ID, FILE_ID));

        assertEquals(422, failure.status().value());
        assertTrue(failure.getMessage().contains("at least one chunk"));
    }

    @Test
    void parent_chunks_cannot_be_edited_or_deleted() {
        DocumentChunk parent = chunk(31L, CHUNK_ID, 0, ChunkStatus.DRAFT, 2, "Parent");
        parent.setChunkType(ChunkType.PARENT.code());
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(parent);

        ChunkingException editFailure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("Changed", false, 40, 2)));
        ChunkingException deleteFailure = assertThrows(ChunkingException.class,
                () -> service.delete(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, 2));

        assertEquals(422, editFailure.status().value());
        assertEquals(422, deleteFailure.status().value());
        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
        verify(chunkMapper, never()).deleteScoped(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2);
    }

    @Test
    void child_edit_uses_child_token_budget() {
        DocumentChunk child = chunk(31L, CHUNK_ID, 1, ChunkStatus.DRAFT, 2, "Old");
        child.setChunkType(ChunkType.CHILD.code());
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5, Map.of(
                        "maxTokens", 512, "childMaxTokens", 8, "childOverlapTokens", 3)));
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(child);

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("123456789", true, 3, 2)));

        assertEquals(422, failure.status().value());
        assertEquals(8, failure.details().get("maxTokens"));
        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
    }

    @Test
    void child_edit_rejects_overlap_that_differs_from_strategy_snapshot() {
        DocumentChunk child = chunk(31L, CHUNK_ID, 1, ChunkStatus.DRAFT, 2, "Old");
        child.setChunkType(ChunkType.CHILD.code());
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5, Map.of(
                        "maxTokens", 512, "childMaxTokens", 64, "childOverlapTokens", 0)));
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(child);

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("Changed", true, 40, 2)));

        assertEquals(422, failure.status().value());
        verify(chunkMapper, never()).update(any(DocumentChunk.class), any());
    }

    @Test
    void editing_first_child_never_sends_its_parent_to_context_enricher() {
        DocumentChunk parent = chunk(30L, UUID.randomUUID(), 0,
                ChunkStatus.ACTIVE, 1, "Parent answer context");
        parent.setChunkType(ChunkType.PARENT.code());
        DocumentChunk child = chunk(31L, CHUNK_ID, 1, ChunkStatus.ACTIVE, 2, "Old");
        child.setChunkType(ChunkType.CHILD.code());
        child.setParentChunkId(parent.getId());
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5, Map.of(
                        "childMaxTokens", 64, "childOverlapTokens", 0)));
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(child);
        when(chunkMapper.findScopedByPosition(FILE_ID, TENANT_ID, KNOWLEDGE_ID, 0))
                .thenReturn(parent);
        ChunkContextEnricher enricher = mock(ChunkContextEnricher.class);
        when(enricher.enrich(any(), eq(64))).thenAnswer(invocation -> {
            List<DocumentChunk> inputs = invocation.getArgument(0);
            assertEquals(List.of(ChunkType.CHILD.code()),
                    inputs.stream().map(DocumentChunk::getChunkType).toList());
            DocumentChunk edited = inputs.get(0);
            return List.of(new EnrichedChunk(edited, null, null, 0, edited.getContent()));
        });
        ChunkCommandService childService = new ChunkCommandService(
                chunkMapper, processingMapper, stateService, new CharacterTokenCounter(),
                new ChunkIndexContentBuilder(), vectorGateway, enricher, millis -> { });

        childService.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Changed", false, 1, 2));

        verify(enricher).enrich(any(), eq(64));
        assertEquals("Parent answer context", parent.getContent());
    }

    @Test
    void deleting_last_child_requests_fully_scoped_empty_parent_cleanup() {
        DocumentChunk child = chunk(31L, CHUNK_ID, 1, ChunkStatus.ACTIVE, 2, "Child");
        child.setChunkType(ChunkType.CHILD.code());
        child.setParentChunkId(30L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(child);
        when(chunkMapper.deleteScoped(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2)).thenReturn(1);

        service.delete(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, 2);

        verify(chunkMapper).deleteEmptyParent(30L, TENANT_ID, KNOWLEDGE_ID, FILE_ID);
        verify(vectorGateway).delete(CHUNK_ID);
    }

    @Test
    void parent_only_file_is_not_confirmable() {
        DocumentChunk parent = chunk(31L, CHUNK_ID, 0, ChunkStatus.DRAFT, 2, "Parent");
        parent.setChunkType(ChunkType.PARENT.code());
        when(processingMapper.selectById(FILE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5));
        when(chunkMapper.findByFile(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(parent));

        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.requireConfirmable(KNOWLEDGE_ID, FILE_ID));

        assertEquals(422, failure.status().value());
    }

    @Test
    void vector_cleanup_has_exactly_three_bounded_attempts_without_rolling_back_success() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        doThrow(new IllegalStateException("vector unavailable")).when(vectorGateway).delete(CHUNK_ID);

        var response = service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2));

        assertEquals("Edited", response.content());
        verify(vectorGateway, times(3)).delete(CHUNK_ID);
    }

    @Test
    void exhausted_vector_cleanup_marks_the_adjusting_file_failed() {
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.ADJUSTING, 5));
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        doThrow(new IllegalStateException("vector unavailable")).when(vectorGateway).delete(CHUNK_ID);

        service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, new EditChunkRequest("Edited", 2));

        verify(stateService).fail(KNOWLEDGE_ID, FILE_ID, PipelineState.ADJUSTING, 5, 100,
                "Vector cleanup failed: vector unavailable");
    }

    @Test
    void vector_cleanup_succeeds_on_each_bounded_attempt_index() {
        for (int successAttempt = 1; successAttempt <= 3; successAttempt++) {
            AtomicInteger calls = new AtomicInteger();
            List<Long> attemptSleeps = new ArrayList<>();
            int expectedSuccessAttempt = successAttempt;
            ChunkVectorGateway gateway = publicId -> {
                if (calls.incrementAndGet() < expectedSuccessAttempt) {
                    throw new IllegalStateException("retry");
                }
            };
            DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
            when(chunkMapper.findScopedByPublicIdForUpdate(
                    FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
            ChunkCommandService attemptService = new ChunkCommandService(
                    chunkMapper, processingMapper, stateService, new CharacterTokenCounter(),
                    new ChunkIndexContentBuilder(), gateway, attemptSleeps::add);

            attemptService.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                    new EditChunkRequest("Edited", 2));

            assertEquals(successAttempt, calls.get());
            assertEquals(List.of(100L, 300L, 900L).subList(0, successAttempt), attemptSleeps);
        }
    }

    @Test
    void real_transaction_rolls_back_target_and_dependent_when_file_transition_fails() {
        List<DocumentChunk> database = activeTargetAndDependent();
        DocumentChunkMapper mutableMapper = mutableChunkMapper(database);
        ChunkVectorGateway cleanup = mock(ChunkVectorGateway.class);
        doThrow(new FileProcessingService.StateConflictException("state changed"))
                .when(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                        PipelineState.CHUNKED, PipelineState.ADJUSTING, 5);
        ChunkCommandService target = new ChunkCommandService(
                mutableMapper, processingMapper, stateService, new CharacterTokenCounter(),
                new ChunkIndexContentBuilder(), cleanup, millis -> { });
        StateTransactionManager transactionManager = new StateTransactionManager(database);
        ChunkCommandService transactional = transactionalProxy(target, transactionManager);

        assertThrows(FileProcessingService.StateConflictException.class, () -> transactional.edit(
                KNOWLEDGE_ID, FILE_ID, CHUNK_ID, new EditChunkRequest("Edited", 2)));

        assertEquals(1, transactionManager.rollbacks());
        assertEquals("Old", database.get(0).getContent());
        assertEquals(ChunkStatus.ACTIVE.code(), database.get(0).getStatus());
        assertEquals("target-index", database.get(0).getIndexContent());
        assertEquals(ChunkStatus.ACTIVE.code(), database.get(1).getStatus());
        assertEquals("dependent-overlap", database.get(1).getOverlapContent());
        assertEquals("dependent-index", database.get(1).getIndexContent());
        verify(mutableMapper, times(2)).update(any(DocumentChunk.class), any());
        verify(cleanup, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void successful_mutable_transaction_cleans_vectors_only_after_committed_state_is_visible() {
        List<DocumentChunk> database = activeTargetAndDependent();
        DocumentChunkMapper mutableMapper = mutableChunkMapper(database);
        AtomicBoolean committed = new AtomicBoolean();
        List<UUID> cleaned = new ArrayList<>();
        ChunkVectorGateway cleanup = publicId -> {
            assertTrue(committed.get(), "vector deletion must observe committed mutable state");
            assertEquals(ChunkStatus.DRAFT.code(), database.get(0).getStatus());
            assertEquals(ChunkStatus.DRAFT.code(), database.get(1).getStatus());
            cleaned.add(publicId);
        };
        ChunkCommandService target = new ChunkCommandService(
                mutableMapper, processingMapper, stateService, new CharacterTokenCounter(),
                new ChunkIndexContentBuilder(), cleanup, millis -> { });
        StateTransactionManager transactionManager = new StateTransactionManager(database, committed);
        ChunkCommandService transactional = transactionalProxy(target, transactionManager);

        transactional.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2));

        assertTrue(committed.get());
        assertEquals("Edited", database.get(0).getContent());
        assertEquals(ChunkStatus.DRAFT.code(), database.get(0).getStatus());
        assertEquals(ChunkStatus.DRAFT.code(), database.get(1).getStatus());
        assertEquals(List.of(CHUNK_ID, NEXT_ID), cleaned);
    }

    @Test
    void registered_vector_cleanup_is_suppressed_when_commit_fails_and_state_rolls_back() {
        List<DocumentChunk> database = activeTargetAndDependent();
        DocumentChunkMapper mutableMapper = mutableChunkMapper(database);
        ChunkVectorGateway cleanup = mock(ChunkVectorGateway.class);
        AtomicBoolean committed = new AtomicBoolean();
        AtomicInteger completionStatus = new AtomicInteger(-1);
        doAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completionStatus.set(status);
                }
            });
            return null;
        }).when(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.CHUNKED, PipelineState.ADJUSTING, 5);
        ChunkCommandService target = new ChunkCommandService(
                mutableMapper, processingMapper, stateService, new CharacterTokenCounter(),
                new ChunkIndexContentBuilder(), cleanup, millis -> { });
        StateTransactionManager transactionManager =
                new StateTransactionManager(database, committed, true);
        ChunkCommandService transactional = transactionalProxy(target, transactionManager);

        assertThrows(IllegalStateException.class, () -> transactional.edit(
                KNOWLEDGE_ID, FILE_ID, CHUNK_ID, new EditChunkRequest("Edited", 2)));

        assertEquals(2, transactionManager.registeredSynchronizations());
        assertEquals(1, transactionManager.rollbacks());
        assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, completionStatus.get());
        assertFalse(committed.get());
        assertEquals("Old", database.get(0).getContent());
        assertEquals(ChunkStatus.ACTIVE.code(), database.get(0).getStatus());
        assertEquals("target-index", database.get(0).getIndexContent());
        assertEquals(ChunkStatus.ACTIVE.code(), database.get(1).getStatus());
        assertEquals("dependent-overlap", database.get(1).getOverlapContent());
        assertEquals("dependent-index", database.get(1).getIndexContent());
        verify(cleanup, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adjusting_file_completes_only_when_every_remaining_chunk_is_active() {
        FileProcessing adjusting = processing(PipelineState.ADJUSTING, 8);
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(adjusting);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(
                        chunk(31L, CHUNK_ID, 0, ChunkStatus.ACTIVE, 3, "First"),
                        chunk(32L, NEXT_ID, 1, ChunkStatus.ACTIVE, 4, "Second")));

        assertTrue(service.completeIfAllActive(KNOWLEDGE_ID, FILE_ID));

        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.ADJUSTING, PipelineState.COMPLETED, 8);
    }

    @Test
    void adjusting_file_remains_adjusting_while_any_draft_exists() {
        FileProcessing adjusting = processing(PipelineState.ADJUSTING, 8);
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(adjusting);
        when(chunkMapper.findByFileForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(List.of(
                        chunk(31L, CHUNK_ID, 0, ChunkStatus.ACTIVE, 3, "First"),
                        chunk(32L, NEXT_ID, 1, ChunkStatus.DRAFT, 4, "Second")));

        assertTrue(!service.completeIfAllActive(KNOWLEDGE_ID, FILE_ID));

        verify(stateService, never()).transition(eq(KNOWLEDGE_ID), eq(FILE_ID),
                eq(PipelineState.ADJUSTING), eq(PipelineState.COMPLETED), anyInt());
    }

    @Test
    void delete_and_dependent_lock_sql_are_scoped_and_physical() throws Exception {
        Configuration configuration = new Configuration();
        try (var stream = getClass().getResourceAsStream("/mapper/DocumentChunkMapper.xml")) {
            new XMLMapperBuilder(stream, configuration,
                    "mapper/DocumentChunkMapper.xml", configuration.getSqlFragments()).parse();
        }
        String delete = sql(configuration,
                "com.starsea.ai.mapper.DocumentChunkMapper.deleteScoped", Map.of(
                        "fileId", FILE_ID, "tenantId", TENANT_ID, "knowledgeId", KNOWLEDGE_ID,
                        "chunkPublicId", CHUNK_ID, "lockVersion", 2));
        String dependentLock = sql(configuration,
                "com.starsea.ai.mapper.DocumentChunkMapper.findNextDependentForUpdate", Map.of(
                        "fileId", FILE_ID, "tenantId", TENANT_ID,
                        "knowledgeId", KNOWLEDGE_ID, "position", 5));
        String emptyParentDelete = sql(configuration,
                "com.starsea.ai.mapper.DocumentChunkMapper.deleteEmptyParent", Map.of(
                        "parentId", 30L, "tenantId", TENANT_ID,
                        "knowledgeId", KNOWLEDGE_ID, "fileId", FILE_ID));

        assertTrue(delete.startsWith("DELETE FROM document_chunk"));
        assertTrue(delete.contains("tenant_id = ?"));
        assertTrue(delete.contains("knowledge_id = ?"));
        assertTrue(delete.contains("file_id = ?"));
        assertTrue(delete.contains("public_id = ?"));
        assertTrue(delete.contains("status <> 1"));
        assertTrue(delete.contains("lock_version = ?"));
        assertTrue(!delete.contains("UPDATE document_chunk"));
        assertTrue(dependentLock.contains("tenant_id = ?"));
        assertTrue(dependentLock.contains("knowledge_id = ?"));
        assertTrue(dependentLock.contains("file_id = ?"));
        assertTrue(dependentLock.contains("position = ?"));
        assertTrue(dependentLock.contains("overlap_enabled = TRUE"));
        assertTrue(!dependentLock.contains("overlap_source_chunk_id"));
        assertTrue(dependentLock.endsWith("FOR UPDATE"));
        assertTrue(emptyParentDelete.startsWith("DELETE FROM document_chunk"));
        assertTrue(emptyParentDelete.contains("id = ?"));
        assertTrue(emptyParentDelete.contains("tenant_id = ?"));
        assertTrue(emptyParentDelete.contains("knowledge_id = ?"));
        assertTrue(emptyParentDelete.contains("file_id = ?"));
        assertTrue(emptyParentDelete.contains("chunk_type = 1"));
        assertTrue(emptyParentDelete.contains("NOT EXISTS"));
        assertTrue(emptyParentDelete.contains("child.tenant_id = ?"));
        assertTrue(emptyParentDelete.contains("child.knowledge_id = ?"));
        assertTrue(emptyParentDelete.contains("child.file_id = ?"));
    }

    private static DocumentChunk chunk(long id, UUID publicId, int position,
                                       ChunkStatus status, int lockVersion, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setPublicId(publicId);
        chunk.setTenantId(TENANT_ID);
        chunk.setKnowledgeId(KNOWLEDGE_ID);
        chunk.setFileId(FILE_ID);
        chunk.setPosition(position);
        chunk.setContent(content);
        chunk.setSectionPath(List.of());
        chunk.setSourceLocator(Map.of());
        chunk.setTokenCount(content.length());
        chunk.setStatus(status.code());
        chunk.setIsModified(false);
        chunk.setLockVersion(lockVersion);
        return chunk;
    }

    private static List<DocumentChunk> activeTargetAndDependent() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        target.setIndexContent("target-index");
        DocumentChunk dependent = chunk(32L, NEXT_ID, 5, ChunkStatus.ACTIVE, 7, "Next");
        dependent.setOverlapEnabled(true);
        dependent.setOverlapTokenLimit(40);
        dependent.setOverlapSourceChunkId(31L);
        dependent.setOverlapContent("dependent-overlap");
        dependent.setOverlapTokenCount(2);
        dependent.setIndexContent("dependent-index");
        return new ArrayList<>(List.of(target, dependent));
    }

    private DocumentChunkMapper mutableChunkMapper(List<DocumentChunk> database) {
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        when(mapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID))
                .thenAnswer(invocation -> database.get(0));
        when(mapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5))
                .thenAnswer(invocation -> database.get(1));
        when(mapper.update(any(DocumentChunk.class), any())).thenAnswer(invocation -> {
            DocumentChunk patch = invocation.getArgument(0);
            int index = patch.getContent() != null ? 0 : 1;
            DocumentChunk updated = copy(database.get(index));
            if (patch.getContent() != null) {
                updated.setContent(patch.getContent());
            }
            if (patch.getTokenCount() != null) {
                updated.setTokenCount(patch.getTokenCount());
            }
            if (patch.getContentHash() != null) {
                updated.setContentHash(patch.getContentHash());
            }
            updated.setOverlapEnabled(patch.getOverlapEnabled());
            updated.setOverlapTokenLimit(patch.getOverlapTokenLimit());
            updated.setOverlapContent(patch.getOverlapContent());
            updated.setOverlapSourceChunkId(patch.getOverlapSourceChunkId());
            updated.setOverlapTokenCount(patch.getOverlapTokenCount());
            updated.setIndexContent(patch.getIndexContent());
            updated.setStatus(ChunkStatus.DRAFT.code());
            updated.setIsModified(index == 0 || updated.getIsModified());
            updated.setLockVersion(updated.getLockVersion() + 1);
            database.set(index, updated);
            return 1;
        });
        return mapper;
    }

    private static DocumentChunk copy(DocumentChunk source) {
        DocumentChunk copy = new DocumentChunk();
        copy.setId(source.getId());
        copy.setPublicId(source.getPublicId());
        copy.setTenantId(source.getTenantId());
        copy.setKnowledgeId(source.getKnowledgeId());
        copy.setFileId(source.getFileId());
        copy.setPosition(source.getPosition());
        copy.setContent(source.getContent());
        copy.setOverlapEnabled(source.getOverlapEnabled());
        copy.setOverlapTokenLimit(source.getOverlapTokenLimit());
        copy.setOverlapContent(source.getOverlapContent());
        copy.setOverlapSourceChunkId(source.getOverlapSourceChunkId());
        copy.setOverlapTokenCount(source.getOverlapTokenCount());
        copy.setIndexContent(source.getIndexContent());
        copy.setSectionPath(source.getSectionPath());
        copy.setSourceLocator(source.getSourceLocator());
        copy.setTokenCount(source.getTokenCount());
        copy.setContentHash(source.getContentHash());
        copy.setBoundaryReason(source.getBoundaryReason());
        copy.setStatus(source.getStatus());
        copy.setIsModified(source.getIsModified());
        copy.setLastError(source.getLastError());
        copy.setLockVersion(source.getLockVersion());
        return copy;
    }

    private static FileProcessing processing(PipelineState state, int lockVersion) {
        return processing(state, lockVersion, Map.of("maxTokens", 512));
    }

    private static FileProcessing processing(PipelineState state, int lockVersion,
                                             Map<String, Object> policy) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(FILE_ID);
        processing.setTenantId(TENANT_ID);
        processing.setKnowledgeId(KNOWLEDGE_ID);
        processing.setPipelineState(state.code());
        processing.setLockVersion(lockVersion);
        processing.setPolicySnapshot(policy);
        return processing;
    }

    private static String sha256(String content) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String sql(Configuration configuration, String statementId,
                              Map<String, Object> parameters) {
        return normalizeSql(boundSql(configuration, statementId, parameters));
    }

    private static BoundSql boundSql(Configuration configuration, String statementId,
                                     Map<String, Object> parameters) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        return statement.getBoundSql(parameters);
    }

    private static String normalizeSql(BoundSql boundSql) {
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    private static ChunkCommandService transactionalProxy(
            ChunkCommandService target, StateTransactionManager transactionManager) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (ChunkCommandService) proxyFactory.getProxy();
    }

    private static final class StateTransactionManager extends AbstractPlatformTransactionManager {
        private final List<DocumentChunk> database;
        private final AtomicBoolean committed;
        private final boolean failCommit;
        private List<DocumentChunk> snapshot = List.of();
        private int rollbacks;
        private int registeredSynchronizations;

        private StateTransactionManager(List<DocumentChunk> database) {
            this(database, new AtomicBoolean(), false);
        }

        private StateTransactionManager(List<DocumentChunk> database, AtomicBoolean committed) {
            this(database, committed, false);
        }

        private StateTransactionManager(List<DocumentChunk> database, AtomicBoolean committed,
                                        boolean failCommit) {
            this.database = database;
            this.committed = committed;
            this.failCommit = failCommit;
            setRollbackOnCommitFailure(true);
        }

        int rollbacks() {
            return rollbacks;
        }

        int registeredSynchronizations() {
            return registeredSynchronizations;
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
            registeredSynchronizations = TransactionSynchronizationManager
                    .getSynchronizations().size();
            if (failCommit) {
                throw new IllegalStateException("commit failed");
            }
            committed.set(true);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
            database.clear();
            database.addAll(snapshot);
        }
    }

    private static final class CharacterTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public String id() {
            return "character-test-counter";
        }
    }
}
