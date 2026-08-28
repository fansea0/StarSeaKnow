package com.fansea.ai.openapi.credential;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineApiCredentialCacheTest {

    @Test
    void positiveEntriesExpireAfterSixtySeconds() {
        MutableTicker ticker = new MutableTicker();
        ApiCredentialCache cache = new CaffeineApiCredentialCache(ticker);
        cache.putValid("positive", credential(1L));

        ticker.advanceSeconds(59);
        assertThat(cache.get("positive")).isInstanceOf(CachedCredentialResult.Hit.class);
        ticker.advanceSeconds(1);
        assertThat(cache.get("positive")).isInstanceOf(CachedCredentialResult.NotCached.class);
    }

    @Test
    void negativeEntriesExpireAfterTenSeconds() {
        MutableTicker ticker = new MutableTicker();
        ApiCredentialCache cache = new CaffeineApiCredentialCache(ticker);
        cache.putMissing("missing");

        ticker.advanceSeconds(9);
        assertThat(cache.get("missing")).isInstanceOf(CachedCredentialResult.Missing.class);
        ticker.advanceSeconds(1);
        assertThat(cache.get("missing")).isInstanceOf(CachedCredentialResult.NotCached.class);
    }

    @Test
    void evictRemovesPositiveAndNegativeEntries() {
        ApiCredentialCache cache = new CaffeineApiCredentialCache(new MutableTicker());
        cache.putValid("positive", credential(1L));
        cache.putMissing("missing");

        cache.evict("positive");
        cache.evict("missing");

        assertThat(cache.get("positive")).isInstanceOf(CachedCredentialResult.NotCached.class);
        assertThat(cache.get("missing")).isInstanceOf(CachedCredentialResult.NotCached.class);
    }

    @Test
    void evictTenantRemovesOnlyThatTenantsPositiveEntries() {
        ApiCredentialCache cache = new CaffeineApiCredentialCache(new MutableTicker());
        cache.putValid("tenant-one", credential(1L));
        cache.putValid("tenant-two", credential(2L));
        cache.putMissing("missing");

        cache.evictTenant(1L);

        assertThat(cache.get("tenant-one")).isInstanceOf(CachedCredentialResult.NotCached.class);
        assertThat(cache.get("tenant-two")).isInstanceOf(CachedCredentialResult.Hit.class);
        assertThat(cache.get("missing")).isInstanceOf(CachedCredentialResult.Missing.class);
    }

    private ApiCredentialResolver.CachedCredential credential(Long tenantId) {
        return new ApiCredentialResolver.CachedCredential(
                1L, tenantId, CredentialType.RAG_RETRIEVAL, "test", "digest", "v1", "active",
                null, List.of(), 60, 10, 2, 3L, new RagKnowledgeScopeSnapshot(Set.of(11L)));
    }

    private static final class MutableTicker implements Ticker {
        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advanceSeconds(long seconds) {
            nanos += TimeUnit.SECONDS.toNanos(seconds);
        }
    }
}
