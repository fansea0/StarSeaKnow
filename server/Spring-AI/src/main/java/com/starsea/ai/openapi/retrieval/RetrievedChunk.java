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
        Map<String, Object> sourceLocator,
        UUID knowledgeId,
        String knowledgeName) {

    public RetrievedChunk(String content, double score, String title, UUID documentId, UUID chunkId,
                          String fileType, Integer pageNumber, Integer chunkIndex, List<String> sectionPath,
                          Map<String, Object> sourceLocator) {
        this(content, score, title, documentId, chunkId, fileType, pageNumber, chunkIndex, sectionPath,
                sourceLocator, null, null);
    }

    public RetrievedChunk {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        sourceLocator = sourceLocator == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sourceLocator));
    }
}
