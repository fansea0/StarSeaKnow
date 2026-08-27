package com.fansea.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Projectname: Spring-AI
 * @Filename: CommonConfigruation
 * @Author: FANSEA
 * @Date:2025/4/3 17:50
 */
@Configuration
public class CommonConfiguration {

    // 配置会话记录advisor
    @Bean
    public ChatMemory chatMemory(){
        // 基于map的内存会话保存
        return new InMemoryChatMemory();
    }


    @Bean
    public ChatClient chatClient(OpenAiChatModel model, ChatMemory chatMemory){
        return ChatClient
                .builder(model)
                .defaultAdvisors(
                        new MessageChatMemoryAdvisor(chatMemory),
                        new SimpleLoggerAdvisor()
                )
                .build();
    }
}
