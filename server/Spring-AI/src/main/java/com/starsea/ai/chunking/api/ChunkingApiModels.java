package com.starsea.ai.chunking.api;

import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.PreprocessingSummary;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChunkingApiModels {

    public static final String NO_AVAILABLE_OVERLAP_REASON_CODE = "NO_AVAILABLE_OVERLAP";

    private ChunkingApiModels() {
    }

    public record PreviewRequest(String strategyCode, Map<String, Object> strategyConfig,
                                 Map<String, Object> contextConfig,
                                 boolean replaceEditedDrafts, int lockVersion) {
        public PreviewRequest {
            strategyConfig = strategyConfig == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(strategyConfig));
            contextConfig = contextConfig == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(contextConfig));
        }

        public PreviewRequest(String strategyCode, ChunkPolicy strategyConfig,
                              boolean replaceEditedDrafts, int lockVersion) {
            this(strategyCode, Map.of("minTokens", strategyConfig.minTokens(),
                            "targetTokens", strategyConfig.targetTokens(),
                            "maxTokens", strategyConfig.maxTokens()),
                    null, replaceEditedDrafts, lockVersion);
        }
    }

    public record EditChunkRequest(String content, Boolean overlapEnabled,
                                   Integer overlapLimit, OverlapUnit overlapUnit,
                                   Integer overlapTokenLimit, Integer lockVersion) {
        public EditChunkRequest(String content, Integer lockVersion) {
            this(content, false, null, null, 40, lockVersion);
        }

        /** Legacy token-only request compatibility. */
        public EditChunkRequest(String content, Boolean overlapEnabled,
                                Integer overlapTokenLimit, Integer lockVersion) {
            this(content, overlapEnabled, null, null, overlapTokenLimit, lockVersion);
        }

        public EditChunkRequest(String content, Boolean overlapEnabled,
                                Integer overlapLimit, OverlapUnit overlapUnit,
                                Integer lockVersion) {
            this(content, overlapEnabled, overlapLimit, overlapUnit, null, lockVersion);
        }

        public Integer resolvedOverlapLimit() {
            if (overlapLimit != null && overlapTokenLimit != null
                    && !overlapLimit.equals(overlapTokenLimit)) {
                throw ChunkingException.unprocessable(
                        "overlapLimit conflicts with legacy overlapTokenLimit");
            }
            return overlapLimit != null ? overlapLimit : overlapTokenLimit;
        }
    }

    public record ConfirmRequest(int lockVersion) {
    }

    public record ChunkResponse(UUID publicId, int position, String content,
                                List<String> sectionPath, Map<String, Object> sourceLocator,
                                int tokenCount, int status, boolean isModified, int lockVersion,
                                boolean overlapEnabled, int overlapLimit, OverlapUnit overlapUnit,
                                String overlapContent, int overlapTokenCount,
                                int overlapCharacterCount, String overlapReductionReason,
                                String overlapUnavailableReason, String lengthUnit,
                                int bodyLength, Integer indexLength, int overlapActualLength,
                                Map<String, Object> boundaryReason) {
        public ChunkResponse(UUID publicId, int position, String content,
                             List<String> sectionPath, Map<String, Object> sourceLocator,
                             int tokenCount, int status, boolean isModified, int lockVersion) {
            this(publicId, position, content, sectionPath, sourceLocator, tokenCount,
                    status, isModified, lockVersion, false, 40, OverlapUnit.TOKENS,
                    null, 0, 0, null, null, "TOKENS", tokenCount, null, 0, Map.of());
        }

        /** Legacy token-only response constructor. */
        public ChunkResponse(UUID publicId, int position, String content,
                             List<String> sectionPath, Map<String, Object> sourceLocator,
                             int tokenCount, int status, boolean isModified, int lockVersion,
                             boolean overlapEnabled, int overlapTokenLimit,
                             String overlapContent, int overlapTokenCount,
                             String overlapUnavailableReason) {
            this(publicId, position, content, sectionPath, sourceLocator, tokenCount,
                    status, isModified, lockVersion, overlapEnabled, overlapTokenLimit,
                    OverlapUnit.TOKENS, overlapContent, overlapTokenCount,
                    overlapContent == null ? 0 : overlapContent.codePointCount(0, overlapContent.length()),
                    null, overlapUnavailableReason, "TOKENS", tokenCount, null,
                    overlapTokenCount, Map.of());
        }

        public ChunkResponse {
            sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
            sourceLocator = sourceLocator == null ? Map.of() : Map.copyOf(sourceLocator);
            boundaryReason = boundaryReason == null ? Map.of() : Map.copyOf(boundaryReason);
        }

        @JsonIgnore
        public int overlapTokenLimit() {
            return overlapLimit;
        }
    }

    public record StrategyResponse(String fileType, List<StrategyCapabilityResponse> strategies) {
        public StrategyResponse {
            strategies = strategies == null ? List.of() : List.copyOf(strategies);
        }
    }

    public record ProcessingResponse(int state, Integer failedFromState, int progress,
                                     String lastError, int lockVersion, String strategyCode,
                                     Map<String, Object> policySnapshot,
                                     Map<String, Object> contextPolicy,
                                     PreprocessingSummary preprocessingSummary,
                                     Boolean delimiterMatched,
                                     Integer forcedSplitCount,
                                     Integer tokenLimitedSplitCount) {
        public ProcessingResponse {
            policySnapshot = policySnapshot == null ? Map.of() : Map.copyOf(policySnapshot);
            contextPolicy = contextPolicy == null ? Map.of() : Map.copyOf(contextPolicy);
        }

        public ProcessingResponse(int state, Integer failedFromState, int progress,
                                  String lastError, int lockVersion, String strategyCode,
                                  Map<String, Object> policySnapshot,
                                  Map<String, Object> contextPolicy) {
            this(state, failedFromState, progress, lastError, lockVersion, strategyCode,
                    policySnapshot, contextPolicy, null, null, null, null);
        }
    }
}
