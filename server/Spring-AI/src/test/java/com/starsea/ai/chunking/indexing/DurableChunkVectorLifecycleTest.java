package com.starsea.ai.chunking.indexing;

import com.starsea.ai.domain.ChunkVectorCleanup;
import com.starsea.ai.mapper.ChunkVectorCleanupMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableChunkVectorLifecycleTest {

    private static final UUID VECTOR_ID =
            UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID CHUNK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void failed_delete_remains_queued_and_is_retried_by_the_next_drain() {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        ChunkVectorCleanup queued = cleanup();
        when(mapper.findDrainable(1_000)).thenReturn(List.of(queued));
        when(mapper.claim(VECTOR_ID)).thenReturn(1, 1);
        when(mapper.release(VECTOR_ID, "vector unavailable")).thenReturn(1);
        when(mapper.deleteClaimed(VECTOR_ID)).thenReturn(1);
        doThrow(new IllegalStateException("vector unavailable"))
                .doNothing().when(gateway).delete(VECTOR_ID);
        DurableChunkVectorLifecycle lifecycle =
                new DurableChunkVectorLifecycle(mapper, gateway);

        lifecycle.drain();
        lifecycle.drain();

        verify(gateway, times(2)).delete(VECTOR_ID);
        verify(mapper).release(VECTOR_ID, "vector unavailable");
        verify(mapper).deleteClaimed(VECTOR_ID);
    }

    @Test
    void active_generation_obligations_are_reconciled_without_vector_deletion() {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        when(mapper.removeActiveObligations()).thenReturn(1);
        when(mapper.findDrainable(1_000)).thenReturn(List.of());
        DurableChunkVectorLifecycle lifecycle =
                new DurableChunkVectorLifecycle(mapper, gateway);

        lifecycle.enqueue(List.of(obligation()));
        lifecycle.drain();

        var order = inOrder(mapper);
        order.verify(mapper).enqueue(VECTOR_ID, 1L, 10L, 20L, CHUNK_ID);
        order.verify(mapper).removeActiveObligations();
        verify(gateway, never()).delete(VECTOR_ID);
    }

    @Test
    void cleanup_claim_sql_rechecks_active_and_pending_references_at_the_delete_boundary()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        String find = sql(configuration, "findDrainable", Map.of("limit", 1000));
        String claim = sql(configuration, "claim", Map.of("vectorId", VECTOR_ID));
        String delete = sql(configuration, "deleteClaimed", Map.of("vectorId", VECTOR_ID));

        assertProtected(find);
        assertProtected(claim);
        assertProtected(delete);
        assertTrue(claim.contains("state = 0"));
        assertTrue(delete.contains("state = 1"));
        assertTrue(find.contains("dc.status = 2"), find);
        assertTrue(claim.contains("dc.status = 2"), claim);
        assertTrue(delete.contains("dc.status = 2"), delete);
    }

    @Test
    void startup_resets_only_stale_external_delete_claims() throws Exception {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        DurableChunkVectorLifecycle lifecycle =
                new DurableChunkVectorLifecycle(mapper, mock(ChunkVectorGateway.class));

        lifecycle.resetAbandonedClaims();

        verify(mapper).resetAbandonedClaims();
        String reset = sql(mapperConfiguration(), "resetAbandonedClaims", Map.of());
        assertTrue(reset.contains("update_time <"), reset);
        assertTrue(reset.contains("interval '1 minute'"), reset);
    }

    private void assertProtected(String sql) {
        assertTrue(sql.contains("document_chunk"), sql);
        assertTrue(sql.contains("vector_id"), sql);
        assertTrue(sql.contains("pending_vector_id"), sql);
        assertTrue(sql.contains("not exists"), sql);
    }

    private Configuration mapperConfiguration() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("mapper/ChunkVectorCleanupMapper.xml")) {
            new XMLMapperBuilder(stream, configuration,
                    "mapper/ChunkVectorCleanupMapper.xml", configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String method, Map<String, Object> parameters) {
        BoundSql bound = configuration.getMappedStatement(
                ChunkVectorCleanupMapper.class.getName() + "." + method).getBoundSql(parameters);
        return bound.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private ChunkVectorCleanup cleanup() {
        ChunkVectorCleanup cleanup = new ChunkVectorCleanup();
        cleanup.setVectorId(VECTOR_ID);
        cleanup.setTenantId(1L);
        cleanup.setKnowledgeId(10L);
        cleanup.setFileId(20L);
        cleanup.setChunkPublicId(CHUNK_ID);
        return cleanup;
    }

    private ChunkVectorLifecycle.CleanupObligation obligation() {
        return new ChunkVectorLifecycle.CleanupObligation(
                VECTOR_ID, 1L, 10L, 20L, CHUNK_ID);
    }
}
