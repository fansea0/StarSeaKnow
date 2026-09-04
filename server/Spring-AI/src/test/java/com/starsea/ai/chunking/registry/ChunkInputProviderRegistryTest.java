package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.model.ChunkInputResult;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkInputProviderRegistryTest {

    @Test
    void routes_by_strategy_before_applying_file_type_scope() {
        ChunkInputProvider general = provider("GENERAL", Set.of("*"));
        ChunkInputProvider markdown = provider("MARKDOWN_OPTIMIZED", Set.of("md", "markdown"));
        ChunkInputProviderRegistry registry = new ChunkInputProviderRegistry(
                java.util.List.of(general, markdown));

        assertEquals(general, registry.require("general", "PDF"));
        assertEquals(general, registry.require("GENERAL", "md"));
        assertEquals(markdown, registry.require("markdown_optimized", ".MD"));
        assertThrows(ChunkStrategyNotFoundException.class,
                () -> registry.require("MARKDOWN_OPTIMIZED", "pdf"));
    }

    @Test
    void rejects_duplicate_normalized_provider_registration() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkInputProviderRegistry(
                java.util.List.of(provider("GENERAL", Set.of("*")),
                        provider("general", Set.of("*")))));
    }

    @Test
    void rejects_global_and_file_specific_providers_for_the_same_strategy() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkInputProviderRegistry(
                java.util.List.of(provider("GENERAL", Set.of("*")),
                        provider("general", Set.of("md")))));
    }

    private ChunkInputProvider provider(String strategyCode, Set<String> types) {
        return new ChunkInputProvider() {
            @Override public String strategyCode() { return strategyCode; }
            @Override public Set<String> supportedFileTypes() { return types; }
            @Override public Capability capability(FileResource resource) { return Capability.supported(); }
            @Override public ChunkInputResult provide(FileResource resource, String sourceHash,
                                                       ChunkStrategyConfig config) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
