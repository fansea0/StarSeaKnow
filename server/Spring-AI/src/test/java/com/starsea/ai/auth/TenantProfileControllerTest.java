package com.starsea.ai.auth;

import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.domain.Tenant;
import com.starsea.ai.mapper.PlatformAdminMapper;
import com.starsea.ai.mapper.TenantMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantProfileController.class)
@Import({AuthAspect.class, GlobalExceptionHandler.class, TenantProfileController.class})
class TenantProfileControllerTest {

    @SpringBootConfiguration
    @EnableAspectJAutoProxy
    static class TestApplication {}

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private TenantMapper tenants;
    @MockBean
    private PlatformAdminMapper platformAdmins;

    @BeforeEach
    void setTenantAdminContext() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 7L, 22L, "tenant_admin", "tenant-jti"));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void tenantAdminRenamesOnlyTheTenantFromItsAuthenticatedContext() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(22L);
        tenant.setCode("ocean");
        tenant.setName("旧工作区");
        when(tenants.selectById(22L)).thenReturn(tenant);

        mockMvc.perform(patch("/tenant/profile")
                        .contentType("application/json")
                        .content("{\"name\":\"海洋智能体\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(22))
                .andExpect(jsonPath("$.data.name").value("海洋智能体"))
                .andExpect(jsonPath("$.data.code").doesNotExist());

        verify(tenants).selectById(22L);
        verify(tenants).updateById(tenant);
    }

    @Test
    void tenantProfileRejectsBlankNameBeforeMutatingTenant() throws Exception {
        mockMvc.perform(patch("/tenant/profile")
                        .contentType("application/json")
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(tenants, org.mockito.Mockito.never()).updateById(any());
    }
}
