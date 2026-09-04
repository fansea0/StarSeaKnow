package com.starsea.ai.chunking.context;

/** Stable explanation for the final overlap chosen for an index document. */
public enum OverlapReductionReason {
    FIRST_CHUNK,
    DISABLED,
    NO_ADJACENT_SOURCE,
    SOURCE_EMPTY,
    NONE,
    CONFIGURED_LIMIT,
    CHARACTER_LIMIT,
    MODEL_TOKEN_LIMIT,
    FORMAT_OVERHEAD
}
