package com.starsea.ai.chunking.model;

import java.util.Objects;

/** Complete validated input to a chunk planner. */
public record ChunkPlanningRequest(
        ParsedStructure structure,
        ChunkStrategyConfig strategyConfig,
        ContextConfig contextConfig,
        int maxIndexTokens) {

    public ChunkPlanningRequest {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(strategyConfig, "strategyConfig");
        Objects.requireNonNull(contextConfig, "contextConfig");
        if (maxIndexTokens <= 0 || maxIndexTokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
            throw new IllegalArgumentException("maxIndexTokens must be between 1 and 512");
        }
    }
}
