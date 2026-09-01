package com.starsea.ai.openapi.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetrievedChunk(
        String content,
        double score,
        String title,
        UUID documentId,
        UUID chunkId,
        String fileType,
        Integer pageNumber,
        Integer chunkIndex,
        List<String> sectionPath,
        Map<String, Object> sourceLocator) {

    public RetrievedChunk {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        sourceLocator = sourceLocator == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sourceLocator));
    }
}
