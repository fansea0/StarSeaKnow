package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.AgentWorkbenchException;
import java.util.List;
import java.util.Map;

/** Internal request. Controllers must obtain history from the server-side context, never from the browser. */
public record ExecutionRequest(String message, Map<String, String> variables, List<ConversationMessage> history) {
    public ExecutionRequest {
        if (message == null || message.isBlank() || message.length() > 16000) throw invalid();
        if (variables != null && variables.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) throw invalid();
        if (history != null && history.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        history = history == null ? List.of() : List.copyOf(history);
        if (variables.size() > 50 || variables.values().stream().anyMatch(value -> value.length() > 16000)
                || variables.values().stream().mapToLong(String::length).sum() > 64000) throw invalid();
        if (history.size() > 40 || history.size() % 2 != 0
                || history.stream().mapToLong(item -> item.content().length()).sum() > 64000) throw invalid();
        for (int i = 0; i < history.size(); i++) {
            if (!(i % 2 == 0 ? "user" : "assistant").equals(history.get(i).role())) throw invalid();
        }
    }

    private static AgentWorkbenchException invalid() {
        return new AgentWorkbenchException(422, "AGENT_MESSAGE_INVALID", "消息或上下文格式不合法或超出长度限制");
    }
    @Override public String toString() { return "ExecutionRequest[content=<redacted>]"; }
}
