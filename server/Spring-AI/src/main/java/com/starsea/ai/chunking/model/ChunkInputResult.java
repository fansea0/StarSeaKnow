package com.starsea.ai.chunking.model;

import java.util.Map;
import java.util.Objects;

/** Provider-owned source conversion output. */
public record ChunkInputResult(
        ParsedStructure structure,
        Map<String, Object> extractorMetadata,
        PreprocessingSummary preprocessingSummary,
        boolean delimiterMatched) {

    public ChunkInputResult {
        Objects.requireNonNull(structure, "structure");
        extractorMetadata = extractorMetadata == null ? Map.of() : Map.copyOf(extractorMetadata);
        preprocessingSummary = preprocessingSummary == null
                ? PreprocessingSummary.empty() : preprocessingSummary;
    }
}
