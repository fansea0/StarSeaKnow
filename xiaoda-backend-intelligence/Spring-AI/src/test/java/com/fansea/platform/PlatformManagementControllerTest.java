package com.fansea.platform;

import com.fansea.ai.auth.AuthAspect;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.config.GlobalExceptionHandler;
import com.fansea.ai.auth.AuthAuditLogger;
import com.fansea.ai.domain.Invite;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.InviteMapper;
import com.fansea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PlatformOverviewController.class, PlatformTenantController.class})
@Import({AuthAspect.class, GlobalExceptionHandler.class, PlatformOverviewController.class, PlatformTenantController.class})
class PlatformManagementControllerTest {

    @SpringBootConfiguration
    @EnableAspectJAutoProxy
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantMapper tenants;

    @MockBean
    private AppUserMapper users;

    @MockBean
    private InviteMapper invites;

    @MockBean
    private AuthAuditLogger audit;

    @BeforeEach
    void setPlatformAdminContext() {
        AuthContext.set(new AuthContext(AuthContext.Kind.PLATFORM, 1L, null, "platform_admin", "test-jti"));
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void overview_returnsCountsForPlatformAdmin() throws Exception {
        when(tenants.selectCount(any())).thenAnswer(invocation ->
                invocation.getArgument(0) == null ? 3L : 2L);
        when(users.selectCount(any())).thenReturn(5L);

        mockMvc.perform(get("/platform/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantTotal").value(3))
                .andExpect(jsonPath("$.data.tenantActive").value(2))
                .andExpect(jsonPath("$.data.tenantDisabled").value(1))
                .andExpect(jsonPath("$.data.userTotal").value(5));
    }

    @Test
    void overview_rejectsTenantAdmin() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "tenant_admin", "test-jti"));

        mockMvc.perform(get("/platform/overview"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void listTenants_filtersAndPaginates() throws Exception {
        Tenant acme = new Tenant();
        acme.setId(10L);
        acme.setCode("acme");
        acme.setName("ACME");
        acme.setStatus(1);

        when(tenants.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<Tenant> page = invocation.getArgument(0);
            page.setRecords(java.util.List.of(acme));
            page.setTotal(1L);
            return page;
        });

        mockMvc.perform(get("/platform/tenants")
                        .param("page", "2")
                        .param("pageSize", "5")
                        .param("keyword", "acme")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].code").value("acme"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void createTenant_returnsInviteExpiration() throws Exception {
        when(tenants.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            Tenant tenant = invocation.getArgument(0);
            tenant.setId(12L);
            return 1;
        }).when(tenants).insert(any(Tenant.class));
        doAnswer(invocation -> {
            Invite invite = invocation.getArgument(0);
            invite.setId(13L);
            return 1;
        }).when(invites).insert(any(Invite.class));

        mockMvc.perform(post("/platform/tenants")
                        .contentType("application/json")
                        .content("{\"code\":\"acme\",\"name\":\"ACME\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value(12))
                .andExpect(jsonPath("$.data.inviteCode").isNotEmpty())
                .andExpect(jsonPath("$.data.inviteExpiresAt").isNotEmpty());
    }

    @Test
    void disableUnknownTenant_returnsDomainError() throws Exception {
        when(tenants.selectById(eq(999999L))).thenReturn(null);

        mockMvc.perform(post("/platform/tenants/999999/disable"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void listTenants_rejectsTenantAdmin() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "tenant_admin", "test-jti"));

        mockMvc.perform(get("/platform/tenants"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }
}
