package com.starsea.ai.chunking.processing;

import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/** Recovers asynchronous chunking work that cannot survive a process restart. */
@Component
public class ChunkPipelineRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkPipelineRecovery.class);

    private final FileProcessingMapper processingMapper;
    private final DocumentChunkMapper chunkMapper;
    private final TransactionOperations transactions;
    private final Duration timeout;
    private final Clock clock;

    @Autowired
    public ChunkPipelineRecovery(FileProcessingMapper processingMapper,
                                 DocumentChunkMapper chunkMapper,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${chunking.recovery.timeout:10m}") Duration timeout) {
        this(processingMapper, chunkMapper, new TransactionTemplate(transactionManager),
                timeout, Clock.systemUTC());
    }

    ChunkPipelineRecovery(FileProcessingMapper processingMapper,
                          DocumentChunkMapper chunkMapper,
                          TransactionOperations transactions,
                          Duration timeout,
                          Clock clock) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("chunking recovery timeout must be positive");
        }
        this.processingMapper = processingMapper;
        this.chunkMapper = chunkMapper;
        this.transactions = transactions;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        RecoverySummary summary = recoverTimedOut();
        if (summary.filesRecovered() > 0) {
            log.warn("Recovered {} timed-out chunking files and {} INDEXING chunks",
                    summary.filesRecovered(), summary.chunksRecovered());
        }
    }

    RecoverySummary recoverTimedOut() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant().minus(timeout), clock.getZone());
        List<FileProcessing> candidates = processingMapper.findTimedOutAsync(cutoff);
        int filesRecovered = 0;
        int chunksRecovered = 0;
        for (FileProcessing candidate : candidates) {
            RecoverySummary result = transactions.execute(status -> recoverCandidate(candidate));
            if (result != null) {
                filesRecovered += result.filesRecovered();
                chunksRecovered += result.chunksRecovered();
            }
        }
        return new RecoverySummary(filesRecovered, chunksRecovered);
    }

    private RecoverySummary recoverCandidate(FileProcessing candidate) {
        PipelineState state;
        try {
            state = PipelineState.fromCode(candidate.getPipelineState());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return RecoverySummary.NONE;
        }
        if (state != PipelineState.CHUNKING && state != PipelineState.VECTORIZING) {
            return RecoverySummary.NONE;
        }
        String error = state + " timed out during application restart recovery";
        int updated = processingMapper.transition(
                candidate.getFileId(), candidate.getTenantId(), candidate.getKnowledgeId(),
                state.code(), PipelineState.FAILED.code(), 0, candidate.getLockVersion(),
                state.code(), error);
        if (updated != 1) {
            return RecoverySummary.NONE;
        }
        int chunks = state == PipelineState.VECTORIZING
                ? chunkMapper.restoreIndexingByFile(candidate.getFileId(), candidate.getTenantId(),
                candidate.getKnowledgeId())
                : 0;
        return new RecoverySummary(1, chunks);
    }

    record RecoverySummary(int filesRecovered, int chunksRecovered) {
        private static final RecoverySummary NONE = new RecoverySummary(0, 0);
    }
}
