package com.fansea.ai.model;

import com.fansea.ai.domain.Agent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.api.OpenAiApi;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleAgentChatClientFactoryTest {

    @Test
    void rejectsAgentChatWhenItsOwnModelConfigurationIsIncomplete() {
        Agent agent = new Agent();
        agent.setModelUrl("https://models.example.com/v1");
        agent.setModelId("example-chat");

        OpenAiCompatibleAgentChatClientFactory factory =
                new OpenAiCompatibleAgentChatClientFactory(new InMemoryChatMemory());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> factory.create(agent))
                .withMessageContaining("模型配置");
    }

    @Test
    void usesArkCompatibleChatCompletionsPathWithoutTheDefaultV1Prefix() throws Exception {
        Agent agent = new Agent();
        agent.setModelUrl("https://ark.cn-beijing.volces.com/api/v3");
        agent.setModelApiKey("test-key");
        agent.setModelId("example-chat");
        OpenAiCompatibleAgentChatClientFactory factory =
                new OpenAiCompatibleAgentChatClientFactory(new InMemoryChatMemory());

        OpenAiApi api = factory.createApi(agent);
        Field completionsPath = OpenAiApi.class.getDeclaredField("completionsPath");
        completionsPath.setAccessible(true);

        assertThat(completionsPath.get(api)).isEqualTo("/chat/completions");
    }
}
