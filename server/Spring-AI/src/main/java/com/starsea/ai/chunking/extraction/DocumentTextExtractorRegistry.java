package com.starsea.ai.chunking.extraction;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class DocumentTextExtractorRegistry {

    private final List<DocumentTextExtractor> extractors;

    public DocumentTextExtractorRegistry(List<DocumentTextExtractor> extractors) {
        Objects.requireNonNull(extractors, "extractors");
        Set<String> registrations = new HashSet<>();
        for (DocumentTextExtractor extractor : extractors) {
            Objects.requireNonNull(extractor, "extractor");
            for (String mediaType : extractor.supportedMediaTypes()) {
                String key = MediaTypeCanonicalizer.canonicalize(mediaType)
                        + "@" + extractor.priority();
                if (!registrations.add(key)) {
                    throw new IllegalArgumentException(
                            "Duplicate document text extractor registration for " + key);
                }
            }
        }
        this.extractors = extractors.stream()
                .sorted(Comparator.comparingInt(DocumentTextExtractor::priority).reversed()
                        .thenComparing(DocumentTextExtractor::id))
                .toList();
    }

    public ExtractionCapability probe(Path path, String suppliedType) {
        ExtractionCapability firstUnavailable = null;
        for (DocumentTextExtractor extractor : extractors) {
            ExtractionCapability capability = extractor.probe(path, suppliedType);
            if (capability.available()) {
                return capability;
            }
            if (firstUnavailable == null) {
                firstUnavailable = capability;
            }
        }
        String detected = firstUnavailable == null ? null : firstUnavailable.detectedMediaType();
        return ExtractionCapability.unavailable(detected, "No registered extractor supports this document");
    }

    public ExtractedText extract(Path path, String suppliedType) {
        return extract(path, probe(path, suppliedType));
    }

    public ExtractedText extract(Path path, ExtractionCapability capability) {
        if (capability == null || !capability.available()) {
            throw new DocumentTextExtractor.ExtractionException(
                    DocumentTextExtractor.FailureReason.UNSUPPORTED,
                    capability == null ? "Document extraction is unavailable" : capability.reason());
        }
        DocumentTextExtractor selected = extractors.stream()
                .filter(extractor -> extractor.id().equals(capability.extractorId())
                        && extractor.version().equals(capability.extractorVersion())
                        && extractor.priority() == capability.priority()
                        && MediaTypeCanonicalizer.supports(
                                extractor, capability.detectedMediaType()))
                .findFirst()
                .orElseThrow(() -> new DocumentTextExtractor.ExtractionException(
                        DocumentTextExtractor.FailureReason.UNSUPPORTED,
                        "Selected extractor is no longer registered"));
        return selected.extract(path, capability);
    }
}
