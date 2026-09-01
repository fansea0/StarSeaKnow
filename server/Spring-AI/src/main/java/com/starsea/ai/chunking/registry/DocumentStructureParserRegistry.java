package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.spi.DocumentStructureParser;
import org.springframework.stereotype.Component;

import java.util.List;

/** Independently resolves the parser for a normalized source-file extension. */
@Component
public final class DocumentStructureParserRegistry {

    private final List<DocumentStructureParser> parsers;

    public DocumentStructureParserRegistry(List<DocumentStructureParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    public DocumentStructureParser require(String fileType) {
        String normalizedFileType = ChunkStrategyRegistry.normalizeFileType(fileType);
        return parsers.stream()
                .filter(parser -> parser.supportedFileTypes().stream()
                        .map(ChunkStrategyRegistry::normalizeFileType)
                        .anyMatch(normalizedFileType::equals))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No document structure parser registered for file type '%s'".formatted(fileType)));
    }
}
