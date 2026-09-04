package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.model.SourceLocator;

public record DelimitedSegment(
        String text,
        int normalizedStart,
        int normalizedEnd,
        SourceLocator sourceLocator,
        BoundaryKind boundaryAfter) {
}
