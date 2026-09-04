package com.starsea.ai.chunking.indexing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                "YES", "t", "t", "t", "t", "t"), output.lines().toList());
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V18__add_vector_generations.sql")) {
            if (stream == null) throw new IOException("V18 migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
