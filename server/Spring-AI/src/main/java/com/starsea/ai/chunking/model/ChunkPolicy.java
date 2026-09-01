package com.starsea.ai.chunking.model;

/** Immutable token budget used to plan source-content boundaries. */
public record ChunkPolicy(int minTokens, int targetTokens, int maxTokens) {

    public static final int MAX_ALLOWED_TOKENS = 512;

    public ChunkPolicy {
        if (minTokens <= 0 || minTokens > targetTokens || targetTokens > maxTokens
                || maxTokens > MAX_ALLOWED_TOKENS) {
            throw new IllegalArgumentException("Expected 0 < minTokens <= targetTokens <= maxTokens <= 512");
        }
    }

    public static ChunkPolicy defaults() {
        return new ChunkPolicy(100, 400, MAX_ALLOWED_TOKENS);
    }
}
