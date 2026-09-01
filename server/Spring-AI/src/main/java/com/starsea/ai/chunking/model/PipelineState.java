package com.starsea.ai.chunking.model;

import java.util.Arrays;

public enum PipelineState {
    UPLOADED(0), CHUNKING(1), CHUNKED(2), ADJUSTING(3),
    CONFIRMED(4), VECTORIZING(5), COMPLETED(6), FAILED(7);

    private final int code;

    PipelineState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PipelineState fromCode(int code) {
        return Arrays.stream(values())
                .filter(value -> value.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown pipeline state: " + code));
    }
}
