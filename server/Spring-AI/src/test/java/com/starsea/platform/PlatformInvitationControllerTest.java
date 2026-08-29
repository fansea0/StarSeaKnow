package com.starsea.platform;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starsea.ai.auth.AuthAspect;
import com.starsea.ai.auth.AuthController;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.auth.AuthAuditLogger;
import com.starsea.ai.auth.AuthService;
import com.starsea.ai.auth.JwtService;
import com.starsea.ai.auth.PasswordEncoder;
import com.starsea.ai.auth.RefreshCookie;
import com.starsea.ai.auth.RefreshTokenService;
import com.starsea.ai.auth.TenantMemberController;
import com.starsea.ai.auth.TenantRegistrationService;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.config.WebMvcConfig;
import com.starsea.ai.domain.PlatformAdmin;
import com.starsea.ai.domain.PlatformInvitation;
import com.starsea.ai.domain.Tenant;
import com.starsea.ai.mapper.PlatformAdminMapper;
import com.starsea.ai.mapper.PlatformInvitationMapper;
import com.starsea.ai.mapper.AppUserMapper;
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
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PlatformInvitationController.class, PlatformTenantController.class, PlatformAuthController.class,
        AuthController.class, TenantMemberController.class})
@Import({AuthAspect.class, GlobalExceptionHandler.class, PlatformInvitationController.class,
        PlatformTenantController.class, PlatformAuthController.class, AuthController.class, TenantMemberController.class,
        WebMvcConfig.class})
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
    @MockBean
    private AuthService businessAuthService;
    @MockBean
    private RefreshTokenService refreshTokens;
    @MockBean
    private JwtService jwt;
    @MockBean
    private TenantRegistrationService registration;
    @MockBean
    private AppUserMapper users;
    @MockBean
    private PasswordEncoder passwordEncoder;

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
    void invitationListFiltersExpiringSoonBeforeApplyingPagination() throws Exception {
        when(invitations.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<PlatformInvitation> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });

        mockMvc.perform(get("/platform/invitations?expiry=EXPIRING_SOON&page=2&pageSize=10"))
                .andExpect(status().isOk());

        ArgumentCaptor<QueryWrapper<PlatformInvitation>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(invitations).selectPage(any(), query.capture());
        assertThat(query.getValue().getExpression().getNormal().getSqlSegment())
                .contains("valid_until")
                .contains("status");
    }

    @Test
    void invitationListFiltersAvailableInvitationsByActiveAndCurrentValidityWindow() throws Exception {
        when(invitations.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<PlatformInvitation> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });

        mockMvc.perform(get("/platform/invitations?expiry=VALID"))
                .andExpect(status().isOk());

        ArgumentCaptor<QueryWrapper<PlatformInvitation>> query = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(invitations).selectPage(any(), query.capture());
        assertThat(query.getValue().getExpression().getNormal().getSqlSegment())
                .contains("status")
                .contains("valid_from")
                .contains("valid_until");
    }

    @Test
    void invitationListReturnsUsedTenantDisplayFromOneBatchLookup() throws Exception {
        PlatformInvitation invitation = new PlatformInvitation();
        invitation.setId(12L);
        invitation.setCode("used-code");
        invitation.setStatus("USED");
        invitation.setUsedTenantId(9L);
        when(invitations.selectPage(any(), any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<PlatformInvitation> page = invocation.getArgument(0);
            page.setRecords(List.of(invitation));
            page.setTotal(1L);
            return page;
        });
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        tenant.setName("海洋工作区");
        tenant.setCode("ocean");
        when(tenants.selectBatchIds(List.of(9L))).thenReturn(List.of(tenant));

        mockMvc.perform(get("/platform/invitations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].usedTenant.name").value("海洋工作区"))
                .andExpect(jsonPath("$.data.items[0].usedTenant.code").value("ocean"));

        verify(tenants).selectBatchIds(List.of(9L));
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
    void platformAdminMustChangePassword_blocksAllRequireLoginEndpoints_butNotBusinessUsers() throws Exception {
        PlatformAdmin admin = new PlatformAdmin();
        admin.setId(1L);
        admin.setMustChangePassword(true);
        when(admins.selectById(1L)).thenReturn(admin);

        mockMvc.perform(get("/tenant/members"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "tenant_member", "test-jti"));
        when(users.selectList(any())).thenReturn(List.of());
        mockMvc.perform(get("/tenant/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void platformTokenCannotAccessBusinessRequireLoginEndpoint_butBusinessUserCan() throws Exception {
        mockMvc.perform(get("/tenant/members"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));

        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 2L, 1L, "tenant_member", "test-jti"));
        when(users.selectList(any())).thenReturn(List.of());
        mockMvc.perform(get("/tenant/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void patchPreflight_isAllowedForConfiguredFrontendOrigin() throws Exception {
        mockMvc.perform(options("/platform/tenants/9/remark")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "PATCH"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
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
