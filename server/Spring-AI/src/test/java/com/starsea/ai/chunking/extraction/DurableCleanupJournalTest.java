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
import java.util.concurrent.atomic.AtomicInteger;

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
        Path source = uploadRoot.resolve(".deleting/1/9/source.txt.deleting-" + UUID.randomUUID());
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
        Path source = deletingFile(uploadRoot.resolve(".deleting/1/9/source.txt"));
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

    @Test
    void prepared_file_deletion_restores_quarantine_when_the_database_owner_still_exists()
            throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("prepared-cache");
        Path uploadRoot = temporaryDirectory.resolve("prepared-upload");
        Path original = uploadRoot.resolve("1/10/source.txt");
        Path quarantined = uploadRoot.resolve(".deleting/1/9/source.txt.deleting-"
                + UUID.randomUUID());
        Files.createDirectories(original.getParent());
        Files.createDirectories(quarantined.getParent());
        Files.writeString(original, "source");
        DurableCleanupJournal journal = journal(cacheRoot, uploadRoot,
                new DurableCleanupJournal.OwnerSnapshot(original.toString(), null, null));
        UUID obligation = journal.prepareFileDeletion(1L, 9L,
                List.of(journal.sourceMove(original, quarantined, 1L, 9L)));
        journal.movePrepared(original, quarantined, DurableCleanupJournal.RootKind.UPLOAD);

        journal.retryPending(8);

        assertEquals("source", Files.readString(original));
        assertFalse(Files.exists(quarantined));
        assertFalse(Files.exists(journal.journalDirectory()
                .resolve("cleanup-" + obligation + ".json")));
    }

    @Test
    void prepared_file_deletion_removes_quarantine_when_the_database_owner_is_absent()
            throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("absent-cache");
        Path uploadRoot = temporaryDirectory.resolve("absent-upload");
        Path original = uploadRoot.resolve("1/10/source.txt");
        Path quarantined = uploadRoot.resolve(".deleting/1/9/source.txt.deleting-"
                + UUID.randomUUID());
        Files.createDirectories(original.getParent());
        Files.createDirectories(quarantined.getParent());
        Files.writeString(original, "source");
        DurableCleanupJournal journal = journal(cacheRoot, uploadRoot,
                DurableCleanupJournal.OwnerSnapshot.absent());
        journal.prepareFileDeletion(1L, 9L,
                List.of(journal.sourceMove(original, quarantined, 1L, 9L)));
        journal.movePrepared(original, quarantined, DurableCleanupJournal.RootKind.UPLOAD);

        journal.retryPending(8);

        assertFalse(Files.exists(original));
        assertFalse(Files.exists(quarantined));
    }

    @Test
    void prepared_generation_is_deleted_unless_metadata_references_that_exact_pair() throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("generation-cache");
        Path uploadRoot = temporaryDirectory.resolve("generation-upload");
        Path text = cacheRoot.resolve("1/9/text-" + UUID.randomUUID() + ".txt");
        Path map = cacheRoot.resolve("1/9/source-map-" + UUID.randomUUID() + ".json");
        AtomicBoolean referenced = new AtomicBoolean();
        DurableCleanupJournal journal = new DurableCleanupJournal(new ObjectMapper(), cacheRoot,
                uploadRoot, (tenantId, fileId) -> referenced.get()
                ? new DurableCleanupJournal.OwnerSnapshot(null, text.toString(), map.toString())
                : new DurableCleanupJournal.OwnerSnapshot(null, null, null));
        UUID first = journal.prepareGeneration(1L, 9L,
                List.of(journal.cacheTarget(text), journal.cacheTarget(map)));
        Files.createDirectories(text.getParent());
        Files.writeString(text, "body");
        Files.writeString(map, "map");

        journal.retryPending(8);

        assertFalse(Files.exists(text));
        assertFalse(Files.exists(map));
        assertFalse(Files.exists(journal.journalDirectory().resolve("cleanup-" + first + ".json")));

        UUID second = journal.prepareGeneration(1L, 9L,
                List.of(journal.cacheTarget(text), journal.cacheTarget(map)));
        Files.writeString(text, "body");
        Files.writeString(map, "map");
        referenced.set(true);
        journal.retryPending(8);

        assertTrue(Files.exists(text));
        assertTrue(Files.exists(map));
        assertFalse(Files.exists(journal.journalDirectory().resolve("cleanup-" + second + ".json")));
    }

    @Test
    void failed_entry_is_deferred_so_a_later_due_obligation_is_not_starved() throws Exception {
        Path cacheRoot = temporaryDirectory.resolve("fair-cache");
        Path uploadRoot = temporaryDirectory.resolve("fair-upload");
        Path blocked = deletingFile(cacheRoot.resolve("1/9/a.txt"));
        Path ready = deletingFile(cacheRoot.resolve("1/10/b.txt"));
        AtomicInteger attempts = new AtomicInteger();
        DurableCleanupJournal journal = new DurableCleanupJournal(new ObjectMapper(), cacheRoot,
                uploadRoot) {
            @Override protected void deleteTarget(Path target) throws IOException {
                attempts.incrementAndGet();
                if (target.equals(blocked)) throw new IOException("still blocked");
                super.deleteTarget(target);
            }
        };
        journal.persist(1L, 9L, List.of(journal.cacheTarget(blocked)));
        journal.persist(1L, 10L, List.of(journal.cacheTarget(ready)));

        journal.retryPending(1);
        journal.retryPending(1);

        assertTrue(Files.exists(blocked));
        assertFalse(Files.exists(ready));
        assertEquals(2, attempts.get());
    }

    @Test
    void real_directory_sync_failure_prevents_an_obligation_from_being_reported_durable() {
        Path cacheRoot = temporaryDirectory.resolve("sync-cache");
        Path uploadRoot = temporaryDirectory.resolve("sync-upload");
        Path target = cacheRoot.resolve("1/9/text-" + UUID.randomUUID() + ".txt");
        DurableCleanupJournal journal = new DurableCleanupJournal(
                new ObjectMapper(), cacheRoot, uploadRoot) {
            @Override protected void syncDirectory(Path directory) throws IOException {
                throw new IOException("real sync failure");
            }
        };

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> journal.persist(1L, 9L, List.of(journal.cacheTarget(target))));
    }

    @Test
    void upload_quarantine_scope_must_match_the_exact_file_identifier() {
        Path cacheRoot = temporaryDirectory.resolve("scope-cache");
        Path uploadRoot = temporaryDirectory.resolve("scope-upload");
        Path original = uploadRoot.resolve("1/10/source.txt");
        Path wrongFileScope = uploadRoot.resolve(".deleting/1/10/source.txt.deleting-"
                + UUID.randomUUID());
        DurableCleanupJournal journal = journal(cacheRoot, uploadRoot,
                new DurableCleanupJournal.OwnerSnapshot(original.toString(), null, null));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> journal.sourceMove(original, wrongFileScope, 1L, 9L));
    }

    @Test
    void upload_original_must_match_the_trusted_database_path_when_the_owner_exists() {
        Path cacheRoot = temporaryDirectory.resolve("owner-scope-cache");
        Path uploadRoot = temporaryDirectory.resolve("owner-scope-upload");
        Path trusted = uploadRoot.resolve("1/10/trusted.txt");
        Path attackerSelected = uploadRoot.resolve("1/10/unrelated.txt");
        Path quarantine = uploadRoot.resolve(".deleting/1/9/unrelated.txt.deleting-"
                + UUID.randomUUID());
        DurableCleanupJournal journal = journal(cacheRoot, uploadRoot,
                new DurableCleanupJournal.OwnerSnapshot(trusted.toString(), null, null));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> journal.sourceMove(attackerSelected, quarantine, 1L, 9L));
    }

    private DurableCleanupJournal journal(Path cacheRoot, Path uploadRoot,
                                           DurableCleanupJournal.OwnerSnapshot snapshot) {
        return new DurableCleanupJournal(new ObjectMapper(), cacheRoot, uploadRoot,
                (tenantId, fileId) -> snapshot);
    }

    private Path deletingFile(Path original) throws IOException {
        Path target = original.resolveSibling(original.getFileName()
                + ".deleting-" + UUID.randomUUID());
        Files.createDirectories(target.getParent());
        Files.writeString(target, "data");
        return target;
    }
}
