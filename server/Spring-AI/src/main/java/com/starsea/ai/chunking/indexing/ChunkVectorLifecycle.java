package com.starsea.ai.chunking.indexing;

import java.util.Collection;
import java.util.UUID;

public interface ChunkVectorLifecycle {

    ChunkVectorLifecycle NOOP = new ChunkVectorLifecycle() {
        @Override
        public void enqueue(Collection<CleanupObligation> obligations) {
        }

        @Override
        public void enqueuePendingOwner(long tenantId, long knowledgeId, long fileId,
                                        int indexingLockVersion) {
        }

        @Override
        public void resetAbandonedClaims() {
        }

        @Override
        public void drain() {
        }
    };

    void enqueue(Collection<CleanupObligation> obligations);

    void enqueuePendingOwner(long tenantId, long knowledgeId, long fileId,
                             int indexingLockVersion);

    default void writerStarted(Collection<CleanupObligation> obligations) {
    }

    default void writerFinished(Collection<CleanupObligation> obligations) {
    }

    void resetAbandonedClaims();

    void drain();

    record CleanupObligation(UUID vectorId, long tenantId, long knowledgeId,
                             long fileId, UUID chunkPublicId) {
    }
}
