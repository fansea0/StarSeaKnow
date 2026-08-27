package com.fansea.ai.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fansea.ai.domain.AppUser;
import com.fansea.ai.domain.PlatformInvitation;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.PlatformInvitationMapper;
import com.fansea.ai.mapper.TenantMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Locale;

@Service
public class TenantRegistrationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformInvitationMapper invitations;
    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    private final AuthAuditLogger audit;

    public TenantRegistrationService(PlatformInvitationMapper invitations, TenantMapper tenants,
                                     AppUserMapper users, PasswordEncoder encoder, AuthService auth,
                                     AuthAuditLogger audit) {
        this.invitations = invitations;
        this.tenants = tenants;
        this.users = users;
        this.encoder = encoder;
        this.auth = auth;
        this.audit = audit;
    }

    @Transactional
    public AuthService.LoginResult register(RegisterRequest request, String ip, String userAgent) {
        validate(request);
        PlatformInvitation invitation = requireAvailableInvitation(request.inviteCode());
        if (users.selectByUsername(request.username()) != null) {
            throw new AuthException(AuthErrorCode.USERNAME_CONFLICT, "username exists");
        }

        Tenant tenant = new Tenant();
        tenant.setCode(nextTenantCode());
        tenant.setName(request.username() + " 的工作区");
        tenant.setStatus(1);
        tenants.insert(tenant);

        AppUser user = new AppUser();
        user.setTenantId(tenant.getId());
        user.setUsername(request.username());
        user.setPasswordHash(encoder.hash(request.password()));
        user.setDisplayName(request.username());
        user.setRole("tenant_admin");
        user.setStatus(1);
        try {
            users.insert(user);
        } catch (DuplicateKeyException e) {
            throw new AuthException(AuthErrorCode.USERNAME_CONFLICT, "username exists");
        }

        if (invitations.consumeIfAvailable(invitation.getId(), OffsetDateTime.now(), tenant.getId(), user.getId()) != 1) {
            throw new AuthException(AuthErrorCode.INVITATION_UNAVAILABLE, "invitation unavailable");
        }
        audit.inviteAccept(invitation.getCode(), user.getId(), tenant.getId());
        return auth.issueBusinessSession(user, ip, userAgent);
    }

    private PlatformInvitation requireAvailableInvitation(String code) {
        PlatformInvitation invitation = invitations.selectOne(new QueryWrapper<PlatformInvitation>().eq("code", code));
        OffsetDateTime now = OffsetDateTime.now();
        if (invitation == null || !"ACTIVE".equals(invitation.getStatus()) || invitation.getUsedAt() != null
                || invitation.getValidFrom().isAfter(now) || invitation.getValidUntil().isBefore(now)) {
            throw new AuthException(AuthErrorCode.INVITATION_UNAVAILABLE, "invitation unavailable");
        }
        return invitation;
    }

    private void validate(RegisterRequest request) {
        if (isBlank(request.inviteCode()) || isBlank(request.username()) || isBlank(request.password())
                || isBlank(request.confirmPassword())) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "registration fields are required");
        }
        if (!request.password().equals(request.confirmPassword())) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "password confirmation does not match");
        }
        if (request.password().length() < 8) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "password must be at least 8 characters");
        }
    }

    private String nextTenantCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = "tenant-" + Long.toUnsignedString(RANDOM.nextLong(), 36).toLowerCase(Locale.ROOT);
            if (tenants.selectCount(new QueryWrapper<Tenant>().eq("code", code)) == 0) {
                return code;
            }
        }
        throw new IllegalStateException("could not allocate tenant code");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record RegisterRequest(String inviteCode, String username, String password, String confirmPassword) { }
}
