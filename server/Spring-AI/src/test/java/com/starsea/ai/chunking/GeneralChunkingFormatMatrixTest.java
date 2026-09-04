package com.starsea.ai.chunking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.chunking.extraction.DocumentFixtureFactory;
import com.starsea.ai.chunking.extraction.DocumentTextExtractorRegistry;
import com.starsea.ai.chunking.extraction.ManagedExtractionCache;
import com.starsea.ai.chunking.extraction.PdfTextExtractor;
import com.starsea.ai.chunking.extraction.PlainTextExtractor;
import com.starsea.ai.chunking.extraction.TikaDocumentTextExtractor;
import com.starsea.ai.chunking.general.GeneralChunkPlanningStrategy;
import com.starsea.ai.chunking.general.GeneralTextCleaner;
import com.starsea.ai.chunking.general.GeneralTextInputProvider;
import com.starsea.ai.chunking.general.TextNormalizer;
import com.starsea.ai.chunking.model.ChunkDraft;
import com.starsea.ai.chunking.model.ChunkPlanningRequest;
import com.starsea.ai.chunking.model.ContextConfig;
import com.starsea.ai.chunking.model.ContextMode;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.model.OverlapUnit;
import com.starsea.ai.chunking.model.StructuredBlock;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.domain.FileTextExtraction;
import com.starsea.ai.mapper.FileTextExtractionMapper;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneralChunkingFormatMatrixTest {

    private static final TokenCounter COUNTER = new TokenCounter() {
        @Override public int count(String text) {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }
        @Override public String id() { return "test-code-point-v1"; }
    };

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "GENERAL is deterministic for .{0}")
    @ValueSource(strings = {"txt", "md", "markdown", "csv", "json", "log", "html", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "epub"})
    void every_public_format_has_deterministic_bodies_locators_and_boundaries(String extension)
            throws Exception {
        Path source = tempDir.resolve("matrix-" + extension + "." + extension);
        String fixtureText = "doc".equals(extension)
                ? "contract-text"
                : "alpha section\nbeta section\ngamma section";
        DocumentFixtureFactory.writer(extension).accept(source, fixtureText);

        GeneralChunkConfig config = new GeneralChunkConfig(
                "\n", com.starsea.ai.chunking.model.DelimiterMode.LITERAL,
                96, false, false, false);
        ContextConfig context = new ContextConfig(
                false, 0, OverlapUnit.CHARACTERS, ContextMode.CHARACTER_TAIL);
        FileResource resource = new FileResource(1L, 10L, extension.hashCode() & 0x7fff_ffffL,
                UUID.nameUUIDFromBytes(extension.getBytes()), source.getFileName().toString(),
                extension, source);
        String hash = sha256(Files.readAllBytes(source));
        GeneralChunkPlanningStrategy planner = new GeneralChunkPlanningStrategy(COUNTER);

        var firstInput = provider(extension, "first").provide(resource, hash, config);
        var first = planner.plan(new ChunkPlanningRequest(
                firstInput.structure(), config, context, 512));
        var secondInput = provider(extension, "second").provide(resource, hash, config);
        var second = planner.plan(new ChunkPlanningRequest(
                secondInput.structure(), config, context, 512));

        assertEquals(first.drafts(), second.drafts());
        assertEquals(first.drafts().stream().map(ChunkDraft::sourceLocator).toList(),
                second.drafts().stream().map(ChunkDraft::sourceLocator).toList());
        assertEquals(first.drafts().stream().map(ChunkDraft::boundaryReason).toList(),
                second.drafts().stream().map(ChunkDraft::boundaryReason).toList());
        assertEquals(firstInput.delimiterMatched(), secondInput.delimiterMatched());
        assertFalse(first.drafts().isEmpty());
        String canonicalCleanedBody = reconstructCleanedBlocks(
                firstInput.structure().blocks());
        assertEquals(canonicalCleanedBody,
                reconstructCleanedBlocks(secondInput.structure().blocks()));
        assertEquals(canonicalCleanedBody, reconstructCleanedBody(first.drafts()));
        assertEquals(canonicalCleanedBody, reconstructCleanedBody(second.drafts()));
        for (String retainedSegment : fixtureText.split("\\n")) {
            assertEquals(1, countOccurrences(first.drafts(), retainedSegment),
                    () -> retainedSegment + " must have exactly one owning draft");
            assertEquals(1, countOccurrences(second.drafts(), retainedSegment),
                    () -> retainedSegment + " must have exactly one owning draft");
        }
        first.drafts().forEach(draft -> {
            assertFalse(draft.content().isBlank());
            assertNotNull(draft.sourceLocator());
            assertNotNull(draft.boundaryReason().get("start"));
            assertNotNull(draft.boundaryReason().get("end"));
            assertTrue(draft.sourceLocator().startOffset() >= 0);
            assertTrue(draft.sourceLocator().endOffset() > draft.sourceLocator().startOffset());
        });
        assertLocatorGranularity(extension, first.drafts());
    }

    private GeneralTextInputProvider provider(String extension, String coldRun) {
        DocumentTextExtractorRegistry registry = new DocumentTextExtractorRegistry(List.of(
                new PlainTextExtractor(20_000_000, 10_000_000),
                new PdfTextExtractor(20_000_000, 10_000_000, 30_000),
                new TikaDocumentTextExtractor(20_000_000, 10_000_000, 30_000, 8)));
        FileTextExtractionMapper mapper = mock(FileTextExtractionMapper.class);
        AtomicReference<FileTextExtraction> row = new AtomicReference<>();
        when(mapper.findScoped(anyLong(), anyLong())).thenAnswer(ignored -> row.get());
        when(mapper.insert(any(FileTextExtraction.class))).thenAnswer(invocation -> {
            row.set(invocation.getArgument(0));
            return 1;
        });
        when(mapper.updateScoped(any(FileTextExtraction.class))).thenAnswer(invocation -> {
            row.set(invocation.getArgument(0));
            return 1;
        });
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper, registry, new ObjectMapper(),
                tempDir.resolve("cache-" + extension + "-" + coldRun));
        return new GeneralTextInputProvider(registry, cache, new TextNormalizer(),
                new GeneralTextCleaner(), COUNTER);
    }

    private String reconstructCleanedBody(List<ChunkDraft> drafts) {
        return String.join("\n", drafts.stream().map(ChunkDraft::content).toList());
    }

    private String reconstructCleanedBlocks(List<StructuredBlock> blocks) {
        return String.join("\n", blocks.stream()
                .map(StructuredBlock::plainText)
                .filter(text -> text != null && !text.isEmpty())
                .toList());
    }

    private long countOccurrences(List<ChunkDraft> drafts, String retainedSegment) {
        return drafts.stream()
                .filter(draft -> draft.content().contains(retainedSegment))
                .count();
    }

    private void assertLocatorGranularity(String extension, List<ChunkDraft> drafts) {
        if ("pdf".equals(extension)) {
            assertTrue(drafts.stream().allMatch(draft ->
                    "PAGE".equals(draft.sourceLocator().type())
                            && Integer.valueOf(1).equals(draft.sourceLocator().startPage())
                            && Integer.valueOf(1).equals(draft.sourceLocator().endPage())));
            return;
        }
        if (List.of("txt", "md", "markdown", "csv", "json", "log").contains(extension)) {
            assertTrue(drafts.stream().allMatch(draft ->
                    "TEXT".equals(draft.sourceLocator().type())
                            && draft.sourceLocator().regions().stream()
                            .anyMatch(region -> region.containsKey("encoding"))));
            return;
        }
        assertTrue(drafts.stream().allMatch(draft ->
                "TEXT".equals(draft.sourceLocator().type())
                        && draft.sourceLocator().regions().stream()
                        .anyMatch(region -> Integer.valueOf(1).equals(region.get("document")))));
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
