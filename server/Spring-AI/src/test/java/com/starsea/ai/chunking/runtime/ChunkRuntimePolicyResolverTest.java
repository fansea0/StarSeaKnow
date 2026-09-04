package com.starsea.ai.chunking.runtime;

import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChunkRuntimePolicyResolverTest {

    private final ChunkRuntimePolicyResolver resolver = new ChunkRuntimePolicyResolver();

    @Test
    void resolves_old_markdown_snapshot_with_tokenizer_and_max_token_fallbacks() {
        ChunkRuntimePolicy runtime = resolver.resolve(
                "MARKDOWN_OPTIMIZED",
                Map.of("minTokens", 20, "targetTokens", 80, "maxTokens", 120,
                        "tokenizer", "legacy-tokenizer"),
                Map.of("overlapEnabled", true, "overlapTokens", 32),
                Map.of());

        assertEquals(new ChunkPolicy(20, 80, 120), runtime.strategyConfig());
        assertEquals(120, runtime.maxIndexTokens());
        assertEquals("legacy-tokenizer", runtime.tokenizerId());
        assertEquals(OverlapUnit.TOKENS, runtime.contextConfig().unit());
        assertEquals(ContextMode.COMPLETE_SENTENCE, runtime.contextConfig().mode());
        assertEquals(32, runtime.contextConfig().limit());
    }

    @Test
    void execution_metadata_overrides_legacy_markdown_runtime_values() {
        ChunkRuntimePolicy runtime = resolver.resolve(
                "MARKDOWN_OPTIMIZED",
                Map.of("minTokens", 20, "targetTokens", 80, "maxTokens", 120,
                        "tokenizer", "legacy-tokenizer"),
                Map.of("enabled", false, "limit", 40),
                Map.of("tokenizerId", "persisted-tokenizer", "tokenHardLimit", 256));

        assertEquals(256, runtime.maxIndexTokens());
        assertEquals("persisted-tokenizer", runtime.tokenizerId());
    }

    @Test
    void normalizes_legacy_enabled_zero_markdown_context_to_disabled() {
        ChunkRuntimePolicy runtime = resolver.resolve(
                "MARKDOWN_OPTIMIZED", Map.of("maxTokens", 512),
                Map.of("overlapEnabled", true, "overlapTokens", 0), Map.of());

        assertEquals(false, runtime.contextConfig().enabled());
        assertEquals(0, runtime.contextConfig().limit());
    }

    @Test
    void resolves_partial_legacy_markdown_snapshot_that_only_saved_max_tokens() {
        ChunkRuntimePolicy runtime = resolver.resolve(
                "MARKDOWN_OPTIMIZED", Map.of("maxTokens", 12), Map.of(), Map.of());

        assertEquals(new ChunkPolicy(12, 12, 12), runtime.strategyConfig());
        assertEquals(12, runtime.maxIndexTokens());
    }

    @Test
    void resolves_general_character_policy_and_server_owned_context_mode() {
        ChunkRuntimePolicy runtime = resolver.resolve(
                "GENERAL",
                Map.of("delimiter", "\n", "delimiterMode", "LITERAL", "maxCharacters", 640,
                        "collapseWhitespace", true, "removeUrls", true, "removeEmails", false),
                Map.of("enabled", true, "limit", 50, "unit", "TOKENS", "mode", "COMPLETE_SENTENCE"),
                Map.of("tokenizerId", "embedding-tokenizer", "tokenHardLimit", 384));

        GeneralChunkConfig config = assertInstanceOf(GeneralChunkConfig.class, runtime.strategyConfig());
        assertEquals(DelimiterMode.LITERAL, config.delimiterMode());
        assertEquals(640, config.maxCharacters());
        assertEquals(OverlapUnit.CHARACTERS, runtime.contextConfig().unit());
        assertEquals(ContextMode.CHARACTER_TAIL, runtime.contextConfig().mode());
        assertEquals(384, runtime.maxIndexTokens());
    }
}
