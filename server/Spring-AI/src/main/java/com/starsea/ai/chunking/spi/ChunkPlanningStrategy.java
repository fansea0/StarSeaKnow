package com.starsea.ai.chunking.spi;

import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPolicy;
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

    List<ChunkDraft> plan(ParsedStructure structure, ChunkPolicy policy);
}
