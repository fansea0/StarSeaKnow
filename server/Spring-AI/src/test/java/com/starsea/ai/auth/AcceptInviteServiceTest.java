package com.starsea.ai.auth;

import com.starsea.ai.domain.Invite;
import com.starsea.ai.domain.AppUser;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.InviteMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AcceptInviteServiceTest {

    private static AcceptInviteService newSvc(InviteMapper invites, TenantMapper tenants,
                                              AppUserMapper users) {
        JwtService jwt = mock(JwtService.class);
        when(jwt.signAccess(anyLong(), anyLong(), anyString())).thenReturn("ACCESS");
        RefreshTokenService refresh = mock(RefreshTokenService.class);
        when(refresh.issue(anyLong(), any(), any())).thenReturn(
                new RefreshTokenService.IssueResult("REFRESH", java.time.Instant.now(),
                        java.util.UUID.randomUUID()));
        return new AcceptInviteService(invites, tenants, users, mock(PasswordEncoder.class),
                jwt, refresh, mock(AuthAuditLogger.class));
    }

    @Test
    void accept_validInvite_createsUserAndReturnsLoginResult() {
        InviteMapper invites = mock(InviteMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        Invite inv = new Invite();
        inv.setId(1L); inv.setTenantId(7L); inv.setCode("ABC");
        inv.setIntendedRole("tenant_admin");
        inv.setExpiresAt(OffsetDateTime.now().plusHours(1));
        when(invites.selectList(any())).thenReturn(List.of(inv));
        when(tenants.selectById(7L)).thenReturn(new com.starsea.ai.domain.Tenant());
        doAnswer(invocation -> {
            invocation.<AppUser>getArgument(0).setId(1L);
            return 1;
        }).when(users).insert(any(AppUser.class));

        AcceptInviteService svc = newSvc(invites, tenants, users);
        AuthService.LoginResult r = svc.accept("ABC", "pw", "Alice", "ip", "ua");
        assertEquals("ACCESS", r.accessToken());
        verify(users).insert(any(com.starsea.ai.domain.AppUser.class));
    }

    @Test
    void accept_expiredInvite_throws41001() {
        InviteMapper invites = mock(InviteMapper.class);
        Invite inv = new Invite();
        inv.setId(1L); inv.setTenantId(7L); inv.setCode("ABC");
        inv.setIntendedRole("tenant_admin");
        inv.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
        when(invites.selectList(any())).thenReturn(List.of(inv));

        AcceptInviteService svc = newSvc(invites, mock(TenantMapper.class), mock(AppUserMapper.class));
        AuthException ex = assertThrows(AuthException.class,
                () -> svc.accept("ABC", "pw", "Alice", "ip", "ua"));
        assertEquals(41001, ex.getCode());
    }

    @Test
    void accept_alreadyAccepted_throws41001() {
        InviteMapper invites = mock(InviteMapper.class);
        Invite inv = new Invite();
        inv.setId(1L); inv.setTenantId(7L); inv.setCode("ABC");
        inv.setIntendedRole("tenant_admin");
        inv.setAcceptedBy(99L);
        inv.setExpiresAt(OffsetDateTime.now().plusHours(1));
        when(invites.selectList(any())).thenReturn(List.of(inv));

        AcceptInviteService svc = newSvc(invites, mock(TenantMapper.class), mock(AppUserMapper.class));
        assertThrows(AuthException.class, () -> svc.accept("ABC", "pw", "Alice", "ip", "ua"));
    }
}
