package com.starsea.ai.chunking.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public final class ChunkingException extends RuntimeException {

    public static final String SOURCE_CHANGED_ERROR_CODE = "SOURCE_CHANGED";

    private final HttpStatus status;
    private final Map<String, Object> details;

    private ChunkingException(HttpStatus status, String message) {
        this(status, message, Map.of());
    }

    private ChunkingException(HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
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

    public static ChunkingException unprocessable(String message, Map<String, Object> details) {
        return new ChunkingException(HttpStatus.UNPROCESSABLE_ENTITY, message, details);
    }

    public static ChunkingException sourceChanged() {
        return unprocessable("源文件已发生变化，请重新生成分块预览",
                Map.of("errorCode", SOURCE_CHANGED_ERROR_CODE));
    }

}
