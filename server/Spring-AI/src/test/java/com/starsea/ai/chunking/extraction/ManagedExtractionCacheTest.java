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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    void failed_replacement_cleanup_is_journaled_without_discarding_the_new_cache_hit() throws Exception {
        Path source = cacheRoot.resolve("replace-source.txt");
        Files.writeString(source, "source");
        Path managedRoot = cacheRoot.resolve("managed-replacement");
        ObjectMapper objectMapper = new ObjectMapper();
        DurableCleanupJournal journal = new DurableCleanupJournal(
                objectMapper, managedRoot, cacheRoot.resolve("upload"));
        StatefulMapper mapper = new StatefulMapper();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger failedDeletes = new AtomicInteger(2);
        ManagedExtractionCache cache = new ManagedExtractionCache(mapper.proxy(),
                new DocumentTextExtractorRegistry(List.of(countingExtractor(calls))),
                objectMapper, managedRoot, journal) {
            @Override
            protected void deleteReplacedManagedFile(Path path) throws java.io.IOException {
                if (failedDeletes.getAndDecrement() > 0) {
                    throw new java.io.IOException("deliberate replacement cleanup failure");
                }
                super.deleteReplacedManagedFile(path);
            }
        };
        cache.getOrExtract(1L, 9L, "a".repeat(64), source, "text/plain");

        ExtractedText replacement = cache.getOrExtract(
                1L, 9L, "b".repeat(64), source, "text/plain");

        assertEquals("cached body", replacement.text());
        assertEquals(2, calls.get());
        try (var entries = Files.list(journal.journalDirectory())) {
            assertEquals(1, entries.filter(path -> path.toString().endsWith(".json")).count());
        }

        journal.retryPending(8);
        try (var files = Files.walk(managedRoot.resolve("1/9"))) {
            assertEquals(2, files.filter(Files::isRegularFile).count(),
                    "only the active text/source-map generation remains");
        }
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

    @Test
    void write_rejects_a_tenant_directory_symlink_escape() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        Path managed = cacheRoot.resolve("managed");
        Path outside = cacheRoot.resolve("outside");
        Files.createDirectories(managed);
        Files.createDirectories(outside);
        Files.createSymbolicLink(managed.resolve("1"), outside);
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(
                        countingExtractor(new AtomicInteger()))), new ObjectMapper(), managed);

        assertThrows(IllegalStateException.class,
                () -> cache.getOrExtract(1L, 9L, "e".repeat(64), source, "text/plain"));
        try (var entries = Files.list(outside)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void cache_hit_rejects_a_managed_text_symlink() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(
                        countingExtractor(new AtomicInteger()))), new ObjectMapper(), cacheRoot.resolve("managed"));
        String hash = "f".repeat(64);
        cache.getOrExtract(1L, 9L, hash, source, "text/plain");
        Path text = Path.of(mapper.row.getManagedTextPath());
        Path outside = cacheRoot.resolve("outside.txt");
        Files.copy(text, outside);
        Files.delete(text);
        Files.createSymbolicLink(text, outside);

        assertThrows(IllegalStateException.class,
                () -> cache.getOrExtract(1L, 9L, hash, source, "text/plain"));
        assertEquals("cached body", Files.readString(outside));
    }

    @Test
    void deletion_rejects_a_managed_file_symlink_without_touching_target() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(
                        countingExtractor(new AtomicInteger()))), new ObjectMapper(), cacheRoot.resolve("managed"));
        cache.getOrExtract(1L, 9L, "1".repeat(64), source, "text/plain");
        Path text = Path.of(mapper.row.getManagedTextPath());
        Path outside = cacheRoot.resolve("outside-delete.txt");
        Files.writeString(outside, "outside");
        Files.delete(text);
        Files.createSymbolicLink(text, outside);

        assertThrows(IllegalStateException.class, () -> cache.deleteManagedFiles(1L, 9L));
        assertEquals("outside", Files.readString(outside));
    }

    @Test
    void managed_file_quarantine_can_restore_both_files() throws Exception {
        Path source = cacheRoot.resolve("source.txt");
        Files.writeString(source, "source");
        StatefulMapper mapper = new StatefulMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(
                mapper.proxy(), new DocumentTextExtractorRegistry(List.of(
                        countingExtractor(new AtomicInteger()))), new ObjectMapper(), cacheRoot.resolve("managed"));
        cache.getOrExtract(1L, 9L, "2".repeat(64), source, "text/plain");
        Path text = Path.of(mapper.row.getManagedTextPath());
        Path sourceMap = Path.of(mapper.row.getSourceMapPath());

        ManagedExtractionCache.ManagedFileQuarantine quarantine =
                cache.quarantineManagedFiles(1L, 9L);
        assertFalse(Files.exists(text));
        assertFalse(Files.exists(sourceMap));

        quarantine.restore();
        assertTrue(Files.isRegularFile(text));
        assertTrue(Files.isRegularFile(sourceMap));
    }

    @Test
    void extraction_for_an_unrelated_tenant_file_key_does_not_wait_on_a_stalled_key() throws Exception {
        Path firstSource = cacheRoot.resolve("first.txt");
        Path secondSource = cacheRoot.resolve("second.txt");
        Files.writeString(firstSource, "first");
        Files.writeString(secondSource, "second");
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        DocumentTextExtractor extractor = new DocumentTextExtractor() {
            @Override public String id() { return "per-key"; }
            @Override public String version() { return "v1"; }
            @Override public int priority() { return 100; }
            @Override public Set<String> supportedMediaTypes() { return Set.of("text/plain"); }
            @Override public ExtractedText extract(Path path, ExtractionCapability capability) {
                if (path.equals(firstSource)) {
                    firstEntered.countDown();
                    try {
                        if (!releaseFirst.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
                String text = path.equals(firstSource) ? "first body" : "second body";
                return new ExtractedText(text, "text/plain", id(), version(),
                        List.of(new SourceSpan(0, text.length(), Map.of())), Map.of());
            }
        };
        KeyedMapper mapper = new KeyedMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(mapper.proxy(),
                new DocumentTextExtractorRegistry(List.of(extractor)), new ObjectMapper(),
                cacheRoot.resolve("managed"));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> cache.getOrExtract(
                    1L, 9L, "a".repeat(64), firstSource, "text/plain"));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            var second = executor.submit(() -> cache.getOrExtract(
                    1L, 10L, "b".repeat(64), secondSource, "text/plain"));
            assertEquals("second body", second.get(1, TimeUnit.SECONDS).text());

            releaseFirst.countDown();
            assertEquals("first body", first.get(1, TimeUnit.SECONDS).text());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void quarantine_holds_the_file_key_until_database_delete_and_cleanup_finish() throws Exception {
        Path source = cacheRoot.resolve("delete-race.txt");
        Files.writeString(source, "source");
        AtomicInteger calls = new AtomicInteger();
        KeyedMapper mapper = new KeyedMapper();
        ManagedExtractionCache cache = new ManagedExtractionCache(mapper.proxy(),
                new DocumentTextExtractorRegistry(List.of(countingExtractor(calls))),
                new ObjectMapper(), cacheRoot.resolve("managed"));
        cache.getOrExtract(1L, 9L, "a".repeat(64), source, "text/plain");
        ManagedExtractionCache.ManagedFileQuarantine quarantine =
                cache.quarantineManagedFiles(1L, 9L);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch racingPreviewStarted = new CountDownLatch(1);
        boolean committed = false;
        try {
            var racingPreview = executor.submit(() -> {
                racingPreviewStarted.countDown();
                return cache.getOrExtract(
                        1L, 9L, "b".repeat(64), source, "text/plain");
            });

            assertTrue(racingPreviewStarted.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> racingPreview.get(250, TimeUnit.MILLISECONDS));
            assertEquals(1, calls.get(), "preview must not re-enter extraction while deletion owns the key");

            Files.delete(source);
            mapper.remove(1L, 9L); // Model the file-row delete and extraction-row cascade.
            quarantine.commit();
            committed = true;
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> racingPreview.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof DocumentTextExtractor.ExtractionException);
            assertEquals(1, calls.get());
            try (var files = Files.walk(cacheRoot.resolve("managed/1/9"))) {
                assertEquals(0, files.filter(Files::isRegularFile).count());
            }
        } finally {
            if (!committed) quarantine.restore();
            executor.shutdownNow();
        }
    }

    @Test
    void failed_post_commit_plaintext_cleanup_persists_a_retry_obligation() throws Exception {
        Path source = cacheRoot.resolve("cleanup-obligation.txt");
        Files.writeString(source, "source");
        StatefulMapper mapper = new StatefulMapper();
        Path managedRoot = cacheRoot.resolve("managed");
        ManagedExtractionCache cache = new ManagedExtractionCache(mapper.proxy(),
                new DocumentTextExtractorRegistry(List.of(countingExtractor(new AtomicInteger()))),
                new ObjectMapper(), managedRoot) {
            @Override
            protected void deleteQuarantined(Path path) throws java.io.IOException {
                throw new java.io.IOException("deliberate cleanup failure");
            }
        };
        cache.getOrExtract(1L, 9L, "c".repeat(64), source, "text/plain");
        ManagedExtractionCache.ManagedFileQuarantine quarantine =
                cache.quarantineManagedFiles(1L, 9L);

        IllegalStateException failure = assertThrows(IllegalStateException.class, quarantine::commit);

        assertTrue(failure.getMessage().contains("cleanup obligation"));
        Path journalDirectory = managedRoot.resolve(".cleanup-journal");
        try (var files = Files.list(journalDirectory)) {
            List<Path> obligations = files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
            assertEquals(1, obligations.size());
            String persisted = Files.readString(obligations.get(0));
            assertTrue(persisted.contains("\"tenantId\":1"));
            assertTrue(persisted.contains("\"fileId\":9"));
            assertTrue(persisted.contains(".deleting-"));
            assertFalse(persisted.contains(managedRoot.toString()),
                    "cleanup records must contain managed relative targets only");
        }
    }

    @Test
    void failed_late_metadata_insert_journals_and_later_removes_the_new_managed_pair() throws Exception {
        Path source = cacheRoot.resolve("late-insert.txt");
        Files.writeString(source, "source");
        Path managedRoot = cacheRoot.resolve("managed");
        ObjectMapper objectMapper = new ObjectMapper();
        DurableCleanupJournal journal = new DurableCleanupJournal(
                objectMapper, managedRoot, cacheRoot.resolve("upload"));
        FileTextExtractionMapper mapper = mock(FileTextExtractionMapper.class);
        when(mapper.insert(any(FileTextExtraction.class))).thenReturn(0);
        ManagedExtractionCache cache = new ManagedExtractionCache(mapper,
                new DocumentTextExtractorRegistry(List.of(countingExtractor(new AtomicInteger()))),
                objectMapper, managedRoot, journal) {
            @Override
            protected void deleteNewManagedFile(Path path) throws java.io.IOException {
                throw new java.io.IOException("deliberate late cleanup failure");
            }
        };

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> cache.getOrExtract(1L, 9L, "d".repeat(64), source, "text/plain"));

        assertEquals("Extraction cache metadata was not saved", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0]
                instanceof ManagedExtractionCache.CleanupPendingException);
        try (var files = Files.walk(managedRoot.resolve("1/9"))) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }

        journal.retryPending(8);

        try (var files = Files.walk(managedRoot.resolve("1/9"))) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
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

    private static final class KeyedMapper {
        private final Map<String, FileTextExtraction> rows = new ConcurrentHashMap<>();
        private final FileTextExtractionMapper delegate = mock(FileTextExtractionMapper.class);

        private FileTextExtractionMapper proxy() {
            when(delegate.findScoped(any(Long.class), any(Long.class))).thenAnswer(inv ->
                    rows.get(key(inv.getArgument(0), inv.getArgument(1))));
            when(delegate.insert(any(FileTextExtraction.class))).thenAnswer(inv -> {
                FileTextExtraction row = inv.getArgument(0);
                rows.put(key(row.getTenantId(), row.getFileId()), row);
                return 1;
            });
            when(delegate.updateScoped(any(FileTextExtraction.class))).thenAnswer(inv -> {
                FileTextExtraction row = inv.getArgument(0);
                rows.put(key(row.getTenantId(), row.getFileId()), row);
                return 1;
            });
            return delegate;
        }

        private void remove(long tenantId, long fileId) {
            rows.remove(key(tenantId, fileId));
        }

        private static String key(long tenantId, long fileId) {
            return tenantId + ":" + fileId;
        }
    }
}
