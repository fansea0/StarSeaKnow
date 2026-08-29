package com.fansea.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.domain.dto.AjaxResult;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformOverviewController {

    private final TenantMapper tenants;
    private final AppUserMapper users;

    @GetMapping("/overview")
    @RequireRole("platform_admin")
    public AjaxResult overview() {
        long total = tenants.selectCount(null);
        long active = tenants.selectCount(new QueryWrapper<Tenant>().eq("status", 1));
        return AjaxResult.success(Map.of(
                "tenantTotal", total,
                "tenantActive", active,
                "tenantDisabled", total - active,
                "userTotal", users.selectCount(null)
        ));
    }
}
