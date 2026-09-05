package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ChunkPlanningResult;
import com.starsea.ai.chunking.model.ChunkPlan;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans source-owned chunks from a parsed document structure. */
public interface ChunkPlanningStrategy {

    String code();

    Set<String> supportedFileTypes();

    String plannerVersion();

    ChunkStrategyDescriptor descriptor();

    default ChunkPlanningResult plan(ChunkPlanningRequest request) {
        if (!(request.strategyConfig() instanceof ChunkPolicy policy)) {
            throw new IllegalArgumentException("This planner requires ChunkPolicy");
        }
        return new ChunkPlanningResult(plan(request.structure(), policy), 0, 0);
    }

    /**
     * Temporary compatibility bridge for callers that have not yet adopted the typed request.
     */
    @Deprecated(forRemoval = false)
    default List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy) {
        return plan(new ChunkPlanningRequest(
                structure, policy, ContextConfig.markdownDefaults(), policy.maxTokens())).drafts();
    }

    /** Validates one strategy's JSON-facing configuration and returns its canonical snapshot. */
    default Map<String, Object> normalizeConfig(Map<String, Object> config) {
        return config == null ? Map.of() : Map.copyOf(config);
    }

    /** Plans a hierarchy for strategies that persist parent and child chunks. */
    default ChunkPlan planConfigured(ParsedStructure structure, Map<String, Object> config) {
        throw new UnsupportedOperationException("Configured planning is not implemented for " + code());
    }
}
