package com.fansea.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.auth.AuthErrorCode;
import com.fansea.ai.auth.AuthException;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.PlatformInvitation;
import com.fansea.ai.domain.dto.AjaxResult;
import com.fansea.ai.mapper.PlatformInvitationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/platform/invitations")
@RequiredArgsConstructor
public class PlatformInvitationController {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformInvitationMapper invitations;

    public record CreateInvitationReq(OffsetDateTime validFrom, OffsetDateTime validUntil) {
    }

    @PostMapping
    @RequireRole("platform_admin")
    public AjaxResult create(@RequestBody CreateInvitationReq req) {
        if (req.validFrom() == null || req.validUntil() == null || !req.validUntil().isAfter(req.validFrom())) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "validUntil must be after validFrom");
        }
        PlatformInvitation invitation = new PlatformInvitation();
        invitation.setCode(randomCode());
        invitation.setStatus("ACTIVE");
        invitation.setValidFrom(req.validFrom());
        invitation.setValidUntil(req.validUntil());
        invitation.setCreatedBy(AuthContext.current().getUserId());
        try {
            invitations.insert(invitation);
        } catch (DuplicateKeyException e) {
            // The code is random and unique by schema; a collision is retryable by the caller.
            throw new AuthException(AuthErrorCode.INVITATION_UNAVAILABLE, "could not create invitation");
        }
        return AjaxResult.success(invitation);
    }

    @GetMapping
    @RequireRole("platform_admin")
    public AjaxResult list(@RequestParam(defaultValue = "1") long page,
                           @RequestParam(defaultValue = "20") long pageSize,
                           @RequestParam(required = false) String status) {
        QueryWrapper<PlatformInvitation> query = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            query.eq("status", status);
        }
        query.orderByDesc("create_time");
        Page<PlatformInvitation> result = invitations.selectPage(new Page<>(page, pageSize), query);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.getRecords());
        data.put("page", page);
        data.put("pageSize", pageSize);
        data.put("total", result.getTotal());
        return AjaxResult.success(data);
    }

    @PostMapping("/{id}/disable")
    @RequireRole("platform_admin")
    public AjaxResult disable(@PathVariable long id) {
        PlatformInvitation invitation = invitations.selectById(id);
        if (invitation == null) {
            throw new AuthException(AuthErrorCode.TENANT_NOT_FOUND, "invitation not found");
        }
        if ("USED".equals(invitation.getStatus())) {
            throw new AuthException(AuthErrorCode.INVITATION_ALREADY_USED, "used invitation cannot be disabled");
        }
        if ("ACTIVE".equals(invitation.getStatus())) {
            int changed = invitations.update(null, new UpdateWrapper<PlatformInvitation>()
                    .eq("id", id)
                    .eq("status", "ACTIVE")
                    .set("status", "DISABLED"));
            if (changed == 0) {
                PlatformInvitation current = invitations.selectById(id);
                if (current != null && "USED".equals(current.getStatus())) {
                    throw new AuthException(AuthErrorCode.INVITATION_ALREADY_USED, "used invitation cannot be disabled");
                }
            }
        }
        return AjaxResult.success();
    }

    private static String randomCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
