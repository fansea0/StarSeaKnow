package com.starsea.ai.controller;

import com.starsea.ai.domain.Agent;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.history.RepositoryHistory;
import com.starsea.ai.model.AgentChatClientFactory;
import com.starsea.ai.service.AgentRagContextService;
import com.starsea.ai.service.AgentService;
import com.starsea.ai.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiChatControllerTest {

    private MockMvc mockMvc;
    private AgentRagContextService agentRagContextService;

    @BeforeEach
    void setUp() {
        RepositoryHistory repositoryHistory = mock(RepositoryHistory.class);
        ChatClient defaultChatClient = mock(ChatClient.class);
        RagService ragService = mock(RagService.class);
        AgentService agentService = mock(AgentService.class);
        agentRagContextService = mock(AgentRagContextService.class);
        AgentChatClientFactory factory = mock(AgentChatClientFactory.class);

        Agent agent = new Agent();
        agent.setRoleDescription("客服助手");
        when(agentService.getById(9L)).thenReturn(agent);
        when(agentRagContextService.retrieveContext(9L, "退款需要什么材料？")).thenReturn("退款资料上下文");

        ChatClient agentChatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(factory.create(agent)).thenReturn(agentChatClient);
        when(agentChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system("客服助手")).thenReturn(requestSpec);
        when(requestSpec.user("请仅用以下内容回答\"退款需要什么材料？\":\n退款资料上下文\n")).thenReturn(requestSpec);
        when(requestSpec.advisors(org.mockito.ArgumentMatchers.any(java.util.function.Consumer.class)))
                .thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("回答"));

        AiChatController controller = new AiChatController(repositoryHistory, defaultChatClient, ragService,
                agentService, agentRagContextService, factory);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void extractsPromptFromJsonBeforeRetrievingAgentContext() throws Exception {
        var result = mockMvc.perform(post("/ai/agent/chat")
                        .queryParam("chatId", "chat-1")
                        .queryParam("agentId", "9")
                        .contentType("application/json")
                        .content("{\"prompt\":\"退款需要什么材料？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().string("回答"));
        verify(agentRagContextService).retrieveContext(9L, "退款需要什么材料？");
    }

    @Test
    void rejectsBlankAgentPrompt() throws Exception {
        mockMvc.perform(post("/ai/agent/chat")
                        .queryParam("chatId", "chat-1")
                        .queryParam("agentId", "9")
                        .contentType("application/json")
                        .content("{\"prompt\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedAgentChatJson() throws Exception {
        mockMvc.perform(post("/ai/agent/chat")
                        .queryParam("chatId", "chat-1")
                        .queryParam("agentId", "9")
                        .contentType("application/json")
                        .content("{\"prompt\":"))
                .andExpect(status().isBadRequest());
    }
}
