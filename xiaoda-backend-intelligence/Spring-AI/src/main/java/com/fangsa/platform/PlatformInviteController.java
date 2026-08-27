package com.fangsa.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.auth.RequireRole;
import com.fangsa.ai.domain.Invite;
import com.fangsa.ai.domain.Tenant;
import com.fanseA.ai.domain.dto.AjaxResult;
import com.fangsa.ai.mapper.InviteMapper;
import com.fangsa.ai.mapper.TenantMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/platform/invites")
@RequiredArgsConstructor
public class PlatformInviteController {

    private final InviteMapper invites;
    private final TenantMapper tenants;

    @GetMapping("/{code}")
    @RequireRole("platform_admin")
    public AjaxResult get(@PathVariable String code) {
        Invite inv = invites.selectList(new QueryWrapper<Invite>().eq("code", code))
                .stream().findFirst().orElse(null);
        if (inv == null) return AjaxResult.success(java.util.Map.of("code", code, "accepted", false));
        Tenant t = tenants.selectById(inv.getTenantId());
        return AjaxResult.success(java.util.Map.of(
                "code", inv.getCode(),
                "tenantId", inv.getTenantId(),
                "tenantName", t == null ? "" : t.getName(),
                "expiresAt", inv.getExpiresAt(),
                "accepted", inv.getAcceptedBy() != null
        ));
    }
}