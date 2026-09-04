package com.starsea.ai.chunking.model;

import java.util.Objects;

/** Strategy and context configuration accepted before asynchronous preview dispatch. */
public record ValidatedPreviewConfig(
        ChunkStrategyConfig strategyConfig,
        ContextConfig contextConfig,
        int maxIndexTokens) {

    public ValidatedPreviewConfig {
        Objects.requireNonNull(strategyConfig, "strategyConfig");
        Objects.requireNonNull(contextConfig, "contextConfig");
        if (maxIndexTokens <= 0 || maxIndexTokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
            throw new IllegalArgumentException("maxIndexTokens must be between 1 and 512");
        }
    }
}
