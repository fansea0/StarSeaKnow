package com.starsea.ai.chunking.markdown;

import com.starsea.ai.chunking.model.ChunkInputResult;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.PreprocessingSummary;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Keeps Markdown parsing behavior behind the strategy-aware input contract. */
@Component
public final class MarkdownStructureInputProvider implements ChunkInputProvider {
    private final MarkdownStructureParser parser;

    public MarkdownStructureInputProvider(MarkdownStructureParser parser) {
        this.parser = parser;
    }

    @Override public String strategyCode() { return "MARKDOWN_OPTIMIZED"; }
    @Override public Set<String> supportedFileTypes() { return Set.of("md", "markdown"); }
    @Override public Capability capability(FileResource resource) { return Capability.supported(); }

    @Override
    public ChunkInputResult provide(FileResource resource, String sourceHash, ChunkStrategyConfig config) {
        return new ChunkInputResult(parser.parse(resource), Map.of(), PreprocessingSummary.empty(), true);
    }
}
