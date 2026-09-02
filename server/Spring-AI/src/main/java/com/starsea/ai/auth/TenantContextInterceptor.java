package com.starsea.ai.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import com.starsea.ai.mapper.AppUserMapper;

import java.util.Set;

public class TenantContextInterceptor implements HandlerInterceptor {

    private final AppUserMapper users;

    public TenantContextInterceptor(AppUserMapper users) {
        this.users = users;
    }

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/auth/login", "/auth/refresh", "/auth/register",
            "/platform/auth/login", "/platform/auth/refresh", "/platform/auth/logout",
            "/error", "/auth/change-password"
    );

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String p = req.getRequestURI();
        for (String e : EXCLUDED_PREFIXES) if (p.startsWith(e)) return true;

        // 已有 AuthException 的(由 JwtAuthFilter 解析失败塞进 request attr),直接抛出
        Object pre = req.getAttribute("authException");
        if (pre instanceof AuthException ae) throw ae;

        if (AuthContext.current() == null) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "login required");
        }
        var user = AuthContext.current().getKind() == AuthContext.Kind.BUSINESS
                ? users.selectById(AuthContext.current().getUserId()) : null;
        if (user != null && Boolean.TRUE.equals(user.getMustChangePassword())
                && !"/auth/logout".equals(p)) {
            throw new AuthException(AuthErrorCode.FORBIDDEN_ROLE, "password must be changed first");
        }
        return true;
    }
}
