package com.starsea.ai.chunking;

import com.starsea.ai.chunking.extraction.DocumentFixtureFactory;
import com.starsea.ai.chunking.extraction.DocumentTextExtractorRegistry;
import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.PdfTextExtractor;
import com.starsea.ai.chunking.extraction.PlainTextExtractor;
import com.starsea.ai.chunking.extraction.TikaDocumentTextExtractor;
import com.starsea.ai.chunking.general.CleanedSegment;
import com.starsea.ai.chunking.general.CleaningResult;
import com.starsea.ai.chunking.general.GeneralBoundaryScanner;
import com.starsea.ai.chunking.general.GeneralChunkPlanningStrategy;
import com.starsea.ai.chunking.general.GeneralTextCleaner;
import com.starsea.ai.chunking.general.NormalizedText;
import com.starsea.ai.chunking.general.TextNormalizer;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralChunkingPerformanceTest {

    private static final int MAX_EXTRACTED_CODE_POINTS = 10_000_000;
    private static final Duration EXTRACTION_LIMIT = Duration.ofSeconds(120);
    private static final Duration STAGE_LIMIT = Duration.ofSeconds(60);
    private static final TokenCounter COUNTER = new TokenCounter() {
        @Override public int count(String text) {
            if (text == null || text.isEmpty()) return 0;
            int codePoints = text.codePointCount(0, text.length());
            return Math.max(1, (codePoints + 7) / 8);
        }
        @Override public String id() { return "benchmark-code-point-eighth-v1"; }
    };

    @TempDir
    Path tempDir;

    static Stream<String> formats() {
        return Stream.of("txt", "html", "pdf");
    }

    @ParameterizedTest(name = "{0}: 1M/10M source and parser path growth")
    @MethodSource("formats")
    void source_and_parser_pipeline_has_bounded_ten_x_growth(String type) throws Exception {
        PipelineMetrics small = runPipeline(type, 1_000_000);
        PipelineMetrics large = runPipeline(type, 10_000_000);

        assertGrowth("extraction", small.extraction(), large.extraction());
        assertGrowth("normalization", small.normalization(), large.normalization());
        assertGrowth("cleaning", small.cleaning(), large.cleaning());
        assertGrowth("planning", small.planning(), large.planning());
        assertTrue(large.chunks() >= small.chunks());
    }

    @org.junit.jupiter.api.Test
    void pdf_parser_extracts_a_meaningful_text_workload_separate_from_padded_source_coverage()
            throws Exception {
        Path source = tempDir.resolve("text-heavy.pdf");
        String line = "meaningful extracted PDF text workload " + "x".repeat(40);
        writeTextHeavyPdf(source, line, 1_000);
        PdfTextExtractor extractor = new PdfTextExtractor(
                2_000_000, 200_000, 30_000, 4_000_000);

        ExtractedText extracted = extractor.extract(source,
                extractor.probe(source, "application/pdf"));

        assertTrue(extracted.text().length() >= line.length() * 900,
                "PDF parser must process substantial extracted text rather than only source padding");
    }

    private PipelineMetrics runPipeline(String type, int sourceBytes) throws Exception {
        Path source = tempDir.resolve("performance-" + sourceBytes + "." + type);
        writeExactFixture(source, type, sourceBytes);
        assertEquals(sourceBytes, Files.size(source));

        DocumentTextExtractorRegistry registry = new DocumentTextExtractorRegistry(List.of(
                new PlainTextExtractor(11_000_000, MAX_EXTRACTED_CODE_POINTS),
                new PdfTextExtractor(11_000_000, MAX_EXTRACTED_CODE_POINTS, 120_000,
                        22_000_000),
                new TikaDocumentTextExtractor(11_000_000, MAX_EXTRACTED_CODE_POINTS,
                        120_000, 8, 44_000_000)));
        GeneralChunkConfig config = new GeneralChunkConfig("\n\n", DelimiterMode.LITERAL,
                4000, false, false, false);
        ContextConfig context = ContextConfig.generalDefaults();

        long extractionStart = System.nanoTime();
        var capability = registry.probe(source, type);
        assertTrue(capability.available(), capability.toString());
        ExtractedText extracted = registry.extract(source, capability);
        Duration extraction = elapsed(extractionStart);
        assertTrue(extracted.text().codePointCount(0, extracted.text().length())
                <= MAX_EXTRACTED_CODE_POINTS);

        long normalizationStart = System.nanoTime();
        NormalizedText normalized = new TextNormalizer().normalize(extracted);
        Duration normalization = elapsed(normalizationStart);

        long cleaningStart = System.nanoTime();
        GeneralBoundaryScanner scanner = new GeneralBoundaryScanner(config);
        CleaningResult cleaned = new GeneralTextCleaner().clean(
                scanner.scan(normalized), config, normalized);
        Duration cleaning = elapsed(cleaningStart);

        long planningStart = System.nanoTime();
        List<StructuredBlock> blocks = new ArrayList<>(cleaned.segments().size());
        for (int index = 0; index < cleaned.segments().size(); index++) {
            CleanedSegment segment = cleaned.segments().get(index);
            blocks.add(segment.toStructuredBlock("performance-" + index,
                    COUNTER.count(segment.text())));
        }
        FileResource resource = new FileResource(1L, 10L, 20L, null,
                source.getFileName().toString(), type, source);
        var planned = new GeneralChunkPlanningStrategy(COUNTER).plan(new ChunkPlanningRequest(
                new ParsedStructure(resource, blocks), config, context, 512));
        Duration planning = elapsed(planningStart);

        assertFalse(planned.drafts().isEmpty());
        for (int index = 0; index < planned.drafts().size(); index++) {
            int characterBudget = index == 0 ? 4000 : 3955;
            assertTrue(planned.drafts().get(index).content().codePointCount(
                    0, planned.drafts().get(index).content().length()) <= characterBudget);
            assertTrue(COUNTER.count(planned.drafts().get(index).content()) <= 512);
        }
        if (!"pdf".equals(type)) {
            assertTrue(planned.drafts().stream().anyMatch(draft ->
                            COUNTER.count(draft.content()) >= 450),
                    "large text fixtures must exercise high-token planning");
            assertTrue(scanner.delimiterMatched(), "large text fixtures are delimiter-dense");
        } else {
            assertTrue(extracted.text().length() < 1_000,
                    "padded PDF fixture covers source-size and parser paths, not extracted-text scale");
        }
        assertTrue(extraction.compareTo(EXTRACTION_LIMIT) < 0,
                "extraction completed outside the generous CI limit: " + extraction);
        assertTrue(normalization.compareTo(STAGE_LIMIT) < 0,
                "normalization completed outside the generous CI limit: " + normalization);
        assertTrue(cleaning.compareTo(STAGE_LIMIT) < 0,
                "cleaning completed outside the generous CI limit: " + cleaning);
        assertTrue(planning.compareTo(STAGE_LIMIT) < 0,
                "planning completed outside the generous CI limit: " + planning);
        System.out.printf(Locale.ROOT,
                "GENERAL_PERF format=%s bytes=%d extractedCodePoints=%d extractionMs=%d normalizationMs=%d cleaningMs=%d planningMs=%d chunks=%d%n",
                type, sourceBytes, normalized.codePointCount(), extraction.toMillis(),
                normalization.toMillis(), cleaning.toMillis(), planning.toMillis(),
                planned.drafts().size());
        return new PipelineMetrics(extraction, normalization, cleaning, planning,
                planned.drafts().size());
    }

    private Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private void assertGrowth(String stage, Duration small, Duration large) {
        long generousLimit = small.toNanos() * 40L + Duration.ofSeconds(5).toNanos();
        assertTrue(large.toNanos() <= generousLimit,
                () -> stage + " growth exceeded the generous 10x workload bound: small="
                        + small + ", large=" + large);
    }

    private void writeExactFixture(Path path, String type, int bytes) throws Exception {
        if ("pdf".equals(type)) {
            DocumentFixtureFactory.writePdf(path, "performance fixture");
            int padding = Math.toIntExact(bytes - Files.size(path));
            if (padding < 2) throw new IllegalArgumentException("PDF target is too small");
            byte[] suffix = new byte[padding];
            suffix[0] = '\n';
            suffix[1] = '%';
            java.util.Arrays.fill(suffix, 2, suffix.length, (byte) 'p');
            Files.write(path, suffix, StandardOpenOption.APPEND);
            return;
        }
        String prefix = "html".equals(type) ? "<html><body>" : "";
        String suffix = "html".equals(type) ? "</body></html>" : "";
        int contentBytes = bytes - prefix.length() - suffix.length();
        byte[] data = new byte[bytes];
        int offset = 0;
        offset = copyAscii(prefix, data, offset);
        byte[] pattern = ("a".repeat(3798) + "\n\n").getBytes(StandardCharsets.US_ASCII);
        int contentEnd = offset + contentBytes;
        while (offset < contentEnd) {
            int copied = Math.min(pattern.length, contentEnd - offset);
            System.arraycopy(pattern, 0, data, offset, copied);
            offset += copied;
        }
        copyAscii(suffix, data, contentEnd);
        Files.write(path, data);
    }

    private void writeTextHeavyPdf(Path path, String line, int lines) throws Exception {
        try (PDDocument document = new PDDocument()) {
            int remaining = lines;
            while (remaining > 0) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(36, 750);
                    int linesOnPage = Math.min(50, remaining);
                    for (int index = 0; index < linesOnPage; index++) {
                        content.showText(line);
                        content.newLineAtOffset(0, -14);
                    }
                    content.endText();
                    remaining -= linesOnPage;
                }
            }
            document.save(path.toFile());
        }
    }

    private int copyAscii(String value, byte[] target, int offset) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
        return offset + bytes.length;
    }

    private record PipelineMetrics(Duration extraction, Duration normalization,
                                   Duration cleaning, Duration planning, int chunks) {
    }
}
