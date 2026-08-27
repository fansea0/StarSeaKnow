package com.fansea.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.auth.AuthErrorCode;
import com.fansea.ai.auth.AuthException;
import com.fansea.ai.auth.AuthAuditLogger;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.Invite;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.domain.dto.AjaxResult;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.InviteMapper;
import com.fansea.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformTenantController {

    private final TenantMapper tenants;
    private final InviteMapper invites;
    private final AppUserMapper users;
    private final AuthAuditLogger audit;

    public record CreateReq(String code, String name) {}

    @PostMapping("/tenants")
    @RequireRole("platform_admin")
    @Transactional
    public AjaxResult create(@RequestBody CreateReq req) {
        Long exists = tenants.selectCount(new QueryWrapper<Tenant>().eq("code", req.code()));
        if (exists != null && exists > 0) {
            throw new AuthException(AuthErrorCode.USERNAME_CONFLICT, "tenant code exists");
        }
        Tenant t = new Tenant();
        t.setCode(req.code()); t.setName(req.name()); t.setStatus(1);
        tenants.insert(t);

        Invite inv = new Invite();
        inv.setTenantId(t.getId());
        inv.setCode(randomCode());
        inv.setIntendedRole("tenant_admin");
        inv.setExpiresAt(OffsetDateTime.now().plusDays(7));
        invites.insert(inv);

        audit.tenantCreate(t.getCode(), AuthContext.current().getUserId());
        return AjaxResult.success(Map.of(
                "tenantId", t.getId(),
                "inviteCode", inv.getCode(),
                "inviteExpiresAt", inv.getExpiresAt()));
    }

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

    private static String randomCode() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
