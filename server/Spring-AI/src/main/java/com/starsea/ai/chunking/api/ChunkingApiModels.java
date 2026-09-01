package com.starsea.ai.chunking.api;

import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChunkingApiModels {

    private ChunkingApiModels() {
    }

    public record PreviewRequest(String strategyCode, ChunkPolicy strategyConfig,
                                 boolean replaceEditedDrafts, int lockVersion) {
    }

    public record EditChunkRequest(String content, Integer lockVersion) {
    }

    public record ChunkResponse(UUID publicId, int position, String content,
                                List<String> sectionPath, Map<String, Object> sourceLocator,
                                int tokenCount, int status, boolean isModified, int lockVersion) {
        public ChunkResponse {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            sourceLocator = sourceLocator == null ? Map.of() : Map.copyOf(sourceLocator);
        }
    }

    public record StrategyResponse(String fileType, List<ChunkStrategyDescriptor> strategies) {
        public StrategyResponse {
            strategies = strategies == null ? List.of() : List.copyOf(strategies);
        }
    }

    public record ProcessingResponse(int state, Integer failedFromState, int progress,
                                     String lastError, int lockVersion, String strategyCode,
                                     Map<String, Object> policySnapshot,
                                     Map<String, Object> contextPolicy) {
        public ProcessingResponse {
            policySnapshot = policySnapshot == null ? Map.of() : Map.copyOf(policySnapshot);
            contextPolicy = contextPolicy == null ? Map.of() : Map.copyOf(contextPolicy);
        }
    }
}
