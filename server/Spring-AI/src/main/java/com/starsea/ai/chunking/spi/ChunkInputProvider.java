package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.ChunkInputResult;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.FileResource;

import java.util.Set;

/** Converts one immutable source snapshot into the common parsed structure for a strategy. */
public interface ChunkInputProvider {

    String strategyCode();

    Set<String> supportedFileTypes();

    default boolean global() {
        return supportedFileTypes().contains("*");
    }

    Capability capability(FileResource resource);

    ChunkInputResult provide(FileResource resource, String sourceHash, ChunkStrategyConfig config);

    record Capability(boolean available, String reason) {
        public static Capability supported() {
            return new Capability(true, null);
        }

        public static Capability unavailable(String reason) {
            return new Capability(false, reason == null || reason.isBlank()
                    ? "Chunk input is unavailable" : reason);
        }
    }
}
