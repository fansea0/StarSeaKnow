package com.starsea.ai.chunking.extraction;

import java.util.List;
import java.util.Map;

public record ExtractedText(
        String text,
        String mediaType,
        String extractorId,
        String extractorVersion,
        List<SourceSpan> sourceSpans,
        Map<String, Object> metadata) {

    public ExtractedText {
        text = text == null ? "" : text;
        sourceSpans = sourceSpans == null ? List.of() : List.copyOf(sourceSpans);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
