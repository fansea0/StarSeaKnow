package com.starsea.ai.chunking.model;

import java.util.List;
import java.util.Map;

/** In-memory chunk candidate before preview persistence. */
public record ChunkDraft(
        List<String> sectionPath,
        String content,
        SourceLocator sourceLocator,
        int tokenCount,
        Map<String, Object> boundaryReason) {

    public ChunkDraft {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        boundaryReason = boundaryReason == null ? Map.of() : Map.copyOf(boundaryReason);
    }
}
