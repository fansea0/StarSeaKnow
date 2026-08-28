package com.fansea.ai.openapi.credential;

public interface ApiCredentialCache {

    CachedCredentialResult get(String keyId);

    void putValid(String keyId, ApiCredentialResolver.CachedCredential value);

    void putMissing(String keyId);

    void evict(String keyId);

    void evictTenant(Long tenantId);
}
