package com.starsea.ai.openapi.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.starsea.ai.openapi.error.ExternalApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public final class InMemoryAuthenticationAttemptLimiter {

    private final Cache<String, FailureState> failures;
    private final int threshold;
    private final long blockNanos;

    public InMemoryAuthenticationAttemptLimiter(ExternalApiTransportProperties properties) {
        this.threshold = Math.max(1, properties.getAuthenticationFailureThreshold());
        this.blockNanos = Duration.ofSeconds(Math.max(1, properties.getAuthenticationFailureBlockSeconds())).toNanos();
        this.failures = Caffeine.newBuilder()
                .maximumSize(Math.max(1, properties.getAuthenticationFailureMaximumSize()))
                .expireAfterWrite(Duration.ofNanos(blockNanos * 2))
                .build();
    }

    public void check(String clientIp) {
        FailureState state = failures.getIfPresent(clientIp);
        if (state != null && state.isBlocked(System.nanoTime())) {
            throw rateLimited();
        }
    }

    public void recordFailure(String clientIp) {
        FailureState state = failures.asMap().computeIfAbsent(clientIp, ignored -> new FailureState());
        boolean blocked = state.failed(threshold, blockNanos, System.nanoTime());
        failures.put(clientIp, state);
        if (blocked) {
            throw rateLimited();
        }
    }

    public void recordSuccess(String clientIp) {
        // A valid key may be available to an attacker and must not erase the failure budget for the source IP.
        // Failure state expires naturally after the configured window; this hook remains for future safe decay.
    }

    public long retryAfterSeconds() {
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(blockNanos));
    }

    private ExternalApiException rateLimited() {
        return new ExternalApiException(HttpStatus.TOO_MANY_REQUESTS, "authentication_rate_limited",
                "Too many authentication failures.");
    }

    private static final class FailureState {
        private int count;
        private long blockedUntil;

        synchronized boolean failed(int threshold, long blockNanos, long now) {
            count++;
            if (count >= threshold) {
                blockedUntil = now + blockNanos;
                return true;
            }
            return false;
        }

        synchronized boolean isBlocked(long now) {
            return now < blockedUntil;
        }
    }
}
