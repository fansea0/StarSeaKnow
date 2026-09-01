package com.starsea.ai.chunking.processing;

import com.starsea.ai.chunking.model.PipelineState;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class ChunkPipelineRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-01T02:00:00Z");

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
        verify(chunkMapper, never()).restoreIndexingByFile(20L, 1L, 10L);
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
        when(chunkMapper.restoreIndexingByFile(20L, 1L, 10L)).thenReturn(2);

        ChunkPipelineRecovery.RecoverySummary summary = recovery(processingMapper, chunkMapper).recoverTimedOut();

        assertEquals(1, summary.filesRecovered());
        assertEquals(2, summary.chunksRecovered());
        verify(chunkMapper).restoreIndexingByFile(20L, 1L, 10L);
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
        verify(chunkMapper, never()).restoreIndexingByFile(20L, 1L, 10L);
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
        TaskScheduler scheduler = mock(TaskScheduler.class);
        doReturn(mock(ScheduledFuture.class)).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        return new ChunkPipelineRecovery(processingMapper, chunkMapper,
                new ImmediateTransactions(), scheduler,
                Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofMinutes(1),
                Clock.fixed(NOW, ZoneOffset.UTC));
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
