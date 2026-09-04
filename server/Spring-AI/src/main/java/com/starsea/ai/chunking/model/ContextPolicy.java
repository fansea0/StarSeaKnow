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

    /** Maps the legacy Markdown facade to the server-owned unit and mode. */
    public ContextConfig toContextConfig() {
        return new ContextConfig(enabled && overlapTokens > 0, overlapTokens,
                OverlapUnit.TOKENS, ContextMode.COMPLETE_SENTENCE);
    }
}
