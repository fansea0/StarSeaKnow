package com.starsea.platform;

import com.starsea.ai.domain.AppUser;
import com.starsea.ai.domain.Tenant;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformTenantUserServiceTest {

    @Test
    void resetMarksOnlyUserInRequestedTenantWithoutGeneratingPassword() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PlatformTenantUserService service = new PlatformTenantUserService(tenants, users);
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        AppUser user = new AppUser();
        user.setId(9L);
        user.setTenantId(7L);
        user.setPasswordHash("existing-hash");
        when(tenants.selectById(7L)).thenReturn(tenant);
        when(users.selectByIdForPlatform(9L)).thenReturn(user);
        when(users.markMustChangePasswordForPlatform(9L, 7L)).thenReturn(1);

        service.resetPassword(7L, 9L);

        assertEquals("existing-hash", user.getPasswordHash());
        verify(users).markMustChangePasswordForPlatform(9L, 7L);
        verify(users, never()).updateById(any());
    }

    @Test
    void listReturnsUsersBelongingToRequestedTenant() {
        TenantMapper tenants = mock(TenantMapper.class);
        AppUserMapper users = mock(AppUserMapper.class);
        PlatformTenantUserService service = new PlatformTenantUserService(tenants, users);
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        AppUser user = new AppUser();
        user.setId(9L);
        user.setTenantId(7L);
        user.setUsername("root");
        when(tenants.selectById(7L)).thenReturn(tenant);
        when(users.selectByTenantIdForPlatform(7L)).thenReturn(List.of(user));

        var result = service.listUsers(7L);

        assertEquals(1, result.size());
        assertEquals("root", result.get(0).username());
        verify(users).selectByTenantIdForPlatform(7L);
    }
}
