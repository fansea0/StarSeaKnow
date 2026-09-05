package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.ChunkInputResult;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.ParentChildPolicy;
import com.starsea.ai.chunking.model.PreprocessingSummary;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Supplies parsed Markdown structure to the parent-child planner. */
@Component
public final class ParentChildStructureInputProvider implements ChunkInputProvider {
    private final MarkdownStructureParser parser;

    public ParentChildStructureInputProvider(MarkdownStructureParser parser) {
        this.parser = parser;
    }

    @Override public String strategyCode() { return "PARENT_CHILD"; }
    @Override public Set<String> supportedFileTypes() { return Set.of("md", "markdown"); }
    @Override public Capability capability(FileResource resource) { return Capability.supported(); }

    @Override
    public ChunkInputResult provide(FileResource resource, String sourceHash, ChunkStrategyConfig config) {
        if (!(config instanceof ParentChildPolicy)) {
            throw new IllegalArgumentException("PARENT_CHILD input requires ParentChildPolicy");
        }
        return new ChunkInputResult(parser.parse(resource), Map.of(), PreprocessingSummary.empty(), true);
    }
}
