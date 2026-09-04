package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.SourceSpan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void unchanged_ten_megabyte_ascii_text_uses_shared_identity_mapping_without_arrays() {
        String source = "x".repeat(10_000_000);

        NormalizedText normalized = new TextNormalizer().normalize(source);

        assertSame(source, normalized.text());
        assertTrue(normalized.hasIdentityOffsetMapping());
        assertEquals(0, normalized.offsetMappingArrayCount());
        assertEquals(1, normalized.offsetMappingSegmentCount());
        assertEquals(9_999_999, normalized.originalCharacterStart(9_999_999));
        assertEquals(10_000_000, normalized.originalOffset(10_000_000));
    }

    @Test
    void changed_offset_mapping_uses_one_packed_array_and_grows_linearly_by_runs() {
        String unit = "a\r\nb\u0000\uD83D\uDE00";
        NormalizedText small = new TextNormalizer().normalize(unit.repeat(100));
        NormalizedText large = new TextNormalizer().normalize(unit.repeat(1_000));

        assertEquals(1, small.offsetMappingArrayCount());
        assertEquals(1, large.offsetMappingArrayCount());
        assertTrue(large.offsetMappingSegmentCount() <= small.offsetMappingSegmentCount() * 11,
                () -> "mapping runs grew from " + small.offsetMappingSegmentCount()
                        + " to " + large.offsetMappingSegmentCount());
    }

    @Test
    void compact_mapping_preserves_boundary_and_character_bias_around_removed_text() {
        NormalizedText normalized = new TextNormalizer().normalize(
                "\u0000A\r\n\uD83D\uDE00\u0002B\u0003");

        assertEquals("A\n\uD83D\uDE00B", normalized.text());
        assertEquals(List.of(1, 2, 4, 5, 7, 9),
                java.util.stream.IntStream.rangeClosed(0, normalized.text().length())
                        .mapToObj(normalized::originalOffset).toList());
        assertEquals(List.of(1, 2, 4, 4, 7),
                java.util.stream.IntStream.range(0, normalized.text().length())
                        .mapToObj(normalized::originalCharacterStart).toList());
        assertEquals(List.of(2, 4, 6, 6, 8),
                java.util.stream.IntStream.range(0, normalized.text().length())
                        .mapToObj(normalized::originalCharacterEnd).toList());
    }
}
