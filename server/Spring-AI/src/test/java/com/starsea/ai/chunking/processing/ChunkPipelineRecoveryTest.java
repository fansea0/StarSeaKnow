package com.starsea.ai.chunking.processing;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.indexing.ChunkVectorLifecycle;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;

class ChunkPipelineRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

    @Test
    void only_the_global_timeout_scan_bypasses_tenant_line_filtering() throws NoSuchMethodException {
        InterceptorIgnore ignore = FileProcessingMapper.class
                .getMethod("findTimedOutAsync", OffsetDateTime.class)
                .getAnnotation(InterceptorIgnore.class);

        assertNotNull(ignore);
        assertEquals("true", ignore.tenantLine());
        assertEquals(1, Arrays.stream(FileProcessingMapper.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(InterceptorIgnore.class))
                .count());
    }

    @Test
    void every_state_transition_refreshes_the_timeout_clock() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("mapper/FileProcessingMapper.xml")) {
            String mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            String transition = mapper.substring(mapper.indexOf("<update id=\"transition\">"),
                    mapper.indexOf("</update>", mapper.indexOf("<update id=\"transition\">")));
            assertEquals(true, transition.contains("update_time = CURRENT_TIMESTAMP"));
        }
    }

    @Test
    void recovery_clears_every_derived_overlap_and_index_field_together() throws IOException {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("mapper/DocumentChunkMapper.xml")) {
            assertNotNull(stream, "document chunk mapper must be packaged");
            String mapper = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            String restore = mapper.substring(mapper.indexOf("<update id=\"restoreIndexingByFile\">"),
                    mapper.indexOf("</update>", mapper.indexOf("<update id=\"restoreIndexingByFile\">")))
                    .replaceAll("\\s+", " ").toLowerCase();

            assertEquals(true, restore.contains("overlap_content = null"));
            assertEquals(true, restore.contains("overlap_source_chunk_id = null"));
            assertEquals(true, restore.contains("overlap_token_count = 0"));
            assertEquals(true, restore.contains("overlap_character_count = 0"));
            assertEquals(true, restore.contains("overlap_reduction_reason = null"));
            assertEquals(true, restore.contains("index_content = null"));
            assertEquals(true, restore.contains("pending_vector_id = null"));
            assertEquals(true, restore.contains("indexing_lock_version = null"));
            assertEquals(true, restore.contains("indexing_lock_version = #{indexinglockversion}"));
        }
    }

    @Test
    void marks_timed_out_chunking_failed_with_its_origin() throws Exception {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessing stale = stale(PipelineState.CHUNKING, 4);
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        when(processingMapper.findTimedOutAsync(cutoff)).thenReturn(List.of(stale));
        when(processingMapper.transition(20L, 1L, 10L, 1, 7, 0, 4, 1,
                "CHUNKING timed out during recovery scan")).thenReturn(1);

        recovery(processingMapper, chunkMapper).run(null);

        verify(processingMapper).transition(20L, 1L, 10L, 1, 7, 0, 4, 1,
                "CHUNKING timed out during recovery scan");
        verify(chunkMapper, never()).restoreIndexingByFile(20L, 1L, 10L, 4);
    }

    @Test
    void restores_indexing_chunks_after_winning_vectorizing_recovery_cas() throws Exception {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessing stale = stale(PipelineState.VECTORIZING, 8);
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        when(processingMapper.findTimedOutAsync(cutoff)).thenReturn(List.of(stale));
        when(processingMapper.transition(20L, 1L, 10L, 5, 7, 0, 8, 5,
                "VECTORIZING timed out during recovery scan")).thenReturn(1);
        when(chunkMapper.restoreIndexingByFile(20L, 1L, 10L, 8)).thenReturn(2);

        ChunkPipelineRecovery.RecoverySummary summary = recovery(processingMapper, chunkMapper).recoverTimedOut();

        assertEquals(1, summary.filesRecovered());
        assertEquals(2, summary.chunksRecovered());
        verify(chunkMapper).restoreIndexingByFile(20L, 1L, 10L, 8);
    }

    @Test
    void hard_crash_recovery_durably_queues_pending_generations_before_clearing_chunks() {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        ChunkVectorLifecycle lifecycle = mock(ChunkVectorLifecycle.class);
        FileProcessing stale = stale(PipelineState.VECTORIZING, 8);
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(
                NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        when(processingMapper.findTimedOutAsync(cutoff)).thenReturn(List.of(stale));
        when(processingMapper.transition(20L, 1L, 10L, 5, 7, 0, 8, 5,
                "VECTORIZING timed out during recovery scan")).thenReturn(1);
        when(chunkMapper.restoreIndexingByFile(20L, 1L, 10L, 8)).thenReturn(1);

        ChunkPipelineRecovery.RecoverySummary summary = recovery(
                processingMapper, chunkMapper, lifecycle)
                .recoverTimedOut();

        assertEquals(1, summary.filesRecovered());
        var order = inOrder(processingMapper, lifecycle, chunkMapper);
        order.verify(processingMapper).transition(20L, 1L, 10L, 5, 7, 0, 8, 5,
                "VECTORIZING timed out during recovery scan");
        order.verify(lifecycle).enqueuePendingOwner(1L, 10L, 20L, 8);
        order.verify(chunkMapper).restoreIndexingByFile(20L, 1L, 10L, 8);
    }

    @Test
    void losing_recovery_cas_never_touches_chunks_started_by_the_winner() {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        FileProcessing stale = stale(PipelineState.VECTORIZING, 8);
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(10)), ZoneOffset.UTC);
        when(processingMapper.findTimedOutAsync(cutoff)).thenReturn(List.of(stale));
        when(processingMapper.transition(20L, 1L, 10L, 5, 7, 0, 8, 5,
                "VECTORIZING timed out during recovery scan")).thenReturn(0);

        ChunkPipelineRecovery.RecoverySummary summary = recovery(processingMapper, chunkMapper).recoverTimedOut();

        assertEquals(0, summary.filesRecovered());
        assertEquals(0, summary.chunksRecovered());
        verify(chunkMapper, never()).restoreIndexingByFile(20L, 1L, 10L, 8);
    }

    @Test
    void a_job_that_is_fresh_at_startup_is_recovered_by_a_later_periodic_scan() throws Exception {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        MutableClock clock = new MutableClock(NOW);
        Duration timeout = Duration.ofMinutes(10);
        Duration initialDelay = Duration.ofMinutes(1);
        Duration fixedDelay = Duration.ofMinutes(1);
        OffsetDateTime startupCutoff = OffsetDateTime.ofInstant(NOW.minus(timeout), ZoneOffset.UTC);
        OffsetDateTime laterCutoff = OffsetDateTime.ofInstant(
                NOW.plus(Duration.ofMinutes(11)).minus(timeout), ZoneOffset.UTC);
        FileProcessing originallyFresh = stale(PipelineState.CHUNKING, 4);
        originallyFresh.setUpdateTime(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(processingMapper.findTimedOutAsync(startupCutoff)).thenReturn(List.of());
        when(processingMapper.findTimedOutAsync(laterCutoff)).thenReturn(List.of(originallyFresh));
        when(processingMapper.transition(20L, 1L, 10L, 1, 7, 0, 4, 1,
                "CHUNKING timed out during recovery scan")).thenReturn(1);
        doReturn(scheduled).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), eq(fixedDelay));
        ChunkPipelineRecovery recovery = new ChunkPipelineRecovery(
                processingMapper, chunkMapper, new ImmediateTransactions(), scheduler,
                timeout, initialDelay, fixedDelay, clock);

        recovery.run(null);
        var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(NOW.plus(initialDelay)), eq(fixedDelay));
        verify(processingMapper, never()).transition(anyLong(), anyLong(), anyLong(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(String.class));

        clock.advance(Duration.ofMinutes(11));
        task.getValue().run();

        verify(processingMapper).transition(20L, 1L, 10L, 1, 7, 0, 4, 1,
                "CHUNKING timed out during recovery scan");
        recovery.close();
        verify(scheduled).cancel(false);
        task.getValue().run();
        verify(processingMapper, times(1)).findTimedOutAsync(laterCutoff);
    }

    @Test
    void periodic_scans_do_not_overlap() throws Exception {
        FileProcessingMapper processingMapper = mock(FileProcessingMapper.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(processingMapper.findTimedOutAsync(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        ChunkPipelineRecovery recovery = recovery(processingMapper, chunkMapper);

        Thread first = new Thread(recovery::scanScheduled);
        first.start();
        assertEquals(true, entered.await(5, TimeUnit.SECONDS));
        recovery.scanScheduled();
        verify(processingMapper, times(1)).findTimedOutAsync(any());
        release.countDown();
        first.join(5000);
    }

    private ChunkPipelineRecovery recovery(FileProcessingMapper processingMapper,
                                            DocumentChunkMapper chunkMapper) {
        return recovery(processingMapper, chunkMapper, ChunkVectorLifecycle.NOOP);
    }

    private ChunkPipelineRecovery recovery(FileProcessingMapper processingMapper,
                                            DocumentChunkMapper chunkMapper,
                                            ChunkVectorLifecycle lifecycle) {
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        return new ChunkPipelineRecovery(processingMapper, chunkMapper,
                new ImmediateTransactions(), scheduler,
                Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(1),
                Clock.fixed(NOW, ZoneOffset.UTC), lifecycle);
    }

    private FileProcessing stale(PipelineState state, int lockVersion) {
        FileProcessing processing = new FileProcessing();
        processing.setFileId(20L);
        processing.setTenantId(1L);
        processing.setKnowledgeId(10L);
        processing.setPipelineState(state.code());
        processing.setLockVersion(lockVersion);
        processing.setUpdateTime(OffsetDateTime.ofInstant(NOW.minus(Duration.ofMinutes(20)), ZoneOffset.UTC));
        return processing;
    }

    private static final class ImmediateTransactions implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
