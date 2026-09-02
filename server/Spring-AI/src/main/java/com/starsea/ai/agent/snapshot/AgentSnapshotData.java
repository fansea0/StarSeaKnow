package com.starsea.ai.agent.snapshot;

import com.starsea.ai.agent.AgentWorkbenchApiModels;

import java.math.BigDecimal;
import java.util.List;

public record AgentSnapshotData(
        String name,
        String description,
        String prologue,
        List<String> tags,
        String systemPrompt,
        List<AgentWorkbenchApiModels.VariableDefinition> variables,
        List<Long> knowledgeIds,
        ModelConfiguration model,
        int retrievalTopK,
        BigDecimal retrievalScoreThreshold) {
    public AgentSnapshotData {
        tags = tags == null ? List.of() : List.copyOf(tags);
        variables = variables == null ? List.of() : List.copyOf(variables);
        knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
    }

    public record ModelConfiguration(
            long providerConnectionId,
            String providerCode,
            String providerName,
            String providerIcon,
            String baseUrl,
            String protocolType,
            String authType,
            String modelId,
            BigDecimal temperature,
            BigDecimal topP,
            int maxTokens,
            int timeoutSeconds) {
    }
}
