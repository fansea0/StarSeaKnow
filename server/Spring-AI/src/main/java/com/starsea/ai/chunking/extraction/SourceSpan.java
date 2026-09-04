package com.starsea.ai.chunking.extraction;

import java.util.Map;

public record SourceSpan(int textStart, int textEnd, Map<String, Object> source) {
    public SourceSpan {
        if (textStart < 0 || textEnd < textStart) {
            throw new IllegalArgumentException("Invalid extracted text span");
        }
        source = source == null ? Map.of() : Map.copyOf(source);
    }
}
