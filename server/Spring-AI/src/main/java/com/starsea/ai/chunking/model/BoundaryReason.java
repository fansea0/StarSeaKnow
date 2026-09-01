package com.starsea.ai.chunking.model;

import java.util.Map;

/** Stable, persistence-ready explanation of an automatically selected chunk boundary. */
public record BoundaryReason(String start, String end, boolean forcedSplit) {

    public Map<String, Object> asMap() {
        return Map.of(
                "start", start,
                "end", end,
                "forcedSplit", forcedSplit);
    }
}
