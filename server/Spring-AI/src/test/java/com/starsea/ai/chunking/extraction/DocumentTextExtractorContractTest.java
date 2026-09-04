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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
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

    @ParameterizedTest(name = "BOM-less {0} plain text is detected deterministically")
    @MethodSource("bomlessTextEncodings")
    void detects_supported_bomless_plain_text_encodings(
            String expectedEncoding, java.nio.charset.Charset charset, String content) throws Exception {
        Path source = tempDir.resolve("bomless-" + expectedEncoding + ".txt");
        Files.write(source, content.getBytes(charset));
        PlainTextExtractor extractor = new PlainTextExtractor(10_000, 10_000);

        ExtractedText result = extractor.extract(source, extractor.probe(source, "text/plain"));

        assertEquals(content, result.text());
        assertEquals(expectedEncoding, result.metadata().get("encoding"));
        assertEquals(expectedEncoding, result.sourceSpans().get(0).source().get("encoding"));
    }

    @Test
    void rejects_bomless_binary_that_happens_to_be_decodable_as_a_legacy_charset() throws Exception {
        Path source = tempDir.resolve("binary.txt");
        Files.write(source, new byte[]{0, 1, 2, 3, 4, (byte) 0x93, (byte) 0x94, 0});
        PlainTextExtractor extractor = new PlainTextExtractor(10_000, 10_000);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.UNRELIABLE_ENCODING, failure.reason());
    }

    @Test
    void rejects_short_legacy_bytes_when_detector_confidence_cannot_disambiguate_them() throws Exception {
        Path source = tempDir.resolve("ambiguous-legacy.txt");
        Files.write(source, new byte[]{(byte) 0xe9, (byte) 0xe9, (byte) 0xe9, (byte) 0xe9});
        PlainTextExtractor extractor = new PlainTextExtractor(10_000, 10_000);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.UNRELIABLE_ENCODING, failure.reason());
    }

    @Test
    void rejects_binary_content_even_when_it_starts_with_a_valid_text_bom() throws Exception {
        Path source = tempDir.resolve("bom-binary.txt");
        Files.write(source, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 0, 1, 2});
        PlainTextExtractor extractor = new PlainTextExtractor(10_000, 10_000);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.UNRELIABLE_ENCODING, failure.reason());
    }

    @Test
    void all_whitespace_plain_text_is_explicitly_a_no_text_failure() throws Exception {
        Path source = tempDir.resolve("whitespace.txt");
        Files.writeString(source, " \t\r\n  ");
        PlainTextExtractor extractor = new PlainTextExtractor(10_000, 10_000);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.NO_TEXT, failure.reason());
    }

    @Test
    void unicode_space_separators_are_explicitly_a_no_text_failure() throws Exception {
        Path source = tempDir.resolve("unicode-whitespace.txt");
        Files.writeString(source, "\u00a0\u2007\u202f");
        PlainTextExtractor extractor = new PlainTextExtractor(10_000, 10_000);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.NO_TEXT, failure.reason());
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
    void plain_text_growth_after_stat_reads_at_most_the_limit_plus_one_byte() throws Exception {
        Path source = tempDir.resolve("growing.txt");
        Files.writeString(source, "stub");
        byte[] grown = "01234567890".getBytes(StandardCharsets.US_ASCII);
        AtomicInteger bytesRead = new AtomicInteger();
        PlainTextExtractor extractor = new PlainTextExtractor(10, 100) {
            @Override protected long sourceSize(Path ignored) { return 4; }
            @Override protected java.io.InputStream openSource(Path ignored) {
                return new java.io.ByteArrayInputStream(grown) {
                    @Override public synchronized int read(byte[] target, int offset, int length) {
                        int read = super.read(target, offset, length);
                        if (read > 0) bytesRead.addAndGet(read);
                        return read;
                    }
                };
            }
        };

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.SOURCE_TOO_LARGE, failure.reason());
        assertEquals(11, bytesRead.get());
    }

    @Test
    void plain_text_enforces_the_code_point_limit_for_a_multibyte_candidate() throws Exception {
        Path source = tempDir.resolve("near-output-limit.txt");
        Files.writeString(source, "😀".repeat(5));
        PlainTextExtractor extractor = new PlainTextExtractor(100, 4);

        DocumentTextExtractor.ExtractionException failure = assertThrows(
                DocumentTextExtractor.ExtractionException.class,
                () -> extractor.extract(source, extractor.probe(source, "text/plain")));

        assertEquals(DocumentTextExtractor.FailureReason.OUTPUT_TOO_LARGE, failure.reason());
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
    void pdf_wall_clock_deadline_returns_while_a_blocking_parser_hook_is_stalled() throws Exception {
        Path source = tempDir.resolve("stalled.pdf");
        writePdf(source, "stalled-parser");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PdfTextExtractor extractor = new PdfTextExtractor(100_000, 10_000, 50, 100_000) {
            @Override
            protected ExtractedText extractBlocking(Path ignored, ExtractionCapability capability) {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignoredInterruption) {
                        // Deliberately model a third-party parser that ignores interruption.
                    }
                }
                return new ExtractedText("late", capability.detectedMediaType(), id(), version(),
                        List.of(), Map.of());
            }
        };
        ExtractionCapability capability = extractor.probe(source, "application/pdf");
        long started = System.nanoTime();
        try {
            DocumentTextExtractor.ExtractionException failure = assertThrows(
                    DocumentTextExtractor.ExtractionException.class,
                    () -> extractor.extract(source, capability));

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(DocumentTextExtractor.FailureReason.TIMEOUT, failure.reason());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started)
                    .compareTo(java.time.Duration.ofSeconds(2)) < 0);
        } finally {
            release.countDown();
        }
    }

    @Test
    void stalled_pdf_parsers_consume_only_the_bounded_worker_capacity() throws Exception {
        Path source = tempDir.resolve("bounded-stalls.pdf");
        writePdf(source, "bounded-stalls");
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        PdfTextExtractor extractor = new PdfTextExtractor(100_000, 10_000, 200, 100_000) {
            @Override
            protected ExtractedText extractBlocking(Path ignored, ExtractionCapability capability) {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                entered.countDown();
                try {
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignoredInterruption) {
                            // Deliberately ignore cancellation to model an uncooperative parser.
                        }
                    }
                    return new ExtractedText("late", capability.detectedMediaType(), id(), version(),
                            List.of(), Map.of());
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        ExtractionCapability capability = extractor.probe(source, "application/pdf");
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> assertThrows(DocumentTextExtractor.ExtractionException.class,
                    () -> extractor.extract(source, capability)));
            var second = callers.submit(() -> assertThrows(DocumentTextExtractor.ExtractionException.class,
                    () -> extractor.extract(source, capability)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            DocumentTextExtractor.ExtractionException saturated = assertThrows(
                    DocumentTextExtractor.ExtractionException.class,
                    () -> extractor.extract(source, capability));

            assertEquals(DocumentTextExtractor.FailureReason.TIMEOUT, saturated.reason());
            assertEquals(2, maximumActive.get());
            assertEquals(DocumentTextExtractor.FailureReason.TIMEOUT,
                    first.get(1, TimeUnit.SECONDS).reason());
            assertEquals(DocumentTextExtractor.FailureReason.TIMEOUT,
                    second.get(1, TimeUnit.SECONDS).reason());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
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
    void pdf_shutdown_releases_the_bounded_executor_and_rejects_later_work() {
        Path source = tempDir.resolve("shutdown.pdf");
        writePdf(source, "shutdown");
        PdfTextExtractor extractor = new PdfTextExtractor(100_000, 10_000, 5_000, 100_000);
        ExtractionCapability capability = extractor.probe(source, "application/pdf");

        extractor.shutdown();

        assertTrue(extractor.executorIsShutdown());
        assertEquals(DocumentTextExtractor.FailureReason.TIMEOUT,
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

    private static Stream<Arguments> bomlessTextEncodings() {
        return Stream.of(
                Arguments.of("UTF-8", StandardCharsets.UTF_8, "UTF-8 中文 café"),
                Arguments.of("GB18030", java.nio.charset.Charset.forName("GB18030"),
                        "GB18030 中文分块测试"),
                Arguments.of("GB18030", java.nio.charset.Charset.forName("GBK"),
                        "GBK 中文兼容测试"),
                Arguments.of("windows-1252", java.nio.charset.Charset.forName("windows-1252"),
                        "Windows résumé — café €"),
                Arguments.of("windows-1252", java.nio.charset.Charset.forName("windows-1252"),
                        "éclair"),
                Arguments.of("windows-1252", java.nio.charset.Charset.forName("windows-1252"),
                        "éé"),
                Arguments.of("windows-1252", java.nio.charset.Charset.forName("windows-1252"),
                        "“smart quotes”"));
    }

    private static ExtractionCapability directTikaCapability(
            TikaDocumentTextExtractor extractor, String mediaType) {
        return new ExtractionCapability(true, mediaType, extractor.id(), extractor.version(),
                extractor.priority(), null);
    }

}
