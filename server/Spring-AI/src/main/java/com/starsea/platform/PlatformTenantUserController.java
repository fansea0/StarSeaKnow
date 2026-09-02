package com.starsea.platform;

import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.auth.RequireRole;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/tenants/{tenantId}/users")
@RequiredArgsConstructor
public class PlatformTenantUserController {

    private final PlatformTenantUserService users;

    @GetMapping
    @RequireRole("platform_admin")
    public AjaxResult list(@PathVariable long tenantId) {
        return AjaxResult.success(users.listUsers(tenantId));
    }

    @PostMapping("/{userId}/reset-password")
    @RequireRole("platform_admin")
    public AjaxResult resetPassword(@PathVariable long tenantId, @PathVariable long userId) {
        users.resetPassword(tenantId, userId);
        return AjaxResult.success();
    }
}
