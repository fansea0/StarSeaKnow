package com.starsea.ai.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvaluationVectorStoreTest {
    @Test void refusesInvalidOrUnrepresentableVectors() {
        assertThrows(IllegalArgumentException.class, () -> EvaluationVectorStore.literal(new double[]{0,0}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationVectorStore.literal(new double[]{Double.NaN,1}));
        assertThrows(IllegalArgumentException.class, () -> EvaluationVectorStore.literal(new double[]{Double.POSITIVE_INFINITY}));
        assertEquals("[-1.0,0.0]", EvaluationVectorStore.literal(new double[]{-1,0}));
        assertEquals("[0.70710677,0.70710677]", EvaluationVectorStore.literal(new double[]{3e38,3e38}));
        assertEquals("[0.70710677,0.70710677]", EvaluationVectorStore.literal(new double[]{1e-30,1e-30}));
        assertEquals("[1.0]", EvaluationVectorStore.literal(new double[]{Double.MAX_VALUE}));
    }

    @Test void postSortsAnnTiesBeforeSelectingTopKWithoutChangingTheDistanceLimitQuery() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        TransactionStatus transaction=mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(jdbc.queryForObject(startsWith("SELECT set_config"),eq(String.class),any(Object[].class))).thenReturn("40");
        when(jdbc.queryForObject(startsWith("SELECT count(*) FROM document_chunk"),eq(Integer.class),any(Object[].class))).thenReturn(1);
        String lower="00000000-0000-0000-0000-000000000001";
        String higher="00000000-0000-0000-0000-000000000002";
        List<EvaluationVectorStore.Score> candidates=List.of(
                new EvaluationVectorStore.Score(higher,.8),
                new EvaluationVectorStore.Score(lower,.8),
                new EvaluationVectorStore.Score("00000000-0000-0000-0000-000000000003",.7));
        doReturn(candidates).when(jdbc).query(anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<EvaluationVectorStore.Score>>any(),any(Object[].class));

        EvaluationVectorStore store=new EvaluationVectorStore(jdbc,manager);
        List<EvaluationVectorStore.Score> selected=store.production(7,11,"a".repeat(64),new double[]{1,0},2,null,40);

        assertEquals(List.of(lower,higher),selected.stream().map(EvaluationVectorStore.Score::chunkId).toList());
        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),org.mockito.ArgumentMatchers.<RowMapper<EvaluationVectorStore.Score>>any(),any(Object[].class));
        assertTrue(sql.getValue().contains(" ORDER BY distance LIMIT ?"));
        assertFalse(sql.getValue().contains("ORDER BY distance,"));
    }

    @Test void failCannotInvalidateACompletedBuild() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        EvaluationVectorStore store=new EvaluationVectorStore(jdbc,manager);

        store.fail(7,"b".repeat(64));

        var sql=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(),eq(7L),eq("b".repeat(64)));
        assertTrue(sql.getValue().contains("status='BUILDING'"));
    }
}
