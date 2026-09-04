package com.starsea.ai.chunking.runtime;

import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.ContextConfig;

import java.util.Objects;

/** Strongly typed policy reconstructed from a persisted preview snapshot. */
public record ChunkRuntimePolicy(
        String strategyCode,
        ChunkStrategyConfig strategyConfig,
        ContextConfig contextConfig,
        int maxIndexTokens,
        String tokenizerId) {

    public ChunkRuntimePolicy {
        Objects.requireNonNull(strategyCode, "strategyCode");
        Objects.requireNonNull(strategyConfig, "strategyConfig");
        Objects.requireNonNull(contextConfig, "contextConfig");
        if (maxIndexTokens <= 0 || maxIndexTokens > 512) {
            throw new IllegalArgumentException("maxIndexTokens must be between 1 and 512");
        }
    }
}
