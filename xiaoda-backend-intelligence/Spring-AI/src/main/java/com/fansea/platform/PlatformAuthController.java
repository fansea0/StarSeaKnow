package com.fansea.platform;

import com.fansea.ai.auth.RefreshCookie;
import com.fansea.ai.domain.dto.AjaxResult;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/platform/auth")
@RequiredArgsConstructor
public class PlatformAuthController {

    private final PlatformAuthService svc;
    private final RefreshCookie cookies;

    public record LoginReq(String username, String password) {}

    @PostMapping("/login")
    public AjaxResult login(@RequestBody LoginReq req, HttpServletResponse resp) {
        PlatformAuthService.PlatformLoginResult r = svc.login(req.username(), req.password(), null, null);
        cookies.setPlatform(resp, "", true);
        return AjaxResult.success(Map.of(
                "accessToken", r.accessToken(),
                "expiresAt", r.expiresAt().toEpochMilli()
        ));
    }

    @PostMapping("/logout")
    public AjaxResult logout(HttpServletResponse resp) {
        cookies.setPlatform(resp, "", true);
        return AjaxResult.success();
    }
}
