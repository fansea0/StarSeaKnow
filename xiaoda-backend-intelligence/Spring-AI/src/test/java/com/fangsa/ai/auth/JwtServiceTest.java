package com.fangsa.ai.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private final JwtService svc = new JwtService(
            "test-secret-must-be-at-least-32-bytes-long-for-hs256",
            15 * 60 * 1000L
    );

    @Test
    void signAndVerify_roundTrip_business() {
        String token = svc.signAccess(42L, 7L, "tenant_admin");
        ParsedClaims c = svc.verifyAccess(token);
        assertEquals("smart-agent", c.getIssuer());
        assertEquals("u:42", c.getSub());
        assertEquals(7L, c.getTenantId());
        assertEquals("tenant_admin", c.getRole());
        assertNotNull(c.getJti());
        assertTrue(c.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void signAndVerify_roundTrip_platform() {
        String token = svc.signPlatformAccess(1L);
        ParsedClaims c = svc.verifyAccess(token);
        assertEquals("smart-agent-platform", c.getIssuer());
        assertEquals("pa:1", c.getSub());
        assertNull(c.getTenantId(), "platform admin must not carry tenant claim");
        assertEquals("platform_admin", c.getRole());
    }

    @Test
    void verifyAccess_expiredToken_throws40101() throws InterruptedException {
        JwtService shortSvc = new JwtService(
                "test-secret-must-be-at-least-32-bytes-long-for-hs256",
                1L // 1 ms TTL
        );
        String t = shortSvc.signAccess(1L, 1L, "tenant_admin");
        Thread.sleep(50);
        AuthException ex = assertThrows(AuthException.class, () -> shortSvc.verifyAccess(t));
        assertEquals(40101, ex.getCode());
    }

    @Test
    void verifyAccess_tamperedSignature_throws40100() {
        String t = svc.signAccess(1L, 1L, "tenant_admin");
        String tampered = t.substring(0, t.length() - 4) + "AAAA";
        AuthException ex = assertThrows(AuthException.class, () -> svc.verifyAccess(tampered));
        assertEquals(40100, ex.getCode());
    }

    @Test
    void verifyAccess_badSignature_throws40100() {
        // 模拟攻击者用其它 secret 签的 token 走业务验签路径
        JwtService other = new JwtService(
                "attacker-secret-must-also-be-at-least-32-bytes-long",
                15 * 60 * 1000L
        );
        String t = other.signPlatformAccess(1L);
        AuthException ex = assertThrows(AuthException.class, () -> svc.verifyAccess(t));
        assertEquals(40100, ex.getCode());
    }
}