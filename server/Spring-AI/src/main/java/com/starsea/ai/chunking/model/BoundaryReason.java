package com.starsea.ai.chunking.model;

import java.util.Map;

/** Stable, persistence-ready explanation of an automatically selected chunk boundary. */
public record BoundaryReason(String start, String end, boolean forcedSplit) {

    public static final String DOCUMENT_START = "DOCUMENT_START";
    public static final String DOCUMENT_END = "DOCUMENT_END";
    public static final String MODEL_TOKEN_LIMIT = "MODEL_TOKEN_LIMIT";

    public Map<String, Object> asMap() {
        return Map.of(
                "start", start,
                "end", end,
                "forcedSplit", forcedSplit);
    }
}
