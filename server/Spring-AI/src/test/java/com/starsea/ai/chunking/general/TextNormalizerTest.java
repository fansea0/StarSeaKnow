package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.SourceSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TextNormalizerTest {

    @Test
    void normalizes_mixed_line_endings_and_removes_unindexable_controls() {
        NormalizedText normalized = new TextNormalizer().normalize("a\r\nb\rc\u0000\u0001\td\n");

        assertEquals("a\nb\nc\td\n", normalized.text());
        assertEquals(2, normalized.controlCharactersRemoved());
        assertEquals(8, normalized.codePointCount());
    }

    @Test
    void keeps_tab_lf_and_supplementary_code_points_intact() {
        NormalizedText normalized = new TextNormalizer().normalize("A\t😀\nB");

        assertEquals("A\t😀\nB", normalized.text());
        assertEquals(5, normalized.codePointCount());
        assertFalse(Character.isHighSurrogate(normalized.text().charAt(normalized.text().length() - 1)));
    }

    @Test
    void maps_normalized_offsets_back_to_extracted_source_spans() {
        ExtractedText extracted = new ExtractedText(
                "ab\r\ncd", "text/plain", "plain", "1",
                List.of(new SourceSpan(0, 4, Map.of("page", 1)),
                        new SourceSpan(4, 6, Map.of("page", 2))), Map.of());

        NormalizedText normalized = new TextNormalizer().normalize(extracted);

        assertEquals("ab\ncd", normalized.text());
        assertEquals(0, normalized.originalOffset(0));
        assertEquals(4, normalized.originalOffset(3));
        assertEquals(List.of(Map.of("page", 1), Map.of("page", 2)),
                normalized.sourceLocator(0, 5).regions());
    }

    @Test
    void source_start_skips_controls_removed_before_retained_text() {
        NormalizedText normalized = new TextNormalizer().normalize("a\u0000b");

        assertEquals(2, normalized.originalOffset(1));
        assertEquals(3, normalized.originalOffset(2));
        assertEquals(0, normalized.sourceLocator(0, 1).startOffset());
        assertEquals(1, normalized.sourceLocator(0, 1).endOffset());
        assertEquals(2, normalized.sourceLocator(1, 2).startOffset());
        assertEquals(3, normalized.sourceLocator(1, 2).endOffset());
    }
}
