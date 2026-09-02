package com.starsea.ai.agent.execution;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.controller.AgentController;
import com.starsea.ai.controller.AiChatController;
import com.starsea.ai.domain.Agent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LegacyAgentRetirementTest {
    @Test void old_agent_endpoints_return_gone_instead_of_bypassing_draft_permissions() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(), new AiChatController(
                        mock(com.starsea.ai.history.RepositoryHistory.class), mock(org.springframework.ai.chat.client.ChatClient.class),
                        mock(com.starsea.ai.service.RagService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/agent/list")).andExpect(status().isGone()).andExpect(jsonPath("$.errorCode").value("AGENT_LEGACY_API_RETIRED"));
        mvc.perform(put("/agent/update/101").contentType(MediaType.APPLICATION_JSON).content("{\"modelApiKey\":\"must-not-be-stored\"}"))
                .andExpect(status().isGone());
        mvc.perform(post("/ai/agent/chat?agentId=101").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"q\"}"))
                .andExpect(status().isGone());
    }

    @Test void generated_agent_selects_never_load_legacy_plaintext_connection_fields() {
        var configuration = new MybatisConfiguration();
        var assistant = new MapperBuilderAssistant(configuration, "agent-mapping-test");
        assistant.setCurrentNamespace("com.starsea.ai.mapper.AgentMapper");
        var mapping = TableInfoHelper.initTableInfo(assistant, Agent.class);
        assertThat(mapping.getAllSqlSelect()).doesNotContain("model_api_key", "model_url", "role_description")
                .contains("agent_model_id", "system_prompt");
    }
}
