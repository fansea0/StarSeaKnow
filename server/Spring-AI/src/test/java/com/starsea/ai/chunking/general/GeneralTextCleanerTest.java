package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralTextCleanerTest {

    @Test
    void replaces_urls_and_emails_with_one_space_before_optional_whitespace_collapse() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, true, true);
        NormalizedText normalized = new TextNormalizer().normalize(
                "left https://example.com/a?q=1  middle test.user+tag@example.co.uk right");
        List<DelimitedSegment> segments = new GeneralBoundaryScanner(config).scan(normalized);

        CleaningResult result = new GeneralTextCleaner().clean(segments, config, normalized);

        assertEquals(List.of("left middle right"), result.segments().stream().map(CleanedSegment::text).toList());
        assertEquals(1, result.stats().urlMatches());
        assertEquals(25, result.stats().urlCharactersReplaced());
        assertEquals(1, result.stats().emailMatches());
        assertEquals(27, result.stats().emailCharactersReplaced());
        assertTrue(result.stats().whitespaceCharactersRemoved() > 0);
    }

    @Test
    void disabled_rules_preserve_punctuation_lists_math_and_code_symbols() {
        String source = "1. item: a+b==c; https://e.test x@y.test";
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                false, false, false);
        NormalizedText normalized = new TextNormalizer().normalize(source);

        CleaningResult result = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized);

        assertEquals(source, result.segments().get(0).text());
        assertEquals(0, result.stats().urlMatches());
        assertEquals(0, result.stats().emailMatches());
    }

    @Test
    void disabled_optional_cleaning_preserves_space_tab_and_line_feed_only_segments() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                false, false, false);
        NormalizedText normalized = new TextNormalizer().normalize(" |||\t|||\n");

        CleaningResult result = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized);

        assertEquals(List.of(" ", "\t", "\n"),
                result.segments().stream().map(CleanedSegment::text).toList());
        assertEquals(0, result.stats().emptySegmentsRemoved());
    }

    @Test
    void enabled_whitespace_collapse_removes_segments_with_no_indexable_body() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, false, false);
        NormalizedText normalized = new TextNormalizer().normalize(" |||\t|||\n");

        CleaningResult result = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized);

        assertEquals(List.of(), result.segments());
        assertEquals(3, result.stats().emptySegmentsRemoved());
    }

    @Test
    void deletes_empty_cleaned_segments_and_transfers_their_delimiter_to_previous_content() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, false, false);
        NormalizedText normalized = new TextNormalizer().normalize("alpha||| \t |||omega");

        CleaningResult result = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized);

        assertEquals(List.of("alpha", "omega"), result.segments().stream().map(CleanedSegment::text).toList());
        assertEquals(BoundaryKind.USER_DELIMITER, result.segments().get(0).boundaryAfter());
        assertEquals(1, result.stats().emptySegmentsRemoved());
    }

    @Test
    void carries_normalizer_control_character_statistics_into_the_result() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                false, false, false);
        NormalizedText normalized = new TextNormalizer().normalize("a\u0000\u0002b");

        CleaningResult result = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized);

        assertEquals("ab", result.segments().get(0).text());
        assertEquals(2, result.stats().controlCharactersRemoved());
    }

    @Test
    void collapses_unicode_space_characters_as_whitespace() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, false, false);
        NormalizedText normalized = new TextNormalizer().normalize("left\u00A0\u3000right");

        CleaningResult result = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized);

        assertEquals("left right", result.segments().get(0).text());
        assertEquals(1, result.stats().whitespaceMatches());
        assertEquals(1, result.stats().whitespaceCharactersRemoved());
    }

    @Test
    void preserves_one_or_two_line_feeds_and_caps_longer_runs_at_two() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, false, false);

        assertEquals("a\nb", clean("a\nb", config));
        assertEquals("a\n\nb", clean("a\n\nb", config));
        assertEquals("a\n\nb", clean("a\n\n\n\nb", config));
    }

    @Test
    void collapses_horizontal_whitespace_without_trimming_meaningful_line_feeds() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, false, false);

        assertEquals("alpha\nbeta\n\ngamma", clean(" \talpha \n \t beta\n\n\n gamma \t", config));
        assertEquals("\nalpha\n", clean(" \t\n alpha \n\t ", config));
    }

    @Test
    void disabled_cleaning_shares_ten_megabyte_identity_source_mapping_without_arrays() {
        String source = "x".repeat(10_000_000);
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                false, false, false);
        NormalizedText normalized = new TextNormalizer().normalize(source);

        CleanedSegment segment = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized)
                .segments().get(0);

        assertSame(source, segment.text());
        assertTrue(segment.offsetMap().hasIdentityMapping());
        assertTrue(segment.offsetMap().sharesSourceMapping(normalized));
        assertEquals(0, segment.offsetMap().mappingArrayCount());
        assertEquals(1, segment.offsetMap().mappingSegmentCount());
        assertEquals(9_999_999, segment.offsetMap().sourceStart(9_999_999));
        assertEquals(10_000_000, segment.offsetMap().sourceEnd(10_000_000));
    }

    @Test
    void cleaned_offset_mapping_uses_one_packed_array_and_linear_run_growth() {
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, true, true);
        String unit = "a   https://example.test/path  user@example.test\n\uD83D\uDE00 ";
        CleanedOffsetMap small = cleanSegment(unit.repeat(100), config).offsetMap();
        CleanedOffsetMap large = cleanSegment(unit.repeat(1_000), config).offsetMap();

        assertEquals(1, small.mappingArrayCount());
        assertEquals(1, large.mappingArrayCount());
        assertTrue(large.mappingSegmentCount() <= small.mappingSegmentCount() * 11,
                () -> "cleaned mapping runs grew from " + small.mappingSegmentCount()
                        + " to " + large.mappingSegmentCount());
    }

    private String clean(String source, GeneralChunkConfig config) {
        return cleanSegment(source, config).text();
    }

    private CleanedSegment cleanSegment(String source, GeneralChunkConfig config) {
        NormalizedText normalized = new TextNormalizer().normalize(source);
        return new GeneralTextCleaner().clean(new GeneralBoundaryScanner(config).scan(normalized),
                config, normalized).segments().get(0);
    }
}
