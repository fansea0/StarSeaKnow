package com.starsea.ai.chunking.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentTextExtractorRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void content_detection_selects_pdf_even_when_extension_and_supplied_type_claim_text() throws Exception {
        Path source = tempDir.resolve("misleading.txt");
        Files.write(source, "%PDF-1.4\n%%EOF".getBytes());
        DocumentTextExtractor plain = extractor("plain", 100, Set.of("text/plain"), "plain");
        DocumentTextExtractor pdf = extractor("pdf", 200, Set.of("application/pdf"), "pdf");
        DocumentTextExtractorRegistry registry = new DocumentTextExtractorRegistry(List.of(plain, pdf));

        ExtractionCapability capability = registry.probe(source, "text/plain");

        assertEquals("application/pdf", capability.detectedMediaType());
        assertEquals("pdf", capability.extractorId());
    }

    @Test
    void duplicate_media_type_and_priority_is_rejected_at_construction() {
        DocumentTextExtractor first = extractor("first", 100, Set.of("text/plain"), "first");
        DocumentTextExtractor second = extractor("second", 100, Set.of("text/plain"), "second");

        assertThrows(IllegalArgumentException.class,
                () -> new DocumentTextExtractorRegistry(List.of(first, second)));
    }

    @Test
    void parameterized_media_types_share_the_canonical_duplicate_registration_key() {
        DocumentTextExtractor first = extractor(
                "first", 100, Set.of("Text/Plain; charset=UTF-8"), "first");
        DocumentTextExtractor second = extractor(
                "second", 100, Set.of("text/plain ; format=flowed"), "second");

        assertThrows(IllegalArgumentException.class,
                () -> new DocumentTextExtractorRegistry(List.of(first, second)));
    }

    @Test
    void selection_with_shared_identity_requires_support_for_the_detected_media_type() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "content");
        DocumentTextExtractor xml = extractor(
                "shared", 100, Set.of("application/xml"), "xml");
        DocumentTextExtractor plain = extractor(
                "shared", 100, Set.of("Text/Plain; charset=UTF-8"), "plain");
        DocumentTextExtractorRegistry registry = new DocumentTextExtractorRegistry(List.of(xml, plain));

        ExtractionCapability capability = registry.probe(source, "text/plain; charset=UTF-8");
        ExtractedText extracted = registry.extract(source, capability);

        assertEquals("text/plain", capability.detectedMediaType());
        assertEquals("plain", extracted.text());
    }

    @Test
    void selected_extractor_failure_is_returned_without_falling_through() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "content");
        DocumentTextExtractor selected = new StubExtractor("selected", 200, Set.of("text/plain")) {
            @Override
            public ExtractedText extract(Path path, ExtractionCapability capability) {
                throw new DocumentTextExtractor.ExtractionException(
                        DocumentTextExtractor.FailureReason.CORRUPT, "selected failed");
            }
        };
        DocumentTextExtractor fallback = extractor("fallback", 100, Set.of("text/plain"), "fallback");
        DocumentTextExtractorRegistry registry = new DocumentTextExtractorRegistry(List.of(fallback, selected));

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> registry.extract(source, "text/plain"));

        assertEquals(DocumentTextExtractor.FailureReason.CORRUPT, failure.reason());
        assertEquals("selected failed", failure.getMessage());
    }

    private DocumentTextExtractor extractor(String id, int priority, Set<String> types, String text) {
        return new StubExtractor(id, priority, types) {
            @Override
            public ExtractedText extract(Path path, ExtractionCapability capability) {
                return new ExtractedText(text, capability.detectedMediaType(), id(), version(),
                        List.of(), Map.of());
            }
        };
    }

    private abstract static class StubExtractor implements DocumentTextExtractor {
        private final String id;
        private final int priority;
        private final Set<String> mediaTypes;

        private StubExtractor(String id, int priority, Set<String> mediaTypes) {
            this.id = id;
            this.priority = priority;
            this.mediaTypes = mediaTypes;
        }

        @Override public String id() { return id; }
        @Override public String version() { return "1"; }
        @Override public int priority() { return priority; }
        @Override public Set<String> supportedMediaTypes() { return mediaTypes; }
    }
}
