package com.starsea.ai.chunking.model;

import java.util.List;
import java.util.Map;

/** A file-format-neutral structural block. */
public record StructuredBlock(
        String blockId,
        BlockType type,
        String rawText,
        String plainText,
        Integer headingLevel,
        List<String> sectionPath,
        int tokenCount,
        SourceLocator sourceLocator,
        Map<String, Object> attributes) {

    public StructuredBlock {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
