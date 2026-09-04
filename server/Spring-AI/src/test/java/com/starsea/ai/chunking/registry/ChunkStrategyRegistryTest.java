package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.markdown.MarkdownParentChildPlanningStrategy;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.chunking.spi.DocumentStructureParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void descriptors_return_every_matching_strategy_in_registration_order() {
        TokenCounter counter = new TokenCounter() {
            @Override
            public int count(String text) {
                return text == null ? 0 : text.length();
            }

            @Override
            public String id() {
                return "test";
            }
        };
        ChunkStrategyRegistry registry = new ChunkStrategyRegistry(List.of(
                new MarkdownStrategy(), new MarkdownParentChildPlanningStrategy(counter)));

        assertEquals(List.of("MARKDOWN_OPTIMIZED", "PARENT_CHILD"),
                registry.descriptors("md").stream().map(ChunkStrategyDescriptor::code).toList());
    }

    @Test
    void rejects_parsers_with_the_same_normalized_file_type() {
        assertThrows(IllegalArgumentException.class, () -> new DocumentStructureParserRegistry(List.of(
                new TestParser(Set.of("md")),
                new TestParser(Set.of(".MD")))));
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

    private record TestParser(Set<String> supportedFileTypes) implements DocumentStructureParser {

        @Override
        public ParsedStructure parse(FileResource resource) {
            throw new UnsupportedOperationException("Not needed for registry lookup tests");
        }
    }
}
