package com.fansea.platform;

import com.fansea.ai.auth.AuthAspect;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.mapper.AppUserMapper;
import com.fansea.ai.mapper.TenantMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlatformOverviewController.class)
@Import({AuthAspect.class, PlatformOverviewController.class})
class PlatformManagementControllerTest {

    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantMapper tenants;

    @MockBean
    private AppUserMapper users;

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
}
