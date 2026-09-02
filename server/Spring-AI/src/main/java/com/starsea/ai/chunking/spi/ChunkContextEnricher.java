package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.ContextPolicy;
import com.starsea.ai.chunking.model.EnrichedChunk;
import com.starsea.ai.domain.DocumentChunk;

import java.util.List;

/** Adds bounded, read-only context to persisted chunks immediately before indexing. */
public interface ChunkContextEnricher {

    List<EnrichedChunk> enrich(List<DocumentChunk> chunks, int maxTokens);

    List<EnrichedChunk> enrich(List<DocumentChunk> chunks, ContextPolicy policy, int maxTokens);
}
