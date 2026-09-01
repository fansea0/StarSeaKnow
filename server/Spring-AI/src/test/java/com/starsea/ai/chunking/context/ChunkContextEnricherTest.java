package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkContextEnricherTest {

    private final DefaultChunkContextEnricher enricher = new DefaultChunkContextEnricher(new CharacterTokenCounter());

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
    void stops_overlap_at_structural_boundaries_and_position_gaps() {
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "PARAGRAPH_END"),
                chunk(2L, 1, List.of("乙"), "当前", "H2_SECTION", "PARAGRAPH_END"));
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "THEMATIC_BREAK"),
                chunk(2L, 1, List.of("甲"), "当前", "THEMATIC_BREAK", "PARAGRAPH_END"));
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "CONTAINER_END"),
                chunk(2L, 1, List.of("甲"), "当前", "PARAGRAPH_END", "PARAGRAPH_END"));
        assertNoOverlap(chunk(1L, 0, List.of("甲"), "前句。", "DOCUMENT_START", "PARAGRAPH_END"),
                chunk(2L, 2, List.of("甲"), "当前", "PARAGRAPH_END", "PARAGRAPH_END"));
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

    private void assertNoOverlap(DocumentChunk previous, DocumentChunk current) {
        EnrichedChunk enriched = enricher.enrich(List.of(previous, current), new ContextPolicy(true, 40), 512).get(1);
        assertNull(enriched.overlapSourceChunkId());
        assertNull(enriched.overlapContent());
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
}
