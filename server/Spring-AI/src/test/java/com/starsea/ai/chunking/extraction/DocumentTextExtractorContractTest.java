package com.starsea.ai.chunking.extraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static com.starsea.ai.chunking.extraction.DocumentFixtureFactory.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorContractTest {

    @TempDir
    Path tempDir;

    private DocumentTextExtractorRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentTextExtractorRegistry(List.of(
                new PlainTextExtractor(2_000_000, 1_000_000),
                new PdfTextExtractor(2_000_000, 1_000_000, 5_000),
                new TikaDocumentTextExtractor(2_000_000, 1_000_000, 5_000, 3)));
    }

    @ParameterizedTest(name = "{0} uses {2}")
    @MethodSource("formats")
    void every_public_format_extracts_text_with_the_expected_adapter(
            String extension, BiConsumer<Path, String> writer, String extractorId) {
        Path source = tempDir.resolve("fixture." + extension);
        writer.accept(source, "contract-text");

        ExtractionCapability capability = registry.probe(source, null);
        ExtractedText result = registry.extract(source, capability);

        assertTrue(capability.available(), capability.toString());
        assertEquals(extractorId, result.extractorId());
        assertTrue(result.text().contains("contract-text"), result.text());
        if ("plain-text".equals(extractorId)) {
            assertEquals("contract-text", result.text());
        }
    }

    @Test
    void markdown_is_returned_as_original_plain_text() throws Exception {
        Path source = tempDir.resolve("guide.md");
        String markdown = "# Heading\n\n- exact markdown\n";
        Files.writeString(source, markdown, StandardCharsets.UTF_8);

        ExtractedText result = registry.extract(source, "text/markdown");

        assertEquals("plain-text", result.extractorId());
        assertEquals(markdown, result.text());
    }

    @Test
    void scanned_pdf_without_text_is_a_typed_no_text_failure() {
        Path source = tempDir.resolve("scan.pdf");
        writePdf(source, null);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> registry.extract(source, (String) null));

        assertEquals(DocumentTextExtractor.FailureReason.NO_TEXT, failure.reason());
    }

    @Test
    void corrupt_pdf_is_a_typed_corrupt_failure() throws Exception {
        Path source = tempDir.resolve("broken.pdf");
        Files.writeString(source, "%PDF-1.7 definitely broken");

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> registry.extract(source, (String) null));

        assertEquals(DocumentTextExtractor.FailureReason.CORRUPT, failure.reason());
    }

    @Test
    void encrypted_pdf_is_a_typed_encrypted_failure() {
        Path source = tempDir.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner", "user", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(source.toFile());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> registry.extract(source, (String) null));

        assertEquals(DocumentTextExtractor.FailureReason.ENCRYPTED, failure.reason());
    }

    @Test
    void source_and_extracted_output_limits_return_typed_failures() throws Exception {
        Path plain = tempDir.resolve("large.txt");
        Files.writeString(plain, "four");
        PlainTextExtractor sourceLimited = new PlainTextExtractor(3, 100);
        ExtractionCapability plainCapability = sourceLimited.probe(plain, "text/plain");

        assertEquals(DocumentTextExtractor.FailureReason.SOURCE_TOO_LARGE,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> sourceLimited.extract(plain, plainCapability)).reason());

        Path html = tempDir.resolve("large.html");
        writeText(html, "<html><body>too much output</body></html>");
        TikaDocumentTextExtractor outputLimited = new TikaDocumentTextExtractor(
                10_000, 5, 5_000, 3, 10_000);
        ExtractionCapability htmlCapability = outputLimited.probe(html, "text/html");

        assertEquals(DocumentTextExtractor.FailureReason.OUTPUT_TOO_LARGE,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> outputLimited.extract(html, htmlCapability)).reason());
    }

    @Test
    void compressed_expansion_limit_is_applied_before_tika_parsing() {
        Path archive = tempDir.resolve("large.zip");
        writeZip(archive, Map.of("expanded.txt", "expanded text"));
        TikaDocumentTextExtractor limited = new TikaDocumentTextExtractor(
                100_000, 10_000, 5_000, 3, 10);
        ExtractionCapability capability = directTikaCapability(limited, "application/zip");

        assertEquals(DocumentTextExtractor.FailureReason.LIMIT_EXCEEDED,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> limited.extract(archive, capability)).reason());
    }

    @Test
    void epub_main_pages_share_the_total_archive_expansion_limit() {
        Path epub = tempDir.resolve("high-compression.epub");
        writeEpubPages(epub, List.of(
                "a".repeat(40_000), "b".repeat(40_000), "c".repeat(40_000)));
        TikaDocumentTextExtractor limited = new TikaDocumentTextExtractor(
                100_000, 500_000, 5_000, 4, 50_000);
        ExtractionCapability capability = limited.probe(epub, "application/epub+zip");

        assertTrue(capability.available(), capability.toString());
        assertEquals("application/epub+zip", capability.detectedMediaType());
        assertEquals(DocumentTextExtractor.FailureReason.LIMIT_EXCEEDED,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> limited.extract(epub, capability)).reason());
    }

    @ParameterizedTest(name = "encrypted {0} reports ENCRYPTED")
    @MethodSource("encryptedOfficeFormats")
    void password_protected_ooxml_is_routed_to_tika_and_reports_encrypted(
            String extension, BiConsumer<Path, String> writer) {
        Path source = tempDir.resolve("protected." + extension);
        encryptOoxml(source, writer, "protected-text");

        ExtractionCapability capability = registry.probe(source, null);
        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> registry.extract(source, capability));

        assertTrue(capability.available(), capability.toString());
        assertEquals("application/x-tika-ooxml-protected", capability.detectedMediaType());
        assertEquals("tika", capability.extractorId());
        assertEquals(DocumentTextExtractor.FailureReason.ENCRYPTED, failure.reason());
    }

    @Test
    void pdf_checks_deadline_after_the_final_page_before_returning_text() {
        Path source = tempDir.resolve("deadline.pdf");
        writePdf(source, "final-page");
        PdfTextExtractor extractor = new PdfTextExtractor(100_000, 10_000, 10, 100_000) {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            protected long nanoTime() {
                return calls.getAndIncrement() < 2 ? 0 : 11_000_000;
            }
        };
        ExtractionCapability capability = extractor.probe(source, "application/pdf");

        assertEquals(DocumentTextExtractor.FailureReason.TIMEOUT,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> extractor.extract(source, capability)).reason());
    }

    @Test
    void pdf_temp_storage_limit_returns_typed_limit_failure() {
        Path source = tempDir.resolve("storage.pdf");
        writePdf(source, "storage-limit");
        PdfTextExtractor extractor = new PdfTextExtractor(100_000, 10_000, 5_000, 1);
        ExtractionCapability capability = extractor.probe(source, "application/pdf");

        assertEquals(DocumentTextExtractor.FailureReason.LIMIT_EXCEEDED,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> extractor.extract(source, capability)).reason());
    }

    @Test
    void tika_rejects_nested_content_instead_of_returning_partial_text_at_depth_limit() {
        Path source = tempDir.resolve("nested.zip");
        writeZipBytes(source, Map.of("nested.zip", zipBytes(Map.of(
                "nested.txt", "nested text"))));
        TikaDocumentTextExtractor extractor = new TikaDocumentTextExtractor(
                100_000, 10_000, 5_000, 0, 100_000);
        ExtractionCapability capability = directTikaCapability(extractor, "application/zip");

        assertEquals(DocumentTextExtractor.FailureReason.LIMIT_EXCEEDED,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> extractor.extract(source, capability)).reason());
    }

    @Test
    void tika_counts_expanded_bytes_across_multiple_embedded_streams() {
        Path source = tempDir.resolve("multiple.zip");
        writeZip(source, Map.of(
                "first.txt", "a".repeat(70),
                "second.txt", "b".repeat(70)));
        TikaDocumentTextExtractor extractor = new TikaDocumentTextExtractor(
                100_000, 10_000, 5_000, 4, 100);
        ExtractionCapability capability = directTikaCapability(extractor, "application/zip");

        assertEquals(DocumentTextExtractor.FailureReason.LIMIT_EXCEEDED,
                assertThrows(DocumentTextExtractor.ExtractionException.class,
                        () -> extractor.extract(source, capability)).reason());
    }

    private static Stream<Arguments> formats() {
        return Stream.of(
                Arguments.of("txt", (BiConsumer<Path, String>) DocumentFixtureFactory::writeText, "plain-text"),
                Arguments.of("md", (BiConsumer<Path, String>) DocumentFixtureFactory::writeText, "plain-text"),
                Arguments.of("markdown", (BiConsumer<Path, String>) DocumentFixtureFactory::writeText, "plain-text"),
                Arguments.of("csv", (BiConsumer<Path, String>) DocumentFixtureFactory::writeText, "plain-text"),
                Arguments.of("json", (BiConsumer<Path, String>) DocumentFixtureFactory::writeText, "plain-text"),
                Arguments.of("log", (BiConsumer<Path, String>) DocumentFixtureFactory::writeText, "plain-text"),
                Arguments.of("html", (BiConsumer<Path, String>) (p, s) -> writeText(p, "<html><body>" + s + "</body></html>"), "tika"),
                Arguments.of("pdf", (BiConsumer<Path, String>) DocumentFixtureFactory::writePdf, "pdfbox"),
                Arguments.of("doc", (BiConsumer<Path, String>) DocumentFixtureFactory::writeDoc, "tika"),
                Arguments.of("docx", (BiConsumer<Path, String>) DocumentFixtureFactory::writeDocx, "tika"),
                Arguments.of("xls", (BiConsumer<Path, String>) DocumentFixtureFactory::writeXls, "tika"),
                Arguments.of("xlsx", (BiConsumer<Path, String>) DocumentFixtureFactory::writeXlsx, "tika"),
                Arguments.of("ppt", (BiConsumer<Path, String>) DocumentFixtureFactory::writePpt, "tika"),
                Arguments.of("pptx", (BiConsumer<Path, String>) DocumentFixtureFactory::writePptx, "tika"),
                Arguments.of("rtf", (BiConsumer<Path, String>) DocumentFixtureFactory::writeRtf, "tika"),
                Arguments.of("epub", (BiConsumer<Path, String>) DocumentFixtureFactory::writeEpub, "tika"));
    }

    private static Stream<Arguments> encryptedOfficeFormats() {
        return Stream.of(
                Arguments.of("docx", (BiConsumer<Path, String>) DocumentFixtureFactory::writeDocx),
                Arguments.of("xlsx", (BiConsumer<Path, String>) DocumentFixtureFactory::writeXlsx),
                Arguments.of("pptx", (BiConsumer<Path, String>) DocumentFixtureFactory::writePptx));
    }

    private static ExtractionCapability directTikaCapability(
            TikaDocumentTextExtractor extractor, String mediaType) {
        return new ExtractionCapability(true, mediaType, extractor.id(), extractor.version(),
                extractor.priority(), null);
    }

}
