package com.starsea.ai.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class AgentWorkbenchApiModels {
    private AgentWorkbenchApiModels() {
    }

    public record VariableDefinition(String name, String label, String defaultValue, boolean required) {
    }

    public record AgentModelCommand(
            Long providerConnectionId,
            String modelId,
            BigDecimal temperature,
            BigDecimal topP,
            Integer maxTokens,
            Integer timeoutSeconds) {
    }

    public record DraftCommand(
            String name,
            String description,
            String prologue,
            String systemPrompt,
            List<String> tags,
            List<VariableDefinition> variables,
            List<Long> knowledgeIds,
            Integer retrievalTopK,
            BigDecimal retrievalScoreThreshold,
            AgentModelCommand model,
            Long lockVersion) {
        public DraftCommand {
            tags = tags == null ? List.of() : List.copyOf(tags);
            variables = variables == null ? List.of() : List.copyOf(variables);
            knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
        }
    }

    public record AgentModelView(
            Long id,
            Long providerConnectionId,
            String modelId,
            BigDecimal temperature,
            BigDecimal topP,
            Integer maxTokens,
            Integer timeoutSeconds) {
    }

    public record AgentDetailView(
            Long id,
            String name,
            String description,
            String prologue,
            String systemPrompt,
            List<String> tags,
            List<VariableDefinition> variables,
            List<Long> knowledgeIds,
            Integer retrievalTopK,
            BigDecimal retrievalScoreThreshold,
            AgentModelView model,
            String status,
            long draftRevision,
            long publishedRevision,
            Long currentSnapshotId,
            long lockVersion,
            OffsetDateTime lastDebuggedAt,
            Long lastDebuggedBy,
            OffsetDateTime updateTime,
            boolean editable) {
        public AgentDetailView {
            tags = tags == null ? List.of() : List.copyOf(tags);
            variables = variables == null ? List.of() : List.copyOf(variables);
            knowledgeIds = knowledgeIds == null ? List.of() : List.copyOf(knowledgeIds);
        }
    }

    public record AgentListItem(
            Long id,
            String name,
            String description,
            List<String> tags,
            String status,
            int knowledgeCount,
            Long currentVersion,
            boolean modelConfigured,
            OffsetDateTime lastDebuggedAt,
            Long lastDebuggedBy,
            OffsetDateTime updateTime) {
        public AgentListItem {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public record AgentPage(List<AgentListItem> items, int page, int pageSize, long total) {
        public AgentPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record AgentMetrics(long all, long published, long debuggedThisWeek, long draftChanged) {
    }

    public record AgentListQuery(Integer page, Integer pageSize, String status, String tag, String keyword) {
        public int normalizedPage() {
            return page == null ? 1 : Math.max(1, page);
        }

        public int normalizedPageSize() {
            return pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        }
    }
}
