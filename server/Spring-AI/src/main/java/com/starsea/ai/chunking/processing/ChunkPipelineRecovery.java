package com.starsea.ai.chunking.processing;

import com.starsea.ai.chunking.model.PipelineState;
import com.starsea.ai.chunking.indexing.DurableChunkVectorLifecycle;
import com.starsea.ai.chunking.indexing.ChunkVectorLifecycle;
import com.starsea.ai.domain.FileProcessing;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.mapper.FileProcessingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recovers asynchronous chunking work that cannot survive a process restart. */
@Component
public class ChunkPipelineRecovery implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChunkPipelineRecovery.class);

    private final FileProcessingMapper processingMapper;
    private final DocumentChunkMapper chunkMapper;
    private final TransactionOperations transactions;
    private final TaskScheduler scheduler;
    private final Duration timeout;
    private final Duration initialDelay;
    private final Duration fixedDelay;
    private final Clock clock;
    private final ChunkVectorLifecycle vectorLifecycle;
    private final AtomicBoolean scanInProgress = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private ScheduledFuture<?> scheduledTask;

    @Autowired
    public ChunkPipelineRecovery(FileProcessingMapper processingMapper,
                                 DocumentChunkMapper chunkMapper,
                                 PlatformTransactionManager transactionManager,
                                 @Qualifier("chunkingRecoveryScheduler") TaskScheduler scheduler,
                                 @Value("${chunking.recovery.timeout:10m}") Duration timeout,
                                 @Value("${chunking.recovery.initial-delay:1m}") Duration initialDelay,
                                 @Value("${chunking.recovery.fixed-delay:1m}") Duration fixedDelay,
                                 DurableChunkVectorLifecycle vectorLifecycle) {
        this(processingMapper, chunkMapper, new TransactionTemplate(transactionManager),
                scheduler, timeout, initialDelay, fixedDelay, Clock.systemUTC(), vectorLifecycle);
    }

    ChunkPipelineRecovery(FileProcessingMapper processingMapper,
                          DocumentChunkMapper chunkMapper,
                          TransactionOperations transactions,
                          TaskScheduler scheduler,
                          Duration timeout,
                          Duration initialDelay,
                          Duration fixedDelay,
                          Clock clock) {
        this(processingMapper, chunkMapper, transactions, scheduler, timeout,
                initialDelay, fixedDelay, clock, ChunkVectorLifecycle.NOOP);
    }

    ChunkPipelineRecovery(FileProcessingMapper processingMapper,
                          DocumentChunkMapper chunkMapper,
                          TransactionOperations transactions,
                          TaskScheduler scheduler,
                          Duration timeout,
                          Duration initialDelay,
                          Duration fixedDelay,
                          Clock clock,
                          ChunkVectorLifecycle vectorLifecycle) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("chunking recovery timeout must be positive");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("chunking recovery initial delay must not be negative");
        }
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()) {
            throw new IllegalArgumentException("chunking recovery fixed delay must be positive");
        }
        this.processingMapper = processingMapper;
        this.chunkMapper = chunkMapper;
        this.transactions = transactions;
        this.scheduler = scheduler;
        this.timeout = timeout;
        this.initialDelay = initialDelay;
        this.fixedDelay = fixedDelay;
        this.clock = clock;
        this.vectorLifecycle = vectorLifecycle;
    }

    @Override
    public void run(ApplicationArguments args) {
        vectorLifecycle.resetAbandonedClaims();
        scanScheduled();
        synchronized (this) {
            if (closed.get()) {
                return;
            }
            scheduledTask = scheduler.scheduleWithFixedDelay(
                    this::scanScheduled, clock.instant().plus(initialDelay), fixedDelay);
            if (scheduledTask == null) {
                throw new IllegalStateException("chunking recovery scan could not be scheduled");
            }
        }
    }

    void scanScheduled() {
        if (closed.get() || !scanInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            logSummary(recoverTimedOut());
            vectorLifecycle.drain();
        } catch (RuntimeException failure) {
            log.error("Unable to complete chunking recovery scan", failure);
        } finally {
            scanInProgress.set(false);
        }
    }

    private void logSummary(RecoverySummary summary) {
        if (summary.filesRecovered() > 0) {
            log.warn("Recovered {} timed-out chunking files and {} INDEXING chunks",
                    summary.filesRecovered(), summary.chunksRecovered());
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
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
        String error = state + " timed out during recovery scan";
        int updated = processingMapper.transition(
                candidate.getFileId(), candidate.getTenantId(), candidate.getKnowledgeId(),
                state.code(), PipelineState.FAILED.code(), 0, candidate.getLockVersion(),
                state.code(), error);
        if (updated != 1) {
            return RecoverySummary.NONE;
        }
        if (state == PipelineState.VECTORIZING) {
            vectorLifecycle.enqueuePendingOwner(candidate.getTenantId(), candidate.getKnowledgeId(),
                    candidate.getFileId(), candidate.getLockVersion());
        }
        int chunks = state == PipelineState.VECTORIZING
                ? chunkMapper.restoreIndexingByFile(candidate.getFileId(), candidate.getTenantId(),
                candidate.getKnowledgeId(), candidate.getLockVersion())
                : 0;
        return new RecoverySummary(1, chunks);
    }

    record RecoverySummary(int filesRecovered, int chunksRecovered) {
        private static final RecoverySummary NONE = new RecoverySummary(0, 0);
    }
}
