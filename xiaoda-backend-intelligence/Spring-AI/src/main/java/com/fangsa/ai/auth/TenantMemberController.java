package com.fangsa.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.domain.AppUser;
import com.fanseA.ai.domain.dto.AjaxResult;
import com.fangsa.ai.mapper.AppUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tenant/members")
@RequiredArgsConstructor
public class TenantMemberController {

    private final AppUserMapper users;
    private final PasswordEncoder encoder;

    public record CreateReq(String username, String displayName, String role) {}
    public record RoleReq(String role) {}

    @GetMapping
    @RequireLogin
    public AjaxResult list() {
        Long tid = AuthContext.current().getTenantId();
        if (tid == null) {
            // platform admin reaching a tenant-scoped list — return empty rather than crash.
            // platform admins use /platform/* for cross-tenant operations, not this endpoint.
            return AjaxResult.success(java.util.List.of());
        }
        List<AppUser> all = users.selectList(new QueryWrapper<AppUser>().eq("tenant_id", tid));
        return AjaxResult.success(all.stream().map(u -> Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
                "role", u.getRole(),
                "status", u.getStatus(),
                "lastLoginAt", u.getLastLoginAt()
        )).toList());
    }

    @PostMapping
    @RequireRole("tenant_admin")
    public AjaxResult create(@RequestBody CreateReq req) {
        long tid = AuthContext.current().getTenantId();
        Long exists = users.selectCount(new QueryWrapper<AppUser>()
                .eq("tenant_id", tid).eq("username", req.username()));
        if (exists != null && exists > 0) {
            throw new AuthException(AuthErrorCode.USERNAME_CONFLICT, "username exists");
        }
        String pwd = randomPassword();
        AppUser u = new AppUser();
        u.setTenantId(tid);
        u.setUsername(req.username());
        u.setDisplayName(req.displayName());
        u.setRole(req.role() == null ? "tenant_member" : req.role());
        u.setPasswordHash(encoder.hash(pwd));
        u.setStatus(1);
        users.insert(u);
        return AjaxResult.success(Map.of("id", u.getId(), "tempPassword", pwd));
    }

    @PutMapping("/{id}/role")
    @RequireRole("tenant_admin")
    public AjaxResult setRole(@PathVariable Long id, @RequestBody RoleReq req) {
        AppUser u = users.selectById(id);
        if (u == null || !u.getTenantId().equals(AuthContext.current().getTenantId()))
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "not in your tenant");
        u.setRole(req.role());
        users.updateById(u);
        return AjaxResult.success();
    }

    @PostMapping("/{id}/disable")
    @RequireRole("tenant_admin")
    public AjaxResult disable(@PathVariable Long id) {
        AppUser u = users.selectById(id);
        if (u == null || !u.getTenantId().equals(AuthContext.current().getTenantId()))
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "not in your tenant");
        u.setStatus(0);
        users.updateById(u);
        return AjaxResult.success();
    }

    @PostMapping("/{id}/reset-password")
    @RequireRole("tenant_admin")
    public AjaxResult resetPwd(@PathVariable Long id) {
        AppUser u = users.selectById(id);
        if (u == null || !u.getTenantId().equals(AuthContext.current().getTenantId()))
            throw new AuthException(AuthErrorCode.CROSS_TENANT, "not in your tenant");
        String pwd = randomPassword();
        u.setPasswordHash(encoder.hash(pwd));
        users.updateById(u);
        return AjaxResult.success(Map.of("tempPassword", pwd));
    }

    private static String randomPassword() {
        byte[] buf = new byte[12];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}