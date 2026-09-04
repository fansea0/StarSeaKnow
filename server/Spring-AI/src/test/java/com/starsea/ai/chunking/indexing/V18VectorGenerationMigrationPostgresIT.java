package com.starsea.ai.chunking.indexing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V18VectorGenerationMigrationPostgresIT {

    @Test
    void backfills_active_vector_ids_and_captures_the_indexing_file_lock_owner() throws Exception {
        String schema = "starseaknow_v18_it_" + UUID.randomUUID().toString().replace("-", "");
        UUID activePublicId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;
                CREATE TABLE file_processing (
                    file_id BIGINT PRIMARY KEY,
                    lock_version INTEGER NOT NULL
                );
                CREATE TABLE document_chunk (
                    id BIGSERIAL PRIMARY KEY,
                    public_id UUID NOT NULL UNIQUE,
                    file_id BIGINT NOT NULL,
                    status SMALLINT NOT NULL
                );
                INSERT INTO file_processing VALUES (20, 9), (21, 12);
                INSERT INTO document_chunk(public_id, file_id, status) VALUES
                    ('%s', 20, 2),
                    ('22222222-2222-2222-2222-222222222222', 21, 1),
                    ('33333333-3333-3333-3333-333333333333', 20, 0);
                %s
                CREATE FUNCTION rejected(command TEXT) RETURNS BOOLEAN LANGUAGE plpgsql AS $body$
                BEGIN
                    EXECUTE command;
                    RETURN FALSE;
                EXCEPTION WHEN check_violation OR unique_violation THEN
                    RETURN TRUE;
                END
                $body$;
                SELECT public_id, vector_id, pending_vector_id, indexing_lock_version
                FROM document_chunk ORDER BY public_id;
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'document_chunk' AND column_name = 'vector_id';
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'chunk_vector_cleanup'
                  AND column_name = 'next_attempt_at';
                SELECT rejected($q$INSERT INTO document_chunk
                    (public_id, file_id, status, pending_vector_id)
                    VALUES ('44444444-4444-4444-4444-444444444444', 20, 1,
                            'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')$q$);
                SELECT rejected($q$UPDATE document_chunk
                    SET indexing_lock_version = 2 WHERE status = 0$q$);
                SELECT rejected($q$UPDATE document_chunk
                    SET vector_id = NULL WHERE status = 2$q$);
                SELECT rejected($q$UPDATE document_chunk
                    SET vector_id = '%s' WHERE status = 0$q$);
                INSERT INTO chunk_vector_cleanup
                    (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id)
                VALUES ('55555555-5555-5555-5555-555555555555', 1, 10, 20, '%s');
                SELECT rejected($q$INSERT INTO chunk_vector_cleanup
                    (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id)
                    VALUES ('55555555-5555-5555-5555-555555555555', 1, 10, 20,
                            '22222222-2222-2222-2222-222222222222')$q$);
                SELECT rejected($q$UPDATE chunk_vector_cleanup
                    SET state = 1
                    WHERE vector_id = '55555555-5555-5555-5555-555555555555'$q$);
                SELECT rejected($q$UPDATE chunk_vector_cleanup
                    SET writer_owner = '66666666-6666-6666-6666-666666666666'
                    WHERE vector_id = '55555555-5555-5555-5555-555555555555'$q$);
                ROLLBACK;
                """.formatted(schema, schema, activePublicId, readMigration(),
                activePublicId, activePublicId);
        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true).start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(List.of(
                activePublicId + "|" + activePublicId + "||",
                "22222222-2222-2222-2222-222222222222||"
                        + "22222222-2222-2222-2222-222222222222|12",
                "33333333-3333-3333-3333-333333333333|||",
                "YES", "NO", "t", "t", "t", "t", "t", "t", "t"),
                output.lines().toList());
    }

    @Test
    void cleanup_claim_skips_a_concurrently_locked_generation_and_future_backoff()
            throws Exception {
        String schema = "starseaknow_v18_claim_it_"
                + UUID.randomUUID().toString().replace("-", "");
        UUID locked = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
        UUID laterDue = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        UUID future = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");
        PsqlResult setup = runPsql(setupSql(schema) + """
                SET search_path TO %s;
                INSERT INTO chunk_vector_cleanup
                    (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id,
                     next_attempt_at)
                VALUES
                    ('%s', 1, 10, 20, '11111111-1111-1111-1111-111111111111',
                     CURRENT_TIMESTAMP - INTERVAL '2 seconds'),
                    ('%s', 1, 10, 20, '22222222-2222-2222-2222-222222222222',
                     CURRENT_TIMESTAMP - INTERVAL '1 second'),
                    ('%s', 1, 10, 20, '33333333-3333-3333-3333-333333333333',
                     CURRENT_TIMESTAMP + INTERVAL '1 hour');
                """.formatted(schema, locked, laterDue, future));
        assertEquals(0, setup.exitCode(), setup.output());

        Process locker = null;
        try {
            locker = startPsql("""
                    SET search_path TO %s;
                    BEGIN;
                    SELECT vector_id FROM chunk_vector_cleanup
                    WHERE vector_id = '%s' FOR UPDATE;
                    SELECT pg_sleep(10);
                    ROLLBACK;
                    """.formatted(schema, locked));
            BufferedReader lockerOutput = new BufferedReader(new InputStreamReader(
                    locker.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(locked.toString(), lockerOutput.readLine());

            PsqlResult claim = runPsql("""
                    SET search_path TO %s;
                    WITH due AS (
                        SELECT candidate.id
                        FROM chunk_vector_cleanup candidate
                        WHERE candidate.state = 0
                          AND candidate.next_attempt_at <= CURRENT_TIMESTAMP
                          AND NOT EXISTS (
                              SELECT 1 FROM document_chunk dc
                              WHERE (dc.status = 2 AND dc.vector_id = candidate.vector_id)
                                 OR dc.pending_vector_id = candidate.vector_id
                          )
                        ORDER BY candidate.next_attempt_at, candidate.id
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE chunk_vector_cleanup q
                    SET state = 1,
                        claim_owner = 'dddddddd-dddd-dddd-dddd-dddddddddddd',
                        claim_lease_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                        update_time = CURRENT_TIMESTAMP
                    FROM due WHERE q.id = due.id
                    RETURNING q.vector_id;
                    """.formatted(schema));

            assertEquals(0, claim.exitCode(), claim.output());
            assertEquals(laterDue.toString(), claim.output());
        } finally {
            if (locker != null && locker.isAlive()) {
                locker.destroyForcibly();
                assertTrue(locker.waitFor(5, TimeUnit.SECONDS));
            }
            PsqlResult teardown = runPsql("DROP SCHEMA IF EXISTS " + schema + " CASCADE;");
            assertEquals(0, teardown.exitCode(), teardown.output());
        }
    }

    @Test
    void active_obligation_survives_an_enqueue_concurrent_with_edit_transition()
            throws Exception {
        String schema = "starseaknow_v18_enqueue_it_"
                + UUID.randomUUID().toString().replace("-", "");
        UUID chunkId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID vectorId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        PsqlResult setup = runPsql(setupSql(schema) + """
                SET search_path TO %s;
                INSERT INTO document_chunk
                    (public_id, tenant_id, knowledge_id, file_id, status, vector_id)
                VALUES ('%s', 1, 10, 20, 2, '%s');
                INSERT INTO chunk_vector_cleanup
                    (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id,
                     state, retry_count)
                VALUES ('%s', 1, 10, 20, '%s', 0, 7);
                """.formatted(schema, chunkId, vectorId, vectorId, chunkId));
        assertEquals(0, setup.exitCode(), setup.output());

        Process editor = null;
        try {
            editor = startPsql("""
                    SET search_path TO %s;
                    BEGIN;
                    UPDATE document_chunk SET status = 0
                    WHERE public_id = '%s';
                    SELECT 'editing';
                    SELECT pg_sleep(3);
                    COMMIT;
                    """.formatted(schema, chunkId));
            BufferedReader editorOutput = new BufferedReader(new InputStreamReader(
                    editor.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("editing", editorOutput.readLine());

            PsqlResult enqueue = runPsql("""
                    SET search_path TO %s;
                    INSERT INTO chunk_vector_cleanup
                        (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id)
                    VALUES ('%s', 1, 10, 20, '%s')
                    ON CONFLICT (vector_id) DO UPDATE
                    SET state = 0,
                        retry_count = 0,
                        next_attempt_at = CURRENT_TIMESTAMP,
                        last_error = NULL,
                        update_time = CURRENT_TIMESTAMP
                    RETURNING state, retry_count;
                    """.formatted(schema, vectorId, chunkId));
            assertEquals(0, enqueue.exitCode(), enqueue.output());
            assertEquals("0|0", enqueue.output());
            assertTrue(editor.waitFor(8, TimeUnit.SECONDS));
            assertEquals(0, editor.exitValue(), readRemaining(editorOutput));

            PsqlResult persisted = runPsql("""
                    SET search_path TO %s;
                    SELECT dc.status, q.state, q.retry_count
                    FROM document_chunk dc
                    JOIN chunk_vector_cleanup q ON q.vector_id = dc.vector_id
                    WHERE dc.public_id = '%s';
                    """.formatted(schema, chunkId));
            assertEquals(0, persisted.exitCode(), persisted.output());
            assertEquals("0|0|0", persisted.output());
        } finally {
            if (editor != null && editor.isAlive()) {
                editor.destroyForcibly();
                editor.waitFor(5, TimeUnit.SECONDS);
            }
            PsqlResult teardown = runPsql("DROP SCHEMA IF EXISTS " + schema + " CASCADE;");
            assertEquals(0, teardown.exitCode(), teardown.output());
        }
    }

    @Test
    void durable_writer_registration_waits_for_cleanup_lock_and_recreates_the_tombstone()
            throws Exception {
        String schema = "starseaknow_v18_writer_it_"
                + UUID.randomUUID().toString().replace("-", "");
        UUID chunkId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID vectorId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID cleanupOwner = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID writerOwner = UUID.fromString("abababab-abab-abab-abab-abababababab");
        PsqlResult setup = runPsql(setupSql(schema) + """
                SET search_path TO %s;
                INSERT INTO chunk_vector_cleanup
                    (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id,
                     state, claim_owner, claim_lease_until)
                VALUES ('%s', 1, 10, 20, '%s', 1, '%s',
                        CURRENT_TIMESTAMP + INTERVAL '2 minutes');
                """.formatted(schema, vectorId, chunkId, cleanupOwner));
        assertEquals(0, setup.exitCode(), setup.output());

        Process cleaner = null;
        try {
            cleaner = startPsql("""
                    SET search_path TO %s;
                    BEGIN;
                    SELECT vector_id FROM chunk_vector_cleanup
                    WHERE vector_id = '%s' AND claim_owner = '%s'
                    FOR UPDATE;
                    DELETE FROM chunk_vector_cleanup WHERE vector_id = '%s';
                    SELECT 'deleting';
                    SELECT pg_sleep(3);
                    COMMIT;
                    """.formatted(schema, vectorId, cleanupOwner, vectorId));
            BufferedReader cleanerOutput = new BufferedReader(new InputStreamReader(
                    cleaner.getInputStream(), StandardCharsets.UTF_8));
            assertEquals(vectorId.toString(), cleanerOutput.readLine());
            assertEquals("deleting", cleanerOutput.readLine());

            PsqlResult writer = runPsql("""
                    SET search_path TO %s;
                    INSERT INTO chunk_vector_cleanup
                        (vector_id, tenant_id, knowledge_id, file_id, chunk_public_id,
                         writer_owner, writer_lease_until)
                    VALUES ('%s', 1, 10, 20, '%s', '%s',
                            CURRENT_TIMESTAMP + INTERVAL '1 minute')
                    ON CONFLICT (vector_id) DO UPDATE
                    SET state = 0,
                        writer_owner = EXCLUDED.writer_owner,
                        writer_lease_until = EXCLUDED.writer_lease_until,
                        claim_owner = NULL,
                        claim_lease_until = NULL,
                        update_time = CURRENT_TIMESTAMP
                    RETURNING writer_owner, state;
                    """.formatted(schema, vectorId, chunkId, writerOwner));
            assertEquals(0, writer.exitCode(), writer.output());
            assertEquals(writerOwner + "|0", writer.output());
            assertTrue(cleaner.waitFor(8, TimeUnit.SECONDS));
            assertEquals(0, cleaner.exitValue(), readRemaining(cleanerOutput));

            PsqlResult persisted = runPsql("""
                    SET search_path TO %s;
                    SELECT writer_owner, writer_lease_until > CURRENT_TIMESTAMP,
                           claim_owner IS NULL
                    FROM chunk_vector_cleanup WHERE vector_id = '%s';
                    """.formatted(schema, vectorId));
            assertEquals(0, persisted.exitCode(), persisted.output());
            assertEquals(writerOwner + "|t|t", persisted.output());
        } finally {
            if (cleaner != null && cleaner.isAlive()) {
                cleaner.destroyForcibly();
                cleaner.waitFor(5, TimeUnit.SECONDS);
            }
            PsqlResult teardown = runPsql("DROP SCHEMA IF EXISTS " + schema + " CASCADE;");
            assertEquals(0, teardown.exitCode(), teardown.output());
        }
    }

    private String setupSql(String schema) throws IOException {
        return """
                CREATE SCHEMA %s;
                SET search_path TO %s;
                CREATE TABLE file_processing (
                    file_id BIGINT PRIMARY KEY,
                    lock_version INTEGER NOT NULL
                );
                CREATE TABLE document_chunk (
                    id BIGSERIAL PRIMARY KEY,
                    public_id UUID NOT NULL UNIQUE,
                    tenant_id BIGINT NOT NULL,
                    knowledge_id BIGINT NOT NULL,
                    file_id BIGINT NOT NULL,
                    status SMALLINT NOT NULL
                );
                %s
                """.formatted(schema, schema, readMigration());
    }

    private Process startPsql(String sql) throws IOException {
        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true).start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        return process;
    }

    private PsqlResult runPsql(String sql) throws IOException, InterruptedException {
        Process process = startPsql(sql);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .strip();
        return new PsqlResult(process.waitFor(), output);
    }

    private String readRemaining(BufferedReader reader) throws IOException {
        return reader.lines().reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private record PsqlResult(int exitCode, String output) {
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V18__add_vector_generations.sql")) {
            if (stream == null) throw new IOException("V18 migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
