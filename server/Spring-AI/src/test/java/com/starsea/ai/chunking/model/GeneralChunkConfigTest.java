package com.starsea.ai.chunking.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralChunkConfigTest {

    @Test
    void supplies_the_locked_general_defaults() {
        GeneralChunkConfig defaults = GeneralChunkConfig.defaults();

        assertEquals("\n", defaults.delimiter());
        assertEquals(DelimiterMode.LITERAL, defaults.delimiterMode());
        assertEquals(500, defaults.maxCharacters());
        assertTrue(defaults.collapseWhitespace());
        assertFalse(defaults.removeUrls());
        assertFalse(defaults.removeEmails());
        assertInstanceOf(ChunkStrategyConfig.class, defaults);
        assertInstanceOf(ChunkStrategyConfig.class, ChunkPolicy.defaults());
    }

    @Test
    void rejects_invalid_delimiters_and_character_ranges() {
        assertThrows(IllegalArgumentException.class,
                () -> config("", DelimiterMode.LITERAL, 500));
        assertThrows(IllegalArgumentException.class,
                () -> config("😀".repeat(257), DelimiterMode.LITERAL, 500));
        assertThrows(IllegalArgumentException.class,
                () -> config("\n", DelimiterMode.LITERAL, 63));
        assertThrows(IllegalArgumentException.class,
                () -> config("\n", DelimiterMode.LITERAL, 4001));
    }

    @Test
    void rejects_invalid_or_zero_width_re2_patterns() {
        assertThrows(IllegalArgumentException.class,
                () -> config("(", DelimiterMode.REGEX, 500));
        assertThrows(IllegalArgumentException.class,
                () -> config("a*", DelimiterMode.REGEX, 500));
        assertThrows(IllegalArgumentException.class,
                () -> config("(?=a)", DelimiterMode.REGEX, 500));
    }

    @Test
    void applies_unit_specific_context_constraints() {
        assertEquals(new ContextConfig(true, 40, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL),
                ContextConfig.generalDefaults());
        assertEquals(new ContextConfig(false, 40, OverlapUnit.TOKENS, ContextMode.COMPLETE_SENTENCE),
                ContextPolicy.defaults().toContextConfig());
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(true, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(false, 513, OverlapUnit.TOKENS, ContextMode.COMPLETE_SENTENCE));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextConfig(false, 1001, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL));
    }

    private GeneralChunkConfig config(String delimiter, DelimiterMode mode, int maxCharacters) {
        return new GeneralChunkConfig(delimiter, mode, maxCharacters, true, false, false);
    }
}
