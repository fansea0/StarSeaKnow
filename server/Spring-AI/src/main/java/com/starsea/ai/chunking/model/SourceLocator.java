package com.starsea.ai.chunking.model;

import java.util.List;
import java.util.Map;

/** Cross-format source coordinates, serialized as one JSONB value by persistence consumers. */
public record SourceLocator(
        String type,
        List<String> blockIds,
        Integer startOffset,
        Integer endOffset,
        Integer startLine,
        Integer endLine,
        Integer startPage,
        Integer endPage,
        List<Map<String, Object>> regions) {

    public SourceLocator {
        blockIds = blockIds == null ? List.of() : List.copyOf(blockIds);
        regions = regions == null ? List.of() : regions.stream().map(Map::copyOf).toList();
    }
}
