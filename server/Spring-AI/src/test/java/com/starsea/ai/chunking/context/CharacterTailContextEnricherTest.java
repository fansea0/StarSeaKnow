package com.starsea.ai.chunking.context;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
        assertEquals(3, tokens.invocations());
    }

    @Test
    void real_bge_wordpiece_selects_about_despite_non_monotonic_suffix_counts() throws IOException {
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(
                new ClassPathResource("tokenizer/bge-base-zh-v1.5-tokenizer.json")
                        .getInputStream(), Map.of());
             com.starsea.ai.chunking.token.HuggingFaceTokenCounter tokens =
                     new com.starsea.ai.chunking.token.HuggingFaceTokenCounter(
                             tokenizer, "BAAI/bge-base-zh-v1.5@7dfbf196")) {
            CharacterTailContextEnricher exact = new CharacterTailContextEnricher(tokens);
            DocumentChunk previous = chunk(11L, 0, "about");
            DocumentChunk current = chunk(12L, 1, "body");
            current.setOverlapLimit(5);

            EnrichedChunk result = exact.enrich(
                    List.of(previous, current), policy(64, 7)).get(1);

            assertEquals("about", result.overlapContent());
            assertEquals(7, tokens.count(result.indexContent()));
        }
    }

    @Test
    void randomized_suffix_selection_matches_an_exhaustive_non_monotonic_oracle() {
        BatchNonMonotonicTokenCounter tokens = new BatchNonMonotonicTokenCounter();
        CharacterTailContextEnricher exact = new CharacterTailContextEnricher(tokens);
        Random random = new Random(20260904L);
        String alphabet = "ab界😀 ";
        for (int iteration = 0; iteration < 200; iteration++) {
            int sourceLength = 1 + random.nextInt(48);
            StringBuilder source = new StringBuilder();
            for (int i = 0; i < sourceLength; i++) {
                int offset = random.nextInt(alphabet.codePointCount(0, alphabet.length()));
                int start = alphabet.offsetByCodePoints(0, offset);
                source.appendCodePoint(alphabet.codePointAt(start));
            }
            DocumentChunk previous = chunk(11L, 0, source.toString());
            DocumentChunk current = chunk(12L, 1, "body");
            int limit = 1 + random.nextInt(sourceLength);
            int maxTokens = 5 + random.nextInt(11);
            current.setOverlapLimit(limit);

            EnrichedChunk result = exact.enrich(
                    List.of(previous, current), policy(256, maxTokens)).get(1);

            assertEquals(exhaustiveTail(source.toString(), limit, maxTokens, tokens),
                    result.overlapContent(), "iteration=" + iteration);
            assertEquals(true, result.indexContent().codePointCount(
                    0, result.indexContent().length()) <= 256);
            assertEquals(true, tokens.rawCount(result.indexContent()) <= maxTokens);
        }
    }

    @Test
    void character_limit_does_not_select_a_blank_tail_omitted_by_the_formatter() {
        DocumentChunk previous = chunk(11L, 0, "A ");
        DocumentChunk current = chunk(12L, 1, "x".repeat(60));
        current.setOverlapLimit(2);

        EnrichedChunk result = enricher.enrich(
                List.of(previous, current), policy(64, 512)).get(1);

        assertNull(result.overlapContent());
        assertEquals("x".repeat(60), result.indexContent());
        assertEquals("FORMAT_OVERHEAD", result.overlapReductionReason());
    }

    @Test
    void unicode_whitespace_only_source_never_becomes_persisted_overlap() {
        DocumentChunk previous = chunk(11L, 0, " \t\u00a0\u3000\n");
        DocumentChunk current = chunk(12L, 1, "body");
        current.setOverlapLimit(5);

        EnrichedChunk result = enricher.enrich(
                List.of(previous, current), policy(64, 512)).get(1);

        assertNull(result.overlapContent());
        assertEquals("body", result.indexContent());
    }

    private static String exhaustiveTail(String source, int limit, int maxTokens,
                                         BatchNonMonotonicTokenCounter tokens) {
        int sourceLength = source.codePointCount(0, source.length());
        String accepted = null;
        for (int length = 1; length <= Math.min(limit, sourceLength); length++) {
            int start = source.offsetByCodePoints(0, sourceLength - length);
            String candidate = source.substring(start);
            if (candidate.codePoints().allMatch(value -> Character.isWhitespace(value)
                    || Character.isSpaceChar(value))) {
                continue;
            }
            String index = new ChunkIndexContentBuilder().build(List.of(), candidate, "body");
            if (tokens.rawCount(index) <= maxTokens) {
                accepted = candidate;
            }
        }
        return accepted;
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
        private final AtomicInteger batches = new AtomicInteger();

        @Override
        public int count(String text) {
            calls.incrementAndGet();
            return rawCount(text);
        }

        @Override
        public List<Integer> countBatch(List<String> texts) {
            batches.incrementAndGet();
            return texts.stream().map(this::rawCount).toList();
        }

        private int rawCount(String text) {
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

        private int invocations() {
            return calls.get() + batches.get();
        }
    }

    private static final class BatchNonMonotonicTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            return rawCount(text);
        }

        @Override
        public List<Integer> countBatch(List<String> texts) {
            return texts.stream().map(this::rawCount).toList();
        }

        private int rawCount(String text) {
            if (text == null || !text.startsWith("上文：")) {
                return 3;
            }
            int end = text.indexOf("\n\n");
            int overlapLength = text.substring(3, end).codePointCount(0, end - 3);
            return 5 + Math.floorMod(overlapLength * 7, 11);
        }

        @Override
        public String id() {
            return "batch-non-monotonic-test";
        }
    }
}
