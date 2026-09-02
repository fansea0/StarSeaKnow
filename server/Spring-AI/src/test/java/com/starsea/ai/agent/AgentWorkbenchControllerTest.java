package com.starsea.ai.agent;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentWorkbenchControllerTest {

    private AgentAggregateService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentAggregateService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentWorkbenchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void controller_requires_login_and_all_mutations_require_tenant_admin() {
        assertThat(AgentWorkbenchController.class.getAnnotation(RequireLogin.class)).isNotNull();
        for (String methodName : List.of("create", "updateDraft", "delete")) {
            Method method = java.util.Arrays.stream(AgentWorkbenchController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            RequireRole role = method.getAnnotation(RequireRole.class);
            assertThat(role).isNotNull();
            assertThat(role.value()).isEqualTo("tenant_admin");
        }
    }

    @Test
    void returns_paginated_agent_list() throws Exception {
        when(service.list(any())).thenReturn(new AgentWorkbenchApiModels.AgentPage(
                List.of(new AgentWorkbenchApiModels.AgentListItem(
                        101L, "合同助手", "审阅合同", List.of("法务"), "PUBLISHED",
                        2, 3L, true, null, null, null)), 1, 20, 1));

        mockMvc.perform(get("/agents?page=1&pageSize=20&status=PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("合同助手"))
                .andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void maps_stale_draft_revision_to_stable_conflict_response() throws Exception {
        when(service.updateDraft(eq(101L), any())).thenThrow(new AgentWorkbenchException(
                409, "AGENT_DRAFT_CONFLICT", "草稿已被其他修改覆盖，请刷新后重试"));

        mockMvc.perform(put("/agents/101/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Agent","systemPrompt":"Prompt","tags":[],"variables":[],
                                 "knowledgeIds":[],"retrievalTopK":5,"retrievalScoreThreshold":0.2,
                                 "model":null,"lockVersion":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AGENT_DRAFT_CONFLICT"));
    }
}
