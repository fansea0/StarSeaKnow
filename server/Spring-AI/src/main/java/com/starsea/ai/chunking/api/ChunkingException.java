package com.starsea.ai.chunking.api;

import org.springframework.http.HttpStatus;

public final class ChunkingException extends RuntimeException {

    private final HttpStatus status;

    private ChunkingException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ChunkingException notFound(String message) {
        return new ChunkingException(HttpStatus.NOT_FOUND, message);
    }

    public static ChunkingException conflict(String message) {
        return new ChunkingException(HttpStatus.CONFLICT, message);
    }

    public static ChunkingException unprocessable(String message) {
        return new ChunkingException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
