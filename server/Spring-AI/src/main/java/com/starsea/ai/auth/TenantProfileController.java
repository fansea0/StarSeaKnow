package com.starsea.ai.auth;

import com.starsea.ai.domain.Tenant;
import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/tenant/profile")
@RequiredArgsConstructor
public class TenantProfileController {

    private final TenantMapper tenants;

    public record UpdateTenantProfileReq(String name) {}

    @PatchMapping
    @RequireRole("tenant_admin")
    public AjaxResult update(@RequestBody UpdateTenantProfileReq req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "tenant name must be between 1 and 64 characters");
        }
        Long tenantId = AuthContext.current().getTenantId();
        if (tenantId == null) {
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "tenant context required");
        }
        Tenant tenant = tenants.selectById(tenantId);
        if (tenant == null) {
            throw new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "tenant not found");
        }
        tenant.setName(name);
        tenants.updateById(tenant);
        return AjaxResult.success(Map.of("id", tenant.getId(), "name", tenant.getName()));
    }
}
