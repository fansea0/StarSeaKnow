package com.starsea.platform;

import com.starsea.ai.auth.AuthErrorCode;
import com.starsea.ai.auth.AuthException;
import com.starsea.ai.domain.AppUser;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PlatformTenantUserService {

    private final TenantMapper tenants;
    private final AppUserMapper users;

    public PlatformTenantUserService(TenantMapper tenants, AppUserMapper users) {
        this.tenants = tenants;
        this.users = users;
    }

    public List<UserView> listUsers(long tenantId) {
        requireTenant(tenantId);
        return users.selectByTenantIdForPlatform(tenantId)
                .stream().map(UserView::from).toList();
    }

    @Transactional
    public void resetPassword(long tenantId, long userId) {
        requireTenant(tenantId);
        AppUser user = users.selectByIdForPlatform(userId);
        if (user == null || !Long.valueOf(tenantId).equals(user.getTenantId())) {
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "user not in tenant");
        }
        if (users.markMustChangePasswordForPlatform(userId, tenantId) != 1) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "user password reset changed concurrently");
        }
    }

    private void requireTenant(long tenantId) {
        if (tenants.selectById(tenantId) == null) {
            throw new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "tenant not found");
        }
    }

    public record UserView(long id, String username, String displayName, String role,
                           Integer status, boolean mustChangePassword) {
        static UserView from(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    user.getStatus(), Boolean.TRUE.equals(user.getMustChangePassword()));
        }
    }
}
