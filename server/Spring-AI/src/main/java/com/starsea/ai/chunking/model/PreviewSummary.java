package com.starsea.ai.chunking.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable preview-level measurements saved with a successful generation. */
public record PreviewSummary(
        PreprocessingSummary preprocessingSummary,
        boolean delimiterMatched,
        int forcedSplitCount,
        int tokenLimitedSplitCount) {

    public PreviewSummary {
        preprocessingSummary = preprocessingSummary == null
                ? PreprocessingSummary.empty() : preprocessingSummary;
        if (forcedSplitCount < 0 || tokenLimitedSplitCount < 0) {
            throw new IllegalArgumentException("preview split counters must not be negative");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("preprocessingSummary", preprocessingSummary.toMap());
        values.put("delimiterMatched", delimiterMatched);
        values.put("forcedSplitCount", forcedSplitCount);
        values.put("tokenLimitedSplitCount", tokenLimitedSplitCount);
        return Map.copyOf(values);
    }

    public static PreviewSummary fromMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return null;
        Object rawPreprocessing = values.get("preprocessingSummary");
        @SuppressWarnings("unchecked")
        Map<String, Object> preprocessing = rawPreprocessing instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return new PreviewSummary(PreprocessingSummary.fromMap(preprocessing),
                Boolean.TRUE.equals(values.get("delimiterMatched")),
                integer(values, "forcedSplitCount"), integer(values, "tokenLimitedSplitCount"));
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
