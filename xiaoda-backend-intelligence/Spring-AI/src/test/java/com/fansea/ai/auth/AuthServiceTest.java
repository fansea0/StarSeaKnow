package com.fansea.ai.auth;

import com.fansea.ai.domain.AppUser;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void login_validCredentials_returnsTokens() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        AuthAuditLogger audit = mock(AuthAuditLogger.class);

        Tenant t = new Tenant(); t.setId(7L); t.setCode("acme");
        AppUser u = new AppUser();
        u.setId(42L); u.setTenantId(7L); u.setUsername("alice");
        u.setPasswordHash("hash"); u.setRole("tenant_admin"); u.setStatus(1);

        when(tenants.selectList(any())).thenReturn(List.of(t));
        when(users.selectList(any())).thenReturn(List.of(u));
        when(enc.matches("pw", "hash")).thenReturn(true);
        when(jwt.signAccess(42L, 7L, "tenant_admin")).thenReturn("ACCESS.jwt");
        when(refresh.issue(42L, "ua", "ip"))
                .thenReturn(new RefreshTokenService.IssueResult("REFRESH", java.time.Instant.now(), java.util.UUID.randomUUID()));

        AuthService svc = new AuthService(tenants, users, enc, jwt, refresh, audit);
        AuthService.LoginResult r = svc.login("acme", "alice", "pw", "ip", "ua");

        assertEquals("ACCESS.jwt", r.accessToken());
        assertEquals("REFRESH", r.refreshRaw());
        assertEquals(42L, r.user().id());
        assertEquals(7L, r.user().tenantId());
    }

    @Test
    void login_wrongPassword_throws40100() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);

        Tenant t = new Tenant(); t.setId(7L); t.setCode("acme");
        AppUser u = new AppUser(); u.setId(42L); u.setTenantId(7L);
        u.setPasswordHash("hash"); u.setRole("tenant_admin"); u.setStatus(1);

        when(tenants.selectList(any())).thenReturn(List.of(t));
        when(users.selectList(any())).thenReturn(List.of(u));
        when(enc.matches(any(), any())).thenReturn(false);

        AuthService svc = new AuthService(tenants, users, enc, mock(JwtService.class),
                mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.login("acme", "alice", "bad", "ip", "ua"));
        assertEquals(40100, ex.getCode());
    }

    @Test
    void login_unknownTenant_throws40100() {
        TenantMapper tenants = mock(TenantMapper.class);
        when(tenants.selectList(any())).thenReturn(List.of());

        AuthService svc = new AuthService(tenants, mock(AppUserMapper.class), mock(PasswordEncoder.class),
                mock(JwtService.class), mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.login("nope", "alice", "pw", "ip", "ua"));
        assertEquals(40100, ex.getCode());
    }
}
