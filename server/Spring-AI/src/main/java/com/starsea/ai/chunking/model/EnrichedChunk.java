package com.starsea.ai.chunking.model;

import com.starsea.ai.domain.DocumentChunk;

/** Read-only indexing representation derived from a persisted, possibly edited chunk. */
public record EnrichedChunk(
        DocumentChunk chunk,
        Long overlapSourceChunkId,
        String overlapContent,
        int overlapTokenCount,
        int overlapCharacterCount,
        String overlapReductionReason,
        String indexContent) {

    /** Legacy Markdown-compatible constructor. */
    public EnrichedChunk(DocumentChunk chunk, Long overlapSourceChunkId, String overlapContent,
                         int overlapTokenCount, String indexContent) {
        this(chunk, overlapSourceChunkId, overlapContent, overlapTokenCount,
                overlapContent == null ? 0 : overlapContent.codePointCount(0, overlapContent.length()),
                null, indexContent);
    }
}
