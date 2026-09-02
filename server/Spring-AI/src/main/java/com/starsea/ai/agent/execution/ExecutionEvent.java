package com.starsea.ai.agent.execution;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExecutionEvent(String type, Object data) {
    public record Citation(String id, UUID knowledgeId, String knowledgeName, UUID documentId, String title,
                           UUID chunkId, String fileType, Integer pageNumber, Integer chunkIndex,
                           List<String> sectionPath, Map<String, Object> sourceLocator, double score, String summary) { }
    public record Retrieval(List<Citation> citations) { }
    public record Delta(String text) { }
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens, long elapsedMs) { }
    public record Complete(String finishReason) { }
    public record Failure(String code, String message) { }
    @Override public String toString() { return "ExecutionEvent[type=" + type + ", data=<redacted>]"; }
}
