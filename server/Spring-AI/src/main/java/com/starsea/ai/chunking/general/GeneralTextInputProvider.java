package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.DocumentTextExtractorRegistry;
import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.ExtractionCapability;
import com.starsea.ai.chunking.extraction.ManagedExtractionCache;
import com.starsea.ai.chunking.model.ChunkInputResult;
import com.starsea.ai.chunking.model.ChunkStrategyConfig;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.PreprocessingSummary;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.ChunkInputProvider;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Extracts, normalizes, delimits and cleans source text for GENERAL planning. */
@Component
public final class GeneralTextInputProvider implements ChunkInputProvider {
    private final DocumentTextExtractorRegistry extractorRegistry;
    private final ManagedExtractionCache extractionCache;
    private final TextNormalizer normalizer;
    private final GeneralTextCleaner cleaner;
    private final TokenCounter tokenCounter;

    public GeneralTextInputProvider(DocumentTextExtractorRegistry extractorRegistry,
                                    ManagedExtractionCache extractionCache,
                                    TextNormalizer normalizer,
                                    GeneralTextCleaner cleaner,
                                    TokenCounter tokenCounter) {
        this.extractorRegistry = extractorRegistry;
        this.extractionCache = extractionCache;
        this.normalizer = normalizer;
        this.cleaner = cleaner;
        this.tokenCounter = tokenCounter;
    }

    @Override public String strategyCode() { return "GENERAL"; }
    @Override public Set<String> supportedFileTypes() { return Set.of("*"); }

    @Override
    public Capability capability(FileResource resource) {
        ExtractionCapability capability = extractorRegistry.probe(resource.path(), resource.fileType());
        return capability.available() ? Capability.supported() : Capability.unavailable(capability.reason());
    }

    @Override
    public ChunkInputResult provide(FileResource resource, String sourceHash, ChunkStrategyConfig rawConfig) {
        if (!(rawConfig instanceof GeneralChunkConfig config)) {
            throw new IllegalArgumentException("GENERAL input requires GeneralChunkConfig");
        }
        ExtractedText extracted = extractionCache.getOrExtract(resource.tenantId(), resource.fileId(),
                sourceHash, resource.path(), resource.fileType());
        if (UnicodeText.isBlank(extracted.text())) {
            throw new IllegalArgumentException("未提取到可分块文字");
        }
        NormalizedText normalized = normalizer.normalize(extracted);
        GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config);
        CleaningResult cleaning = cleaner.clean(scanner.scan(normalized), config, normalized);
        PreprocessingSummary summary = PreprocessingSummary.from(cleaning.stats());
        if (cleaning.segments().isEmpty()) {
            throw new IllegalArgumentException("清洗后没有可分块文字");
        }
        List<StructuredBlock> blocks = new ArrayList<>(cleaning.segments().size());
        for (int index = 0; index < cleaning.segments().size(); index++) {
            CleanedSegment segment = cleaning.segments().get(index);
            blocks.add(segment.toStructuredBlock("general-" + (index + 1),
                    tokenCounter.count(segment.text())));
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("extractorId", extracted.extractorId());
        metadata.put("extractorVersion", extracted.extractorVersion());
        metadata.put("mediaType", extracted.mediaType());
        return new ChunkInputResult(new ParsedStructure(resource, blocks), metadata,
                summary, scanner.delimiterMatched());
    }
}
