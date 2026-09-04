package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.model.SourceLocator;

public record BoundaryUnit(
        String text,
        int cleanedStart,
        int cleanedEnd,
        SourceLocator sourceLocator,
        BoundaryKind boundaryAfter) {
}
