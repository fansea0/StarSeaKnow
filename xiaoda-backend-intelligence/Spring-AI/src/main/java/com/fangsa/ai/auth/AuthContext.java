package com.fangsa.ai.auth;

public class AuthContext {
    public enum Kind { BUSINESS, PLATFORM }

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    private final Kind kind;
    private final long userId;
    private final Long tenantId;
    private final String role;
    private final String jti;

    public AuthContext(Kind kind, long userId, Long tenantId, String role, String jti) {
        this.kind = kind; this.userId = userId; this.tenantId = tenantId;
        this.role = role; this.jti = jti;
    }

    public static AuthContext current() { return HOLDER.get(); }
    public static void set(AuthContext ctx) { HOLDER.set(ctx); }
    public static void clear() { HOLDER.remove(); }

    public Kind getKind() { return kind; }
    public long getUserId() { return userId; }
    public Long getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public String getJti() { return jti; }
}