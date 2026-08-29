package com.fansea.ai.auth;

import com.fansea.ai.domain.AppUser;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

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

        t.setStatus(1);
        when(tenants.selectById(7L)).thenReturn(t);
        when(users.selectByUsername("alice")).thenReturn(u);
        when(enc.matches("pw", "hash")).thenReturn(true);
        when(jwt.signAccess(42L, 7L, "tenant_admin")).thenReturn("ACCESS.jwt");
        when(refresh.issue(42L, "ua", "ip"))
                .thenReturn(new RefreshTokenService.IssueResult("REFRESH", java.time.Instant.now(), java.util.UUID.randomUUID()));

        AuthService svc = new AuthService(tenants, users, enc, jwt, refresh, audit);
        AuthService.LoginResult r = svc.login("alice", "pw", "ip", "ua");

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

        t.setStatus(1);
        when(tenants.selectById(7L)).thenReturn(t);
        when(users.selectByUsername("alice")).thenReturn(u);
        when(enc.matches(any(), any())).thenReturn(false);

        AuthService svc = new AuthService(tenants, users, enc, mock(JwtService.class),
                mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.login("alice", "bad", "ip", "ua"));
        assertEquals(40100, ex.getCode());
    }

    @Test
    void login_unknownUsername_throws40100() {
        TenantMapper tenants = mock(TenantMapper.class);

        AuthService svc = new AuthService(tenants, mock(AppUserMapper.class), mock(PasswordEncoder.class),
                mock(JwtService.class), mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.login("alice", "pw", "ip", "ua"));
        assertEquals(40100, ex.getCode());
    }

    @Test
    void login_disabledUser_throws40100() {
        AppUser user = new AppUser();
        user.setStatus(0);
        AppUserMapper users = mock(AppUserMapper.class);
        when(users.selectByUsername("alice")).thenReturn(user);

        AuthService service = new AuthService(mock(TenantMapper.class), users, mock(PasswordEncoder.class),
                mock(JwtService.class), mock(RefreshTokenService.class), mock(AuthAuditLogger.class));

        assertEquals(40100, assertThrows(AuthException.class,
                () -> service.login("alice", "pw", "ip", "ua")).getCode());
    }

    @Test
    void login_disabledTenant_throws40100() {
        AppUser user = new AppUser();
        user.setTenantId(7L);
        user.setStatus(1);
        Tenant tenant = new Tenant();
        tenant.setStatus(0);
        AppUserMapper users = mock(AppUserMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        when(users.selectByUsername("alice")).thenReturn(user);
        when(tenants.selectById(7L)).thenReturn(tenant);

        AuthService service = new AuthService(tenants, users, mock(PasswordEncoder.class), mock(JwtService.class),
                mock(RefreshTokenService.class), mock(AuthAuditLogger.class));

        assertEquals(40100, assertThrows(AuthException.class,
                () -> service.login("alice", "pw", "ip", "ua")).getCode());
    }
}
