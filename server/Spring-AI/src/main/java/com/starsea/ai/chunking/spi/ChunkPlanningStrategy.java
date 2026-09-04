package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlan;
import com.starsea.ai.chunking.model.ChunkPolicy;
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

    List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy);

    /** Validates one strategy's JSON-facing configuration and returns its canonical snapshot. */
    default Map<String, Object> normalizeConfig(Map<String, Object> config) {
        return config == null ? Map.of() : Map.copyOf(config);
    }

    /** Plans chunks from a strategy-specific configuration. */
    default ChunkPlan planConfigured(ParsedStructure structure, Map<String, Object> config) {
        throw new UnsupportedOperationException("Configured planning is not implemented for " + code());
    }
}
