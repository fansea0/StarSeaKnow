package com.fansea.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fansea.ai.auth.AuthErrorCode;
import com.fansea.ai.auth.AuthException;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.domain.dto.AjaxResult;
import com.fansea.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final TenantMapper tenants;
    public record UpdateRemarkReq(String remark) {}

    @GetMapping("/tenants")
    @RequireRole("platform_admin")
    public AjaxResult list(@RequestParam(defaultValue = "1") long page,
                           @RequestParam(defaultValue = "20") long pageSize,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Integer status) {
        QueryWrapper<Tenant> query = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            query.and(q -> q.like("code", keyword).or().like("name", keyword));
        }
        if (status != null) {
            query.eq("status", status);
        }
        Page<Tenant> result = tenants.selectPage(new Page<>(page, pageSize), query);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.getRecords());
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("total", result.getTotal());
        return AjaxResult.success(data);
    }

    @PostMapping("/tenants/{id}/disable")
    @RequireRole("platform_admin")
    public AjaxResult disable(@PathVariable Long id) {
        Tenant t = tenants.selectById(id);
        if (t == null) {
            throw new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "tenant not found");
        }
        t.setStatus(0);
        tenants.updateById(t);
        return AjaxResult.success();
    }

    @PatchMapping("/tenants/{id}/remark")
    @RequireRole("platform_admin")
    public AjaxResult updateRemark(@PathVariable Long id, @RequestBody UpdateRemarkReq req) {
        if (req.remark() != null && req.remark().length() > 512) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "remark is too long");
        }
        Tenant tenant = tenants.selectById(id);
        if (tenant == null) {
            throw new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "tenant not found");
        }
        tenant.setRemark(req.remark());
        tenants.updateById(tenant);
        return AjaxResult.success();
    }
}
