package com.starsea.ai.chunking.indexing;

import com.starsea.ai.domain.ChunkVectorCleanup;
import com.starsea.ai.mapper.ChunkVectorCleanupMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Service
public class DurableChunkVectorLifecycle implements ChunkVectorLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DurableChunkVectorLifecycle.class);
    private static final int DRAIN_BATCH_SIZE = 1_000;
    private static final int WRITER_FENCE_COUNT = 64;
    private static final int WRITER_LEASE_SECONDS = 60;
    private static final int CLAIM_LEASE_SECONDS = 120;
    private static final int WRITER_HEARTBEAT_SECONDS = 20;
    private static final ScheduledExecutorService WRITER_HEARTBEATS =
            Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());

    private final ChunkVectorCleanupMapper cleanupMapper;
    private final ChunkVectorGateway gateway;
    private final TransactionOperations requiresNew;
    private final UUID instanceId;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Object[] writerFences = writerFences();
    private final ConcurrentHashMap<UUID, Integer> activeWriters = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> heartbeatTask;

    @Autowired
    public DurableChunkVectorLifecycle(ChunkVectorCleanupMapper cleanupMapper,
                                       ChunkVectorGateway gateway,
                                       PlatformTransactionManager transactionManager) {
        this(cleanupMapper, gateway, requiresNew(transactionManager),
                UUID.randomUUID(), true);
    }

    public DurableChunkVectorLifecycle(ChunkVectorCleanupMapper cleanupMapper,
                                       ChunkVectorGateway gateway) {
        this(cleanupMapper, gateway, new DirectTransactions(), UUID.randomUUID(), false);
    }

    DurableChunkVectorLifecycle(ChunkVectorCleanupMapper cleanupMapper,
                                ChunkVectorGateway gateway,
                                TransactionOperations requiresNew) {
        this(cleanupMapper, gateway, requiresNew, UUID.randomUUID(), false);
    }

    DurableChunkVectorLifecycle(ChunkVectorCleanupMapper cleanupMapper,
                                ChunkVectorGateway gateway,
                                TransactionOperations requiresNew,
                                UUID instanceId) {
        this(cleanupMapper, gateway, requiresNew, instanceId, false);
    }

    private DurableChunkVectorLifecycle(ChunkVectorCleanupMapper cleanupMapper,
                                        ChunkVectorGateway gateway,
                                        TransactionOperations requiresNew,
                                        UUID instanceId,
                                        boolean scheduleHeartbeat) {
        this.cleanupMapper = Objects.requireNonNull(cleanupMapper, "cleanupMapper");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.requiresNew = Objects.requireNonNull(requiresNew, "requiresNew");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.heartbeatTask = scheduleHeartbeat
                ? WRITER_HEARTBEATS.scheduleWithFixedDelay(this::heartbeatSafely,
                WRITER_HEARTBEAT_SECONDS, WRITER_HEARTBEAT_SECONDS, TimeUnit.SECONDS)
                : null;
    }

    @Override
    @Transactional
    public void enqueue(Collection<CleanupObligation> obligations) {
        for (CleanupObligation obligation : normalized(obligations)) {
            cleanupMapper.enqueue(obligation.vectorId(), obligation.tenantId(),
                    obligation.knowledgeId(), obligation.fileId(), obligation.chunkPublicId());
        }
    }

    @Override
    @Transactional
    public void enqueuePendingOwner(long tenantId, long knowledgeId, long fileId,
                                    int indexingLockVersion) {
        cleanupMapper.enqueuePendingByOwner(fileId, tenantId, knowledgeId, indexingLockVersion);
    }

    @Override
    public void writerStarted(Collection<CleanupObligation> obligations) {
        List<CleanupObligation> normalized = normalized(obligations);
        withWriterFences(0, writerFenceIndexes(normalized), () -> {
            normalized.forEach(obligation -> activeWriters.merge(
                    obligation.vectorId(), 1, Integer::sum));
            try {
                inNewTransaction(() -> {
                    for (CleanupObligation obligation : normalized) {
                        cleanupMapper.startWriter(obligation.vectorId(),
                                obligation.tenantId(), obligation.knowledgeId(),
                                obligation.fileId(), obligation.chunkPublicId(),
                                instanceId, WRITER_LEASE_SECONDS);
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                normalized.forEach(obligation ->
                        finishWriterUnderFence(obligation.vectorId()));
                throw failure;
            }
        });
    }

    @Override
    public void writerFinished(Collection<CleanupObligation> obligations) {
        RuntimeException acknowledgementFailure = null;
        for (CleanupObligation obligation : normalized(obligations)) {
            UUID vectorId = obligation.vectorId();
            try {
                synchronized (writerFence(vectorId)) {
                    if (finishWriterUnderFence(vectorId)) {
                        inNewTransaction(() -> cleanupMapper.finishWriter(vectorId,
                                obligation.tenantId(), obligation.knowledgeId(),
                                obligation.fileId(), obligation.chunkPublicId(), instanceId));
                    }
                }
            } catch (RuntimeException failure) {
                if (acknowledgementFailure == null) {
                    acknowledgementFailure = failure;
                } else {
                    acknowledgementFailure.addSuppressed(failure);
                }
            }
        }
        drain();
        if (acknowledgementFailure != null) {
            throw acknowledgementFailure;
        }
    }

    @Override
    public void withWriterFence(Collection<CleanupObligation> obligations, Runnable action) {
        Objects.requireNonNull(action, "action");
        List<CleanupObligation> normalized = normalized(obligations);
        java.util.Set<UUID> vectorIds = normalized.stream()
                .map(CleanupObligation::vectorId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (vectorIds.isEmpty()) {
            action.run();
            return;
        }
        inNewTransaction(() -> {
            List<ChunkVectorCleanup> locked = cleanupMapper.lockWriters(instanceId, vectorIds);
            if (locked == null || locked.size() != vectorIds.size()
                    || !locked.stream().map(ChunkVectorCleanup::getVectorId)
                    .collect(java.util.stream.Collectors.toSet()).equals(vectorIds)) {
                throw new IllegalStateException("Vector writer fence ownership was lost");
            }
            cleanupMapper.renewWriterLeases(instanceId, vectorIds, WRITER_LEASE_SECONDS);
            action.run();
            cleanupMapper.renewWriterLeases(instanceId, vectorIds, WRITER_LEASE_SECONDS);
            return null;
        });
    }

    @Override
    public void resetAbandonedClaims() {
        inNewTransaction(cleanupMapper::resetAbandonedClaims);
    }

    @Override
    public void drain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        int failures = 0;
        try {
            inNewTransaction(cleanupMapper::reclaimExpiredClaims);
            while (true) {
                List<ChunkVectorCleanup> claimed = inNewTransaction(
                        () -> cleanupMapper.claimDue(instanceId,
                                CLAIM_LEASE_SECONDS, DRAIN_BATCH_SIZE));
                if (claimed == null || claimed.isEmpty()) {
                    break;
                }
                for (ChunkVectorCleanup cleanup : claimed) {
                    if (!deleteClaimed(cleanup)) {
                        failures++;
                    }
                }
            }
        } finally {
            draining.set(false);
        }
        if (failures > 0) {
            log.warn("{} queued vector generation deletions failed and were rescheduled", failures);
        }
    }

    @PreDestroy
    void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }

    void renewWriterLeases() {
        List<UUID> vectorIds = List.copyOf(activeWriters.keySet());
        if (!vectorIds.isEmpty()) {
            inNewTransaction(() -> cleanupMapper.renewWriterLeases(
                    instanceId, java.util.Set.copyOf(vectorIds), WRITER_LEASE_SECONDS));
        }
    }

    private boolean deleteClaimed(ChunkVectorCleanup cleanup) {
        UUID vectorId = cleanup.getVectorId();
        synchronized (writerFence(vectorId)) {
            if (activeWriters.containsKey(vectorId)) {
                inNewTransaction(() -> cleanupMapper.deferForWriter(vectorId, instanceId));
                return true;
            }
            try {
                boolean deleted = Boolean.TRUE.equals(inNewTransaction(() -> {
                    ChunkVectorCleanup locked = cleanupMapper.lockClaimedForDelete(
                            vectorId, instanceId);
                    if (locked == null) {
                        return false;
                    }
                    gateway.delete(vectorId);
                    return cleanupMapper.deleteClaimed(vectorId, instanceId) == 1;
                }));
                if (!deleted) {
                    inNewTransaction(() -> cleanupMapper.release(vectorId, instanceId,
                            "Generation became protected while cleanup was completing"));
                }
                return true;
            } catch (RuntimeException failure) {
                inNewTransaction(() -> cleanupMapper.release(
                        vectorId, instanceId, failureSummary(failure)));
                return false;
            }
        }
    }

    private void heartbeatSafely() {
        try {
            renewWriterLeases();
        } catch (RuntimeException failure) {
            log.warn("Unable to renew active vector writer leases", failure);
        }
    }

    private boolean finishWriterUnderFence(UUID vectorId) {
        Integer writers = activeWriters.get(vectorId);
        if (writers == null) {
            return false;
        }
        if (writers > 1) {
            activeWriters.put(vectorId, writers - 1);
            return false;
        }
        activeWriters.remove(vectorId);
        return true;
    }

    private Object writerFence(UUID vectorId) {
        return writerFences[writerFenceIndex(vectorId)];
    }

    private int writerFenceIndex(UUID vectorId) {
        return (vectorId.hashCode() & Integer.MAX_VALUE) % writerFences.length;
    }

    private List<Integer> writerFenceIndexes(List<CleanupObligation> obligations) {
        TreeSet<Integer> indexes = new TreeSet<>();
        obligations.forEach(obligation -> indexes.add(writerFenceIndex(obligation.vectorId())));
        return List.copyOf(indexes);
    }

    private void withWriterFences(int offset, List<Integer> fenceIndexes, Runnable action) {
        if (offset == fenceIndexes.size()) {
            action.run();
            return;
        }
        synchronized (writerFences[fenceIndexes.get(offset)]) {
            withWriterFences(offset + 1, fenceIndexes, action);
        }
    }

    private static Object[] writerFences() {
        Object[] fences = new Object[WRITER_FENCE_COUNT];
        for (int index = 0; index < fences.length; index++) {
            fences[index] = new Object();
        }
        return fences;
    }

    private List<CleanupObligation> normalized(Collection<CleanupObligation> obligations) {
        Objects.requireNonNull(obligations, "obligations");
        return obligations.stream()
                .filter(Objects::nonNull)
                .filter(obligation -> obligation.vectorId() != null)
                .toList();
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        return requiresNew.execute(status -> action.get());
    }

    private String failureSummary(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static ThreadFactory daemonThreadFactory() {
        return task -> {
            Thread thread = new Thread(task, "chunk-vector-writer-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class DirectTransactions implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(new SimpleTransactionStatus(true));
        }
    }
}
