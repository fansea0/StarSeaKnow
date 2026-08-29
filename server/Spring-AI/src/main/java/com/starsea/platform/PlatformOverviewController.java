package com.starsea.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.Tenant;
import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.TenantMapper;
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
