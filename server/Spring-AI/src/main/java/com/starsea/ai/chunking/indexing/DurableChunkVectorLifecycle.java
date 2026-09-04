package com.starsea.ai.chunking.indexing;

import com.starsea.ai.domain.ChunkVectorCleanup;
import com.starsea.ai.mapper.ChunkVectorCleanupMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DurableChunkVectorLifecycle implements ChunkVectorLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DurableChunkVectorLifecycle.class);
    private static final int DRAIN_BATCH_SIZE = 1_000;

    private final ChunkVectorCleanupMapper cleanupMapper;
    private final ChunkVectorGateway gateway;
    private final AtomicBoolean draining = new AtomicBoolean();

    public DurableChunkVectorLifecycle(ChunkVectorCleanupMapper cleanupMapper,
                                       ChunkVectorGateway gateway) {
        this.cleanupMapper = cleanupMapper;
        this.gateway = gateway;
    }

    @Override
    @Transactional
    public void enqueue(Collection<CleanupObligation> obligations) {
        Objects.requireNonNull(obligations, "obligations");
        for (CleanupObligation obligation : obligations) {
            if (obligation != null && obligation.vectorId() != null) {
                cleanupMapper.enqueue(obligation.vectorId(), obligation.tenantId(),
                        obligation.knowledgeId(), obligation.fileId(), obligation.chunkPublicId());
            }
        }
    }

    @Override
    @Transactional
    public void enqueuePendingOwner(long tenantId, long knowledgeId, long fileId,
                                    int indexingLockVersion) {
        cleanupMapper.enqueuePendingByOwner(fileId, tenantId, knowledgeId, indexingLockVersion);
    }

    @Override
    public void resetAbandonedClaims() {
        cleanupMapper.resetAbandonedClaims();
    }

    @Override
    public void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            cleanupMapper.resetAbandonedClaims();
            cleanupMapper.removeActiveObligations();
            for (ChunkVectorCleanup cleanup : cleanupMapper.findDrainable(DRAIN_BATCH_SIZE)) {
                deleteClaimed(cleanup);
            }
        } finally {
            draining.set(false);
        }
    }

    private void deleteClaimed(ChunkVectorCleanup cleanup) {
        if (cleanupMapper.claim(cleanup.getVectorId()) != 1) {
            return;
        }
        try {
            gateway.delete(cleanup.getVectorId());
            if (cleanupMapper.deleteClaimed(cleanup.getVectorId()) != 1) {
                cleanupMapper.release(cleanup.getVectorId(),
                        "Generation became protected while cleanup was completing");
            }
        } catch (RuntimeException failure) {
            cleanupMapper.release(cleanup.getVectorId(), failureSummary(failure));
            log.warn("Unable to delete queued vector generation {}", cleanup.getVectorId(), failure);
        }
    }

    private String failureSummary(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
