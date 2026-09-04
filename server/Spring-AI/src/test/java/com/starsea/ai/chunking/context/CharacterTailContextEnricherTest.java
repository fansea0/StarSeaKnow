package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CharacterTailContextEnricherTest {

    private final CharacterTailContextEnricher enricher =
            new CharacterTailContextEnricher(new CodePointTokenCounter());
    private final ChunkRuntimePolicy policy = new ChunkRuntimePolicy("GENERAL",
            new GeneralChunkConfig("\n", DelimiterMode.LITERAL, 64, false, false, false),
            new ContextConfig(true, 3, OverlapUnit.CHARACTERS,
                    com.starsea.ai.chunking.model.ContextMode.CHARACTER_TAIL),
            512, "code-point-test");

    @Test
    void copies_the_configured_unicode_tail_from_the_adjacent_independent_body() {
        DocumentChunk previous = chunk(11L, 0, "prefixA😀B");
        previous.setOverlapContent("derived value must never be chained");
        DocumentChunk current = chunk(12L, 1, "body");
        current.setOverlapEnabled(true);
        current.setOverlapLimit(3);
        current.setOverlapUnit(OverlapUnit.CHARACTERS);

        EnrichedChunk result = enricher.enrich(List.of(current, previous), policy).get(1);

        assertEquals("A😀B", result.overlapContent());
        assertEquals("上文：A😀B\n\nbody", result.indexContent());
    }

    @Test
    void does_not_bridge_a_deleted_position_gap() {
        DocumentChunk previous = chunk(11L, 0, "previous");
        DocumentChunk current = chunk(12L, 2, "body");
        current.setOverlapEnabled(true);
        current.setOverlapLimit(4);
        current.setOverlapUnit(OverlapUnit.CHARACTERS);

        EnrichedChunk result = enricher.enrich(List.of(previous, current), policy).get(1);

        assertNull(result.overlapContent());
        assertEquals("NO_ADJACENT_SOURCE", result.overlapReductionReason());
    }

    @Test
    void disabled_or_zero_character_context_produces_body_only() {
        DocumentChunk previous = chunk(11L, 0, "previous");
        DocumentChunk disabled = chunk(12L, 1, "body");
        disabled.setOverlapEnabled(false);
        disabled.setOverlapLimit(4);
        disabled.setOverlapUnit(OverlapUnit.CHARACTERS);

        EnrichedChunk result = enricher.enrich(List.of(previous, disabled), policy).get(1);

        assertNull(result.overlapContent());
        assertEquals("body", result.indexContent());
        assertEquals("DISABLED", result.overlapReductionReason());
    }

    @Test
    void first_chunk_keeps_no_overlap_with_an_explainable_reason() {
        EnrichedChunk result = enricher.enrich(List.of(chunk(11L, 7, "body")), policy).get(0);

        assertNull(result.overlapContent());
        assertEquals(0, result.overlapCharacterCount());
        assertEquals("FIRST_CHUNK", result.overlapReductionReason());
    }

    @Test
    void shrinks_only_the_tail_to_fit_the_complete_character_budget() {
        DocumentChunk previous = chunk(11L, 0, "0123456789ABCDEFGHIJ");
        DocumentChunk current = chunk(12L, 1, "x".repeat(55));
        current.setOverlapLimit(10);

        EnrichedChunk result = enricher.enrich(List.of(previous, current), policy).get(1);

        assertEquals("GHIJ", result.overlapContent());
        assertEquals(4, result.overlapCharacterCount());
        assertEquals(64, result.indexContent().codePointCount(0, result.indexContent().length()));
        assertEquals("CHARACTER_LIMIT", result.overlapReductionReason());
    }

    @Test
    void shrinks_the_tail_when_the_model_token_limit_is_tighter() {
        CharacterTailContextEnricher doubleTokenEnricher =
                new CharacterTailContextEnricher(new DoubleCodePointTokenCounter());
        ChunkRuntimePolicy tightTokens = policy(64, 20);
        DocumentChunk previous = chunk(11L, 0, "ABCDE");
        DocumentChunk current = chunk(12L, 1, "body");
        current.setOverlapLimit(3);

        EnrichedChunk result = doubleTokenEnricher.enrich(List.of(previous, current), tightTokens).get(1);

        assertEquals("E", result.overlapContent());
        assertEquals(12, result.overlapTokenCount());
        assertEquals("MODEL_TOKEN_LIMIT", result.overlapReductionReason());
    }

    @Test
    void reports_token_limit_when_both_budgets_reduce_but_tokens_are_tighter() {
        CharacterTailContextEnricher doubleTokenEnricher =
                new CharacterTailContextEnricher(new DoubleCodePointTokenCounter());
        ChunkRuntimePolicy combined = policy(64, 112);
        DocumentChunk previous = chunk(11L, 0, "ABCDEFGHIJKLMNOPQRST");
        DocumentChunk current = chunk(12L, 1, "x".repeat(50));
        current.setOverlapLimit(20);

        EnrichedChunk result = doubleTokenEnricher.enrich(
                List.of(previous, current), combined).get(1);

        assertEquals("T", result.overlapContent());
        assertEquals(1, result.overlapCharacterCount());
        assertEquals("MODEL_TOKEN_LIMIT", result.overlapReductionReason());
    }

    @Test
    void reports_character_limit_when_both_budgets_reduce_but_characters_are_tighter() {
        CharacterTailContextEnricher doubleTokenEnricher =
                new CharacterTailContextEnricher(new DoubleCodePointTokenCounter());
        ChunkRuntimePolicy combined = policy(64, 130);
        DocumentChunk previous = chunk(11L, 0, "ABCDEFGHIJKLMNOPQRST");
        DocumentChunk current = chunk(12L, 1, "x".repeat(54));
        current.setOverlapLimit(20);

        EnrichedChunk result = doubleTokenEnricher.enrich(
                List.of(previous, current), combined).get(1);

        assertEquals("PQRST", result.overlapContent());
        assertEquals(5, result.overlapCharacterCount());
        assertEquals("CHARACTER_LIMIT", result.overlapReductionReason());
    }

    @Test
    void equal_character_and_token_caps_use_the_stable_character_priority() {
        CharacterTailContextEnricher doubleTokenEnricher =
                new CharacterTailContextEnricher(new DoubleCodePointTokenCounter());
        ChunkRuntimePolicy equalCaps = policy(64, 128);
        DocumentChunk previous = chunk(11L, 0, "ABCDEFGHIJKLMNOPQRST");
        DocumentChunk current = chunk(12L, 1, "x".repeat(54));
        current.setOverlapLimit(20);

        EnrichedChunk result = doubleTokenEnricher.enrich(
                List.of(previous, current), equalCaps).get(1);

        assertEquals("PQRST", result.overlapContent());
        assertEquals("CHARACTER_LIMIT", result.overlapReductionReason());
    }

    @Test
    void emits_no_overlap_when_the_formatter_label_has_no_room() {
        DocumentChunk previous = chunk(11L, 0, "previous");
        DocumentChunk current = chunk(12L, 1, "x".repeat(60));
        current.setOverlapLimit(3);

        EnrichedChunk result = enricher.enrich(List.of(previous, current), policy).get(1);

        assertNull(result.overlapContent());
        assertEquals("FORMAT_OVERHEAD", result.overlapReductionReason());
        assertEquals("x".repeat(60), result.indexContent());
    }

    @Test
    void rejects_a_body_that_alone_exceeds_either_final_budget() {
        assertThrows(IllegalArgumentException.class,
                () -> enricher.enrich(List.of(chunk(11L, 0, "x".repeat(65))), policy));
        CharacterTailContextEnricher doubleTokenEnricher =
                new CharacterTailContextEnricher(new DoubleCodePointTokenCounter());
        assertThrows(IllegalArgumentException.class,
                () -> doubleTokenEnricher.enrich(List.of(chunk(11L, 0, "123456")), policy(64, 10)));
    }

    @Test
    void token_saturated_body_short_circuits_without_scanning_the_maximum_overlap() {
        CountingOverlapTokenCounter tokens = new CountingOverlapTokenCounter();
        CharacterTailContextEnricher countingEnricher = new CharacterTailContextEnricher(tokens);
        DocumentChunk previous = chunk(11L, 0, "界".repeat(1_000));
        DocumentChunk current = chunk(12L, 1, "文".repeat(64));
        current.setOverlapLimit(1_000);

        EnrichedChunk result = countingEnricher.enrich(
                List.of(previous, current), policy(2_000, 64)).get(1);

        assertNull(result.overlapContent());
        assertEquals("FORMAT_OVERHEAD", result.overlapReductionReason());
        assertEquals(2, tokens.calls());
    }

    @Test
    void maximum_unicode_overlap_uses_bounded_tokenizer_calls_and_keeps_the_exact_tail() {
        CountingOverlapTokenCounter tokens = new CountingOverlapTokenCounter();
        CharacterTailContextEnricher countingEnricher = new CharacterTailContextEnricher(tokens);
        String source = "前😀".repeat(500);
        DocumentChunk previous = chunk(11L, 0, source);
        DocumentChunk current = chunk(12L, 1, "界".repeat(40));
        current.setOverlapLimit(1_000);

        EnrichedChunk result = countingEnricher.enrich(
                List.of(previous, current), policy(2_000, 64)).get(1);

        assertEquals("😀前😀前😀前😀前😀前😀前😀前😀前😀前😀", result.overlapContent());
        assertEquals(19, result.overlapCharacterCount());
        assertEquals(64, result.indexContent().codePointCount(0, result.indexContent().length()));
        assertEquals("MODEL_TOKEN_LIMIT", result.overlapReductionReason());
        assertEquals(true, tokens.calls() <= 16, "tokenizer calls=" + tokens.calls());
    }

    private static ChunkRuntimePolicy policy(int maxCharacters, int maxTokens) {
        return new ChunkRuntimePolicy("GENERAL",
                new GeneralChunkConfig("\n", DelimiterMode.LITERAL, maxCharacters,
                        false, false, false),
                new ContextConfig(true, 3, OverlapUnit.CHARACTERS,
                        com.starsea.ai.chunking.model.ContextMode.CHARACTER_TAIL),
                maxTokens, "test");
    }

    private static DocumentChunk chunk(long id, int position, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setPosition(position);
        chunk.setContent(content);
        chunk.setSectionPath(List.of());
        chunk.setOverlapEnabled(true);
        chunk.setOverlapLimit(40);
        chunk.setOverlapUnit(OverlapUnit.CHARACTERS);
        return chunk;
    }

    private static final class CodePointTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length());
        }

        @Override
        public String id() {
            return "code-point-test";
        }
    }

    private static final class DoubleCodePointTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length()) * 2;
        }

        @Override
        public String id() {
            return "double-code-point-test";
        }
    }

    private static final class CountingOverlapTokenCounter implements TokenCounter {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public int count(String text) {
            calls.incrementAndGet();
            if (text == null) {
                return 0;
            }
            int length = text.codePointCount(0, text.length());
            return !text.startsWith("上文：") && length > 100 ? 1 : length;
        }

        @Override
        public String id() {
            return "counting-code-point-test";
        }

        private int calls() {
            return calls.get();
        }
    }
}
