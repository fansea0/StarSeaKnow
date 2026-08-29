package com.starsea.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starsea.ai.domain.AppUser;
import com.starsea.ai.domain.Invite;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.InviteMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class AcceptInviteService {

    private final InviteMapper invites;
    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthAuditLogger audit;

    public AcceptInviteService(InviteMapper invites, TenantMapper tenants, AppUserMapper users,
                               PasswordEncoder encoder, JwtService jwt, RefreshTokenService refresh,
                               AuthAuditLogger audit) {
        this.invites = invites; this.tenants = tenants; this.users = users;
        this.encoder = encoder; this.jwt = jwt; this.refresh = refresh; this.audit = audit;
    }

    @Transactional
    public AuthService.LoginResult accept(String code, String password, String displayName,
                                          String ip, String ua) {
        Invite inv = invites.selectList(new QueryWrapper<Invite>().eq("code", code))
                .stream().findFirst()
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVITE_INVALID, "invite not found"));
        if (inv.getAcceptedBy() != null) {
            throw new AuthException(AuthErrorCode.INVITE_INVALID, "invite already accepted");
        }
        if (inv.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new AuthException(AuthErrorCode.INVITE_INVALID, "invite expired");
        }
        if (tenants.selectById(inv.getTenantId()) == null) {
            throw new AuthException(AuthErrorCode.INVITE_INVALID, "tenant gone");
        }

        AppUser u = new AppUser();
        u.setTenantId(inv.getTenantId());
        u.setUsername("admin"); // 第一位管理员固定 username=admin
        u.setPasswordHash(encoder.hash(password));
        u.setDisplayName(displayName);
        u.setRole(inv.getIntendedRole());
        u.setStatus(1);
        users.insert(u);

        inv.setAcceptedBy(u.getId());
        invites.updateById(inv);

        audit.inviteAccept(code, u.getId(), inv.getTenantId());

        // 手工签发(避免再次 BCrypt 校验已哈希过的密码)
        String access = jwt.signAccess(u.getId(), u.getTenantId(), u.getRole());
        var rr = refresh.issue(u.getId(), ua, ip);
        return new AuthService.LoginResult(access, rr.rawToken(), rr.expiresAt(),
                new AuthService.UserView(u.getId(), u.getTenantId(), u.getUsername(),
                        u.getDisplayName(), u.getRole()));
    }
}
