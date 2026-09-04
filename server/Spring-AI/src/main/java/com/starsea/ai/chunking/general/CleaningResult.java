package com.starsea.ai.chunking.general;

import java.util.List;

public record CleaningResult(List<CleanedSegment> segments, CleaningStats stats) {
    public CleaningResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
