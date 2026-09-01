package com.starsea.ai.chunking.registry;

/** Raised when no registered strategy supports the requested code and file type. */
public class ChunkStrategyNotFoundException extends RuntimeException {

    public ChunkStrategyNotFoundException(String code, String fileType) {
        super("No chunk strategy registered for code '%s' and file type '%s'".formatted(code, fileType));
    }
}
