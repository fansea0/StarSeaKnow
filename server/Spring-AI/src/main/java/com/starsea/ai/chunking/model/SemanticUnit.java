package com.starsea.ai.chunking.model;

import java.util.List;
import java.util.Map;

/** Adjacent blocks that a file-type-specific planner should keep together where possible. */
public record SemanticUnit(
        String unitId,
        List<StructuredBlock> blocks,
        List<String> sectionPath,
        int tokenCount,
        Map<String, Object> attributes) {

    public SemanticUnit {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
