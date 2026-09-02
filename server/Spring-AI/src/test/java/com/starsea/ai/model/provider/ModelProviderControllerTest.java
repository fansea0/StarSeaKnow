package com.starsea.ai.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModelProviderControllerTest {

    private ModelProviderService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ModelProviderService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ModelProviderController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void all_provider_management_routes_require_tenant_admin() {
        RequireRole role = ModelProviderController.class.getAnnotation(RequireRole.class);

        assertThat(role).isNotNull();
        assertThat(role.value()).isEqualTo("tenant_admin");
    }

    @Test
    void lists_safe_provider_views_without_secret_fields() throws Exception {
        when(service.listProviders()).thenReturn(List.of(providerView()));

        mockMvc.perform(get("/model-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("OpenAI"))
                .andExpect(jsonPath("$.data[0].apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data[0].apiKeyLastFour").value("1234"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("ciphertext"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("nonce"))));
    }

    @Test
    void create_response_never_echoes_submitted_api_key() throws Exception {
        when(service.createConnection(any())).thenReturn(providerView());

        mockMvc.perform(post("/model-providers/connections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"catalogProviderId":1,"apiKey":"secret-never-returned",
                                 "selectableModels":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectionId").value(30))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-never-returned"))));

        String serializedCommand = new ObjectMapper().writeValueAsString(
                new ModelProviderApiModels.ConnectionCommand(
                        1L, null, null, null, "secret-never-returned", List.of()));
        assertThat(serializedCommand).doesNotContain("secret-never-returned", "apiKey");
    }

    @Test
    void maps_model_provider_errors_to_http_status_and_stable_code() throws Exception {
        when(service.listProviders()).thenThrow(new ModelProviderException(
                502, "MODEL_PROVIDER_UNAVAILABLE", "厂商连接失败"));

        mockMvc.perform(get("/model-providers"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("MODEL_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.msg").value("厂商连接失败"));
    }

    private ModelProviderApiModels.ProviderView providerView() {
        return new ModelProviderApiModels.ProviderView(
                1L, 30L, "OPENAI", "OpenAI", "provider/openai",
                "https://api.openai.com/v1", "OPENAI_COMPATIBLE", "API_KEY",
                List.of(new ModelSuggestion("gpt-4o-mini", "GPT-4o Mini", 128000)),
                true, false, true, "1234");
    }
}
