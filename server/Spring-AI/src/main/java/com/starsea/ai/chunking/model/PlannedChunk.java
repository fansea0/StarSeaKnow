package com.starsea.ai.chunking.model;

import java.util.Objects;

/** One ordered chunk in a planner result, before any persistence identifiers exist. */
public record PlannedChunk(
        String key,
        String parentKey,
        ChunkType type,
        int siblingPosition,
        ChunkDraft draft,
        boolean overlapEnabled,
        int overlapTokenLimit) {

    public PlannedChunk {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A planned chunk key is required");
        }
        type = Objects.requireNonNull(type, "type");
        draft = Objects.requireNonNull(draft, "draft");
        if (siblingPosition < 0) {
            throw new IllegalArgumentException("A planned chunk sibling position cannot be negative");
        }
        if (type == ChunkType.CHILD && (parentKey == null || parentKey.isBlank())) {
            throw new IllegalArgumentException("A CHILD planned chunk requires a parent key");
        }
        if (type != ChunkType.CHILD && parentKey != null) {
            throw new IllegalArgumentException("Only a CHILD planned chunk may have a parent key");
        }
        if (overlapTokenLimit < 0) {
            throw new IllegalArgumentException("A planned chunk overlap token limit cannot be negative");
        }
    }
}
