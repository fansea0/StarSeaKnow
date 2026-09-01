package com.starsea.ai.chunking.processing;

import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                "CHUNKING timed out during application restart recovery")).thenReturn(1);

        recovery(processingMapper, chunkMapper).run(null);

        verify(processingMapper).transition(20L, 1L, 10L, 1, 7, 0, 4, 1,
                "CHUNKING timed out during application restart recovery");
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
                "VECTORIZING timed out during application restart recovery")).thenReturn(1);
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
                "VECTORIZING timed out during application restart recovery")).thenReturn(0);

        ChunkPipelineRecovery.RecoverySummary summary = recovery(processingMapper, chunkMapper).recoverTimedOut();

        assertEquals(0, summary.filesRecovered());
        assertEquals(0, summary.chunksRecovered());
        verify(chunkMapper, never()).restoreIndexingByFile(20L, 1L, 10L);
    }

    private ChunkPipelineRecovery recovery(FileProcessingMapper processingMapper,
                                            DocumentChunkMapper chunkMapper) {
        return new ChunkPipelineRecovery(processingMapper, chunkMapper,
                new ImmediateTransactions(),
                Duration.ofMinutes(10), Clock.fixed(NOW, ZoneOffset.UTC));
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
}
