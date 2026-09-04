package com.starsea.ai.chunking.model;

import java.util.List;

/** Planner output plus preview-level boundary counters. */
public record ChunkPlanningResult(
        List<ChunkDraft> drafts,
        int forcedSplitCount,
        int tokenLimitedSplitCount) {

    public ChunkPlanningResult {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        if (forcedSplitCount < 0 || tokenLimitedSplitCount < 0) {
            throw new IllegalArgumentException("split counters must not be negative");
        }
    }
}
