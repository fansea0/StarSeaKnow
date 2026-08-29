package com.starsea.ai.openapi.retrieval;

import java.util.UUID;

public record RetrievedChunk(
        String content,
        double score,
        String title,
        UUID documentId,
        UUID chunkId,
        String fileType,
        Integer pageNumber,
        Integer chunkIndex) {
}
