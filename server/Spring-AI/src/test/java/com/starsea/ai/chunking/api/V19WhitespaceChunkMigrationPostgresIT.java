package com.starsea.ai.chunking.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V19WhitespaceChunkMigrationPostgresIT {

    @Test
    void permits_retained_whitespace_but_still_rejects_empty_chunk_content() throws Exception {
        String schema = "starseaknow_v19_it_" + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;
                CREATE TABLE document_chunk (
                    id BIGINT PRIMARY KEY,
                    content TEXT NOT NULL CHECK (length(btrim(content)) > 0)
                );
                %s
                INSERT INTO document_chunk VALUES (1, '   '), (2, U&'\\00A0\\2007\\202F');
                DO $$
                BEGIN
                    BEGIN
                        INSERT INTO document_chunk VALUES (3, '');
                        RAISE EXCEPTION 'empty content was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;
                END;
                $$;
                SELECT count(*) FROM document_chunk;
                ROLLBACK;
                """.formatted(schema, schema, readMigration());

        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true).start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals("2", output);
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V19__allow_retained_whitespace_chunks.sql")) {
            if (stream == null) throw new IOException("V19 migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
