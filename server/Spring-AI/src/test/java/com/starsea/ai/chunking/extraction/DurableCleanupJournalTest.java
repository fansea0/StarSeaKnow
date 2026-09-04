package com.starsea.ai.chunking.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableCleanupJournalTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void source_only_obligation_survives_a_failed_retry_and_is_removed_after_recovery() throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("cache");
        Path uploadRoot = temporaryDirectory.resolve("upload");
        Path source = uploadRoot.resolve("1/9/source.txt.deleting-" + UUID.randomUUID());
        Files.createDirectories(source.getParent());
        Files.writeString(source, "source");
        AtomicBoolean available = new AtomicBoolean();
        DurableCleanupJournal journal = new DurableCleanupJournal(
                new ObjectMapper(), cacheRoot, uploadRoot) {
            @Override
            protected void deleteTarget(Path target) throws IOException {
                if (!available.get()) throw new IOException("deliberate failure");
                super.deleteTarget(target);
            }
        };

        UUID obligation = journal.persist(1L, 9L,
                List.of(journal.sourceTarget(source)));
        journal.retryPending(8);

        assertTrue(Files.exists(source));
        assertTrue(Files.exists(journal.journalDirectory()
                .resolve("cleanup-" + obligation + ".json")));

        available.set(true);
        journal.retryPending(8);

        assertFalse(Files.exists(source));
        try (var entries = Files.list(journal.journalDirectory())) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void one_obligation_removes_managed_source_and_cache_quarantines_together() throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("cache");
        Path uploadRoot = temporaryDirectory.resolve("upload");
        Path source = deletingFile(uploadRoot.resolve("1/9/source.txt"));
        Path text = deletingFile(cacheRoot.resolve("1/9/text.txt"));
        Path sourceMap = deletingFile(cacheRoot.resolve("1/9/source-map.json"));
        DurableCleanupJournal journal = new DurableCleanupJournal(
                new ObjectMapper(), cacheRoot, uploadRoot);

        journal.persist(1L, 9L, List.of(
                journal.sourceTarget(source),
                journal.cacheTarget(text),
                journal.cacheTarget(sourceMap)));
        journal.retryPending(8);

        assertFalse(Files.exists(source));
        assertFalse(Files.exists(text));
        assertFalse(Files.exists(sourceMap));
        try (var entries = Files.list(journal.journalDirectory())) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void malicious_relative_target_is_rejected_without_deleting_outside_data() throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("cache");
        Path uploadRoot = temporaryDirectory.resolve("upload");
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "keep");
        DurableCleanupJournal journal = new DurableCleanupJournal(
                new ObjectMapper(), cacheRoot, uploadRoot);
        Files.createDirectories(journal.journalDirectory());
        UUID obligation = UUID.randomUUID();
        Files.writeString(journal.journalDirectory().resolve("cleanup-" + obligation + ".json"), """
                {"obligationId":"%s","tenantId":1,"fileId":9,
                 "targets":[{"root":"UPLOAD","relativePath":"../outside.txt"}]}
                """.formatted(obligation));

        journal.retryPending(8);

        assertEquals("keep", Files.readString(outside));
        assertFalse(Files.exists(journal.journalDirectory()
                .resolve("cleanup-" + obligation + ".json")));
        assertTrue(Files.exists(journal.journalDirectory()
                .resolve("cleanup-" + obligation + ".rejected")));
    }

    private Path deletingFile(Path original) throws IOException {
        Path target = original.resolveSibling(original.getFileName()
                + ".deleting-" + UUID.randomUUID());
        Files.createDirectories(target.getParent());
        Files.writeString(target, "data");
        return target;
    }
}
