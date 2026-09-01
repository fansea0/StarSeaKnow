package com.starsea.ai.chunking.model;

import java.util.List;

/** Ordered structural representation produced by a file-type-specific parser. */
public record ParsedStructure(FileResource resource, List<StructuredBlock> blocks) {

    public ParsedStructure {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
