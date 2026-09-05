package com.starsea.ai.chunking.model;

/** Persistence-independent hierarchy role for a planned chunk. */
public enum ChunkType {
    SINGLE(0),
    PARENT(1),
    CHILD(2);

    private final int code;

    ChunkType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ChunkType fromCode(int code) {
        for (ChunkType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown chunk type code: " + code);
    }
}
