package com.starsea.ai.chunking.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V16GeneralChunkingMigrationPostgresIT {

    @Test
    void migrates_overlap_data_and_enforces_unit_specific_limits() throws Exception {
        String schema = "starseaknow_v16_it_" + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;

                CREATE TABLE file_processing (file_id BIGINT PRIMARY KEY);
                CREATE TABLE document_chunk (
                    case_name TEXT PRIMARY KEY,
                    overlap_enabled BOOLEAN NOT NULL,
                    overlap_token_limit INTEGER NOT NULL,
                    overlap_content TEXT,
                    overlap_token_count INTEGER NOT NULL DEFAULT 0,
                    CONSTRAINT chk_document_chunk_overlap_token_limit
                        CHECK (overlap_token_limit BETWEEN 1 AND 512)
                );
                INSERT INTO file_processing VALUES (1);
                INSERT INTO document_chunk VALUES
                    ('ascii', true, 40, 'old text', 2),
                    ('unicode', false, 512, 'A😀中', 3);

                %s

                DO $$
                BEGIN
                    BEGIN
                        INSERT INTO document_chunk
                            (case_name, overlap_enabled, overlap_limit, overlap_unit)
                        VALUES ('bad-token', false, 513, 'TOKENS');
                        RAISE EXCEPTION 'token limit over 512 was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;
                    BEGIN
                        INSERT INTO document_chunk
                            (case_name, overlap_enabled, overlap_limit, overlap_unit)
                        VALUES ('valid-character', false, 1000, 'CHARACTERS');
                    END;
                    BEGIN
                        INSERT INTO document_chunk
                            (case_name, overlap_enabled, overlap_limit, overlap_unit)
                        VALUES ('bad-character', false, 1001, 'CHARACTERS');
                        RAISE EXCEPTION 'character limit over 1000 was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;
                    BEGIN
                        INSERT INTO document_chunk
                            (case_name, overlap_enabled, overlap_limit, overlap_unit)
                        VALUES ('bad-enabled-zero', true, 0, 'CHARACTERS');
                        RAISE EXCEPTION 'enabled zero limit was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;
                END;
                $$;

                SELECT case_name || '|' || overlap_limit || '|' || overlap_unit || '|'
                       || overlap_token_count || '|' || overlap_character_count
                FROM document_chunk
                WHERE case_name IN ('ascii', 'unicode')
                ORDER BY case_name;
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'file_processing'
                  AND column_name IN ('execution_metadata', 'preview_summary')
                ORDER BY column_name;
                ROLLBACK;
                """.formatted(schema, schema, readMigration());

        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(List.of(
                "ascii|40|TOKENS|2|8",
                "unicode|512|TOKENS|3|3",
                "jsonb",
                "jsonb"), output.lines().toList());
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V16__add_general_chunking_runtime.sql")) {
            if (stream == null) throw new IOException("V16 migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
