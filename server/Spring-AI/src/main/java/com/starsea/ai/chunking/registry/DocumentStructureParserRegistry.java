package com.starsea.ai.chunking.registry;

import com.starsea.ai.chunking.spi.DocumentStructureParser;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Independently resolves the parser for a normalized source-file extension. */
@Component
public final class DocumentStructureParserRegistry {

    private final List<DocumentStructureParser> parsers;

    public DocumentStructureParserRegistry(List<DocumentStructureParser> parsers) {
        this.parsers = List.copyOf(parsers);
        validateNoDuplicateRegistrations();
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

    private void validateNoDuplicateRegistrations() {
        Set<String> registeredFileTypes = new HashSet<>();
        for (DocumentStructureParser parser : parsers) {
            for (String fileType : parser.supportedFileTypes()) {
                String normalizedFileType = ChunkStrategyRegistry.normalizeFileType(fileType);
                if (!registeredFileTypes.add(normalizedFileType)) {
                    throw new IllegalArgumentException(
                            "Duplicate document structure parser registration: " + normalizedFileType);
                }
            }
        }
    }
}
