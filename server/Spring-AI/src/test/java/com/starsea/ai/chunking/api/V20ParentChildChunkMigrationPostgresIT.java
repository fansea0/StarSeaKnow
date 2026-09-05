package com.starsea.ai.chunking.api;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V20ParentChildChunkMigrationPostgresIT {

    @Test
    void migrates_historical_chunks_and_enforces_parent_child_integrity() throws Exception {
        Assumptions.assumeTrue(postgresIsAvailable(), "local PostgreSQL is unavailable");
        String schema = "starseaknow_v20_it_" + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;
                CREATE EXTENSION IF NOT EXISTS pgcrypto;
                CREATE OR REPLACE FUNCTION update_timestamp()
                RETURNS TRIGGER AS $$ BEGIN NEW.update_time = CURRENT_TIMESTAMP; RETURN NEW; END; $$ LANGUAGE plpgsql;
                CREATE TABLE file (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL);
                CREATE TABLE knowledge (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    UNIQUE (id, tenant_id)
                );

                %s
                %s
                %s

                INSERT INTO file (id, tenant_id) VALUES (20, 1);
                INSERT INTO knowledge (id, tenant_id) VALUES (10, 1);

                INSERT INTO document_chunk
                    (public_id, tenant_id, knowledge_id, file_id, position, content,
                     token_count, content_hash)
                VALUES ('10000000-0000-0000-0000-000000000001', 1, 10, 20, 0,
                        'historical body', 2, repeat('a', 64));

                %s
                %s

                SELECT chunk_type || '|' || sibling_position || '|'
                       || COALESCE(parent_chunk_id::text, 'NULL')
                FROM document_chunk WHERE id = 1;

                DO $$
                DECLARE parent_id BIGINT;
                BEGIN
                    BEGIN
                        INSERT INTO document_chunk
                            (tenant_id, knowledge_id, file_id, position, content, token_count,
                             content_hash, chunk_type, sibling_position)
                        VALUES (1, 10, 20, 1, 'orphan child', 2, repeat('b', 64), 2, 0);
                        RAISE EXCEPTION 'child without parent was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    INSERT INTO document_chunk
                        (tenant_id, knowledge_id, file_id, position, content, token_count,
                         content_hash, chunk_type, sibling_position)
                    VALUES (1, 10, 20, 1, 'parent', 1, repeat('c', 64), 1, 0)
                    RETURNING id INTO parent_id;

                    BEGIN
                        INSERT INTO document_chunk
                            (tenant_id, knowledge_id, file_id, position, content, token_count,
                             content_hash, chunk_type, parent_chunk_id, sibling_position)
                        VALUES (1, 10, 20, 2, 'single with parent', 3, repeat('d', 64),
                                0, parent_id, 0);
                        RAISE EXCEPTION 'single with parent was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    INSERT INTO document_chunk
                        (tenant_id, knowledge_id, file_id, position, content, token_count,
                         content_hash, chunk_type, parent_chunk_id, sibling_position)
                    VALUES (1, 10, 20, 2, 'child', 1, repeat('e', 64), 2, parent_id, 0);

                    UPDATE document_chunk
                    SET status = 1, indexing_lock_version = 7
                    WHERE id = parent_id;
                    UPDATE document_chunk
                    SET status = 2, indexing_lock_version = NULL
                    WHERE id = parent_id;

                    BEGIN
                        UPDATE document_chunk SET status = 2 WHERE content = 'child';
                        RAISE EXCEPTION 'active child without vector was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO document_chunk
                            (tenant_id, knowledge_id, file_id, position, content, token_count,
                             content_hash, chunk_type, parent_chunk_id, sibling_position)
                        VALUES (1, 10, 20, 3, 'duplicate child', 2, repeat('f', 64),
                                2, parent_id, 0);
                        RAISE EXCEPTION 'duplicate sibling position was accepted';
                    EXCEPTION WHEN unique_violation THEN NULL;
                    END;

                    DELETE FROM document_chunk WHERE id = parent_id;
                    IF EXISTS (SELECT 1 FROM document_chunk WHERE content = 'child') THEN
                        RAISE EXCEPTION 'parent deletion did not cascade to child';
                    END IF;
                END;
                $$;
                ROLLBACK;
                """.formatted(schema, schema, migration("V7__add_document_chunking_pipeline.sql"),
                migration("V8__add_per_chunk_overlap_settings.sql"),
                migration("V18__add_vector_generations.sql"),
                migration("V20__add_parent_child_chunks.sql"),
                migration("V22__allow_parent_chunks_without_vectors.sql"));

        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(List.of("0|0|NULL"), output.lines().toList());
    }

    private boolean postgresIsAvailable() {
        try {
            Process process = new ProcessBuilder("psql", "-X", "-q", "-At", "-d", "postgres",
                    "-c", "SELECT 1").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String migration(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/" + name)) {
            if (stream == null) {
                throw new IOException(name + " migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
