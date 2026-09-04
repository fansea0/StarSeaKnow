package com.starsea.ai.chunking.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.domain.FileTextExtraction;
import com.starsea.ai.mapper.FileTextExtractionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedExtractionCacheTest {

    @TempDir
    Path cacheRoot;

    @Test
    void matching_key_and_managed_files_returns_cache_hit_without_extracting_again() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        AtomicInteger calls = new AtomicInteger();
        DocumentTextExtractor extractor = countingExtractor(calls);
        DocumentTextExtractorRegistry registry = new DocumentTextExtractorRegistry(List.of(extractor));
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), registry, new ObjectMapper(), cacheRoot.resolve("managed"));

        String sourceHash = "a".repeat(64);
        ExtractedText first = cache.getOrExtract(1L, 9L, sourceHash, source, "text/plain");
        ExtractedText second = cache.getOrExtract(1L, 9L, sourceHash, source, "text/plain");

        assertEquals("cached body", first.text());
        assertEquals(first, second);
        assertEquals(1, calls.get());
        assertTrue(Path.of(mapper.row.getManagedTextPath()).startsWith(cacheRoot.resolve("managed/1/9")));
        assertTrue(Files.isRegularFile(Path.of(mapper.row.getManagedTextPath())));
        assertTrue(Files.isRegularFile(Path.of(mapper.row.getSourceMapPath())));
    }

    @Test
    void changed_source_hash_removes_stale_files_and_reextracts() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        AtomicInteger calls = new AtomicInteger();
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(countingExtractor(calls))),
                new ObjectMapper(), cacheRoot.resolve("managed"));
        cache.getOrExtract(1L, 9L, "a".repeat(64), source, "text/plain");
        Path oldText = Path.of(mapper.row.getManagedTextPath());
        Path oldMap = Path.of(mapper.row.getSourceMapPath());
        mapper.row.setManagedTextPath(cacheRoot.resolve("managed/1/9/stale-text.txt").toString());
        mapper.row.setSourceMapPath(cacheRoot.resolve("managed/1/9/stale-map.json").toString());
        Files.move(oldText, Path.of(mapper.row.getManagedTextPath()));
        Files.move(oldMap, Path.of(mapper.row.getSourceMapPath()));

        cache.getOrExtract(1L, 9L, "b".repeat(64), source, "text/plain");

        assertEquals(2, calls.get());
        assertFalse(Files.exists(cacheRoot.resolve("managed/1/9/stale-text.txt")));
        assertFalse(Files.exists(cacheRoot.resolve("managed/1/9/stale-map.json")));
        assertEquals("b".repeat(64), mapper.row.getSourceHash());
    }

    @Test
    void same_length_text_tampering_invalidates_the_managed_file_pair() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        AtomicInteger calls = new AtomicInteger();
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(countingExtractor(calls))),
                new ObjectMapper(), cacheRoot.resolve("managed"));
        String sourceHash = "d".repeat(64);
        cache.getOrExtract(1L, 9L, sourceHash, source, "text/plain");
        Files.writeString(Path.of(mapper.row.getManagedTextPath()), "tamper body");

        ExtractedText result = cache.getOrExtract(1L, 9L, sourceHash, source, "text/plain");

        assertEquals("cached body", result.text());
        assertEquals(2, calls.get());
    }

    @Test
    void tenant_and_file_ids_have_distinct_normalized_directories() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        List<Path> paths = new ArrayList<>();
        for (long tenantId : List.of(1L, 2L)) {
            StatefulMapper mapper = new StatefulMapper();
            ManagedExtractionCache cache = new ManagedExtractionCache(
                    mapper.proxy(), new DocumentTextExtractorRegistry(List.of(countingExtractor(new AtomicInteger()))),
                    new ObjectMapper(), cacheRoot.resolve("managed"));
            cache.getOrExtract(tenantId, 9L, "c".repeat(64), source, "text/plain");
            paths.add(Path.of(mapper.row.getManagedTextPath()));
        }

        assertEquals(2, paths.stream().distinct().count());
        assertTrue(paths.get(0).startsWith(cacheRoot.resolve("managed/1/9")));
        assertTrue(paths.get(1).startsWith(cacheRoot.resolve("managed/2/9")));
    }

    @Test
    void deletion_refuses_metadata_paths_outside_managed_root() throws Exception {
        Path outside = cacheRoot.resolve("outside.txt");
        Files.writeString(outside, "must remain");
        StatefulMapper mapper = new StatefulMapper();
        mapper.row = row(1L, 9L, "hash", outside, cacheRoot.resolve("outside-map.json"));
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(countingExtractor(new AtomicInteger()))),
                new ObjectMapper(), cacheRoot.resolve("managed"));

        assertThrows(IllegalStateException.class, () -> cache.deleteManagedFiles(1L, 9L));
        assertTrue(Files.exists(outside));
    }

    private DocumentTextExtractor countingExtractor(AtomicInteger calls) {
        return new DocumentTextExtractor() {
            @Override public String id() { return "counting"; }
            @Override public String version() { return "v1"; }
            @Override public int priority() { return 100; }
            @Override public Set<String> supportedMediaTypes() { return Set.of("text/plain"); }
            @Override public ExtractedText extract(Path path, ExtractionCapability capability) {
                calls.incrementAndGet();
                return new ExtractedText("cached body", "text/plain", id(), version(),
                        List.of(new SourceSpan(0, 11, Map.of("line", 1))), Map.of("fixture", true));
            }
        };
    }

    private static FileTextExtraction row(long tenantId, long fileId, String hash,
                                          Path textPath, Path mapPath) {
        FileTextExtraction row = new FileTextExtraction();
        row.setTenantId(tenantId);
        row.setFileId(fileId);
        row.setSourceHash(hash);
        row.setExtractorId("counting");
        row.setExtractorVersion("v1");
        row.setMediaType("text/plain");
        row.setManagedTextPath(textPath.toString());
        row.setSourceMapPath(mapPath.toString());
        row.setCharacterCount(11L);
        return row;
    }

    private static final class StatefulMapper {
        private FileTextExtraction row;
        private final FileTextExtractionMapper delegate = mock(FileTextExtractionMapper.class);

        private FileTextExtractionMapper proxy() {
            when(delegate.findScoped(any(Long.class), any(Long.class))).thenAnswer(inv -> row);
            when(delegate.insert(any(FileTextExtraction.class))).thenAnswer(inv -> {
                row = inv.getArgument(0);
                return 1;
            });
            when(delegate.updateScoped(any(FileTextExtraction.class))).thenAnswer(inv -> {
                row = inv.getArgument(0);
                return 1;
            });
            return delegate;
        }
    }
}
