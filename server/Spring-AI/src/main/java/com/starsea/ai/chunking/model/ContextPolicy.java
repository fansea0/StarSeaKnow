package com.starsea.ai.chunking.model;

/** Settings for context enrichment after source chunk boundaries are decided. */
public record ContextPolicy(boolean enabled, int overlapTokens) {

    public ContextPolicy {
        if (overlapTokens < 0 || overlapTokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
            throw new IllegalArgumentException("overlapTokens must be between 0 and 512");
        }
    }

    public static ContextPolicy defaults() {
        return new ContextPolicy(false, 40);
    }
}
