package com.fansea.platform;

import com.fansea.ai.auth.AuthAspect;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.config.GlobalExceptionHandler;
import com.fansea.ai.auth.AuthAuditLogger;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.TenantMapper;
import com.fansea.ai.tenant.TenantStatusChangeNotifier;
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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    private TenantStatusChangeNotifier tenantStatusChanges;

    @MockBean
    private com.fansea.ai.mapper.PlatformAdminMapper platformAdmins;

    @BeforeEach
    void setPlatformAdminContext() {
        AuthContext.set(new AuthContext(AuthContext.Kind.PLATFORM, 1L, null, "platform_admin", "test-jti"));
        com.fansea.ai.domain.PlatformAdmin admin = new com.fansea.ai.domain.PlatformAdmin();
        admin.setId(1L);
        admin.setMustChangePassword(false);
        when(platformAdmins.selectById(1L)).thenReturn(admin);
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
    void disableUnknownTenant_returnsDomainError() throws Exception {
        when(tenants.selectById(eq(999999L))).thenReturn(null);

        mockMvc.perform(post("/platform/tenants/999999/disable"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void disablingTenantNotifiesDerivedSecurityState() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setStatus(1);
        when(tenants.selectById(10L)).thenReturn(tenant);
        when(tenants.updateById(tenant)).thenReturn(1);

        mockMvc.perform(post("/platform/tenants/10/disable"))
                .andExpect(status().isOk());

        verify(tenants).updateById(tenant);
        verify(tenantStatusChanges).statusChanged(10L);
    }

    @Test
    void disablingTenantRejectsAConcurrentMissingUpdateAndDoesNotNotify() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setStatus(1);
        when(tenants.selectById(10L)).thenReturn(tenant);
        when(tenants.updateById(tenant)).thenReturn(0);

        mockMvc.perform(post("/platform/tenants/10/disable"))
                .andExpect(status().isBadRequest());

        verify(tenantStatusChanges, never()).statusChanged(10L);
    }

    @Test
    void notifierFailureDoesNotFailAnAlreadySuccessfulTenantDisable() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(10L);
        tenant.setStatus(1);
        when(tenants.selectById(10L)).thenReturn(tenant);
        when(tenants.updateById(tenant)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("cache unavailable"))
                .when(tenantStatusChanges).statusChanged(10L);

        mockMvc.perform(post("/platform/tenants/10/disable"))
                .andExpect(status().isOk());

        verify(tenants).updateById(tenant);
    }

    @Test
    void listTenants_rejectsTenantAdmin() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "tenant_admin", "test-jti"));

        mockMvc.perform(get("/platform/tenants"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }
}
