package com.starsea.ai.chunking.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered planner result with the maximum token budget for vectorized chunks. */
public record ChunkPlan(List<PlannedChunk> chunks, int indexMaxTokens) {

    public ChunkPlan {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        if (indexMaxTokens <= 0 || indexMaxTokens > ChunkPolicy.MAX_ALLOWED_TOKENS) {
            throw new IllegalArgumentException("The index token limit must be between 1 and 512");
        }
        Set<String> keys = new HashSet<>();
        for (PlannedChunk chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks cannot contain null values");
            if (!keys.add(chunk.key())) {
                throw new IllegalArgumentException("Planned chunk keys must be unique: " + chunk.key());
            }
        }
    }

    public static ChunkPlan flat(List<ChunkDraft> drafts, int indexMaxTokens) {
        List<ChunkDraft> source = drafts == null ? List.of() : List.copyOf(drafts);
        return new ChunkPlan(java.util.stream.IntStream.range(0, source.size())
                .mapToObj(position -> new PlannedChunk(
                        "single-" + position, null, ChunkType.SINGLE, position,
                        Objects.requireNonNull(source.get(position), "drafts cannot contain null values"), false, 40))
                .toList(), indexMaxTokens);
    }
}
