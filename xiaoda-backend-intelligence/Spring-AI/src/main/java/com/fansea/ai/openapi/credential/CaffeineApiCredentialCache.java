package com.fansea.ai.openapi.credential;

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

    void cleanUp() { validCredentials.cleanUp(); missingCredentials.cleanUp(); }
    long positiveSize() { return validCredentials.estimatedSize(); }
    long negativeSize() { return missingCredentials.estimatedSize(); }
}
