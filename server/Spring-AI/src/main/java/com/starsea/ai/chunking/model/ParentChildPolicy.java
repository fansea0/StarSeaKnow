package com.starsea.ai.chunking.model;

import java.util.Locale;

/** Validated strategy policy for Markdown parent-child chunking. */
public record ParentChildPolicy(
        ParentMode parentMode,
        int parentMaxTokens,
        int childMaxTokens,
        int childOverlapTokens) implements ChunkStrategyConfig {

    public static final int MIN_PARENT_TOKENS = 128;
    public static final int MAX_PARENT_TOKENS = 4096;
    public static final int MIN_CHILD_TOKENS = 32;
    public static final int MAX_CHILD_TOKENS = 512;
    public static final int MAX_CHILD_OVERLAP_TOKENS = 128;

    public ParentChildPolicy {
        if (parentMode == null) {
            throw new IllegalArgumentException("A parent mode is required");
        }
        if (parentMaxTokens < MIN_PARENT_TOKENS || parentMaxTokens > MAX_PARENT_TOKENS) {
            throw new IllegalArgumentException("parentMaxTokens must be between 128 and 4096");
        }
        if (childMaxTokens < MIN_CHILD_TOKENS || childMaxTokens > MAX_CHILD_TOKENS) {
            throw new IllegalArgumentException("childMaxTokens must be between 32 and 512");
        }
        if (childOverlapTokens < 0 || childOverlapTokens > MAX_CHILD_OVERLAP_TOKENS
                || childOverlapTokens >= childMaxTokens) {
            throw new IllegalArgumentException("childOverlapTokens must be between 0 and 128 and smaller than childMaxTokens");
        }
        if (parentMode == ParentMode.PARAGRAPH && parentMaxTokens < childMaxTokens) {
            throw new IllegalArgumentException("parentMaxTokens must not be smaller than childMaxTokens in paragraph mode");
        }
    }

    public static ParentChildPolicy defaults() {
        return new ParentChildPolicy(ParentMode.PARAGRAPH, 1024, 256, 32);
    }

    public enum ParentMode {
        PARAGRAPH,
        FULL_DOCUMENT;

        public static ParentMode fromConfig(Object value) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("parentMode must be PARAGRAPH or FULL_DOCUMENT");
            }
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("parentMode must be PARAGRAPH or FULL_DOCUMENT", exception);
            }
        }
    }
}
