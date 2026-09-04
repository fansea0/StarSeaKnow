package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.context.ChunkIndexContentBuilder;
import com.starsea.ai.chunking.model.*;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeneralChunkPlanningStrategyTest {

    @Test
    void packs_short_segments_with_exactly_one_line_feed_and_uses_empty_section_paths() {
        GeneralChunkPlanningStrategy strategy = new GeneralChunkPlanningStrategy(codePointCounter());
        ChunkPlanningResult result = strategy.plan(request(List.of("alpha", "beta", "gamma"),
                64, ContextConfig.generalDefaults(), 512));

        assertEquals(1, result.drafts().size());
        assertEquals("alpha\nbeta\ngamma", result.drafts().get(0).content());
        assertEquals(List.of(), result.drafts().get(0).sectionPath());
    }

    @Test
    void later_chunks_reserve_configured_overlap_and_five_format_code_points_without_backfill() {
        GeneralChunkPlanningStrategy strategy = new GeneralChunkPlanningStrategy(codePointCounter());
        ContextConfig overlap = new ContextConfig(true, 10, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);
        ChunkPlanningResult result = strategy.plan(request(
                List.of("a".repeat(64), "b".repeat(49), "c".repeat(10)), 64, overlap, 512));

        assertEquals(List.of(64, 49, 10), result.drafts().stream()
                .map(draft -> UnicodeText.length(draft.content())).toList());
    }

    @Test
    void rejects_a_later_body_budget_that_cannot_hold_one_code_point() {
        GeneralChunkPlanningStrategy strategy = new GeneralChunkPlanningStrategy(codePointCounter());
        ContextConfig overlap = new ContextConfig(true, 60, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> strategy.plan(request(List.of("a".repeat(64), "b"), 64, overlap, 512)));

        assertTrue(error.getMessage().contains("body budget"));
    }

    @Test
    void oversized_text_prefers_line_then_sentence_then_whitespace_then_forced_code_point() {
        GeneralChunkPlanningStrategy strategy = new GeneralChunkPlanningStrategy(codePointCounter());

        ChunkPlanningResult lines = strategy.plan(request(List.of("a".repeat(40) + "\n" + "b".repeat(40)),
                64, disabledContext(), 512));
        ChunkPlanningResult sentences = strategy.plan(request(List.of("甲".repeat(35) + "。" + "乙".repeat(35) + "."),
                64, disabledContext(), 512));
        ChunkPlanningResult spaces = strategy.plan(request(List.of("x".repeat(35) + " " + "y".repeat(35)),
                64, disabledContext(), 512));
        ChunkPlanningResult forced = strategy.plan(request(List.of("😀".repeat(70)),
                64, disabledContext(), 512));

        assertEquals("LINE_BREAK", lines.drafts().get(0).boundaryReason().get("end"));
        assertEquals("SENTENCE_END", sentences.drafts().get(0).boundaryReason().get("end"));
        assertEquals("WHITESPACE", spaces.drafts().get(0).boundaryReason().get("end"));
        assertEquals("FORCED_CHARACTER", forced.drafts().get(0).boundaryReason().get("end"));
        assertTrue(forced.drafts().get(0).content().codePoints().allMatch(cp -> cp == 0x1F600));
        assertEquals(1, forced.forcedSplitCount());
    }

    @Test
    void token_limit_splits_body_marks_model_boundary_and_reports_real_count() {
        TokenCounter counter = new TokenCounter() {
            @Override public int count(String text) { return UnicodeText.length(text) * 2; }
            @Override public String id() { return "double-code-point"; }
        };
        GeneralChunkPlanningStrategy strategy = new GeneralChunkPlanningStrategy(counter);

        ChunkPlanningResult result = strategy.plan(request(List.of("word ".repeat(20)),
                64, disabledContext(), 30));

        assertTrue(result.drafts().size() > 2);
        assertTrue(result.drafts().stream().allMatch(draft -> draft.tokenCount() <= 30));
        assertEquals("MODEL_TOKEN_LIMIT", result.drafts().get(0).boundaryReason().get("end"));
        assertEquals(result.drafts().size() - 1, result.tokenLimitedSplitCount());
    }

    @Test
    void token_limited_hard_boundaries_report_both_token_and_forced_split_counts() {
        TokenCounter counter = new TokenCounter() {
            @Override public int count(String text) { return UnicodeText.length(text) * 2; }
            @Override public String id() { return "double-code-point"; }
        };

        ChunkPlanningResult result = new GeneralChunkPlanningStrategy(counter).plan(
                request(List.of("x".repeat(40)), 64, disabledContext(), 30));

        assertEquals(2, result.tokenLimitedSplitCount());
        assertEquals(2, result.forcedSplitCount());
        assertEquals("MODEL_TOKEN_LIMIT", result.drafts().get(0).boundaryReason().get("end"));
        assertEquals(true, result.drafts().get(0).boundaryReason().get("forcedSplit"));
    }

    @Test
    void body_and_preview_are_identical_for_general_and_each_respects_both_limits() {
        GeneralChunkPlanningStrategy strategy = new GeneralChunkPlanningStrategy(codePointCounter());
        ChunkPlanningResult result = strategy.plan(request(List.of("中😀文 ".repeat(30)),
                64, disabledContext(), 50));

        for (ChunkDraft draft : result.drafts()) {
            assertEquals(draft.content(), ChunkIndexContentBuilder.preview(draft.sectionPath(), draft.content()));
            assertTrue(UnicodeText.length(draft.content()) <= 64);
            assertTrue(draft.tokenCount() <= 50);
            assertWellFormedUtf16(draft.content());
        }
    }

    @Test
    void cleaned_leading_url_email_and_whitespace_map_split_locators_to_retained_source() {
        String prefix = " https://drop.test user@example.com \t";
        String retained = "😀".repeat(70);
        String source = prefix + retained;
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64,
                true, true, true);
        NormalizedText normalized = new TextNormalizer().normalize(source);
        CleanedSegment cleaned = new GeneralTextCleaner().clean(
                new GeneralBoundaryScanner(config).scan(normalized), config, normalized).segments().get(0);
        StructuredBlock block = cleaned.toStructuredBlock("mapped", UnicodeText.length(cleaned.text()));
        ChunkPlanningRequest request = new ChunkPlanningRequest(new ParsedStructure(null, List.of(block)),
                config, disabledContext(), 512);

        List<ChunkDraft> drafts = new GeneralChunkPlanningStrategy(codePointCounter()).plan(request).drafts();

        assertEquals(List.of(64, 6), drafts.stream().map(draft -> UnicodeText.length(draft.content())).toList());
        assertEquals(prefix.length(), drafts.get(0).sourceLocator().startOffset());
        assertEquals(prefix.length() + 128, drafts.get(0).sourceLocator().endOffset());
        assertEquals(prefix.length() + 128, drafts.get(1).sourceLocator().startOffset());
        assertEquals(source.length(), drafts.get(1).sourceLocator().endOffset());
    }

    @Test
    void indexed_planning_work_grows_linearly_for_ten_times_more_input() {
        AtomicInteger smallCalls = new AtomicInteger();
        AtomicInteger largeCalls = new AtomicInteger();
        TokenCounter smallCounter = countingCodePointCounter(smallCalls);
        TokenCounter largeCounter = countingCodePointCounter(largeCalls);
        String small = "😀".repeat(640);
        String large = small.repeat(10);

        UnicodeText.CodePointIndex smallIndex = UnicodeText.index(small);
        UnicodeText.CodePointIndex largeIndex = UnicodeText.index(large);
        ChunkPlanningResult smallResult = new GeneralChunkPlanningStrategy(smallCounter).plan(
                requestWithCounter(small, smallCounter));
        ChunkPlanningResult largeResult = new GeneralChunkPlanningStrategy(largeCounter).plan(
                requestWithCounter(large, largeCounter));

        assertEquals(640, smallIndex.scanOperations());
        assertEquals(6_400, largeIndex.scanOperations());
        assertEquals(10 * smallIndex.scanOperations(), largeIndex.scanOperations());
        assertEquals(10 * smallResult.drafts().size(), largeResult.drafts().size());
        assertTrue(largeCalls.get() <= smallCalls.get() * 11,
                () -> "token work grew from " + smallCalls + " to " + largeCalls);
    }

    private ChunkPlanningRequest request(List<String> texts, int maxCharacters,
                                         ContextConfig context, int maxTokens) {
        List<StructuredBlock> blocks = java.util.stream.IntStream.range(0, texts.size())
                .mapToObj(index -> block(index, texts.get(index), index + 1 < texts.size()))
                .toList();
        GeneralChunkConfig config = new GeneralChunkConfig("|||", DelimiterMode.LITERAL,
                maxCharacters, false, false, false);
        return new ChunkPlanningRequest(new ParsedStructure(null, blocks), config, context, maxTokens);
    }

    private StructuredBlock block(int index, String text, boolean hasDelimiterAfter) {
        return new StructuredBlock("g" + index, BlockType.PARAGRAPH, text, text, null, List.of(),
                UnicodeText.length(text),
                new SourceLocator("TEXT", List.of("g" + index), 0, text.length(), null, null,
                        null, null, List.of()),
                Map.of("boundaryAfter", hasDelimiterAfter ? "USER_DELIMITER" : "DOCUMENT_END"));
    }

    private ContextConfig disabledContext() {
        return new ContextConfig(false, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);
    }

    private TokenCounter codePointCounter() {
        return new TokenCounter() {
            @Override public int count(String text) { return UnicodeText.length(text); }
            @Override public String id() { return "code-point"; }
        };
    }

    private TokenCounter countingCodePointCounter(AtomicInteger calls) {
        return new TokenCounter() {
            @Override public int count(String text) {
                calls.incrementAndGet();
                return UnicodeText.length(text);
            }
            @Override public String id() { return "counting-code-point"; }
        };
    }

    private ChunkPlanningRequest requestWithCounter(String text, TokenCounter counter) {
        StructuredBlock block = new StructuredBlock("scale", BlockType.PARAGRAPH, text, text,
                null, List.of(), counter.count(text),
                new SourceLocator("TEXT", List.of("scale"), 0, text.length(),
                        null, null, null, null, List.of()),
                Map.of("boundaryAfter", "DOCUMENT_END"));
        return new ChunkPlanningRequest(new ParsedStructure(null, List.of(block)),
                new GeneralChunkConfig("|||", DelimiterMode.LITERAL, 64, false, false, false),
                disabledContext(), 512);
    }

    private void assertWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < value.length() && Character.isLowSurrogate(value.charAt(++index)));
            } else {
                assertFalse(Character.isLowSurrogate(current));
            }
        }
    }
}
