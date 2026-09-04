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
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.DelimiterMode;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.model.ParsedStructure;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    static Stream<Arguments> fixtures() {
        return Stream.of("txt", "html", "pdf")
                .flatMap(type -> Stream.of(1_000_000, 10_000_000)
                        .map(size -> Arguments.of(type, size)));
    }

    @ParameterizedTest(name = "{0} {1} bytes")
    @MethodSource("fixtures")
    void real_general_pipeline_completes_large_fixture_with_recorded_stage_metrics(
            String type, int sourceBytes) throws Exception {
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
        ContextConfig context = new ContextConfig(
                false, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);

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
    }

    private Duration elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
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
        java.util.Arrays.fill(data, offset, offset + contentBytes, (byte) 'a');
        copyAscii(suffix, data, offset + contentBytes);
        Files.write(path, data);
    }

    private int copyAscii(String value, byte[] target, int offset) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
        return offset + bytes.length;
    }
}
