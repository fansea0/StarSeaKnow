package com.starsea.ai.openapi.retrieval;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class InMemoryCredentialRateLimiter implements CredentialRateLimiter {

    private static final long MAX_CREDENTIAL_BUCKETS = 10_000;
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);

    private final Clock clock;
    private final int maxBuckets;
    private final Cache<Long, Bucket> idleBuckets;
    private final Map<Long, Bucket> activeBuckets = new HashMap<>();
    private final Object stateLock = new Object();

    public InMemoryCredentialRateLimiter() {
        this(Clock.systemUTC(), (int) MAX_CREDENTIAL_BUCKETS);
    }

    public InMemoryCredentialRateLimiter(Clock clock) {
        this(clock, (int) MAX_CREDENTIAL_BUCKETS);
    }

    InMemoryCredentialRateLimiter(Clock clock, int maxBuckets) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxBuckets < 1) {
            throw new IllegalArgumentException("maxBuckets must be positive");
        }
        this.maxBuckets = maxBuckets;
        this.idleBuckets = Caffeine.newBuilder()
                .maximumSize(maxBuckets)
                .expireAfterWrite(IDLE_EXPIRY)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(this.clock.millis()))
                .build();
    }

    @Override
    public RateLimitLease acquire(long credentialId, int requestsPerMinute, int burstCapacity,
                                  int maxConcurrency) {
        validatePolicy(requestsPerMinute, burstCapacity, maxConcurrency);
        long nowMillis = clock.millis();
        synchronized (stateLock) {
            Bucket bucket = activeBuckets.get(credentialId);
            if (bucket == null) {
                idleBuckets.cleanUp();
                bucket = idleBuckets.asMap().remove(credentialId);
                if (bucket == null) {
                    makeRoomOrReject(requestsPerMinute, nowMillis);
                    bucket = new Bucket(burstCapacity, nowMillis);
                }
                activeBuckets.put(credentialId, bucket);
            }
            bucket.refill(nowMillis, requestsPerMinute, burstCapacity);
            if (bucket.inFlight >= maxConcurrency) {
                throw rejected(requestsPerMinute, bucket, burstCapacity, nowMillis, 1);
            }
            if (bucket.tokens < 1.0) {
                double tokensNeeded = 1.0 - bucket.tokens;
                long retryAfter = (long) Math.ceil(tokensNeeded * 60.0 / requestsPerMinute);
                moveToIdleIfInactive(credentialId, bucket);
                throw rejected(requestsPerMinute, bucket, burstCapacity, nowMillis, retryAfter);
            }

            bucket.tokens -= 1.0;
            bucket.inFlight++;
            int remaining = remaining(bucket.tokens, burstCapacity);
            long reset = fullResetEpochSecond(bucket.tokens, requestsPerMinute, burstCapacity, nowMillis);
            return new Lease(this, credentialId, bucket, requestsPerMinute, remaining, reset);
        }
    }

    private void makeRoomOrReject(int requestsPerMinute, long nowMillis) {
        int totalBuckets = activeBuckets.size() + idleBuckets.asMap().size();
        if (totalBuckets < maxBuckets) {
            return;
        }
        Iterator<Long> idleCredentialIds = idleBuckets.asMap().keySet().iterator();
        if (idleCredentialIds.hasNext()) {
            idleBuckets.invalidate(idleCredentialIds.next());
            return;
        }
        throw new RateLimitExceededException(requestsPerMinute, 0,
                ceilEpochSecond(nowMillis + 1_000.0), 1);
    }

    private void release(long credentialId, Bucket bucket) {
        synchronized (stateLock) {
            bucket.inFlight = Math.max(0, bucket.inFlight - 1);
            moveToIdleIfInactive(credentialId, bucket);
        }
    }

    private void moveToIdleIfInactive(long credentialId, Bucket bucket) {
        if (bucket.inFlight == 0 && activeBuckets.get(credentialId) == bucket) {
            activeBuckets.remove(credentialId);
            idleBuckets.put(credentialId, bucket);
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
        double resetMillis = nowMillis + missing * 60_000.0 / requestsPerMinute;
        return ceilEpochSecond(resetMillis);
    }

    private static long ceilEpochSecond(double epochMillis) {
        return (long) Math.ceil(epochMillis / 1_000.0);
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
        private final InMemoryCredentialRateLimiter owner;
        private final long credentialId;
        private final Bucket bucket;
        private final int limit;
        private final int remaining;
        private final long resetEpochSecond;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(InMemoryCredentialRateLimiter owner, long credentialId, Bucket bucket,
                      int limit, int remaining, long resetEpochSecond) {
            this.owner = owner;
            this.credentialId = credentialId;
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
                owner.release(credentialId, bucket);
            }
        }
    }
}
