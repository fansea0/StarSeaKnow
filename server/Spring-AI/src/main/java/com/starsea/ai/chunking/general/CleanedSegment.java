package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.model.BlockType;
import com.starsea.ai.chunking.model.SourceLocator;
import com.starsea.ai.chunking.model.StructuredBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CleanedSegment(
        String text,
        int normalizedStart,
        int normalizedEnd,
        SourceLocator sourceLocator,
        BoundaryKind boundaryAfter,
        CleanedOffsetMap offsetMap) {

    public static final String OFFSET_MAP_ATTRIBUTE = "generalCleanedOffsetMap";

    public CleanedSegment(String text, int normalizedStart, int normalizedEnd,
                          SourceLocator sourceLocator, BoundaryKind boundaryAfter) {
        this(text, normalizedStart, normalizedEnd, sourceLocator, boundaryAfter,
                CleanedOffsetMap.identity(text, sourceLocator != null && sourceLocator.startOffset() != null
                        ? sourceLocator.startOffset() : normalizedStart));
    }

    public StructuredBlock toStructuredBlock(String blockId, int tokenCount) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("boundaryAfter", boundaryAfter == null ? "DOCUMENT_END" : boundaryAfter.name());
        attributes.put(OFFSET_MAP_ATTRIBUTE, offsetMap);
        SourceLocator locator = sourceLocator == null ? null : new SourceLocator(
                sourceLocator.type(), List.of(blockId), sourceLocator.startOffset(), sourceLocator.endOffset(),
                sourceLocator.startLine(), sourceLocator.endLine(), sourceLocator.startPage(),
                sourceLocator.endPage(), sourceLocator.regions());
        return new StructuredBlock(blockId, BlockType.PARAGRAPH, text, text, null, List.of(),
                tokenCount, locator, attributes);
    }
}
