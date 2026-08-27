package com.fansea.ai.auth;

import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.TenantMapper;
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
    private final TenantMapper tenants;

    public JwtAuthFilter(JwtService jwtService, TenantMapper tenants) {
        this.jwtService = jwtService;
        this.tenants = tenants;
    }

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
                AuthContext.Kind kind = "smart-agent-platform".equals(c.getIssuer())
                        ? AuthContext.Kind.PLATFORM : AuthContext.Kind.BUSINESS;
                String subjectPrefix = kind == AuthContext.Kind.PLATFORM ? "pa:" : "u:";
                if (c.getSub() == null || !c.getSub().startsWith(subjectPrefix)) {
                    throw new AuthException(AuthErrorCode.MISSING_TOKEN, "invalid token subject");
                }
                if (kind == AuthContext.Kind.BUSINESS && !hasActiveTenant(c.getTenantId())) {
                    throw new AuthException(AuthErrorCode.CROSS_TENANT, "tenant is disabled or not found");
                }
                long uid = Long.parseLong(c.getSub().substring(subjectPrefix.length()));
                ctx = new AuthContext(kind, uid, c.getTenantId(), c.getRole(), c.getJti());
            } catch (AuthException e) {
                // 401 — filter 不直接写响应,交给 GlobalExceptionHandler 兜底
                req.setAttribute("authException", e);
            } catch (JwtException | IllegalArgumentException e) {
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

    private boolean hasActiveTenant(Long tenantId) {
        if (tenantId == null) {
            return false;
        }
        Tenant tenant = tenants.selectById(tenantId);
        return tenant != null && Integer.valueOf(1).equals(tenant.getStatus());
    }
}
