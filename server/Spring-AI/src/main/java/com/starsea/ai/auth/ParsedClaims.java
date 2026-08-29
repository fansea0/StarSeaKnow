package com.fansea.ai.auth;

import java.time.Instant;

public class ParsedClaims {
    private final String issuer;
    private final String sub;
    private final Long tenantId;
    private final String role;
    private final String jti;
    private final Instant expiresAt;

    public ParsedClaims(String issuer, String sub, Long tenantId, String role, String jti, Instant expiresAt) {
        this.issuer = issuer; this.sub = sub; this.tenantId = tenantId;
        this.role = role; this.jti = jti; this.expiresAt = expiresAt;
    }

    public String getIssuer() { return issuer; }
    public String getSub() { return sub; }
    public Long getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public String getJti() { return jti; }
    public Instant getExpiresAt() { return expiresAt; }
}
