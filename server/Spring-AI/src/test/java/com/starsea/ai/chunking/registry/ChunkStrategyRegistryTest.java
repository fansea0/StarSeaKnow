package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.spi.ChunkPlanningStrategy;
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

    private static final class MarkdownStrategy implements ChunkPlanningStrategy {

        @Override
        public String code() {
            return "MARKDOWN_OPTIMIZED";
        }

        @Override
        public Set<String> supportedFileTypes() {
            return Set.of("md");
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
}
