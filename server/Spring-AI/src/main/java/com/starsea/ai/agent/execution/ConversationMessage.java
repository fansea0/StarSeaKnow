package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.AgentWorkbenchException;

/** Wire message; ExecutionRequest restricts history to user/assistant, the assembler supplies system instructions. */
public record ConversationMessage(String role, String content) {
    public ConversationMessage {
        if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
            throw new AgentWorkbenchException(422, "AGENT_MESSAGE_INVALID", "消息角色不合法");
        }
        if (content == null) throw new AgentWorkbenchException(422, "AGENT_MESSAGE_INVALID", "消息内容不能为空");
    }

    @Override public String toString() { return "ConversationMessage[role=" + role + ", content=<redacted>]"; }
}
