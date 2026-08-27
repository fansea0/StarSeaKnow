package com.fangsa.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fangsa.ai.auth.AuthAuditLogger;
import com.fangsa.ai.auth.AuthErrorCode;
import com.fangsa.ai.auth.AuthException;
import com.fangsa.ai.auth.JwtService;
import com.fangsa.ai.auth.PasswordEncoder;
import com.fangsa.ai.auth.RefreshTokenService;
import com.fangsa.ai.domain.PlatformAdmin;
import com.fangsa.ai.mapper.PlatformAdminMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class PlatformAuthService {

    private final PlatformAdminMapper admins;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    @SuppressWarnings("unused")
    private final RefreshTokenService refresh;
    private final AuthAuditLogger audit;

    public PlatformAuthService(PlatformAdminMapper admins, PasswordEncoder encoder, JwtService jwt,
                               RefreshTokenService refresh, AuthAuditLogger audit) {
        this.admins = admins;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refresh = refresh;
        this.audit = audit;
    }

    public record PlatformLoginResult(String accessToken, String refreshRaw, Instant expiresAt) {}

    public PlatformLoginResult login(String username, String rawPwd, String ip, String ua) {
        PlatformAdmin a = admins.selectList(new QueryWrapper<PlatformAdmin>().eq("username", username))
                .stream().findFirst()
                .orElseThrow(() -> new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials"));
        if (!encoder.matches(rawPwd, a.getPasswordHash())) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "bad credentials");
        }
        // refresh_token.user_id 强引用 app_user.id,平台管理员不写入 refresh 表;
        // 平台 su 重新登录即可,前端 cookie sm_platform_refresh 设置 Max-Age=0 提示浏览器清掉。
        String access = jwt.signPlatformAccess(a.getId());
        audit.login(a.getId(), null, ip, ua);
        return new PlatformLoginResult(access, null, Instant.now().plusSeconds(15 * 60));
    }
}