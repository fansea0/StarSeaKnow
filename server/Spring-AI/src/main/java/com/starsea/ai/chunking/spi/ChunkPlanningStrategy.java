package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ChunkPlanningResult;
import com.starsea.ai.chunking.model.ChunkPolicy;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.registry.ChunkStrategyDescriptor;

import java.util.List;
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
}
