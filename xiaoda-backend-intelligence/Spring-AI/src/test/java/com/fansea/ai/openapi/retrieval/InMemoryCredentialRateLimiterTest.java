package com.fansea.ai.openapi.retrieval;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryCredentialRateLimiterTest {

    @Test
    void allowsConfiguredBurstThenReturnsRetryAfter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock);

        for (int request = 0; request < 10; request++) {
            try (CredentialRateLimiter.RateLimitLease ignored = limiter.acquire(41L, 60, 10, 5)) {
                assertThat(ignored.limit()).isEqualTo(60);
                assertThat(ignored.remaining()).isEqualTo(9 - request);
            }
        }

        assertThatThrownBy(() -> limiter.acquire(41L, 60, 10, 5))
                .isInstanceOfSatisfying(CredentialRateLimiter.RateLimitExceededException.class, exception -> {
                    assertThat(exception.retryAfterSeconds()).isEqualTo(1);
                    assertThat(exception.limit()).isEqualTo(60);
                    assertThat(exception.remaining()).isZero();
                    assertThat(exception.resetEpochSecond()).isGreaterThan(clock.instant().getEpochSecond());
                });
    }

    @Test
    void refillsAtRequestsPerMinuteWithoutExceedingBurstCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock);
        for (int request = 0; request < 10; request++) {
            limiter.acquire(42L, 5, 10, 10).close();
        }

        clock.advance(Duration.ofMinutes(1));

        for (int request = 0; request < 5; request++) {
            limiter.acquire(42L, 5, 10, 10).close();
        }
        assertThatThrownBy(() -> limiter.acquire(42L, 5, 10, 10))
                .isInstanceOf(CredentialRateLimiter.RateLimitExceededException.class);
    }

    @Test
    void closeReleasesConcurrencyPermitAfterSuccess() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock);
        CredentialRateLimiter.RateLimitLease first = limiter.acquire(43L, 60, 10, 1);

        assertThatThrownBy(() -> limiter.acquire(43L, 60, 10, 1))
                .isInstanceOf(CredentialRateLimiter.RateLimitExceededException.class);
        first.close();

        try (CredentialRateLimiter.RateLimitLease ignored = limiter.acquire(43L, 60, 10, 1)) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void finallyReleasesConcurrencyPermitAfterCallerException() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock);

        try {
            try (CredentialRateLimiter.RateLimitLease ignored = limiter.acquire(44L, 60, 10, 1)) {
                throw new IllegalStateException("retrieval failed");
            }
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("retrieval failed");
        }

        try (CredentialRateLimiter.RateLimitLease ignored = limiter.acquire(44L, 60, 10, 1)) {
            assertThat(ignored).isNotNull();
        }
    }

    @Test
    void rejectsInvalidPolicyInsteadOfCreatingUnusableBucketState() {
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(Clock.systemUTC());

        assertThatThrownBy(() -> limiter.acquire(45L, 0, 10, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.acquire(45L, 60, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.acquire(45L, 60, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activeLeaseCannotExpireIntoFreshConcurrencyBucket() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock);
        CredentialRateLimiter.RateLimitLease active = limiter.acquire(46L, 60, 10, 1);

        clock.advance(Duration.ofMinutes(11));

        assertThatThrownBy(() -> limiter.acquire(46L, 60, 10, 1))
                .isInstanceOf(CredentialRateLimiter.RateLimitExceededException.class);
        active.close();
        limiter.acquire(46L, 60, 10, 1).close();
    }

    @Test
    void failsClosedWhenConfiguredBucketCapacityIsAllActive() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock, 2);
        CredentialRateLimiter.RateLimitLease first = limiter.acquire(47L, 60, 10, 1);
        CredentialRateLimiter.RateLimitLease second = limiter.acquire(48L, 60, 10, 1);

        assertThatThrownBy(() -> limiter.acquire(49L, 60, 10, 1))
                .isInstanceOf(CredentialRateLimiter.RateLimitExceededException.class);
        assertThatThrownBy(() -> limiter.acquire(47L, 60, 10, 1))
                .isInstanceOf(CredentialRateLimiter.RateLimitExceededException.class);

        first.close();
        limiter.acquire(49L, 60, 10, 1).close();
        assertThatThrownBy(() -> limiter.acquire(48L, 60, 10, 1))
                .isInstanceOf(CredentialRateLimiter.RateLimitExceededException.class);
        second.close();
    }

    @Test
    void concurrentAcquisitionAllowsOnlyConfiguredSameCredentialConcurrency() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock, 16);
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(callers);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int caller = 0; caller < callers; caller++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try (CredentialRateLimiter.RateLimitLease ignored =
                                 limiter.acquire(50L, 600, 100, 1)) {
                        acquired.incrementAndGet();
                        attempted.countDown();
                        releaseWinner.await();
                    } catch (CredentialRateLimiter.RateLimitExceededException exception) {
                        rejected.incrementAndGet();
                        attempted.countDown();
                    }
                    return null;
                }));
            }

            start.countDown();
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(acquired).hasValue(1);
            assertThat(rejected).hasValue(callers - 1);
            releaseWinner.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
        }

        limiter.acquire(50L, 600, 100, 1).close();
    }

    @Test
    void resetEpochSecondCeilsAbsoluteFractionalResetInstant() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00.900Z"));
        InMemoryCredentialRateLimiter limiter = new InMemoryCredentialRateLimiter(clock);

        try (CredentialRateLimiter.RateLimitLease first = limiter.acquire(51L, 60, 10, 1)) {
            assertThat(first.resetEpochSecond())
                    .isEqualTo(Instant.parse("2026-08-28T00:00:02Z").getEpochSecond());
        }
        for (int request = 1; request < 10; request++) {
            limiter.acquire(51L, 60, 10, 1).close();
        }

        assertThatThrownBy(() -> limiter.acquire(51L, 60, 10, 1))
                .isInstanceOfSatisfying(CredentialRateLimiter.RateLimitExceededException.class, exception ->
                        assertThat(exception.resetEpochSecond())
                                .isEqualTo(Instant.parse("2026-08-28T00:00:11Z").getEpochSecond()));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
