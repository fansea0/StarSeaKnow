package com.fangsa.ai.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuthAuditLogger {
    private static final Logger log = LoggerFactory.getLogger("com.fangsa.ai.auth");

    public void login(long userId, Long tenantId, String ip, String ua) {
        log.info("AUTH_LOGIN user={} tenantId={} ip={} ua={}", userId, tenantId, ip, ua);
    }
    public void loginFail(String tenantCode, String username, String ip) {
        log.warn("AUTH_LOGIN_FAIL tenant={} user={} ip={}", tenantCode, username, ip);
    }
    public void refresh(long userId, UUID family, String ip) {
        log.info("AUTH_REFRESH user={} family={} ip={}", userId, family, ip);
    }
    public void refreshReuse(UUID family, String ip) {
        log.warn("AUTH_REFRESH_REUSE family={} ip={}", family, ip);
    }
    public void logout(long userId, UUID family) {
        log.info("AUTH_LOGOUT user={} family={}", userId, family);
    }
    public void tenantCreate(String code, long by) {
        log.info("TENANT_CREATE code={} by_platform_admin={}", code, by);
    }
    public void inviteAccept(String code, long userId, long tenantId) {
        log.info("AUTH_INVITE_ACCEPT code={} userId={} tenantId={}", code, userId, tenantId);
    }
}