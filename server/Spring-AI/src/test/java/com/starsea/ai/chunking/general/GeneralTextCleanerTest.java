package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private String clean(String source, GeneralChunkConfig config) {
        NormalizedText normalized = new TextNormalizer().normalize(source);
        return new GeneralTextCleaner().clean(new GeneralBoundaryScanner(config).scan(normalized),
                config, normalized).segments().get(0).text();
    }
}
