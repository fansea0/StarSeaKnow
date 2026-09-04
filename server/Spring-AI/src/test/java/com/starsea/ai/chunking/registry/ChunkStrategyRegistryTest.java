package com.starsea.ai.chunking.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.chunking.api.ChunkingException;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.ValidatedPreviewConfig;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkStrategyRegistryTest {

    @Test
    void rejects_invalid_token_order() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkPolicy(400, 100, 512));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPolicy(100, 400, 513));
    }

    @Test
    void keeps_context_overlap_out_of_the_chunk_policy() {
        assertEquals(new ContextPolicy(false, 40), ContextPolicy.defaults());
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy(true, -1));
        assertThrows(IllegalArgumentException.class, () -> new ContextPolicy(true, 513));
    }

    @Test
    void registry_returns_only_real_matching_implementations() {
        ChunkStrategyRegistry registry = new ChunkStrategyRegistry(List.of(new MarkdownStrategy()));

        assertEquals("MARKDOWN_OPTIMIZED", registry.require("MARKDOWN_OPTIMIZED", "md").code());
        assertEquals("MARKDOWN_OPTIMIZED", registry.require("markdown_optimized", ".MD").code());
        assertThrows(ChunkStrategyNotFoundException.class, () -> registry.require("PARENT_CHILD", "md"));
        assertThrows(ChunkStrategyNotFoundException.class,
                () -> registry.require("MARKDOWN_OPTIMIZED", "pdf"));
    }

    @Test
    void rejects_strategies_with_the_same_normalized_code_and_file_type() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkStrategyRegistry(List.of(
                new MarkdownStrategy("MARKDOWN_OPTIMIZED", Set.of("md")),
                new MarkdownStrategy("markdown_optimized", Set.of(".MD")))));
    }

    @Test
    void allows_one_strategy_to_support_multiple_distinct_file_types() {
        ChunkStrategyRegistry registry = new ChunkStrategyRegistry(List.of(
                new MarkdownStrategy("MARKDOWN_OPTIMIZED", Set.of("md", "markdown"))));

        assertEquals("MARKDOWN_OPTIMIZED", registry.require("MARKDOWN_OPTIMIZED", "markdown").code());
    }

    @Test
    void rejects_parsers_with_the_same_normalized_file_type() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentStructureParserRegistry(List.of(
                new TestParser(Set.of("md")),
                new TestParser(Set.of(".MD")))));
    }

    @Test
    void validates_general_raw_config_and_derives_character_context_on_the_server() {
        ChunkStrategyRegistry registry = registryWith(new GeneralStrategy());

        ValidatedPreviewConfig validated = registry.validatePreviewConfig(
                "general", "pdf",
                Map.of("delimiter", "\n", "delimiterMode", "LITERAL", "maxCharacters", 500,
                        "collapseWhitespace", true, "removeUrls", false, "removeEmails", false),
                Map.of("enabled", true, "limit", 40));

        GeneralChunkConfig config = (GeneralChunkConfig) validated.strategyConfig();
        assertEquals(DelimiterMode.LITERAL, config.delimiterMode());
        assertEquals(500, config.maxCharacters());
        assertEquals(new ContextConfig(true, 40, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL),
                validated.contextConfig());
        assertEquals(ChunkPolicy.MAX_ALLOWED_TOKENS, validated.maxIndexTokens());
    }

    @Test
    void global_strategy_matches_file_types_without_an_extension_allowlist() {
        ChunkStrategyRegistry registry = registryWith(new GeneralStrategy());

        assertEquals("GENERAL", registry.require("general", "docx").code());
    }

    @Test
    void normalizes_zero_general_overlap_to_disabled() {
        ValidatedPreviewConfig validated = registryWith(new GeneralStrategy()).validatePreviewConfig(
                "GENERAL", "txt", Map.of(), Map.of("enabled", true, "limit", 0));

        assertEquals(new ContextConfig(false, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL),
                validated.contextConfig());
    }

    @Test
    void rejects_general_overlap_that_leaves_no_body_budget_before_dispatch() {
        ChunkingException failure = assertThrows(ChunkingException.class,
                () -> registryWith(new GeneralStrategy()).validatePreviewConfig(
                        "GENERAL", "txt",
                        Map.of("delimiter", "\n", "delimiterMode", "LITERAL", "maxCharacters", 64,
                                "collapseWhitespace", true, "removeUrls", false, "removeEmails", false),
                        Map.of("enabled", true, "limit", 59)));

        assertEquals("INVALID_STRATEGY_CONFIG", failure.details().get("code"));
        assertTrue(((Map<?, ?>) failure.details().get("fieldErrors")).containsKey("limit"));
    }

    @Test
    void descriptor_exposes_context_defaults_separately_from_strategy_fields() {
        ChunkStrategyDescriptor descriptor = new GeneralStrategy().descriptor();

        assertEquals(new ContextConfig(true, 40, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL),
                descriptor.defaultContextConfig());
    }

    @Test
    void markdown_user_budget_does_not_replace_the_server_token_hard_limit() {
        ValidatedPreviewConfig validated = registryWith(new MarkdownStrategy()).validatePreviewConfig(
                "MARKDOWN_OPTIMIZED", "md",
                Map.of("minTokens", 20, "targetTokens", 80, "maxTokens", 120), Map.of());

        assertEquals(new ChunkPolicy(20, 80, 120), validated.strategyConfig());
        assertEquals(ChunkPolicy.MAX_ALLOWED_TOKENS, validated.maxIndexTokens());
    }

    private ChunkStrategyRegistry registryWith(ChunkPlanningStrategy strategy) {
        return new ChunkStrategyRegistry(List.of(strategy), new ObjectMapper());
    }

    private static final class MarkdownStrategy implements ChunkPlanningStrategy {

        private final String code;
        private final Set<String> supportedFileTypes;

        private MarkdownStrategy() {
            this("MARKDOWN_OPTIMIZED", Set.of("md"));
        }

        private MarkdownStrategy(String code, Set<String> supportedFileTypes) {
            this.code = code;
            this.supportedFileTypes = supportedFileTypes;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public Set<String> supportedFileTypes() {
            return supportedFileTypes;
        }

        @Override
        public String plannerVersion() {
            return "test-v1";
        }

        @Override
        public ChunkStrategyDescriptor descriptor() {
            return new ChunkStrategyDescriptor(code(), supportedFileTypes(), plannerVersion());
        }

        @Override
        public List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy) {
            return List.of();
        }
    }

    private static final class GeneralStrategy implements ChunkPlanningStrategy {

        @Override
        public String code() {
            return "GENERAL";
        }

        @Override
        public Set<String> supportedFileTypes() {
            return Set.of();
        }

        @Override
        public String plannerVersion() {
            return "general-v1";
        }

        @Override
        public ChunkStrategyDescriptor descriptor() {
            return new ChunkStrategyDescriptor(code(), "GLOBAL", supportedFileTypes(), plannerVersion(), List.of(),
                    ContextConfig.generalDefaults());
        }

        @Override
        public List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy) {
            return List.of();
        }
    }

    private record TestParser(Set<String> supportedFileTypes) implements DocumentStructureParser {

        @Override
        public ParsedStructure parse(FileResource resource) {
            throw new UnsupportedOperationException("Not needed for registry lookup tests");
        }
    }
}
