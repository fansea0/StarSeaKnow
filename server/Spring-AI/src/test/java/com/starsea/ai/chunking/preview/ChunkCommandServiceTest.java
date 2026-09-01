package com.starsea.ai.chunking.preview;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.indexing.ChunkVectorGateway;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.processing.FileProcessingService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
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
    private List<Long> sleeps;
    private ChunkCommandService service;

    @BeforeEach
    void setUp() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, TENANT_ID,
                "tenant_admin", "jti"));
        chunkMapper = mock(DocumentChunkMapper.class);
        processingMapper = mock(FileProcessingMapper.class);
        stateService = mock(FileProcessingService.class);
        vectorGateway = mock(ChunkVectorGateway.class);
        sleeps = new ArrayList<>();
        TokenCounter counter = new CharacterTokenCounter();
        service = new ChunkCommandService(chunkMapper, processingMapper, stateService,
                counter, new ChunkIndexContentBuilder(), vectorGateway, sleeps::add);
        when(processingMapper.selectById(FILE_ID)).thenReturn(processing(PipelineState.CHUNKED, 5));
        when(processingMapper.findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID))
                .thenReturn(processing(PipelineState.CHUNKED, 5));
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
    void edit_rejects_blank_content_before_any_mutation() {
        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                        new EditChunkRequest("  \n", 2)));

        assertEquals(422, failure.status().value());
        verify(processingMapper, never()).findScopedForUpdate(FILE_ID, TENANT_ID, KNOWLEDGE_ID);
        verify(chunkMapper, never()).updateContent(eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID),
                eq(CHUNK_ID), anyString(), anyInt(), anyString(), anyInt());
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
        verify(chunkMapper, never()).updateContent(eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID),
                eq(CHUNK_ID), anyString(), anyInt(), anyString(), anyInt());
    }

    @Test
    void edit_active_atomically_invalidates_target_and_dependent_before_vector_cleanup() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.ACTIVE, 2, "Old");
        target.setSectionPath(List.of());
        DocumentChunk dependent = chunk(32L, NEXT_ID, 5, ChunkStatus.ACTIVE, 7, "Next");
        dependent.setOverlapSourceChunkId(31L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5, 31L)).thenReturn(dependent);
        when(chunkMapper.updateContent(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID,
                "Edited", 6, sha256("Edited"), 2)).thenReturn(1);
        when(chunkMapper.invalidateDependent(FILE_ID, TENANT_ID, KNOWLEDGE_ID,
                32L, 31L, 7)).thenReturn(1);

        var response = service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2));

        assertEquals("Edited", response.content());
        assertEquals(ChunkStatus.DRAFT.code(), response.status());
        assertEquals(3, response.lockVersion());
        verify(chunkMapper).updateContent(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID,
                "Edited", 6, sha256("Edited"), 2);
        verify(chunkMapper).invalidateDependent(FILE_ID, TENANT_ID, KNOWLEDGE_ID,
                32L, 31L, 7);
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.CHUNKED, PipelineState.ADJUSTING, 5);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway).delete(NEXT_ID);
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
        verify(chunkMapper, never()).updateContent(eq(FILE_ID), eq(TENANT_ID), eq(KNOWLEDGE_ID),
                eq(CHUNK_ID), anyString(), anyInt(), anyString(), anyInt());
        verify(vectorGateway, never()).delete(CHUNK_ID);
    }

    @Test
    void affected_row_mismatch_is_an_optimistic_lock_conflict_without_vector_cleanup() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.updateContent(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID,
                "Edited", 6, sha256("Edited"), 2)).thenReturn(0);

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
        dependent.setOverlapSourceChunkId(31L);
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.findNextDependentForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, 5, 31L)).thenReturn(dependent);
        when(chunkMapper.invalidateDependent(FILE_ID, TENANT_ID, KNOWLEDGE_ID,
                32L, 31L, 7)).thenReturn(1);
        when(chunkMapper.deleteScoped(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2)).thenReturn(1);

        service.delete(KNOWLEDGE_ID, FILE_ID, CHUNK_ID, 2);

        verify(chunkMapper).deleteScoped(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID, 2);
        verify(chunkMapper, never()).deleteById(31L);
        verify(stateService).transition(KNOWLEDGE_ID, FILE_ID,
                PipelineState.CHUNKED, PipelineState.ADJUSTING, 5);
        verify(vectorGateway).delete(CHUNK_ID);
        verify(vectorGateway).delete(NEXT_ID);
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
    void vector_cleanup_has_three_bounded_attempts_without_rolling_back_success() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.updateContent(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID,
                "Edited", 6, sha256("Edited"), 2)).thenReturn(1);
        doThrow(new IllegalStateException("vector unavailable")).when(vectorGateway).delete(CHUNK_ID);

        var response = service.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2));

        assertEquals("Edited", response.content());
        verify(vectorGateway, times(4)).delete(CHUNK_ID);
        assertEquals(List.of(100L, 300L, 900L), sleeps);
    }

    @Test
    void vector_cleanup_runs_only_after_the_database_transaction_commits() {
        DocumentChunk target = chunk(31L, CHUNK_ID, 4, ChunkStatus.DRAFT, 2, "Old");
        when(chunkMapper.findScopedByPublicIdForUpdate(
                FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID)).thenReturn(target);
        when(chunkMapper.updateContent(FILE_ID, TENANT_ID, KNOWLEDGE_ID, CHUNK_ID,
                "Edited", 6, sha256("Edited"), 2)).thenReturn(1);
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(invocation -> {
            assertTrue(committed.get(), "vector deletion must observe a committed database state");
            return null;
        }).when(vectorGateway).delete(CHUNK_ID);
        AbstractPlatformTransactionManager transactionManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                committed.set(true);
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        ChunkCommandService transactional = (ChunkCommandService) proxyFactory.getProxy();

        transactional.edit(KNOWLEDGE_ID, FILE_ID, CHUNK_ID,
                new EditChunkRequest("Edited", 2));

        assertTrue(committed.get());
        verify(vectorGateway).delete(CHUNK_ID);
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
    void command_sql_is_fully_scoped_conditional_and_delete_is_physical() throws Exception {
        Configuration configuration = new Configuration();
        try (var stream = getClass().getResourceAsStream("/mapper/DocumentChunkMapper.xml")) {
            new XMLMapperBuilder(stream, configuration,
                    "mapper/DocumentChunkMapper.xml", configuration.getSqlFragments()).parse();
        }
        String update = sql(configuration,
                "com.starsea.ai.mapper.DocumentChunkMapper.updateContent", Map.of(
                        "fileId", FILE_ID, "tenantId", TENANT_ID, "knowledgeId", KNOWLEDGE_ID,
                        "chunkPublicId", CHUNK_ID, "content", "Edited", "tokenCount", 6,
                        "contentHash", "hash", "lockVersion", 2));
        String delete = sql(configuration,
                "com.starsea.ai.mapper.DocumentChunkMapper.deleteScoped", Map.of(
                        "fileId", FILE_ID, "tenantId", TENANT_ID, "knowledgeId", KNOWLEDGE_ID,
                        "chunkPublicId", CHUNK_ID, "lockVersion", 2));

        assertTrue(update.contains("tenant_id = ?"));
        assertTrue(update.contains("knowledge_id = ?"));
        assertTrue(update.contains("file_id = ?"));
        assertTrue(update.contains("public_id = ?"));
        assertTrue(update.contains("status <> 1"));
        assertTrue(update.contains("lock_version = ?"));
        assertTrue(update.contains("index_content = NULL"));
        assertTrue(delete.startsWith("DELETE FROM document_chunk"));
        assertTrue(delete.contains("tenant_id = ?"));
        assertTrue(delete.contains("knowledge_id = ?"));
        assertTrue(delete.contains("file_id = ?"));
        assertTrue(delete.contains("public_id = ?"));
        assertTrue(delete.contains("status <> 1"));
        assertTrue(delete.contains("lock_version = ?"));
        assertTrue(!delete.contains("UPDATE document_chunk"));
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
        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(parameters);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
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
