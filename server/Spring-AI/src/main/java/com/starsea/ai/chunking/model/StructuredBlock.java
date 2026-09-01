package com.starsea.ai.chunking.model;

import java.util.List;
import java.util.Map;

/** A file-format-neutral structural block. Type is a stable textual code until Task 3 adds Markdown block enums. */
public record StructuredBlock(
        String blockId,
        String type,
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
