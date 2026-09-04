package com.starsea.ai.chunking.general;

import java.util.Map;

/** Exact extracted-text interval owning one persisted source region. */
record MappedSourceRegion(int sourceStart, int sourceEnd, Map<String, Object> region) {
    MappedSourceRegion {
        if (sourceStart < 0 || sourceEnd < sourceStart) {
            throw new IllegalArgumentException("Invalid mapped source region");
        }
        region = region == null ? Map.of() : Map.copyOf(region);
    }
}
