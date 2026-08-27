package com.fangsa.ai.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/auth/login", "/auth/refresh", "/auth/accept-invite",
            "/platform/auth/login", "/platform/auth/refresh", "/platform/auth/logout"
    );

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) { this.jwtService = jwtService; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        for (String e : EXCLUDED_PREFIXES) if (p.startsWith(e)) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        AuthContext ctx = null;
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            try {
                ParsedClaims c = jwtService.verifyAccess(token);
                long uid = Long.parseLong(c.getSub().substring(2));
                AuthContext.Kind kind = "smart-agent-platform".equals(c.getIssuer())
                        ? AuthContext.Kind.PLATFORM : AuthContext.Kind.BUSINESS;
                ctx = new AuthContext(kind, uid, c.getTenantId(), c.getRole(), c.getJti());
            } catch (AuthException e) {
                // 401 — filter 不直接写响应,交给 GlobalExceptionHandler 兜底
                req.setAttribute("authException", e);
            } catch (JwtException e) {
                req.setAttribute("authException",
                        new AuthException(AuthErrorCode.MISSING_TOKEN, "invalid token"));
            }
        }
        if (ctx != null) AuthContext.set(ctx);
        try {
            chain.doFilter(req, resp);
        } finally {
            AuthContext.clear();
        }
    }
}