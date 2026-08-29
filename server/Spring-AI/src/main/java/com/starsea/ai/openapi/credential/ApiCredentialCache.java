package com.fansea.ai.openapi.credential;

public interface ApiCredentialCache {

    record LoadToken(String keyId, long invalidationEpoch) { }

    CachedCredentialResult get(String keyId);

    void putValid(String keyId, ApiCredentialResolver.CachedCredential value);

    void putMissing(String keyId);

    LoadToken beginLoad(String keyId);

    void publishValid(LoadToken token, ApiCredentialResolver.CachedCredential value);

    void publishMissing(LoadToken token);

    void evict(String keyId);

    void evictTenant(Long tenantId);
}
