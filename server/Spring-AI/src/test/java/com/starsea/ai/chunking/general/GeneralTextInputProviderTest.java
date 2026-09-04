package com.starsea.ai.chunking.general;

import com.starsea.ai.chunking.extraction.DocumentTextExtractorRegistry;
import com.starsea.ai.chunking.extraction.ExtractedText;
import com.starsea.ai.chunking.extraction.ManagedExtractionCache;
import com.starsea.ai.chunking.model.FileResource;
import com.starsea.ai.chunking.model.GeneralChunkConfig;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.starsea.ai.chunking.extraction.ExtractionCapability;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneralTextInputProviderTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"txt", "md", "markdown", "csv", "json", "log", "html", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "epub"})
    void every_public_format_is_general_capable_when_extractor_probe_succeeds(String type) {
        DocumentTextExtractorRegistry registry = mock(DocumentTextExtractorRegistry.class);
        Path source = tempDir.resolve("source." + type);
        when(registry.probe(source, type)).thenReturn(new ExtractionCapability(
                true, "application/test", "extractor", "v1", 10, null));
        GeneralTextInputProvider provider = new GeneralTextInputProvider(
                registry, mock(ManagedExtractionCache.class), new TextNormalizer(),
                new GeneralTextCleaner(), mock(TokenCounter.class));

        var capability = provider.capability(new FileResource(
                1L, 10L, 20L, null, "source." + type, type, source));

        assertEquals(true, capability.available());
    }

    @Test
    void cached_extraction_is_normalized_delimited_and_converted_with_boundary_and_offset_map() {
        ManagedExtractionCache cache = mock(ManagedExtractionCache.class);
        TokenCounter counter = mock(TokenCounter.class);
        when(counter.count(anyString())).thenAnswer(invocation ->
                ((String) invocation.getArgument(0)).codePointCount(0,
                        ((String) invocation.getArgument(0)).length()));
        FileResource resource = new FileResource(1L, 10L, 20L, null,
                "notes.txt", "txt", tempDir.resolve("snapshot.txt"));
        when(cache.getOrExtract(anyLong(), anyLong(), anyString(), eq(resource.path()), eq("txt")))
                .thenReturn(new ExtractedText("first\r\nsecond", "text/plain",
                        "plain-text", "v1", List.of(), Map.of()));
        GeneralTextInputProvider provider = new GeneralTextInputProvider(
                mock(DocumentTextExtractorRegistry.class), cache, new TextNormalizer(),
                new GeneralTextCleaner(), counter);

        var result = provider.provide(resource, "a".repeat(64), GeneralChunkConfig.defaults());

        assertEquals(List.of("first", "second"), result.structure().blocks().stream()
                .map(block -> block.plainText()).toList());
        assertEquals("USER_DELIMITER",
                result.structure().blocks().get(0).attributes().get("boundaryAfter"));
        assertInstanceOf(CleanedOffsetMap.class,
                result.structure().blocks().get(0).attributes().get(CleanedSegment.OFFSET_MAP_ATTRIBUTE));
        assertEquals("plain-text", result.extractorMetadata().get("extractorId"));
        assertEquals("v1", result.extractorMetadata().get("extractorVersion"));
        assertEquals(true, result.delimiterMatched());
    }

    @Test
    void empty_extracted_text_fails_after_extraction_instead_of_at_the_source_byte_stage() {
        ManagedExtractionCache cache = mock(ManagedExtractionCache.class);
        FileResource resource = new FileResource(1L, 10L, 20L, null,
                "blank.pdf", "pdf", tempDir.resolve("snapshot.pdf"));
        when(cache.getOrExtract(anyLong(), anyLong(), anyString(), eq(resource.path()), eq("pdf")))
                .thenReturn(new ExtractedText("   ", "application/pdf",
                        "pdfbox", "v1", List.of(), Map.of()));
        GeneralTextInputProvider provider = new GeneralTextInputProvider(
                mock(DocumentTextExtractorRegistry.class), cache, new TextNormalizer(),
                new GeneralTextCleaner(), mock(TokenCounter.class));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> provider.provide(resource, "b".repeat(64), GeneralChunkConfig.defaults()));

        assertEquals("未提取到可分块文字", failure.getMessage());
    }
}
