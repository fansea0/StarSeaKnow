package com.starsea.ai.chunking.api;

import org.springframework.http.HttpStatus;

import java.util.Map;

public final class ChunkingException extends RuntimeException {

    public static final String SOURCE_CHANGED_ERROR_CODE = "SOURCE_CHANGED";
    public static final String GENERAL_CONTEXT_UNAVAILABLE_ERROR_CODE =
            "GENERAL_CONTEXT_UNAVAILABLE";
    public static final String GENERAL_CONTEXT_UNAVAILABLE_MESSAGE =
            "GENERAL 分块的字符上下文处理尚未启用";

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

    public static ChunkingException generalContextUnavailable() {
        return unprocessable(GENERAL_CONTEXT_UNAVAILABLE_MESSAGE,
                Map.of("errorCode", GENERAL_CONTEXT_UNAVAILABLE_ERROR_CODE));
    }
}
