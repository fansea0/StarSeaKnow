package com.fansea.ai.auth;

import com.fansea.ai.domain.AppUser;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.TenantMapper;
import org.springframework.stereotype.Service;

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
    public record LoginResult(String accessToken, String refreshRaw, Instant expiresAt, UserView user) {}

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
        return new LoginResult(access, rr.rawToken(), rr.expiresAt(), UserView.from(u));
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
        return new RefreshResult(access, rr.newRawToken(), rr.userId());
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

    public record RefreshResult(String accessToken, String newRefreshRaw, long userId) {}
}
