package com.starsea.ai.chunking.extraction;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.sl.usermodel.TextBox;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorContractTest {

    private static final String WORD_97_FIXTURE_GZIP_BASE64 = """
            H4sICF8tmmoCA2ZpeHR1cmUuZG9jAO2Z709bVRjHn3Nbym03SimVTYbSsQrIxu8xQaZCYYx1QjtAUOdcoC2uE1qEEjHxhdGY+EKTGV/oCxNjgq80BvUP0Df6zmhi9mLv5ksTs0zjmyWO+j3PPZfVCnJbiNmkD/lw7j299zzPec5znnvuuT/+UHH94y+qf6Yc6SUbrWec5MiqE6DcPPEQaapuPZPJmNWZotxTcluVcgztGL8SIMe8FOjACVxgH9gPyoBbjblHlUW5d2WMUvhLk59OURLlIr1C+UgVIia7PSv3rFu8zqoU9Reu38zf+c7/cuMRQBXACyqBD9zHMUF0ABwE94NqcAjUgAfAg6BW6T2Msk4dB1A+BOpBA2gED4MmcBQcA82gBbSCNtAOOkAnOA66wAnwCOgGPeBRfp4RnQSPgcfBE6AP9IMgGACD4BQYAqfBMDgDQuAseBKMgFEQBhFwDoyBcTABngKTYAo8DZ4Bz4Lzqo8X7rKcKWCNzWXEkMOpcUx8Y4TGkBy/kUR0MbWUmk37p1KLsebB1IvL8/FkmmNiZFzWDaaiHAnyuAUn/HtLN/3R8+VL28eiMJYRBYsXEedCK+fvrEykvz2/ZTT2uwOjlUJWm6dpmlNx3B6gpoDoD8goCjZROGSjc2AgdJDmh532JRAJ2Sk5bNfT4PlQCU3jt4vDPfZ/tWWI1msFaWKI588wxaEzRgnk1Rd45pRT5epN8q2ukCMgMDvCIQcUO6C4hqRCQ1EN4jjoke0c5/kXhP0xZGU/4itOK8jRcuZ5yBvzCYEWMe9W30adn1o9QniFH8d2xGqClvhaDXNUtl2KeK9Du3Wij+2LwroFXJFA+0m2z4vWVti+/bDPyw45oQt5q+z/BV3Ucw87RD1nijO4L8Y2ybNKvsvQNMnXBcUkZ4gI/B+n2Y1xSOMvjjuze+RC9jD6QuwZHZ7RYYEPntHRpg4TfOxlN/ePC45amaaqdSNzyeManTZWrW5Vap6sioiR2Ti1na66IqyE2QTcNA9jl2D0KMqXUcpHp+yOdF4X2rESx+MYyHmawZ0yGDvrrWnvh+MSKoAT0CQqtr9HBsAMLF1khxuD7If+OLc1a6FPZx3QRNY0JWkZA+nP0prm2jjqZCrttdjWKC9GUmyptGJJ+gxeEp7Ce2y2k+3F3kDh9lzs2ok9I1ym+JGTgtekTfIacwzk48hOQn8H2fdbhNQlsUnKVP/LuBQ8Id7gUPp7rXlrg438fGCnouzkYdO2+23mZA0/D9L20ZW3Ja9ttRaUYaJt9lym629+9Put8CXPp+/qdLThq2tS56tqfSjU+sqp1lEutT7ap9Y9cq0YU8G4oLr5y21j7acp+/uy9Fk53kx+/USIEb8TPrs59nW2a9STwE0D06m56WT3Jg6z61XU6r5z7t52nV3OTxmhjh1Zx7nyFv+/oWbgDQu5Rl7jyydwNMPLblVu7Nzg/ANt54FZXWAbP2EsZrLurdeMt4Ki/D+lOif+/ivR6FrR+UUpyp6WKC+n07zIjqJs5ndL+U5ZVnTOXhD19l+UPSnCXOf/WfTFnhKbj+QGrmimk23UFyEKRjSqXXu9xb/2Xf/htaS9Dhy5krQHQCd+b+wgd5v1919Zo139/uqHLYc8772P999jtz6X3wdKcuqeI+M7h7nd4sl6192q/m6S3fz+J/uZ+w1h07GT2xEHzAk8wFuOCxSmGbqc/5YMvCo12kntBluUyxsJJExzWDEUKi5ol3pteeiX9ppv+u00gZXLTME2uJX+fL7/SVu7So3jEhqnZfhfbvrKsZd7+tk79ebXga2kEfrNb4ZW9fvBZ+p4inXFaBBllC2JcxxafvssoP9HgLnvU/IPzfn5o7sA/TJnpHdxDu/k++9ftQnENQAkAAA=
            """;

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
                Arguments.of("txt", textWriter(), "plain-text"),
                Arguments.of("md", textWriter(), "plain-text"),
                Arguments.of("markdown", textWriter(), "plain-text"),
                Arguments.of("csv", textWriter(), "plain-text"),
                Arguments.of("json", textWriter(), "plain-text"),
                Arguments.of("log", textWriter(), "plain-text"),
                Arguments.of("html", (BiConsumer<Path, String>) (p, s) -> writeText(p, "<html><body>" + s + "</body></html>"), "tika"),
                Arguments.of("pdf", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writePdf, "pdfbox"),
                Arguments.of("doc", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeDoc, "tika"),
                Arguments.of("docx", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeDocx, "tika"),
                Arguments.of("xls", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeXls, "tika"),
                Arguments.of("xlsx", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeXlsx, "tika"),
                Arguments.of("ppt", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writePpt, "tika"),
                Arguments.of("pptx", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writePptx, "tika"),
                Arguments.of("rtf", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeRtf, "tika"),
                Arguments.of("epub", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeEpub, "tika"));
    }

    private static Stream<Arguments> encryptedOfficeFormats() {
        return Stream.of(
                Arguments.of("docx", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeDocx),
                Arguments.of("xlsx", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writeXlsx),
                Arguments.of("pptx", (BiConsumer<Path, String>) DocumentTextExtractorContractTest::writePptx));
    }

    private static ExtractionCapability directTikaCapability(
            TikaDocumentTextExtractor extractor, String mediaType) {
        return new ExtractionCapability(true, mediaType, extractor.id(), extractor.version(),
                extractor.priority(), null);
    }

    private static BiConsumer<Path, String> textWriter() {
        return DocumentTextExtractorContractTest::writeText;
    }

    private static void writeText(Path path, String text) {
        try { Files.writeString(path, text, StandardCharsets.UTF_8); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writePdf(Path path, String text) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(path.toFile());
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writeDocx(Path path, String text) {
        try (XWPFDocument doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(path)) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writeDoc(Path path, String text) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(
                Base64.getMimeDecoder().decode(WORD_97_FIXTURE_GZIP_BASE64)))) {
            Files.write(path, gzip.readAllBytes());
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writeRtf(Path path, String text) {
        writeText(path, "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Times New Roman;}}"
                + "\\f0\\fs24 " + text + "\\par}");
    }

    private static void writeXlsx(Path path, String text) {
        try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(path)) {
            book.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(text);
            book.write(out);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writeXls(Path path, String text) {
        try (HSSFWorkbook book = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(path)) {
            book.createSheet("Sheet1").createRow(0).createCell(0).setCellValue(text);
            book.write(out);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writePptx(Path path, String text) {
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(path)) {
            show.createSlide().createTextBox().setText(text);
            show.write(out);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writePpt(Path path, String text) {
        try (HSLFSlideShow show = new HSLFSlideShow(); OutputStream out = Files.newOutputStream(path)) {
            HSLFSlide slide = show.createSlide();
            TextBox<?, ?> box = slide.createTextBox();
            box.setText(text);
            show.write(out);
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void writeEpub(Path path, String text) {
        writeEpubPages(path, List.of(text));
    }

    private static void writeEpubPages(Path path, List<String> pages) {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            StringBuilder manifest = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            for (int index = 0; index < pages.size(); index++) {
                manifest.append("<item id=\"page").append(index).append("\" href=\"page")
                        .append(index).append(".xhtml\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"page").append(index).append("\"/>");
            }
            put(zip, "content.opf", "<?xml version=\"1.0\"?><package version=\"2.0\" xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>fixture</dc:title><dc:identifier id=\"id\">fixture</dc:identifier></metadata><manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>");
            for (int index = 0; index < pages.size(); index++) {
                put(zip, "page" + index + ".xhtml",
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                                + pages.get(index) + "</body></html>");
            }
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        put(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] zipBytes(Map<String, String> entries) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeZip(Path path, Map<String, String> entries) {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void writeZipBytes(Path path, Map<String, byte[]> entries) {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                put(zip, entry.getKey(), entry.getValue());
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
    private static void encryptOoxml(Path target, BiConsumer<Path, String> writer, String text) {
        try {
            Path plain = Files.createTempFile(target.getParent(), "plain-", ".ooxml");
            writer.accept(plain, text);
            try (OPCPackage packageFile = OPCPackage.open(plain.toFile());
                 POIFSFileSystem filesystem = new POIFSFileSystem()) {
                EncryptionInfo encryptionInfo = new EncryptionInfo(EncryptionMode.agile);
                Encryptor encryptor = encryptionInfo.getEncryptor();
                encryptor.confirmPassword("password");
                try (OutputStream encrypted = encryptor.getDataStream(filesystem)) {
                    packageFile.save(encrypted);
                }
                try (OutputStream output = Files.newOutputStream(target)) {
                    filesystem.writeFilesystem(output);
                }
            } finally {
                Files.deleteIfExists(plain);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

}
