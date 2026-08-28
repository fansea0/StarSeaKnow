package com.fansea.ai.openapi.retrieval;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InMemoryCredentialRateLimiter implements CredentialRateLimiter {

    private static final long MAX_CREDENTIAL_BUCKETS = 10_000;
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);

    private final Clock clock;
    private final Cache<Long, Bucket> buckets;

    public InMemoryCredentialRateLimiter() {
        this(Clock.systemUTC());
    }

    public InMemoryCredentialRateLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.buckets = Caffeine.newBuilder()
                .maximumSize(MAX_CREDENTIAL_BUCKETS)
                .expireAfterAccess(IDLE_EXPIRY)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(this.clock.millis()))
                .build();
    }

    @Override
    public RateLimitLease acquire(long credentialId, int requestsPerMinute, int burstCapacity,
                                  int maxConcurrency) {
        validatePolicy(requestsPerMinute, burstCapacity, maxConcurrency);
        long nowMillis = clock.millis();
        Bucket bucket = buckets.get(credentialId, ignored -> new Bucket(burstCapacity, nowMillis));
        synchronized (bucket) {
            bucket.refill(nowMillis, requestsPerMinute, burstCapacity);
            if (bucket.inFlight >= maxConcurrency) {
                throw rejected(requestsPerMinute, bucket, burstCapacity, nowMillis, 1);
            }
            if (bucket.tokens < 1.0) {
                double tokensNeeded = 1.0 - bucket.tokens;
                long retryAfter = (long) Math.ceil(tokensNeeded * 60.0 / requestsPerMinute);
                throw rejected(requestsPerMinute, bucket, burstCapacity, nowMillis, retryAfter);
            }

            bucket.tokens -= 1.0;
            bucket.inFlight++;
            int remaining = remaining(bucket.tokens, burstCapacity);
            long reset = fullResetEpochSecond(bucket.tokens, requestsPerMinute, burstCapacity, nowMillis);
            return new Lease(bucket, requestsPerMinute, remaining, reset);
        }
    }

    private RateLimitExceededException rejected(int requestsPerMinute, Bucket bucket, int burstCapacity,
                                                long nowMillis, long retryAfter) {
        return new RateLimitExceededException(requestsPerMinute, remaining(bucket.tokens, burstCapacity),
                fullResetEpochSecond(bucket.tokens, requestsPerMinute, burstCapacity, nowMillis), retryAfter);
    }

    private static int remaining(double tokens, int burstCapacity) {
        return Math.max(0, Math.min(burstCapacity, (int) Math.floor(tokens)));
    }

    private static long fullResetEpochSecond(double tokens, int requestsPerMinute, int burstCapacity,
                                             long nowMillis) {
        double missing = Math.max(0.0, burstCapacity - tokens);
        long seconds = missing == 0.0 ? 0 : (long) Math.ceil(missing * 60.0 / requestsPerMinute);
        return Math.floorDiv(nowMillis, 1_000) + seconds;
    }

    private static void validatePolicy(int requestsPerMinute, int burstCapacity, int maxConcurrency) {
        if (requestsPerMinute < 1 || burstCapacity < 1 || maxConcurrency < 1) {
            throw new IllegalArgumentException("rate limit policy values must be positive");
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;
        private int inFlight;

        private Bucket(int burstCapacity, long nowMillis) {
            this.tokens = burstCapacity;
            this.lastRefillMillis = nowMillis;
        }

        private void refill(long nowMillis, int requestsPerMinute, int burstCapacity) {
            long elapsedMillis = Math.max(0, nowMillis - lastRefillMillis);
            if (elapsedMillis > 0) {
                tokens = Math.min(burstCapacity,
                        tokens + elapsedMillis * requestsPerMinute / 60_000.0);
                lastRefillMillis = nowMillis;
            } else {
                tokens = Math.min(tokens, burstCapacity);
            }
        }
    }

    private static final class Lease implements RateLimitLease {
        private final Bucket bucket;
        private final int limit;
        private final int remaining;
        private final long resetEpochSecond;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Bucket bucket, int limit, int remaining, long resetEpochSecond) {
            this.bucket = bucket;
            this.limit = limit;
            this.remaining = remaining;
            this.resetEpochSecond = resetEpochSecond;
        }

        @Override
        public int limit() {
            return limit;
        }

        @Override
        public int remaining() {
            return remaining;
        }

        @Override
        public long resetEpochSecond() {
            return resetEpochSecond;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                synchronized (bucket) {
                    bucket.inFlight = Math.max(0, bucket.inFlight - 1);
                }
            }
        }
    }
}
