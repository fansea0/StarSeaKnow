package com.starsea.ai.openapi.retrieval;

import java.util.Objects;
import java.util.Set;

public record RetrievalQuery(String query, Set<Long> knowledgeIds, int topK, double scoreThreshold) {

    public RetrievalQuery {
        query = Objects.requireNonNull(query, "query must not be null");
        knowledgeIds = Set.copyOf(Objects.requireNonNull(knowledgeIds, "knowledgeIds must not be null"));
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        if (!Double.isFinite(scoreThreshold) || scoreThreshold < 0.0 || scoreThreshold > 1.0) {
            throw new IllegalArgumentException("scoreThreshold must be between 0 and 1");
        }
    }
}
