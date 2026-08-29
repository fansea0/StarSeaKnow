package com.starsea.ai.auth;

import com.starsea.ai.domain.dto.AjaxResult;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final RefreshTokenService refresh;
    private final JwtService jwt;
    private final AuthAuditLogger audit;
    private final TenantRegistrationService registration;
    private final RefreshCookie cookies;

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}
    public record RegisterReq(@NotBlank String inviteCode, @NotBlank String username,
                              @NotBlank String password, @NotBlank String confirmPassword) {}

    @PostMapping("/login")
    public AjaxResult login(@RequestBody LoginReq req, HttpServletResponse resp) {
        AuthService.LoginResult r = auth.login(req.username(), req.password(), null, null);
        cookies.setBusiness(resp, r.refreshRaw(), false);
        return AjaxResult.success(Map.of(
                "accessToken", r.accessToken(),
                "expiresAt", r.expiresAt().toEpochMilli(),
                "user", r.user()
        ));
    }

    @PostMapping("/refresh")
    public AjaxResult refresh(@CookieValue(name = "sm_refresh", required = false) String cookie,
                              HttpServletResponse resp) {
        if (cookie == null || cookie.isBlank())
            throw new AuthException(AuthErrorCode.REFRESH_EXPIRED, "no refresh cookie");
        AuthService.RefreshResult r = auth.refresh(cookie, null, null);
        cookies.setBusiness(resp, r.newRefreshRaw(), false);
        return AjaxResult.success(Map.of(
                "accessToken", r.accessToken(),
                "expiresAt", Instant.now().plusSeconds(15 * 60).toEpochMilli()
        ));
    }

    @PostMapping("/logout")
    public AjaxResult logout(@CookieValue(name = "sm_refresh", required = false) String cookie,
                             HttpServletResponse resp) {
        auth.logout(cookie);
        cookies.setBusiness(resp, null, true);
        return AjaxResult.success();
    }

    @PostMapping("/register")
    public AjaxResult register(@RequestBody RegisterReq req, HttpServletResponse resp) {
        AuthService.LoginResult r = registration.register(
                new TenantRegistrationService.RegisterRequest(req.inviteCode(), req.username(), req.password(), req.confirmPassword()),
                null, null);
        cookies.setBusiness(resp, r.refreshRaw(), false);
        return AjaxResult.success(Map.of(
                "accessToken", r.accessToken(),
                "expiresAt", r.expiresAt().toEpochMilli(),
                "user", r.user()
        ));
    }

    @GetMapping("/me")
    @RequireLogin
    public AjaxResult me() {
        AuthContext ctx = AuthContext.current();
        var u = auth.requireUser(ctx.getUserId());
        var t = auth.requireTenant(u.getTenantId());
        return AjaxResult.success(Map.of(
                "user", AuthService.UserView.from(u),
                "tenant", Map.of("id", t.getId(), "code", t.getCode(), "name", t.getName())
        ));
    }
}
