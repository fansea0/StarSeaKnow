package com.starsea.ai.model.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public final class ModelProviderApiModels {
    private ModelProviderApiModels() {
    }

    public record ConnectionCommand(
            Long catalogProviderId,
            String customName,
            String customIcon,
            String baseUrl,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            String apiKey,
            List<ModelSuggestion> selectableModels) {
        public ConnectionCommand {
            selectableModels = selectableModels == null ? List.of() : List.copyOf(selectableModels);
        }

        @Override
        public String toString() {
            return "ConnectionCommand[catalogProviderId=" + catalogProviderId
                    + ", customName=" + customName
                    + ", customIcon=" + customIcon
                    + ", baseUrl=" + baseUrl
                    + ", apiKey=<redacted>, selectableModels=" + selectableModels + "]";
        }
    }

    public record ProviderView(
            Long catalogProviderId,
            Long connectionId,
            String code,
            String name,
            String icon,
            String baseUrl,
            String protocolType,
            String authType,
            List<ModelSuggestion> selectableModels,
            boolean configured,
            boolean custom,
            boolean apiKeyConfigured,
            String apiKeyLastFour) {
        public ProviderView {
            selectableModels = selectableModels == null ? List.of() : List.copyOf(selectableModels);
        }
    }

    public record ConnectionTestResult(boolean connected, List<ModelSuggestion> discoveredModels) {
        public ConnectionTestResult {
            discoveredModels = discoveredModels == null ? List.of() : List.copyOf(discoveredModels);
        }
    }

    public record ModelListCommand(List<ModelSuggestion> models) {
        public ModelListCommand {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }
}
