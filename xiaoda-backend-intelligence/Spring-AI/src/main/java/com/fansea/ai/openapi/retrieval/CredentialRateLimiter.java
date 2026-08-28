package com.fansea.ai.openapi.retrieval;

import com.fansea.ai.openapi.error.ExternalApiException;
import org.springframework.http.HttpStatus;

public interface CredentialRateLimiter {

    RateLimitLease acquire(long credentialId, int requestsPerMinute, int burstCapacity, int maxConcurrency);

    interface RateLimitLease extends AutoCloseable {
        int limit();

        int remaining();

        long resetEpochSecond();

        @Override
        void close();
    }

    final class RateLimitExceededException extends ExternalApiException {
        private final int limit;
        private final int remaining;
        private final long resetEpochSecond;
        private final long retryAfterSeconds;

        public RateLimitExceededException(int limit, int remaining, long resetEpochSecond,
                                          long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "Rate limit exceeded.");
            this.limit = limit;
            this.remaining = remaining;
            this.resetEpochSecond = resetEpochSecond;
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }

        public int limit() {
            return limit;
        }

        public int remaining() {
            return remaining;
        }

        public long resetEpochSecond() {
            return resetEpochSecond;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
