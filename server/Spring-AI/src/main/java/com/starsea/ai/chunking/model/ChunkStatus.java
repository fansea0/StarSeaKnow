package com.starsea.ai.chunking.model;

import java.util.Arrays;

public enum ChunkStatus {
    DRAFT(0), INDEXING(1), ACTIVE(2);

    private final int code;

    ChunkStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ChunkStatus fromCode(int code) {
        return Arrays.stream(values())
                .filter(value -> value.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown chunk status: " + code));
    }
}
