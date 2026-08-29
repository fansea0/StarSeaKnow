package com.starsea.ai.auth;

public enum AuthErrorCode {
    MISSING_TOKEN(40100),
    EXPIRED(40101),
    REFRESH_EXPIRED(40102),
    REFRESH_REUSE(40103),
    FORBIDDEN_ROLE(40301),
    CROSS_TENANT(40302),
    INVITE_INVALID(41001),
    INVITATION_UNAVAILABLE(41002),
    REGISTRATION_INVALID(40001),
    USERNAME_CONFLICT(40901),
    INVITATION_ALREADY_USED(40902),
    TENANT_NOT_FOUND(40401);

    private final int code;
    AuthErrorCode(int code) { this.code = code; }
    public int code() { return code; }
}
