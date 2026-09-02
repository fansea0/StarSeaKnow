package com.starsea.ai.auth;

import com.starsea.ai.domain.AppUser;
import com.starsea.ai.domain.Tenant;
import com.starsea.ai.mapper.AppUserMapper;
import com.starsea.ai.mapper.TenantMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final TenantMapper tenants;
    private final AppUserMapper users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refresh;
    private final AuthAuditLogger audit;

    public AuthService(TenantMapper tenants, AppUserMapper users, PasswordEncoder encoder,
                       JwtService jwt, RefreshTokenService refresh, AuthAuditLogger audit) {
        this.tenants = tenants; this.users = users; this.encoder = encoder;
        this.jwt = jwt; this.refresh = refresh; this.audit = audit;
    }

    public record UserView(long id, long tenantId, String username, String displayName, String role) {
        public static UserView from(AppUser u) {
            return new UserView(u.getId(), u.getTenantId(), u.getUsername(), u.getDisplayName(), u.getRole());
        }
    }
    public record LoginResult(String accessToken, String refreshRaw, Instant expiresAt, UserView user,
                              boolean mustChangePassword) {}

    public LoginResult login(String username, String rawPwd, String ip, String ua) {
        AppUser u = users.selectByUsername(username);
        if (u == null || !Integer.valueOf(1).equals(u.getStatus())) {
            audit.loginFail("username", username, ip);
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }
        Tenant t = tenants.selectById(u.getTenantId());
        if (t == null || !Integer.valueOf(1).equals(t.getStatus())) {
            audit.loginFail("username", username, ip);
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }

        if (!encoder.matches(rawPwd, u.getPasswordHash())) {
            audit.loginFail("username", username, ip);
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }

        return issueBusinessSession(u, ip, ua);
    }

    LoginResult issueBusinessSession(AppUser u, String ip, String ua) {
        String access = jwt.signAccess(u.getId(), u.getTenantId(), u.getRole());
        RefreshTokenService.IssueResult rr = refresh.issue(u.getId(), ua, ip);
        audit.login(u.getId(), u.getTenantId(), ip, ua);
        return new LoginResult(access, rr.rawToken(), rr.expiresAt(), UserView.from(u),
                Boolean.TRUE.equals(u.getMustChangePassword()));
    }

    @Transactional
    public void changePassword(long userId, String currentPassword, String newPassword, String confirmPassword) {
        if (isBlank(currentPassword) || isBlank(newPassword) || isBlank(confirmPassword)) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "password fields are required");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "password confirmation does not match");
        }
        if (newPassword.length() < 8) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "password must be at least 8 characters");
        }
        AppUser user = users.selectById(userId);
        if (user == null || !encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }
        user.setPasswordHash(encoder.hash(newPassword));
        user.setMustChangePassword(false);
        if (users.updateById(user) != 1) {
            throw new AuthException(AuthErrorCode.REGISTRATION_INVALID, "password changed concurrently");
        }
    }

    public AppUser requireUser(long userId) {
        AppUser u = users.selectById(userId);
        if (u == null) throw new AuthException(AuthErrorCode.MISSING_TOKEN, "user not found");
        return u;
    }

    public Tenant requireTenant(long tenantId) {
        Tenant t = tenants.selectById(tenantId);
        if (t == null) throw new AuthException(AuthErrorCode.CROSS_TENANT, "tenant not found");
        return t;
    }

    public RefreshResult refresh(String rawRefresh, String ip, String ua) {
        RefreshTokenService.RotateResult rr = refresh.rotate(rawRefresh, ua, ip);
        AppUser u = users.selectByIdForRefresh(rr.userId());
        if (u == null) throw new AuthException(AuthErrorCode.REFRESH_EXPIRED, "user gone");
        String access = jwt.signAccess(u.getId(), u.getTenantId(), u.getRole());
        audit.refresh(u.getId(), rr.familyId(), ip);
        return new RefreshResult(access, rr.newRawToken(), rr.userId(), UserView.from(u),
                Boolean.TRUE.equals(u.getMustChangePassword()));
    }

    public void logout(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) return;
        String hash = sha256(rawRefresh);
        refresh.findByHash(hash).ifPresent(row -> {
            refresh.revokeFamily(row.familyId());
            audit.logout(row.userId(), row.familyId());
        });
    }

    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            var d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record RefreshResult(String accessToken, String newRefreshRaw, long userId, UserView user,
                                boolean mustChangePassword) {}

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
