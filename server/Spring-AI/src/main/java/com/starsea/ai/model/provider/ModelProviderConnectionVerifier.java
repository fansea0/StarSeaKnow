package com.starsea.ai.model.provider;

import java.util.List;

public interface ModelProviderConnectionVerifier {

    VerifiedConnection verify(String baseUrl, String authType, String apiKey);

    record VerifiedConnection(List<ModelSuggestion> discoveredModels) {
        public VerifiedConnection {
            discoveredModels = discoveredModels == null ? List.of() : List.copyOf(discoveredModels);
        }
    }
}
