package com.fansea.ai.openapi.credential;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CaffeineApiCredentialCache implements ApiCredentialCache {

    private static final Duration POSITIVE_TTL = Duration.ofSeconds(60);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(10);

    private final Cache<String, ApiCredentialResolver.CachedCredential> validCredentials;
    private final Cache<String, Boolean> missingCredentials;

    public CaffeineApiCredentialCache() {
        this(Ticker.systemTicker());
    }

    public CaffeineApiCredentialCache(Ticker ticker) {
        validCredentials = Caffeine.newBuilder()
                .expireAfterWrite(POSITIVE_TTL)
                .ticker(ticker)
                .build();
        missingCredentials = Caffeine.newBuilder()
                .expireAfterWrite(NEGATIVE_TTL)
                .ticker(ticker)
                .build();
    }

    @Override
    public CachedCredentialResult get(String keyId) {
        ApiCredentialResolver.CachedCredential credential = validCredentials.getIfPresent(keyId);
        if (credential != null) {
            return new CachedCredentialResult.Hit(credential);
        }
        return missingCredentials.getIfPresent(keyId) != null
                ? new CachedCredentialResult.Missing()
                : new CachedCredentialResult.NotCached();
    }

    @Override
    public void putValid(String keyId, ApiCredentialResolver.CachedCredential value) {
        missingCredentials.invalidate(keyId);
        validCredentials.put(keyId, value);
    }

    @Override
    public void putMissing(String keyId) {
        validCredentials.invalidate(keyId);
        missingCredentials.put(keyId, Boolean.TRUE);
    }

    @Override
    public void evict(String keyId) {
        validCredentials.invalidate(keyId);
        missingCredentials.invalidate(keyId);
    }

    @Override
    public void evictTenant(Long tenantId) {
        if (tenantId != null) {
            validCredentials.asMap().entrySet()
                    .removeIf(entry -> tenantId.equals(entry.getValue().tenantId()));
        }
    }
}
