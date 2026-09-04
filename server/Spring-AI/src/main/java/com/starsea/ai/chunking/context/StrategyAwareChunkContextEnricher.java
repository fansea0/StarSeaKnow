package com.starsea.ai.chunking.context;

import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.chunking.runtime.ChunkRuntimePolicy;
import com.starsea.ai.chunking.spi.ChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.DocumentChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Selects the persisted strategy's one valid context algorithm. */
@Component
@Primary
public final class StrategyAwareChunkContextEnricher implements ChunkContextEnricher {

    private final DefaultChunkContextEnricher markdown;
    private final CharacterTailContextEnricher general;

    @Autowired
    public StrategyAwareChunkContextEnricher(TokenCounter tokenCounter) {
        Objects.requireNonNull(tokenCounter, "tokenCounter");
        this.markdown = new DefaultChunkContextEnricher(tokenCounter);
        this.general = new CharacterTailContextEnricher(tokenCounter);
    }

    @Override
    public List<EnrichedChunk> enrich(List<DocumentChunk> chunks, ChunkRuntimePolicy policy) {
        return switch (policy.strategyCode()) {
            case "GENERAL" -> general.enrich(chunks, policy);
            case "MARKDOWN_OPTIMIZED" -> markdown.enrich(chunks, policy.maxIndexTokens());
            default -> throw new IllegalArgumentException("Unknown chunk strategy: " + policy.strategyCode());
        };
    }

    @Override
    public List<EnrichedChunk> enrich(List<DocumentChunk> chunks, int maxTokens) {
        return markdown.enrich(chunks, maxTokens);
    }

    @Override
    public List<EnrichedChunk> enrich(List<DocumentChunk> chunks, ContextPolicy policy, int maxTokens) {
        return markdown.enrich(chunks, policy, maxTokens);
    }
}
