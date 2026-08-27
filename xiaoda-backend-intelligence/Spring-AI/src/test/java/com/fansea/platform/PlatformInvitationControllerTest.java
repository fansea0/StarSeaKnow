package com.fansea.platform;

import com.fansea.ai.auth.AuthAspect;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.auth.AuthAuditLogger;
import com.fansea.ai.auth.RefreshCookie;
import com.fansea.ai.config.GlobalExceptionHandler;
import com.fansea.ai.domain.PlatformAdmin;
import com.fansea.ai.domain.PlatformInvitation;
import com.fansea.ai.domain.Tenant;
import com.fansea.ai.mapper.PlatformAdminMapper;
import com.fansea.ai.mapper.PlatformInvitationMapper;
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

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PlatformInvitationController.class, PlatformTenantController.class, PlatformAuthController.class})
@Import({AuthAspect.class, GlobalExceptionHandler.class, PlatformInvitationController.class,
        PlatformTenantController.class, PlatformAuthController.class})
class PlatformInvitationControllerTest {

    @SpringBootConfiguration
    @EnableAspectJAutoProxy
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PlatformInvitationMapper invitations;
    @MockBean
    private TenantMapper tenants;
    @MockBean
    private PlatformAdminMapper admins;
    @MockBean
    private AuthAuditLogger audit;
    @MockBean
    private PlatformAuthService authService;
    @MockBean
    private RefreshCookie cookies;

    @BeforeEach
    void setPlatformAdminContext() {
        AuthContext.set(new AuthContext(AuthContext.Kind.PLATFORM, 1L, null, "platform_admin", "test-jti"));
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(1L);
        admin.setMustChangePassword(false);
        when(admins.selectById(1L)).thenReturn(admin);
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void invitationCreateListAndDisable_arePlatformOnly() throws Exception {
        doAnswer(invocation -> {
            PlatformInvitation invitation = invocation.getArgument(0);
            invitation.setId(10L);
            return 1;
        }).when(invitations).insert(any(PlatformInvitation.class));
        PlatformInvitation invitation = new PlatformInvitation();
        invitation.setId(10L);
        invitation.setCode("invite-code");
        invitation.setStatus("ACTIVE");
        invitation.setValidFrom(OffsetDateTime.now().minusMinutes(1));
        invitation.setValidUntil(OffsetDateTime.now().plusDays(1));
        when(invitations.selectList(any())).thenReturn(List.of(invitation));
        when(invitations.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<PlatformInvitation> page = invocation.getArgument(0);
            page.setRecords(List.of(invitation));
            page.setTotal(1L);
            return page;
        });
        when(invitations.selectById(10L)).thenReturn(invitation);
        when(invitations.update(eq(null), any())).thenReturn(1);

        mockMvc.perform(post("/platform/invitations")
                        .contentType("application/json")
                        .content("{\"validFrom\":\"2026-08-27T00:00:00Z\",\"validUntil\":\"2026-09-01T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/platform/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"));

        mockMvc.perform(post("/platform/invitations/10/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "platform_admin", "test-jti"));
        mockMvc.perform(get("/platform/invitations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    void platformAdminMustChangePassword_beforeManagementAccess() throws Exception {
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(1L);
        admin.setMustChangePassword(true);
        when(admins.selectById(1L)).thenReturn(admin);
        when(authService.changeInitialPassword(eq(1L), eq("old"), eq("Strong!123"), eq("Strong!123")))
                .thenReturn(false);

        mockMvc.perform(get("/platform/invitations"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(post("/platform/auth/change-initial-password")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"old\",\"newPassword\":\"Strong!123\",\"confirmPassword\":\"Strong!123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void platformCanUpdateTenantRemark_butTenantCannot() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        when(tenants.selectById(9L)).thenReturn(tenant);

        mockMvc.perform(patch("/platform/tenants/9/remark")
                        .contentType("application/json")
                        .content("{\"remark\":\"North region\"}"))
                .andExpect(status().isOk());

        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "tenant_admin", "test-jti"));
        mockMvc.perform(patch("/platform/tenants/9/remark")
                        .contentType("application/json")
                        .content("{\"remark\":\"nope\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }
}
