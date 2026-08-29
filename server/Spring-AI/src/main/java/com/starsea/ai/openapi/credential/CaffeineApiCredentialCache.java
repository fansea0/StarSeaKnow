package com.starsea.ai.openapi.credential;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

@Component
public class CaffeineApiCredentialCache implements ApiCredentialCache {

    private final Cache<String, ApiCredentialResolver.CachedCredential> validCredentials;
    private final Cache<String, Boolean> missingCredentials;
    private long invalidationEpoch;

    @Autowired
    public CaffeineApiCredentialCache(ApiKeyProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    CaffeineApiCredentialCache() {
        this(new ApiKeyProperties(), Ticker.systemTicker());
    }

    CaffeineApiCredentialCache(Ticker ticker) {
        this(new ApiKeyProperties(), ticker);
    }

    CaffeineApiCredentialCache(ApiKeyProperties properties, Ticker ticker) {
        validCredentials = Caffeine.newBuilder()
                .expireAfterWrite(properties.getPositiveCacheTtl())
                .maximumSize(properties.getPositiveCacheMaximumSize())
                .ticker(ticker)
                .build();
        missingCredentials = Caffeine.newBuilder()
                .expireAfterWrite(properties.getNegativeCacheTtl())
                .maximumSize(properties.getNegativeCacheMaximumSize())
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
    public synchronized void putValid(String keyId, ApiCredentialResolver.CachedCredential value) {
        missingCredentials.invalidate(keyId);
        validCredentials.put(keyId, value);
    }

    @Override
    public synchronized void putMissing(String keyId) {
        validCredentials.invalidate(keyId);
        missingCredentials.put(keyId, Boolean.TRUE);
    }

    @Override
    public synchronized LoadToken beginLoad(String keyId) {
        return new LoadToken(keyId, invalidationEpoch);
    }

    @Override
    public synchronized void publishValid(LoadToken token, ApiCredentialResolver.CachedCredential value) {
        if (token != null && token.invalidationEpoch() == invalidationEpoch) {
            putValid(token.keyId(), value);
        }
    }

    @Override
    public synchronized void publishMissing(LoadToken token) {
        if (token != null && token.invalidationEpoch() == invalidationEpoch) {
            putMissing(token.keyId());
        }
    }

    @Override
    public synchronized void evict(String keyId) {
        invalidationEpoch++;
        validCredentials.invalidate(keyId);
        missingCredentials.invalidate(keyId);
    }

    @Override
    public synchronized void evictTenant(Long tenantId) {
        if (tenantId != null) {
            invalidationEpoch++;
            validCredentials.asMap().entrySet()
                    .removeIf(entry -> tenantId.equals(entry.getValue().tenantId()));
        }
    }

    void cleanUp() { validCredentials.cleanUp(); missingCredentials.cleanUp(); }
    long positiveSize() { return validCredentials.estimatedSize(); }
    long negativeSize() { return missingCredentials.estimatedSize(); }
}
