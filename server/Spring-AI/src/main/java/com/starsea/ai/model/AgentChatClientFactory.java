package com.fansea.ai.model;

import com.fansea.ai.domain.Agent;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Creates a chat client from the model configuration owned by one agent.
 */
public interface AgentChatClientFactory {

    ChatClient create(Agent agent);
}
