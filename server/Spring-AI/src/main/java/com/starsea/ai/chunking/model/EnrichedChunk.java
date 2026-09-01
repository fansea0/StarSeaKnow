package com.starsea.ai.chunking.model;

import com.starsea.ai.domain.DocumentChunk;

/** Read-only indexing representation derived from a persisted, possibly edited chunk. */
public record EnrichedChunk(
        DocumentChunk chunk,
        Long overlapSourceChunkId,
        String overlapContent,
        int overlapTokenCount,
        String indexContent) {
}
