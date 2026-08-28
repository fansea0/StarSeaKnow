package com.fansea.ai.auth;

import com.fansea.ai.openapi.credential.ApiCredentialResolver;
import com.fansea.ai.openapi.credential.CredentialScopeSnapshot;

public class AuthContext {
    public enum Kind { BUSINESS, PLATFORM, EXTERNAL_API }

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private final Kind kind;
    private final long userId;
    private final Long tenantId;
    private final String role;
    private final String jti;
    private final Long credentialId;
    private final String credentialType;
    private final String credentialEnvironment;
    private final CredentialScopeSnapshot credentialScope;
    private final Integer requestsPerMinute;
    private final Integer burstCapacity;
    private final Integer maxConcurrency;

    public AuthContext(Kind kind, long userId, Long tenantId, String role, String jti) {
        this(kind, userId, tenantId, role, jti, null, null, null, null, null, null, null);
    }

    private AuthContext(Kind kind, long userId, Long tenantId, String role, String jti,
                        Long credentialId, String credentialType, String credentialEnvironment,
                        CredentialScopeSnapshot credentialScope, Integer requestsPerMinute,
                        Integer burstCapacity, Integer maxConcurrency) {
        this.kind = kind; this.userId = userId; this.tenantId = tenantId;
        this.role = role; this.jti = jti;
        this.credentialId = credentialId;
        this.credentialType = credentialType;
        this.credentialEnvironment = credentialEnvironment;
        this.credentialScope = credentialScope;
        this.requestsPerMinute = requestsPerMinute;
        this.burstCapacity = burstCapacity;
        this.maxConcurrency = maxConcurrency;
    }

    public static AuthContext external(ApiCredentialResolver.ResolvedCredential credential) {
        return new AuthContext(Kind.EXTERNAL_API, credential.credentialId(), credential.tenantId(),
                "external_api", null, credential.credentialId(), credential.credentialType().name(),
                credential.environment(), credential.scope(), credential.requestsPerMinute(),
                credential.burstCapacity(), credential.maxConcurrency());
    }

    public static AuthContext current() { return HOLDER.get(); }
    public static void set(AuthContext ctx) { HOLDER.set(ctx); }
    public static void clear() { HOLDER.remove(); }

    public Kind getKind() { return kind; }
    public long getUserId() { return userId; }
    public Long getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public String getJti() { return jti; }
    public Long getCredentialId() { return credentialId; }
    public String getCredentialType() { return credentialType; }
    public String getCredentialEnvironment() { return credentialEnvironment; }
    public CredentialScopeSnapshot getCredentialScope() { return credentialScope; }
    public Integer getRequestsPerMinute() { return requestsPerMinute; }
    public Integer getBurstCapacity() { return burstCapacity; }
    public Integer getMaxConcurrency() { return maxConcurrency; }
}
