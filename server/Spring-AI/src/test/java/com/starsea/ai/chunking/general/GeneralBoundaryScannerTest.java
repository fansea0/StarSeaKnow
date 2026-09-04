package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralBoundaryScannerTest {

    @Test
    void literal_backslash_n_is_distinct_from_an_actual_line_feed() {
        NormalizedText text = new TextNormalizer().normalize("a\\nb\nc");

        List<DelimitedSegment> literal = new GeneralBoundaryScanner(
                config("\\n", DelimiterMode.LITERAL)).scan(text);
        List<DelimitedSegment> lineFeed = new GeneralBoundaryScanner(
                config("\n", DelimiterMode.LITERAL)).scan(text);

        assertEquals(List.of("a", "b\nc"), literal.stream().map(DelimitedSegment::text).toList());
        assertEquals(List.of("a\\nb", "c"), lineFeed.stream().map(DelimitedSegment::text).toList());
    }

    @Test
    void consumes_every_multi_character_delimiter_without_putting_it_in_the_body() {
        List<DelimitedSegment> segments = new GeneralBoundaryScanner(
                config("|||", DelimiterMode.LITERAL)).scan(new TextNormalizer().normalize("甲|||乙||||||丙"));

        assertEquals(List.of("甲", "乙", "", "丙"), segments.stream().map(DelimitedSegment::text).toList());
        assertEquals(java.util.Arrays.asList(BoundaryKind.USER_DELIMITER, BoundaryKind.USER_DELIMITER,
                        BoundaryKind.USER_DELIMITER, null),
                segments.stream().map(DelimitedSegment::boundaryAfter).toList());
        assertTrue(segments.stream().noneMatch(segment -> segment.text().contains("|||")));
    }

    @Test
    void regex_is_compiled_once_and_consumes_multiple_candidates() {
        GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config("\\|{2,3}", DelimiterMode.REGEX));

        List<DelimitedSegment> first = scanner.scan(new TextNormalizer().normalize("a||b|||c"));
        List<DelimitedSegment> second = scanner.scan(new TextNormalizer().normalize("d||e"));

        assertEquals(List.of("a", "b", "c"), first.stream().map(DelimitedSegment::text).toList());
        assertEquals(List.of("d", "e"), second.stream().map(DelimitedSegment::text).toList());
    }

    @Test
    void actual_crlf_delimiters_match_after_source_lf_normalization_in_both_modes() {
        NormalizedText text = new TextNormalizer().normalize("alpha\r\nbeta\rgamma");

        List<String> literal = new GeneralBoundaryScanner(
                config("\r\n", DelimiterMode.LITERAL)).scan(text).stream()
                .map(DelimitedSegment::text).toList();
        List<String> regex = new GeneralBoundaryScanner(
                config("(?:\r\n|\r)", DelimiterMode.REGEX)).scan(text).stream()
                .map(DelimitedSegment::text).toList();

        assertEquals(List.of("alpha", "beta", "gamma"), literal);
        assertEquals(List.of("alpha", "beta", "gamma"), regex);
    }

    @Test
    void rejects_a_regex_that_produces_a_zero_width_match_on_real_input() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new GeneralBoundaryScanner(config("x*", DelimiterMode.REGEX))
                        .scan(new TextNormalizer().normalize("bbb")));

        assertTrue(error.getMessage().contains("零宽"));
    }

    @Test
    void reports_when_no_delimiter_was_matched() {
        GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config("|||", DelimiterMode.LITERAL));
        scanner.scan(new TextNormalizer().normalize("plain text"));

        assertFalse(scanner.delimiterMatched());
    }

    @Test
    void fallback_scan_exposes_the_highest_priority_boundary_unit() {
        GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config("|||", DelimiterMode.LITERAL));

        BoundaryUnit unit = GeneralBoundaryScanner.scanFallback(
                "a".repeat(20) + "。" + "b".repeat(20) + "\nrest", 42,
                null);

        assertEquals("a".repeat(20) + "。" + "b".repeat(20) + "\n", unit.text());
        assertEquals(BoundaryKind.LINE_BREAK, unit.boundaryAfter());
        assertEquals(42, unit.cleanedEnd());
    }

    @Test
    void fallback_scan_treats_non_breaking_space_as_whitespace_and_keeps_it() {
        BoundaryUnit unit = GeneralBoundaryScanner.scanFallback("alpha\u00a0omega", 6, null);

        assertEquals("alpha\u00a0", unit.text());
        assertEquals(BoundaryKind.WHITESPACE, unit.boundaryAfter());
        assertEquals(6, unit.cleanedEnd());
    }

    @Test
    void scanner_reuses_the_pattern_compiled_while_validating_the_config() throws Exception {
        GeneralChunkConfig config = config("\\|{2,3}", DelimiterMode.REGEX);
        GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config);

        com.google.re2j.Pattern validatedPattern = patternField(config);
        com.google.re2j.Pattern scannerPattern = patternField(scanner);

        assertNotNull(validatedPattern, "validated config must retain its compiled delimiter pattern");
        assertSame(validatedPattern, scannerPattern);
        assertEquals(List.of("a", "b", "c"), scanner.scan(
                new TextNormalizer().normalize("a||b|||c")).stream()
                .map(DelimitedSegment::text).toList());
    }

    private com.google.re2j.Pattern patternField(Object owner) throws IllegalAccessException {
        java.lang.reflect.Field field = Arrays.stream(owner.getClass().getDeclaredFields())
                .filter(candidate -> candidate.getType() == com.google.re2j.Pattern.class)
                .findFirst()
                .orElse(null);
        if (field == null) return null;
        field.setAccessible(true);
        return (com.google.re2j.Pattern) field.get(owner);
    }

    private GeneralChunkConfig config(String delimiter, DelimiterMode mode) {
        return new GeneralChunkConfig(delimiter, mode, 64, false, false, false);
    }
}
