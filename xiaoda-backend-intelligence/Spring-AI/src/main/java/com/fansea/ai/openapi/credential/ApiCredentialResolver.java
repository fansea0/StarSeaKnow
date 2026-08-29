package com.fansea.ai.openapi.credential;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.TenantMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Component
public class ApiCredentialResolver {

    private static final String AUTHENTICATION_FAILED = "authentication_failed";
    private static final String CREDENTIAL_DISABLED = "credential_disabled";
    private static final String CREDENTIAL_TYPE_NOT_SUPPORTED = "credential_type_not_supported";

    private final ApiCredentialMapper credentials;
    private final ApiCredentialKnowledgeMapper credentialKnowledge;
    private final TenantMapper tenants;
    private final ApiCredentialCache cache;
    private final ApiKeyCodec codec;
    private final Map<CredentialType, Function<ApiCredential, CredentialScopeSnapshot>> scopeLoaders;

    public ApiCredentialResolver(ApiCredentialMapper credentials,
                                 ApiCredentialKnowledgeMapper credentialKnowledge,
                                 TenantMapper tenants,
                                 ApiCredentialCache cache,
                                 ApiKeyCodec codec) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.credentialKnowledge = Objects.requireNonNull(credentialKnowledge, "credentialKnowledge");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.scopeLoaders = Map.of(CredentialType.RAG_RETRIEVAL, this::loadRagScope);
    }

    public ResolvedCredential resolve(ApiKeyCodec.ParsedKey parsedKey) {
        if (parsedKey == null || parsedKey.keyId() == null) {
            throw failure(AUTHENTICATION_FAILED);
        }
        CachedCredentialResult cached = cache.get(parsedKey.keyId());
        if (cached instanceof CachedCredentialResult.Hit hit) {
            return resolveCached(parsedKey, hit.credential());
        }
        if (cached instanceof CachedCredentialResult.Missing) {
            throw failure(AUTHENTICATION_FAILED);
        }
        return loadAndResolve(parsedKey);
    }

    private ResolvedCredential loadAndResolve(ApiKeyCodec.ParsedKey parsedKey) {
        ApiCredentialCache.LoadToken loadToken = cache.beginLoad(parsedKey.keyId());
        ApiCredential credential = credentials.selectOne(new LambdaQueryWrapper<ApiCredential>()
                .eq(ApiCredential::getKeyId, parsedKey.keyId())
                .isNull(ApiCredential::getDeletedAt));
        if (credential == null) {
            cache.publishMissing(loadToken);
            throw failure(AUTHENTICATION_FAILED);
        }

        CredentialType credentialType = storedCredentialType(credential);
        verifyParsedIdentity(parsedKey, credentialType, credential.getEnvironment());
        verifyCredentialIsActive(credential.getStatus(), credential.getExpiresAt(), credential.getDeletedAt());
        verifyTenantIsEnabled(credential.getTenantId());
        verifySecret(parsedKey, credential.getSecretDigest(), credential.getPepperVersion());

        Function<ApiCredential, CredentialScopeSnapshot> loader = scopeLoaders.get(credentialType);
        if (loader == null) {
            throw failure(CREDENTIAL_TYPE_NOT_SUPPORTED);
        }
        CachedCredential cached = new CachedCredential(
                credential.getId(), credential.getTenantId(), credentialType, credential.getEnvironment(),
                credential.getSecretDigest(), credential.getPepperVersion(), credential.getStatus(), credential.getExpiresAt(),
                credential.getAllowedIpCidrs(), credential.getRequestsPerMinute(), credential.getBurstCapacity(),
                credential.getMaxConcurrency(), credential.getAuthorizationVersion(), loader.apply(credential));
        cache.publishValid(loadToken, cached);
        return resolved(cached);
    }

    private ResolvedCredential resolveCached(ApiKeyCodec.ParsedKey parsedKey, CachedCredential credential) {
        verifyParsedIdentity(parsedKey, credential.credentialType(), credential.environment());
        verifySecret(parsedKey, credential.secretDigest(), credential.pepperVersion());
        verifyCredentialIsActive(credential.status(), credential.expiresAt(), null);
        return resolved(credential);
    }

    private CredentialType storedCredentialType(ApiCredential credential) {
        try {
            return CredentialType.valueOf(credential.getCredentialType());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw failure(CREDENTIAL_TYPE_NOT_SUPPORTED);
        }
    }

    private void verifyParsedIdentity(ApiKeyCodec.ParsedKey parsedKey, CredentialType credentialType, String environment) {
        if (parsedKey.credentialType() != credentialType
                || !Objects.equals(parsedKey.environment(), environment)
                || !Objects.equals(parsedKey.rawPrefix(), credentialType.prefix() + "_" + environment)) {
            throw failure(AUTHENTICATION_FAILED);
        }
    }

    private void verifyCredentialIsActive(String status, OffsetDateTime expiresAt, OffsetDateTime deletedAt) {
        if (deletedAt != null) {
            throw failure(AUTHENTICATION_FAILED);
        }
        if ("disabled".equals(status)) {
            throw failure(CREDENTIAL_DISABLED);
        }
        if (!"active".equals(status) || (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now()))) {
            throw failure(AUTHENTICATION_FAILED);
        }
    }

    private void verifyTenantIsEnabled(Long tenantId) {
        Tenant tenant = tenantId == null ? null : tenants.selectById(tenantId);
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getStatus())) {
            throw failure(CREDENTIAL_DISABLED);
        }
    }

    private void verifySecret(ApiKeyCodec.ParsedKey parsedKey, String secretDigest, String pepperVersion) {
        if (!codec.verify(parsedKey, secretDigest, pepperVersion)) {
            throw failure(AUTHENTICATION_FAILED);
        }
    }

    private CredentialScopeSnapshot loadRagScope(ApiCredential credential) {
        List<ApiCredentialKnowledge> links = credentialKnowledge.selectList(new LambdaQueryWrapper<ApiCredentialKnowledge>()
                .eq(ApiCredentialKnowledge::getCredentialId, credential.getId()));
        Set<Long> knowledgeIds = links.stream()
                .map(ApiCredentialKnowledge::getKnowledgeId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new RagKnowledgeScopeSnapshot(knowledgeIds);
    }

    private ResolvedCredential resolved(CachedCredential credential) {
        return new ResolvedCredential(
                credential.credentialId(), credential.tenantId(), credential.credentialType(), credential.environment(),
                credential.status(), credential.expiresAt(), credential.allowedIpCidrs(), credential.requestsPerMinute(),
                credential.burstCapacity(), credential.maxConcurrency(), credential.authorizationVersion(), credential.scope());
    }

    private CredentialAuthenticationException failure(String code) {
        return new CredentialAuthenticationException(code);
    }

    public record CachedCredential(
            Long credentialId, Long tenantId, CredentialType credentialType, String environment,
            String secretDigest, String pepperVersion, String status, OffsetDateTime expiresAt,
            List<String> allowedIpCidrs, Integer requestsPerMinute, Integer burstCapacity, Integer maxConcurrency,
            Long authorizationVersion, CredentialScopeSnapshot scope) {
        public CachedCredential {
            allowedIpCidrs = allowedIpCidrs == null ? List.of() : List.copyOf(allowedIpCidrs);
            Objects.requireNonNull(credentialType, "credentialType");
            Objects.requireNonNull(scope, "scope");
        }
    }

    public record ResolvedCredential(
            Long credentialId, Long tenantId, CredentialType credentialType, String environment,
            String status, OffsetDateTime expiresAt, List<String> allowedIpCidrs, Integer requestsPerMinute,
            Integer burstCapacity, Integer maxConcurrency, Long authorizationVersion, CredentialScopeSnapshot scope) {
        public ResolvedCredential {
            allowedIpCidrs = allowedIpCidrs == null ? List.of() : List.copyOf(allowedIpCidrs);
            Objects.requireNonNull(credentialType, "credentialType");
            Objects.requireNonNull(scope, "scope");
        }
    }
}
