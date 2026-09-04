package com.starsea.ai.chunking.indexing;

import com.starsea.ai.domain.ChunkVectorCleanup;
import com.starsea.ai.mapper.ChunkVectorCleanupMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionOperations;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private static final UUID INSTANCE_ID =
            UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Test
    void failed_delete_remains_queued_and_is_retried_by_the_next_drain() {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        ChunkVectorGateway gateway = mock(ChunkVectorGateway.class);
        ChunkVectorCleanup queued = cleanup();
        when(mapper.claimDue(eq(INSTANCE_ID), anyInt(), eq(1_000))).thenReturn(
                List.of(queued), List.of(), List.of(queued), List.of());
        when(mapper.lockClaimedForDelete(VECTOR_ID, INSTANCE_ID)).thenReturn(queued);
        when(mapper.release(VECTOR_ID, INSTANCE_ID, "vector unavailable")).thenReturn(1);
        when(mapper.deleteClaimed(VECTOR_ID, INSTANCE_ID)).thenReturn(1);
        doThrow(new IllegalStateException("vector unavailable"))
                .doNothing().when(gateway).delete(VECTOR_ID);
        DurableChunkVectorLifecycle lifecycle =
                new DurableChunkVectorLifecycle(
                        mapper, gateway, new ImmediateTransactions(), INSTANCE_ID);

        lifecycle.drain();
        lifecycle.drain();

        verify(gateway, times(2)).delete(VECTOR_ID);
        verify(mapper).release(VECTOR_ID, INSTANCE_ID, "vector unavailable");
        verify(mapper).deleteClaimed(VECTOR_ID, INSTANCE_ID);
    }

    @Test
    void recovery_delete_before_late_publish_retains_tombstone_until_restart() throws Exception {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        ChunkVectorCleanup queued = cleanup();
        when(mapper.claimDue(any(UUID.class), anyInt(), eq(1_000))).thenReturn(
                List.of(queued), List.of(), List.of(queued), List.of());
        when(mapper.lockClaimedForDelete(eq(VECTOR_ID), any(UUID.class))).thenReturn(queued);
        when(mapper.deleteClaimed(eq(VECTOR_ID), any(UUID.class))).thenReturn(1);
        Map<UUID, String> vectors = new HashMap<>();
        CountDownLatch firstDeleteCompleted = new CountDownLatch(1);
        CountDownLatch allowFirstDeleteReturn = new CountDownLatch(1);
        AtomicInteger deletes = new AtomicInteger();
        ChunkVectorGateway gateway = vectorId -> {
            vectors.remove(vectorId);
            if (deletes.incrementAndGet() == 1) {
                firstDeleteCompleted.countDown();
                try {
                    if (!allowFirstDeleteReturn.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("delete latch timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        };
        DurableChunkVectorLifecycle oldProcess = new DurableChunkVectorLifecycle(
                mapper, gateway, new ImmediateTransactions(), INSTANCE_ID);

        Thread recoveryDrain = new Thread(oldProcess::drain, "recovery-vector-delete");
        recoveryDrain.start();
        assertTrue(firstDeleteCompleted.await(5, TimeUnit.SECONDS));
        CountDownLatch writerRegistered = new CountDownLatch(1);
        Thread staleWriter = new Thread(() -> {
            oldProcess.writerStarted(List.of(obligation()));
            writerRegistered.countDown();
        }, "stale-vector-writer");
        staleWriter.start();
        assertFalse(writerRegistered.await(200, TimeUnit.MILLISECONDS));
        allowFirstDeleteReturn.countDown();
        assertTrue(writerRegistered.await(5, TimeUnit.SECONDS));
        vectors.put(VECTOR_ID, "late stale generation");
        recoveryDrain.join(5_000);
        staleWriter.join(5_000);

        assertFalse(recoveryDrain.isAlive());
        assertFalse(staleWriter.isAlive());
        assertEquals("late stale generation", vectors.get(VECTOR_ID));
        verify(mapper).startWriter(VECTOR_ID, 1L, 10L, 20L, CHUNK_ID,
                INSTANCE_ID, 60);
        verify(mapper).deleteClaimed(eq(VECTOR_ID), any(UUID.class));

        DurableChunkVectorLifecycle restarted = new DurableChunkVectorLifecycle(
                mapper, gateway, new ImmediateTransactions(), UUID.randomUUID());
        restarted.resetAbandonedClaims();
        restarted.drain();

        assertFalse(vectors.containsKey(VECTOR_ID));
        verify(mapper, times(2)).deleteClaimed(eq(VECTOR_ID), any(UUID.class));
        assertEquals(2, deletes.get());
    }

    @Test
    void writer_registration_waiting_behind_tombstone_completion_recreates_the_obligation()
            throws Exception {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        when(mapper.claimDue(eq(INSTANCE_ID), anyInt(), eq(1_000)))
                .thenReturn(List.of(cleanup()), List.of());
        when(mapper.lockClaimedForDelete(VECTOR_ID, INSTANCE_ID)).thenReturn(cleanup());
        CountDownLatch deleteEntered = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        when(mapper.deleteClaimed(VECTOR_ID, INSTANCE_ID)).thenAnswer(invocation -> {
            deleteEntered.countDown();
            assertTrue(allowDeleteCommit.await(5, TimeUnit.SECONDS));
            return 1;
        });
        DurableChunkVectorLifecycle lifecycle = new DurableChunkVectorLifecycle(
                mapper, mock(ChunkVectorGateway.class), new ImmediateTransactions(), INSTANCE_ID);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        CountDownLatch writerAttempted = new CountDownLatch(1);
        CountDownLatch writerRegistered = new CountDownLatch(1);

        Thread drain = new Thread(lifecycle::drain, "cleanup-tombstone-completion");
        drain.start();
        assertTrue(deleteEntered.await(5, TimeUnit.SECONDS));
        Thread writer = new Thread(() -> {
            writerAttempted.countDown();
            try {
                lifecycle.writerStarted(List.of(obligation()));
            } catch (Throwable failure) {
                threadFailure.set(failure);
            } finally {
                writerRegistered.countDown();
            }
        }, "late-vector-writer-registration");
        writer.start();
        assertTrue(writerAttempted.await(5, TimeUnit.SECONDS));

        assertFalse(writerRegistered.await(200, TimeUnit.MILLISECONDS),
                "registration must share the tombstone completion fence");
        allowDeleteCommit.countDown();
        drain.join(5_000);
        writer.join(5_000);

        assertFalse(drain.isAlive());
        assertFalse(writer.isAlive());
        assertEquals(null, threadFailure.get());
        verify(mapper).startWriter(VECTOR_ID, 1L, 10L, 20L, CHUNK_ID,
                INSTANCE_ID, 60);
    }

    @Test
    void cleanup_claim_sql_is_due_locked_and_rechecks_active_and_pending_references()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        String claim = sql(configuration, "claimDue", Map.of(
                "claimOwner", INSTANCE_ID, "leaseSeconds", 120, "limit", 1000));
        String lock = sql(configuration, "lockClaimedForDelete", Map.of(
                "vectorId", VECTOR_ID, "claimOwner", INSTANCE_ID));
        String delete = sql(configuration, "deleteClaimed", Map.of(
                "vectorId", VECTOR_ID, "claimOwner", INSTANCE_ID));

        assertProtected(claim);
        assertProtected(lock);
        assertProtected(delete);
        assertTrue(claim.contains("next_attempt_at <= current_timestamp"), claim);
        assertTrue(claim.contains("for update skip locked"), claim);
        assertTrue(claim.contains("returning"), claim);
        assertTrue(delete.contains("state = 1"));
        assertTrue(claim.contains("dc.status = 2"), claim);
        assertTrue(delete.contains("dc.status = 2"), delete);
        assertTrue(claim.contains("writer_lease_until <= current_timestamp"), claim);
        assertTrue(lock.contains("for update"), lock);
        assertTrue(lock.contains("claim_owner"), lock);
    }

    @Test
    void active_obligation_stays_dormant_and_concurrent_enqueue_refreshes_the_same_row()
            throws Exception {
        Configuration configuration = mapperConfiguration();
        String enqueue = sql(configuration, "enqueue", Map.of(
                "vectorId", VECTOR_ID, "tenantId", 1L, "knowledgeId", 10L,
                "fileId", 20L, "chunkPublicId", CHUNK_ID));

        assertTrue(enqueue.contains("on conflict (vector_id) do update"), enqueue);
        assertTrue(enqueue.contains("state = 0"), enqueue);
        assertTrue(enqueue.contains("next_attempt_at = current_timestamp"), enqueue);
        assertFalse(configuration.hasStatement(
                ChunkVectorCleanupMapper.class.getName() + ".removeActiveObligations"));
    }

    @Test
    void durable_writer_lease_is_started_renewed_and_acknowledged_by_its_owner()
            throws Exception {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        when(mapper.claimDue(eq(INSTANCE_ID), anyInt(), eq(1_000)))
                .thenReturn(List.of());
        DurableChunkVectorLifecycle lifecycle = new DurableChunkVectorLifecycle(
                mapper, mock(ChunkVectorGateway.class), new ImmediateTransactions(), INSTANCE_ID);

        lifecycle.writerStarted(List.of(obligation()));
        lifecycle.renewWriterLeases();
        lifecycle.writerFinished(List.of(obligation()));

        verify(mapper).startWriter(VECTOR_ID, 1L, 10L, 20L, CHUNK_ID,
                INSTANCE_ID, 60);
        verify(mapper).renewWriterLeases(INSTANCE_ID, Set.of(VECTOR_ID), 60);
        verify(mapper).finishWriter(VECTOR_ID, 1L, 10L, 20L, CHUNK_ID, INSTANCE_ID);

        Configuration configuration = mapperConfiguration();
        String start = sql(configuration, "startWriter", Map.of(
                "vectorId", VECTOR_ID, "tenantId", 1L, "knowledgeId", 10L,
                "fileId", 20L, "chunkPublicId", CHUNK_ID,
                "writerOwner", INSTANCE_ID, "leaseSeconds", 60));
        String finish = sql(configuration, "finishWriter", Map.of(
                "vectorId", VECTOR_ID, "tenantId", 1L, "knowledgeId", 10L,
                "fileId", 20L, "chunkPublicId", CHUNK_ID,
                "writerOwner", INSTANCE_ID));
        assertTrue(start.contains("on conflict (vector_id) do update"), start);
        assertTrue(start.contains("writer_lease_until"), start);
        assertTrue(start.contains("claim_owner = null"), start);
        assertTrue(finish.contains("writer_owner = null"), finish);
        assertTrue(finish.contains("where chunk_vector_cleanup.writer_owner ="), finish);
    }

    @Test
    void writer_fence_locks_every_owned_generation_while_external_io_runs()
            throws Exception {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        ChunkVectorCleanup owned = cleanup();
        owned.setWriterOwner(INSTANCE_ID);
        when(mapper.lockWriters(INSTANCE_ID, Set.of(VECTOR_ID)))
                .thenReturn(List.of(owned));
        DurableChunkVectorLifecycle lifecycle = new DurableChunkVectorLifecycle(
                mapper, mock(ChunkVectorGateway.class),
                new ImmediateTransactions(), INSTANCE_ID);
        AtomicBoolean externalIoRan = new AtomicBoolean();

        lifecycle.withWriterFence(List.of(obligation()), () -> externalIoRan.set(true));

        assertTrue(externalIoRan.get());
        verify(mapper).lockWriters(INSTANCE_ID, Set.of(VECTOR_ID));
        verify(mapper, times(2)).renewWriterLeases(
                INSTANCE_ID, Set.of(VECTOR_ID), 60);
        String lock = sql(mapperConfiguration(), "lockWriters", Map.of(
                "writerOwner", INSTANCE_ID, "vectorIds", Set.of(VECTOR_ID)));
        assertTrue(lock.contains("writer_owner = ?"), lock);
        assertTrue(lock.contains("order by q.vector_id"), lock);
        assertTrue(lock.endsWith("for update"), lock);
    }

    @Test
    void writer_fence_refuses_external_io_after_tombstone_ownership_is_lost() {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        when(mapper.lockWriters(INSTANCE_ID, Set.of(VECTOR_ID))).thenReturn(List.of());
        DurableChunkVectorLifecycle lifecycle = new DurableChunkVectorLifecycle(
                mapper, mock(ChunkVectorGateway.class),
                new ImmediateTransactions(), INSTANCE_ID);
        AtomicBoolean externalIoRan = new AtomicBoolean();

        RuntimeException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> lifecycle.withWriterFence(
                        List.of(obligation()), () -> externalIoRan.set(true)));

        assertFalse(externalIoRan.get());
        assertTrue(failure.getMessage().contains("ownership was lost"));
    }

    @Test
    void one_thousand_permanently_failing_oldest_rows_do_not_starve_a_later_generation() {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        List<ChunkVectorCleanup> blocked = new ArrayList<>();
        Set<UUID> blockedIds = new HashSet<>();
        for (int index = 0; index < 1_000; index++) {
            UUID id = UUID.nameUUIDFromBytes(("blocked-" + index).getBytes());
            blocked.add(cleanup(id));
            blockedIds.add(id);
        }
        UUID laterId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(mapper.claimDue(eq(INSTANCE_ID), anyInt(), eq(1_000))).thenReturn(
                blocked, List.of(cleanup(laterId)), List.of());
        when(mapper.release(any(UUID.class), eq(INSTANCE_ID), anyString())).thenReturn(1);
        when(mapper.lockClaimedForDelete(any(UUID.class), eq(INSTANCE_ID)))
                .thenAnswer(invocation -> cleanup(invocation.getArgument(0)));
        when(mapper.deleteClaimed(laterId, INSTANCE_ID)).thenReturn(1);
        List<UUID> deleted = new ArrayList<>();
        ChunkVectorGateway gateway = vectorId -> {
            if (blockedIds.contains(vectorId)) {
                throw new IllegalStateException("permanent failure");
            }
            deleted.add(vectorId);
        };
        DurableChunkVectorLifecycle lifecycle = new DurableChunkVectorLifecycle(
                mapper, gateway, new ImmediateTransactions(), INSTANCE_ID);

        lifecycle.drain();

        assertEquals(List.of(laterId), deleted);
        verify(mapper, times(1_000)).release(
                any(UUID.class), eq(INSTANCE_ID), eq("permanent failure"));
        verify(mapper).deleteClaimed(laterId, INSTANCE_ID);
    }

    @Test
    void retry_release_uses_bounded_exponential_backoff() throws Exception {
        String release = sql(mapperConfiguration(), "release",
                Map.of("vectorId", VECTOR_ID, "claimOwner", INSTANCE_ID,
                        "lastError", "failure"));

        assertTrue(release.contains("retry_count = retry_count + 1"), release);
        assertTrue(release.contains("power(2"), release);
        assertTrue(release.contains("least(300"), release);
        assertTrue(release.contains("next_attempt_at"), release);
    }

    @Test
    void startup_resets_abandoned_claims_from_the_dead_process() throws Exception {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        DurableChunkVectorLifecycle lifecycle =
                new DurableChunkVectorLifecycle(mapper, mock(ChunkVectorGateway.class));

        lifecycle.resetAbandonedClaims();

        verify(mapper).resetAbandonedClaims();
        String reset = sql(mapperConfiguration(), "resetAbandonedClaims", Map.of());
        assertTrue(reset.contains("where state = 1"), reset);
        assertTrue(reset.contains("claim_lease_until <= current_timestamp"), reset);
        assertTrue(reset.contains("next_attempt_at = current_timestamp"), reset);
    }

    @Test
    void claims_and_completion_use_requires_new_transactions() {
        ChunkVectorCleanupMapper mapper = mock(ChunkVectorCleanupMapper.class);
        when(mapper.claimDue(any(UUID.class), anyInt(), eq(1_000)))
                .thenReturn(List.of(cleanup()), List.of());
        when(mapper.lockClaimedForDelete(eq(VECTOR_ID), any(UUID.class)))
                .thenReturn(cleanup());
        when(mapper.deleteClaimed(eq(VECTOR_ID), any(UUID.class))).thenReturn(1);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        DurableChunkVectorLifecycle lifecycle =
                new DurableChunkVectorLifecycle(mapper, mock(ChunkVectorGateway.class), manager);

        lifecycle.drain();

        ArgumentCaptor<TransactionDefinition> definitions =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager, times(4)).getTransaction(definitions.capture());
        assertTrue(definitions.getAllValues().stream().allMatch(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
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
        return cleanup(VECTOR_ID);
    }

    private ChunkVectorCleanup cleanup(UUID vectorId) {
        ChunkVectorCleanup cleanup = new ChunkVectorCleanup();
        cleanup.setVectorId(vectorId);
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

    private static final class ImmediateTransactions implements TransactionOperations {
        @Override
        public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    }
}
