package com.starsea.ai.chunking.model;

import java.util.Objects;

/** Validated context settings after the server fixes the unit and enrichment mode. */
public record ContextConfig(boolean enabled, int limit, OverlapUnit unit, ContextMode mode) {

    public ContextConfig {
        Objects.requireNonNull(unit, "unit: overlap unit is required");
        Objects.requireNonNull(mode, "mode: context mode is required");
        int maximum = unit == OverlapUnit.TOKENS ? 512 : 1000;
        if (limit < 0 || limit > maximum) {
            throw new IllegalArgumentException("limit: context limit must be between 0 and " + maximum);
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
}
