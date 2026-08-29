package com.fansea.ai.openapi.credential;

import com.fansea.ai.auth.AuthAspect;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.config.GlobalExceptionHandler;
import com.fansea.ai.mapper.PlatformAdminMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TenantApiCredentialController.class)
@Import({AuthAspect.class, GlobalExceptionHandler.class, TenantApiCredentialController.class})
class TenantApiCredentialControllerTest {

    private static final UUID CREDENTIAL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID KNOWLEDGE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String RAW_KEY = "rag_test_abcdefghijklmnop.abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO";

    @SpringBootConfiguration
    @EnableAspectJAutoProxy
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private ApiCredentialService service;
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
    void tenantMemberCannotManageCredentials() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 8L, 22L, "tenant_member", "tenant-jti"));

        mockMvc.perform(get("/tenant/api-credentials"))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any());
    }

    @Test
    void externalApiCredentialCannotManageCredentials() throws Exception {
        ApiCredentialResolver.ResolvedCredential external = new ApiCredentialResolver.ResolvedCredential(
                41L, 22L, CredentialType.RAG_RETRIEVAL, "test", "active", null, List.of(),
                60, 10, 5, 1L, new RagKnowledgeScopeSnapshot(Set.of()));
        AuthContext.set(AuthContext.external(external));

        mockMvc.perform(get("/tenant/api-credentials"))
                .andExpect(status().isForbidden());

        verify(service, never()).list(any());
    }

    @Test
    void tenantAdminCreatesCredentialAndReceivesSecretOnceWithNoStore() throws Exception {
        when(service.create(any(), any())).thenReturn(new ApiCredentialService.CreatedCredential(view(), RAW_KEY));

        mockMvc.perform(post("/tenant/api-credentials")
                        .contentType("application/json")
                        .content("""
                                {"name":"客服检索","credentialType":"RAG_RETRIEVAL","environment":"test",
                                 "knowledgeIds":["10000000-0000-0000-0000-000000000001"],
                                 "allowedIpCidrs":["10.0.0.0/24"],"requestsPerMinute":60,
                                 "burstCapacity":10,"maxConcurrency":5,"expiresAt":"2030-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.apiKey").value(RAW_KEY))
                .andExpect(jsonPath("$.data.credential.id").value(CREDENTIAL_ID.toString()))
                .andExpect(jsonPath("$.data.credential.secretDigest").doesNotExist())
                .andExpect(jsonPath("$.data.credential.pepperVersion").doesNotExist());
    }

    @Test
    void invalidCreateDtoReturnsBadRequestInsteadOfA200ErrorEnvelope() throws Exception {
        mockMvc.perform(post("/tenant/api-credentials")
                        .contentType("application/json")
                        .content("""
                                {"credentialType":"RAG_RETRIEVAL","environment":"test","knowledgeIds":[],
                                 "allowedIpCidrs":[],"requestsPerMinute":60,"burstCapacity":10,"maxConcurrency":5}
                                """))
                .andExpect(status().isBadRequest());

        verify(service, never()).create(any(), any());
    }

    @Test
    void missingCreateBodyReturnsNonLeakyBadRequest() throws Exception {
        mockMvc.perform(post("/tenant/api-credentials")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("invalid credential management request"));

        verify(service, never()).create(any(), any());
    }

    @Test
    void malformedCreateJsonReturnsNonLeakyBadRequest() throws Exception {
        mockMvc.perform(post("/tenant/api-credentials")
                        .contentType("application/json")
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("invalid credential management request"));

        verify(service, never()).create(any(), any());
    }

    @Test
    void invalidCredentialUuidReturnsNonLeakyBadRequest() throws Exception {
        mockMvc.perform(get("/tenant/api-credentials/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("invalid credential management request"));

        verify(service, never()).get(any(), any());
    }

    @Test
    void rotationReturnsNewSecretWithNoStore() throws Exception {
        when(service.rotate(any(), any())).thenReturn(new ApiCredentialService.RotatedCredential(view(), RAW_KEY));

        mockMvc.perform(post("/tenant/api-credentials/{credentialId}/rotate", CREDENTIAL_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.data.apiKey").value(RAW_KEY));
    }

    @Test
    void listAndDetailNeverExposeSecretsOrRawKey() throws Exception {
        when(service.list(any())).thenReturn(List.of(view()));
        when(service.get(any(), any())).thenReturn(view());

        String listBody = mockMvc.perform(get("/tenant/api-credentials"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String detailBody = mockMvc.perform(get("/tenant/api-credentials/{credentialId}", CREDENTIAL_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(listBody).doesNotContain("secretDigest", "pepperVersion", "apiKey", RAW_KEY);
        assertThat(detailBody).doesNotContain("secretDigest", "pepperVersion", "apiKey", RAW_KEY);
    }

    @Test
    void tenantAdminCanIdempotentlySoftDeleteCredential() throws Exception {
        mockMvc.perform(delete("/tenant/api-credentials/{credentialId}", CREDENTIAL_ID))
                .andExpect(status().isNoContent());

        verify(service).delete(CREDENTIAL_ID, AuthContext.current());
    }

    @Test
    void patchCannotChangeCredentialType() throws Exception {
        mockMvc.perform(patch("/tenant/api-credentials/{credentialId}", CREDENTIAL_ID)
                        .contentType("application/json")
                        .content("{\"credentialType\":\"AGENT_INVOKE\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any(), any());
    }

    @Test
    void patchRejectsEveryUnknownField() throws Exception {
        mockMvc.perform(patch("/tenant/api-credentials/{credentialId}", CREDENTIAL_ID)
                        .contentType("application/json")
                        .content("{\"unexpected\":true}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).update(any(), any(), any());
    }

    @Test
    void patchCarriesStatusAndExpiryPresenceWithoutAmbiguity() throws Exception {
        when(service.update(any(), any(), any())).thenReturn(view());

        mockMvc.perform(patch("/tenant/api-credentials/{credentialId}", CREDENTIAL_ID)
                        .contentType("application/json")
                        .content("{\"status\":\"disabled\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/tenant/api-credentials/{credentialId}", CREDENTIAL_ID)
                        .contentType("application/json")
                        .content("{\"expiresAt\":null}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ApiCredentialService.UpdateCredentialCommand> commands =
                ArgumentCaptor.forClass(ApiCredentialService.UpdateCredentialCommand.class);
        verify(service, org.mockito.Mockito.times(2)).update(any(), commands.capture(), any());
        assertThat(commands.getAllValues().get(0).status()).isEqualTo("disabled");
        assertThat(commands.getAllValues().get(0).expiresAtPresent()).isFalse();
        assertThat(commands.getAllValues().get(1).expiresAtPresent()).isTrue();
        assertThat(commands.getAllValues().get(1).expiresAt()).isNull();
    }

    private ApiCredentialService.ApiCredentialView view() {
        return new ApiCredentialService.ApiCredentialView(
                CREDENTIAL_ID, "客服检索", "RAG_RETRIEVAL", null, "test", "active", null,
                List.of("10.0.0.0/24"), 60, 10, 5, 1L, "rag_test_abcdef", "WXYZ",
                Instant.parse("2028-01-01T00:00:00Z"), null, null, null, Set.of(KNOWLEDGE_ID));
    }
}
