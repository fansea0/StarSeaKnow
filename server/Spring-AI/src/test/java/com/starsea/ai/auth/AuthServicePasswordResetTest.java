package com.starsea.ai.auth;

import com.starsea.ai.domain.AppUser;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.time.Instant;

class AuthServicePasswordResetTest {

    @Test
    void loginCarriesForcedChangeFlagFromUserRecord() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        AuthService service = new AuthService(tenants, users, encoder, mock(JwtService.class),
                refresh, mock(AuthAuditLogger.class));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setTenantId(7L);
        user.setUsername("root");
        user.setRole("tenant_admin");
        user.setStatus(1);
        user.setPasswordHash("hash");
        user.setMustChangePassword(true);
        when(users.selectByUsername("root")).thenReturn(user);
        var tenant = new com.starsea.ai.domain.Tenant();
        tenant.setStatus(1);
        when(tenants.selectById(7L)).thenReturn(tenant);
        when(encoder.matches("current", "hash")).thenReturn(true);
        when(refresh.issue(9L, null, null)).thenReturn(
                new RefreshTokenService.IssueResult("refresh", Instant.now(), null));

        assertEquals(true, service.login("root", "current", null, null).mustChangePassword());
    }

    @Test
    void changePasswordClearsForcedChangeFlagAfterVerifyingCurrentPassword() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(tenants, users, encoder, mock(JwtService.class),
                mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setPasswordHash("old-hash");
        user.setMustChangePassword(true);
        when(users.selectById(9L)).thenReturn(user);
        when(encoder.matches("old", "old-hash")).thenReturn(true);
        when(encoder.hash("new-password")).thenReturn("new-hash");
        when(users.updateById(user)).thenReturn(1);

        service.changePassword(9L, "old", "new-password", "new-password");

        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(false, user.getMustChangePassword());
        verify(users).updateById(user);
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordWithoutClearingFlag() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        AuthService service = new AuthService(tenants, users, encoder, mock(JwtService.class),
                mock(RefreshTokenService.class), mock(AuthAuditLogger.class));
        AppUser user = new AppUser();
        user.setId(9L);
        user.setPasswordHash("old-hash");
        user.setMustChangePassword(true);
        when(users.selectById(9L)).thenReturn(user);
        when(encoder.matches("wrong", "old-hash")).thenReturn(false);

        assertThrows(AuthException.class,
                () -> service.changePassword(9L, "wrong", "new-password", "new-password"));
        verify(users, never()).updateById(any());
    }
}
