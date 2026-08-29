package com.starsea.ai.model;

import com.starsea.ai.domain.Agent;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Creates a chat client from the model configuration owned by one agent.
 */
public interface AgentChatClientFactory {

    ChatClient create(Agent agent);
}
