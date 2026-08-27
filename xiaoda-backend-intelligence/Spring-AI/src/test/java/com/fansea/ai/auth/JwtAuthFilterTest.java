package com.fansea.ai.auth;

import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void disabledTenant_rejectsExistingBusinessAccessToken() throws Exception {
        JwtService jwtService = new JwtService("test-secret-that-is-at-least-thirty-two-bytes", 60_000);
        TenantMapper tenants = mock(TenantMapper.class);
        Tenant disabled = new Tenant();
        disabled.setId(7L);
        disabled.setStatus(0);
        when(tenants.selectById(7L)).thenReturn(disabled);
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, tenants);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agent/list");
        request.addHeader("Authorization", "Bearer " + jwtService.signAccess(11L, 7L, "tenant_admin"));
        AtomicReference<AuthContext> contextSeenByChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, resp) -> contextSeenByChain.set(AuthContext.current()));

        AuthException exception = (AuthException) request.getAttribute("authException");
        assertNull(contextSeenByChain.get());
        assertEquals(AuthErrorCode.CROSS_TENANT.code(), exception.getCode());
    }
}
