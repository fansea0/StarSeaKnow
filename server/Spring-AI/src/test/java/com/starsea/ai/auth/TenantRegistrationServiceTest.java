package com.starsea.ai.auth;

import com.starsea.ai.domain.PlatformInvitation;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.PlatformInvitationMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantRegistrationServiceTest {

    @Test
    void duplicateUsernameDuringInsertBecomesControlledConflictWithoutInvitationConsumption() {
        PlatformInvitationMapper invitations = mock(PlatformInvitationMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PlatformInvitation invitation = new PlatformInvitation();
        invitation.setId(1L);
        invitation.setCode("code");
        invitation.setStatus("ACTIVE");
        invitation.setValidFrom(OffsetDateTime.now().minusMinutes(1));
        invitation.setValidUntil(OffsetDateTime.now().plusMinutes(1));
        when(invitations.selectOne(any())).thenReturn(invitation);
        when(tenants.selectCount(any())).thenReturn(0L);
        doAnswer(call -> {
            call.<com.starsea.ai.domain.Tenant>getArgument(0).setId(2L);
            return 1;
        }).when(tenants).insert(any());
        when(users.insert(any())).thenThrow(new DuplicateKeyException("uq_app_user_username"));

        TenantRegistrationService service = new TenantRegistrationService(invitations, tenants, users,
                mock(PasswordEncoder.class), mock(AuthService.class), mock(AuthAuditLogger.class));

        AuthException exception = assertThrows(AuthException.class, () -> service.register(
                new TenantRegistrationService.RegisterRequest("code", "alice", "Strong!123", "Strong!123"), null, null));

        assertEquals(AuthErrorCode.USERNAME_CONFLICT.code(), exception.getCode());
        verify(invitations, never()).consumeIfAvailable(anyLong(), any(), anyLong(), anyLong());
    }
}
