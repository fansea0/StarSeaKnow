package com.fansea.ai.openapi.retrieval;

import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.openapi.credential.ApiCredentialResolver;
import com.fansea.ai.openapi.credential.CredentialType;
import com.fansea.ai.openapi.credential.RagKnowledgeScopeSnapshot;
import com.fansea.ai.service.RagService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class RetrievalDeadlineExecutorTest {

    @Test
    void normalCompletionReleasesLeaseExactlyOnce() throws Exception {
        RagService ragService = mock(RagService.class);
        org.mockito.Mockito.when(ragService.retrieve(any())).thenReturn(List.of());
        RetrievalDeadlineExecutor executor = new RetrievalDeadlineExecutor(ragService, Duration.ofSeconds(1), 1, 1);
        TrackingLease lease = new TrackingLease();

        assertThat(executor.retrieve(new RetrievalQuery("q", Set.of(11L), 1, 0), externalContext(), lease))
                .isEmpty();

        assertThat(lease.closeCount).hasValue(1);
        executor.close();
    }

    @Test
    void rejectedSubmissionReleasesLeaseExactlyOnce() {
        RetrievalDeadlineExecutor executor = new RetrievalDeadlineExecutor(mock(RagService.class),
                Duration.ofSeconds(1), 1, 1);
        TrackingLease lease = new TrackingLease();
        executor.close();

        assertThatThrownBy(() -> executor.retrieve(new RetrievalQuery("q", Set.of(11L), 1, 0),
                externalContext(), lease)).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        assertThat(lease.closeCount).hasValue(1);
    }

    @Test
    void closeImmediatelyCancelsQueuedTaskButKeepsRunningLeaseUntilWorkerActuallyExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RagService ragService = mock(RagService.class);
        doAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return List.of();
        }).when(ragService).retrieve(any());
        RetrievalDeadlineExecutor executor = new RetrievalDeadlineExecutor(ragService, Duration.ofSeconds(30), 1, 1);
        TrackingLease runningLease = new TrackingLease();
        TrackingLease queuedLease = new TrackingLease();
        AtomicReference<Throwable> runningFailure = new AtomicReference<>();
        AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
        Thread runningCaller = caller(executor, "running", runningLease, runningFailure);
        Thread queuedCaller = caller(executor, "queued", queuedLease, queuedFailure);

        runningCaller.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        queuedCaller.start();
        awaitQueueSize(executor, 1);
        executor.close();
        executor.close();
        queuedCaller.join(500);

        try {
            assertThat(queuedCaller.isAlive()).isFalse();
            assertThat(queuedFailure.get()).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(queuedLease.closeCount).hasValue(1);
            assertThat(runningLease.closeCount).hasValue(0);
        } finally {
            if (queuedCaller.isAlive()) queuedCaller.interrupt();
            release.countDown();
            queuedCaller.join(1_000);
            runningCaller.join(1_000);
        }
        assertThat(runningFailure.get()).isNull();
        assertThat(runningLease.closeCount).hasValue(1);
    }

    @Test
    void timedOutQueuedTaskThatNeverStartsReleasesItsLeaseExactlyOnce() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RagService ragService = mock(RagService.class);
        doAnswer(invocation -> {
            entered.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return List.of();
        }).when(ragService).retrieve(any());
        RetrievalDeadlineExecutor executor = new RetrievalDeadlineExecutor(ragService, Duration.ofMillis(80), 1, 1);
        TrackingLease runningLease = new TrackingLease();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        Thread firstCaller = new Thread(() -> {
            try {
                executor.retrieve(new RetrievalQuery("first", Set.of(11L), 1, 0), externalContext(), runningLease);
            } catch (Throwable failure) {
                firstFailure.set(failure);
            }
        });
        firstCaller.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        firstCaller.join(1_000);
        assertThat(firstFailure.get()).isInstanceOf(TimeoutException.class);

        TrackingLease queuedLease = new TrackingLease();
        assertThatThrownBy(() -> executor.retrieve(new RetrievalQuery("queued", Set.of(11L), 1, 0),
                externalContext(), queuedLease)).isInstanceOf(TimeoutException.class);

        assertThat(queuedLease.closeCount).hasValue(1);
        assertThat(runningLease.closeCount).hasValue(0);
        release.countDown();
        assertThat(runningLease.closedLatch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(runningLease.closeCount).hasValue(1);
        executor.close();
    }

    @Test
    void timedOutNonCooperativeTaskKeepsLeaseUntilItActuallyExitsAndClearsContext() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Long> observedTenant = new AtomicReference<>();
        AtomicBoolean contextClearedAfterInterrupt = new AtomicBoolean();
        RagService hanging = mock(RagService.class);
        doAnswer(invocation -> {
            observedTenant.set(AuthContext.current().getTenantId());
            try {
                new CountDownLatch(1).await();
                return List.of();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return List.of();
            }
        }).when(hanging).retrieve(any());
        RetrievalDeadlineExecutor executor = new RetrievalDeadlineExecutor(hanging, Duration.ofMillis(40), 1, 1);
        AuthContext context = externalContext();
        TrackingLease lease = new TrackingLease();

        assertThatThrownBy(() -> executor.retrieve(new RetrievalQuery("q", Set.of(11L), 1, 0), context, lease))
                .isInstanceOf(TimeoutException.class);
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(lease.closeCount).hasValue(0);
        release.countDown();
        assertThat(lease.closedLatch.await(1, TimeUnit.SECONDS)).isTrue();
        executor.executeProbe(() -> contextClearedAfterInterrupt.set(AuthContext.current() == null));
        assertThat(contextClearedAfterInterrupt).isTrue();
        assertThat(observedTenant).hasValue(22L);
        executor.close();
    }

    private static final class TrackingLease implements CredentialRateLimiter.RateLimitLease {
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();
        @Override public int limit() { return 60; }
        @Override public int remaining() { return 9; }
        @Override public long resetEpochSecond() { return 1; }
        @Override public void close() { closeCount.incrementAndGet(); closedLatch.countDown(); }
    }

    private Thread caller(RetrievalDeadlineExecutor executor, String query, TrackingLease lease,
                          AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                executor.retrieve(new RetrievalQuery(query, Set.of(11L), 1, 0), externalContext(), lease);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
    }

    private void awaitQueueSize(RetrievalDeadlineExecutor executor, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (executor.queuedTaskCount() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(executor.queuedTaskCount()).isEqualTo(expected);
    }

    private AuthContext externalContext() {
        ApiCredentialResolver.ResolvedCredential credential = new ApiCredentialResolver.ResolvedCredential(
                41L, 22L, CredentialType.RAG_RETRIEVAL, "test", "active", null, List.of(),
                60, 10, 5, 1L, new RagKnowledgeScopeSnapshot(Set.of(11L)));
        return AuthContext.external(credential);
    }
}
