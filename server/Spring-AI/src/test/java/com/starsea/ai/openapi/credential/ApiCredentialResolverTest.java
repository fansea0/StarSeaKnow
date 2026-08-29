package com.fansea.ai.openapi.credential;

import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCredentialResolverTest {

    private static final String PEPPER = "12345678901234567890123456789012";

    private ApiCredentialMapper credentials;
    private ApiCredentialKnowledgeMapper credentialKnowledge;
    private TenantMapper tenants;
    private ApiCredentialCache cache;
    private ApiKeyCodec codec;
    private ApiKeyCodec.IssuedKey issued;
    private ApiKeyCodec.ParsedKey parsedKey;
    private ApiCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        credentials = mock(ApiCredentialMapper.class);
        credentialKnowledge = mock(ApiCredentialKnowledgeMapper.class);
        tenants = mock(TenantMapper.class);
        cache = new InMemoryCredentialCache();
        codec = codec();
        issued = codec.issue(CredentialType.RAG_RETRIEVAL, "test");
        parsedKey = codec.parse(issued.rawKey());
        resolver = new ApiCredentialResolver(credentials, credentialKnowledge, tenants, cache, codec);
        when(tenants.selectById(1L)).thenReturn(activeTenant());
    }

    @Test
    void cacheMissLoadsCredentialAndRagScopeOnce() {
        when(credentials.selectOne(any())).thenReturn(activeRagCredential());
        when(credentialKnowledge.selectList(any())).thenReturn(List.of(link(11L), link(12L)));

        ApiCredentialResolver.ResolvedCredential first = resolver.resolve(parsedKey);
        ApiCredentialResolver.ResolvedCredential second = resolver.resolve(parsedKey);

        assertThat(first.scope()).isInstanceOf(RagKnowledgeScopeSnapshot.class);
        assertThat(((RagKnowledgeScopeSnapshot) first.scope()).knowledgeIds())
                .containsExactlyInAnyOrder(11L, 12L);
        assertThat(second).isEqualTo(first);
        verify(credentials, times(1)).selectOne(any());
        verify(credentialKnowledge, times(1)).selectList(any());
    }

    @Test
    void cacheHitStillVerifiesSecret() {
        when(credentials.selectOne(any())).thenReturn(activeRagCredential());
        when(credentialKnowledge.selectList(any())).thenReturn(List.of(link(11L)));
        resolver.resolve(parsedKey);
        ApiKeyCodec.ParsedKey forged = new ApiKeyCodec.ParsedKey(
                parsedKey.rawPrefix(), parsedKey.keyId(), "different-secret",
                parsedKey.credentialType(), parsedKey.environment());

        assertThatThrownBy(() -> resolver.resolve(forged))
                .isInstanceOf(CredentialAuthenticationException.class)
                .extracting(error -> ((CredentialAuthenticationException) error).code())
                .isEqualTo("authentication_failed");
        verify(credentials, times(1)).selectOne(any());
    }

    @Test
    void negativeCacheAvoidsRepeatedDatabaseLookup() {
        when(credentials.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve(parsedKey))
                .isInstanceOf(CredentialAuthenticationException.class);
        assertThatThrownBy(() -> resolver.resolve(parsedKey))
                .isInstanceOf(CredentialAuthenticationException.class);

        verify(credentials, times(1)).selectOne(any());
    }

    @Test
    void rejectsRevokedCredentialsWithoutSensitiveDetails() {
        ApiCredential credential = activeRagCredential();
        credential.setStatus("revoked");
        when(credentials.selectOne(any())).thenReturn(credential);

        assertAuthenticationFailure(parsedKey);
    }

    @Test
    void rejectsExpiredCredentialsWithoutSensitiveDetails() {
        ApiCredential credential = activeRagCredential();
        credential.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        when(credentials.selectOne(any())).thenReturn(credential);

        assertAuthenticationFailure(parsedKey);
    }

    @Test
    void rejectsCredentialTypeAndPrefixMismatch() {
        ApiCredential credential = activeRagCredential();
        credential.setCredentialType(CredentialType.AGENT_INVOKE.name());
        when(credentials.selectOne(any())).thenReturn(credential);

        assertAuthenticationFailure(parsedKey);
    }

    @Test
    void rejectsDisabledTenant() {
        Tenant disabled = activeTenant();
        disabled.setStatus(0);
        when(tenants.selectById(1L)).thenReturn(disabled);
        when(credentials.selectOne(any())).thenReturn(activeRagCredential());

        assertThatThrownBy(() -> resolver.resolve(parsedKey))
                .isInstanceOf(CredentialAuthenticationException.class)
                .extracting(error -> ((CredentialAuthenticationException) error).code())
                .isEqualTo("credential_disabled");
    }

    @Test
    void preservesAuthorizationVersionInResolvedSnapshot() {
        ApiCredential credential = activeRagCredential();
        credential.setAuthorizationVersion(7L);
        when(credentials.selectOne(any())).thenReturn(credential);
        when(credentialKnowledge.selectList(any())).thenReturn(List.of(link(11L)));

        ApiCredentialResolver.ResolvedCredential resolved = resolver.resolve(parsedKey);

        assertThat(resolved.authorizationVersion()).isEqualTo(7L);
    }

    @Test
    void agentInvokeDoesNotLoadRagScopeBeforeRejectingUnsupportedType() {
        ApiKeyCodec.IssuedKey agentIssued = codec.issue(CredentialType.AGENT_INVOKE, "test");
        ApiCredential agentCredential = activeRagCredential();
        agentCredential.setCredentialType(CredentialType.AGENT_INVOKE.name());
        agentCredential.setEnvironment("test");
        agentCredential.setKeyId(agentIssued.keyId());
        agentCredential.setSecretDigest(agentIssued.digest());
        agentCredential.setPepperVersion(agentIssued.pepperVersion());
        when(credentials.selectOne(any())).thenReturn(agentCredential);

        assertThatThrownBy(() -> resolver.resolve(codec.parse(agentIssued.rawKey())))
                .isInstanceOf(CredentialAuthenticationException.class)
                .extracting(error -> ((CredentialAuthenticationException) error).code())
                .isEqualTo("credential_type_not_supported");
        verify(credentialKnowledge, never()).selectList(any());
    }

    private void assertAuthenticationFailure(ApiKeyCodec.ParsedKey key) {
        assertThatThrownBy(() -> resolver.resolve(key))
                .isInstanceOf(CredentialAuthenticationException.class)
                .extracting(error -> ((CredentialAuthenticationException) error).code())
                .isEqualTo("authentication_failed");
    }

    private ApiCredential activeRagCredential() {
        ApiCredential credential = new ApiCredential();
        credential.setId(41L);
        credential.setTenantId(1L);
        credential.setCredentialType(CredentialType.RAG_RETRIEVAL.name());
        credential.setKeyId(issued.keyId());
        credential.setSecretDigest(issued.digest());
        credential.setPepperVersion(issued.pepperVersion());
        credential.setEnvironment(issued.environment());
        credential.setStatus("active");
        credential.setAllowedIpCidrs(List.of("127.0.0.1/32"));
        credential.setRequestsPerMinute(60);
        credential.setBurstCapacity(10);
        credential.setMaxConcurrency(2);
        credential.setAuthorizationVersion(3L);
        return credential;
    }

    private ApiCredentialKnowledge link(Long knowledgeId) {
        ApiCredentialKnowledge link = new ApiCredentialKnowledge();
        link.setCredentialId(41L);
        link.setTenantId(1L);
        link.setCredentialType(CredentialType.RAG_RETRIEVAL.name());
        link.setKnowledgeId(knowledgeId);
        return link;
    }

    private Tenant activeTenant() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setStatus(1);
        return tenant;
    }

    private ApiKeyCodec codec() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setActivePepperVersion("v1");
        properties.setPeppers(new LinkedHashMap<>(Map.of("v1", PEPPER)));
        properties.afterPropertiesSet();
        return new ApiKeyCodec(properties);
    }

    private static final class InMemoryCredentialCache implements ApiCredentialCache {
        private final Map<String, ApiCredentialResolver.CachedCredential> valid = new LinkedHashMap<>();
        private final Map<String, Boolean> missing = new LinkedHashMap<>();

        @Override
        public CachedCredentialResult get(String keyId) {
            ApiCredentialResolver.CachedCredential credential = valid.get(keyId);
            if (credential != null) {
                return new CachedCredentialResult.Hit(credential);
            }
            return missing.containsKey(keyId)
                    ? new CachedCredentialResult.Missing()
                    : new CachedCredentialResult.NotCached();
        }

        @Override
        public void putValid(String keyId, ApiCredentialResolver.CachedCredential value) {
            missing.remove(keyId);
            valid.put(keyId, value);
        }

        @Override
        public void putMissing(String keyId) {
            valid.remove(keyId);
            missing.put(keyId, true);
        }

        @Override
        public LoadToken beginLoad(String keyId) {
            return new LoadToken(keyId, 0);
        }

        @Override
        public void publishValid(LoadToken token, ApiCredentialResolver.CachedCredential value) {
            putValid(token.keyId(), value);
        }

        @Override
        public void publishMissing(LoadToken token) {
            putMissing(token.keyId());
        }

        @Override
        public void evict(String keyId) {
            valid.remove(keyId);
            missing.remove(keyId);
        }

        @Override
        public void evictTenant(Long tenantId) {
            valid.entrySet().removeIf(entry -> tenantId.equals(entry.getValue().tenantId()));
        }
    }
}
