package com.fansea.ai.model;

import com.fansea.ai.domain.Agent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpenAiCompatibleAgentChatClientFactory implements AgentChatClientFactory {

    private final ChatMemory chatMemory;

    public OpenAiCompatibleAgentChatClientFactory(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    @Override
    public ChatClient create(Agent agent) {
        OpenAiApi api = createApi(agent);
        OpenAiChatOptions options = new OpenAiChatOptions();
        options.setModel(agent.getModelId().trim());
        OpenAiChatModel model = new OpenAiChatModel(api, options);
        return ChatClient.builder(model)
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory), new SimpleLoggerAdvisor())
                .build();
    }

    OpenAiApi createApi(Agent agent) {
        validate(agent);
        String endpoint = agent.getModelUrl().trim();
        return OpenAiApi.builder()
                .baseUrl(endpoint)
                .apiKey(agent.getModelApiKey().trim())
                .completionsPath("/chat/completions")
                .build();
    }

    private void validate(Agent agent) {
        if (agent == null || !StringUtils.hasText(agent.getModelUrl())
                || !StringUtils.hasText(agent.getModelApiKey()) || !StringUtils.hasText(agent.getModelId())) {
            throw new IllegalArgumentException("该智能体的模型配置不完整，请填写接口地址、API Key 和模型 ID");
        }
    }
}
