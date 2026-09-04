package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkContextEnricherTest {

    private final DefaultChunkContextEnricher enricher = new DefaultChunkContextEnricher(new CharacterTokenCounter());

    @Test
    void strategy_aware_entry_keeps_markdown_complete_sentence_format_exactly() {
        StrategyAwareChunkContextEnricher strategyAware =
                new StrategyAwareChunkContextEnricher(new CharacterTokenCounter());
        DocumentChunk first = chunk(11L, 0, List.of("Title"), "One. unfinished",
                "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(12L, 1, List.of("Title"), "Body",
                "PARAGRAPH_END", "PARAGRAPH_END");
        ChunkRuntimePolicy policy = new ChunkRuntimePolicy("MARKDOWN_OPTIMIZED",
                new ChunkPolicy(1, 20, 512), ContextConfig.markdownDefaults(),
                512, "character-test");

        EnrichedChunk result = strategyAware.enrich(List.of(first, second), policy).get(1);

        assertNull(result.overlapContent());
        assertEquals("标题：Title\n\nBody", result.indexContent());
    }

    @Test
    void per_chunk_enabled_setting_overrides_disabled_legacy_file_policy() {
        DocumentChunk first = chunk(1L, 0, List.of(), "Clean source.",
                "DOCUMENT_START", "PARAGRAPH_END");
        first.setOverlapContent("poisoned derived overlap");
        DocumentChunk second = chunk(2L, 1, List.of(), "Body",
                "PARAGRAPH_END", "PARAGRAPH_END");
        second.setOverlapEnabled(true);
        second.setOverlapTokenLimit(40);

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), 512).get(1);

        assertEquals("Clean source.", enriched.overlapContent());
    }

    @Test
    void per_chunk_disabled_setting_overrides_enabled_legacy_file_policy() {
        DocumentChunk first = chunk(1L, 0, List.of(), "Previous sentence.",
                "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(2L, 1, List.of(), "Body",
                "PARAGRAPH_END", "PARAGRAPH_END");
        second.setOverlapEnabled(false);
        second.setOverlapTokenLimit(40);

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), 512).get(1);

        assertNull(enriched.overlapContent());
    }

    @Test
    void disabled_policy_returns_title_and_edited_body_without_overlap() {
        DocumentChunk first = chunk(11L, 0, List.of("招生录取类问题"), "第一段完整句。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(12L, 1, List.of("招生录取类问题"), "编辑后的正文", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), ContextPolicy.defaults(), 512).get(1);

        assertNull(enriched.overlapSourceChunkId());
        assertNull(enriched.overlapContent());
        assertEquals(0, enriched.overlapTokenCount());
        assertEquals("标题：招生录取类问题\n\n编辑后的正文", enriched.indexContent());
    }

    @Test
    void adds_only_complete_chinese_and_english_sentences_in_source_order() {
        DocumentChunk first = chunk(11L, 0, List.of("招生录取类问题"),
                "中文句一。中文句二！English one? English two! unfinished", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(12L, 1, List.of("招生录取类问题"), "编辑后的正文", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), new ContextPolicy(true, 40), 512).get(1);

        assertEquals(11L, enriched.overlapSourceChunkId());
        assertEquals("中文句一。中文句二！English one? English two!", enriched.overlapContent());
        assertEquals("标题：招生录取类问题\n上文：中文句一。中文句二！English one? English two!\n\n编辑后的正文",
                enriched.indexContent());
    }

    @Test
    void stops_before_truncating_a_sentence_to_fit_default_overlap_budget() {
        DocumentChunk first = chunk(11L, 0, List.of("标题"),
                "0123456789012345678901234567890123456789。短句。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(12L, 1, List.of("标题"), "正文", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), new ContextPolicy(true, 40), 512).get(1);

        assertEquals("短句。", enriched.overlapContent());
        assertTrue(enriched.overlapTokenCount() <= 40);
    }

    @Test
    void retains_closing_chinese_quotes_and_english_brackets_with_the_sentence() {
        DocumentChunk chinese = chunk(1L, 0, List.of(), "他说：“可以。”", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk english = chunk(2L, 1, List.of(), "Use it.\")", "PARAGRAPH_END", "PARAGRAPH_END");
        DocumentChunk current = chunk(3L, 2, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");

        List<EnrichedChunk> enriched = enricher.enrich(List.of(chinese, english, current), new ContextPolicy(true, 40), 512);

        assertEquals("他说：“可以。”", enriched.get(1).overlapContent());
        assertEquals("Use it.\")", enriched.get(2).overlapContent());
    }

    @Test
    void omits_decimal_and_dotted_abbreviation_fragments_when_whole_sentence_exceeds_tight_budget() {
        DocumentChunk decimal = chunk(1L, 0, List.of(), "金额为 1.2。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk decimalCurrent = chunk(2L, 1, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");
        DocumentChunk abbreviation = chunk(3L, 0, List.of(), "见 e.g. 示例。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk abbreviationCurrent = chunk(4L, 1, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");
        DocumentChunk ieAbbreviation = chunk(5L, 0, List.of(), "即 i.e. 示例。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk ieAbbreviationCurrent = chunk(6L, 1, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");

        assertNull(enricher.enrich(List.of(decimal, decimalCurrent), new ContextPolicy(true, 8), 512)
                .get(1).overlapContent());
        assertNull(enricher.enrich(List.of(abbreviation, abbreviationCurrent), new ContextPolicy(true, 8), 512)
                .get(1).overlapContent());
        assertNull(enricher.enrich(List.of(ieAbbreviation, ieAbbreviationCurrent), new ContextPolicy(true, 8), 512)
                .get(1).overlapContent());
    }

    @Test
    void never_selects_dotted_title_or_initial_fragments_under_a_tight_budget() {
        assertNoOverlapWithinBudget("研究 Ph.D. 项目。", 10);
        assertNoOverlapWithinBudget("研究 U.S.A. 项目。", 10);
        assertNoOverlapWithinBudget("A. Smith arrived.", 10);
    }

    @Test
    void keeps_dotted_titles_and_initials_inside_the_later_complete_sentence() {
        assertWholeSentenceOverlap("研究 Ph.D. 项目。", 40);
        assertWholeSentenceOverlap("研究 U.S.A. 项目。", 40);
        assertWholeSentenceOverlap("A. Smith arrived.", 40);
    }

    @Test
    void stops_overlap_at_a_peer_label_on_the_same_title_path() {
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "PEER_LABEL"),
                chunk(2L, 1, List.of("甲"), "Q1：当前", "PEER_LABEL", "PARAGRAPH_END"));
    }

    @Test
    void stops_overlap_at_thematic_and_heading_boundaries() {
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "THEMATIC_BREAK"),
                chunk(2L, 1, List.of("甲"), "当前", "THEMATIC_BREAK", "PARAGRAPH_END"));
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "H2_SECTION"),
                chunk(2L, 1, List.of("甲"), "当前", "H2_SECTION", "PARAGRAPH_END"));
    }

    @Test
    void stops_overlap_when_previous_or_current_chunk_is_a_container() {
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "CONTAINER_END"),
                chunk(2L, 1, List.of("甲"), "当前", "PARAGRAPH_END", "PARAGRAPH_END"));
        for (String content : List.of("| 标题 | 值 |\n| --- | --- |", "```java\nrun();\n```", "    run();")) {
            DocumentChunk prose = chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "PARAGRAPH_END");
            DocumentChunk container = chunk(2L, 1, List.of("甲"), content, "PARAGRAPH_END", "CONTAINER_END");
            container.setSourceLocator(Map.of("type", "markdown", "blockIds", List.of("markdown-2")));
            assertNoOverlap(prose, container);
        }
    }

    @Test
    void does_not_bridge_a_deleted_position_gap_when_input_is_unsorted() {
        DocumentChunk first = chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk afterDeletedChunk = chunk(2L, 2, List.of("甲"), "当前", "PARAGRAPH_END", "PARAGRAPH_END");

        List<EnrichedChunk> enriched = enricher.enrich(List.of(afterDeletedChunk, first), new ContextPolicy(true, 40), 512);

        assertEquals(0, enriched.get(0).chunk().getPosition());
        assertEquals(2, enriched.get(1).chunk().getPosition());
        assertNull(enriched.get(1).overlapContent());
    }

    @Test
    void omits_overlap_when_current_index_text_already_consumes_the_budget() {
        DocumentChunk first = chunk(11L, 0, List.of(), "前句。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(12L, 1, List.of(), "0123456789", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), new ContextPolicy(true, 40), 10).get(1);

        assertNull(enriched.overlapContent());
        assertEquals(0, enriched.overlapTokenCount());
        assertEquals("0123456789", enriched.indexContent());
    }

    @Test
    void omits_overlap_when_current_title_and_body_consume_the_budget() {
        DocumentChunk first = chunk(11L, 0, List.of("标题"), "前句。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(12L, 1, List.of("标题"), "前句。", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(first, second), new ContextPolicy(true, 40), 10).get(1);

        assertNull(enriched.overlapContent());
        assertEquals("标题：标题\n\n前句。", enriched.indexContent());
    }

    @Test
    void rejects_current_title_and_body_that_exceed_the_supplied_budget() {
        DocumentChunk current = chunk(12L, 0, List.of("标题"), "正文", "DOCUMENT_START", "PARAGRAPH_END");

        assertThrows(IllegalArgumentException.class,
                () -> enricher.enrich(List.of(current), new ContextPolicy(true, 40), 5));
    }

    @Test
    void counts_overlap_as_the_difference_between_complete_final_texts() {
        DefaultChunkContextEnricher specialTokenEnricher = new DefaultChunkContextEnricher(new SpecialTokenCounter());
        DocumentChunk first = chunk(1L, 0, List.of(), "前句。", "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk second = chunk(2L, 1, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = specialTokenEnricher.enrich(List.of(first, second), new ContextPolicy(true, 40), 512).get(1);

        assertEquals(8, enriched.overlapTokenCount());
    }

    private void assertNoOverlap(DocumentChunk previous, DocumentChunk current) {
        EnrichedChunk enriched = enricher.enrich(List.of(previous, current), new ContextPolicy(true, 40), 512).get(1);
        assertNull(enriched.overlapSourceChunkId());
        assertNull(enriched.overlapContent());
    }

    private void assertNoOverlapWithinBudget(String previousBody, int overlapBudget) {
        DocumentChunk previous = chunk(1L, 0, List.of(), previousBody, "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk current = chunk(2L, 1, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(previous, current), new ContextPolicy(true, overlapBudget), 512).get(1);

        assertNull(enriched.overlapContent());
    }

    private void assertWholeSentenceOverlap(String previousBody, int overlapBudget) {
        DocumentChunk previous = chunk(1L, 0, List.of(), previousBody, "DOCUMENT_START", "PARAGRAPH_END");
        DocumentChunk current = chunk(2L, 1, List.of(), "正文", "PARAGRAPH_END", "PARAGRAPH_END");

        EnrichedChunk enriched = enricher.enrich(List.of(previous, current), new ContextPolicy(true, overlapBudget), 512).get(1);

        assertEquals(previousBody, enriched.overlapContent());
    }

    private DocumentChunk chunk(long id, int position, List<String> path, String content, String start, String end) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setPosition(position);
        chunk.setSectionPath(path);
        chunk.setContent(content);
        chunk.setBoundaryReason(Map.of("start", start, "end", end, "forcedSplit", false));
        return chunk;
    }

    private static final class CharacterTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length());
        }

        @Override
        public String id() {
            return "character-test";
        }
    }

    private static final class SpecialTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length()) + 2;
        }

        @Override
        public String id() {
            return "special-token-test";
        }
    }
}
