package com.starsea.ai.chunking.model;

import java.util.Objects;

/** Validated context settings after the server fixes the unit and enrichment mode. */
public record ContextConfig(boolean enabled, int limit, OverlapUnit unit, ContextMode mode) {

    public static final int MIN_LIMIT = 0;
    public static final int MAX_TOKEN_LIMIT = 512;
    public static final int MAX_CHARACTER_LIMIT = 1000;

    public ContextConfig {
        Objects.requireNonNull(unit, "unit: overlap unit is required");
        Objects.requireNonNull(mode, "mode: context mode is required");
        int maximum = maximumLimit(unit);
        if (limit < MIN_LIMIT || limit > maximum) {
            throw new IllegalArgumentException("limit: context limit must be between " + MIN_LIMIT + " and " + maximum);
        }
        if (enabled && limit == 0) {
            throw new IllegalArgumentException("limit: enabled context requires a positive limit");
        }
        if ((unit == OverlapUnit.TOKENS) != (mode == ContextMode.COMPLETE_SENTENCE)) {
            throw new IllegalArgumentException("mode: context unit and mode are incompatible");
        }
    }

    public static ContextConfig generalDefaults() {
        return new ContextConfig(true, 40, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);
    }

    public static ContextConfig markdownDefaults() {
        return new ContextConfig(false, 40, OverlapUnit.TOKENS, ContextMode.COMPLETE_SENTENCE);
    }

    public static int maximumLimit(OverlapUnit unit) {
        return unit == OverlapUnit.TOKENS ? MAX_TOKEN_LIMIT : MAX_CHARACTER_LIMIT;
    }
}
