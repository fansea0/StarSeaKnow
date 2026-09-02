package com.starsea.ai.chunking.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V8PerChunkOverlapMigrationPostgresIT {

    @Test
    void migrates_legacy_overlap_semantics_without_enabling_invalid_budgets() throws Exception {
        String migration = readMigration();
        String schema = "starseaknow_v8_it_"
                + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;

                CREATE TABLE file_processing (
                    file_id BIGINT NOT NULL,
                    tenant_id BIGINT NOT NULL,
                    knowledge_id BIGINT NOT NULL,
                    context_policy JSONB NOT NULL
                );
                CREATE TABLE document_chunk (
                    case_name TEXT NOT NULL,
                    file_id BIGINT NOT NULL,
                    tenant_id BIGINT NOT NULL,
                    knowledge_id BIGINT NOT NULL,
                    overlap_content TEXT,
                    overlap_token_count INTEGER NOT NULL DEFAULT 0
                );

                INSERT INTO file_processing VALUES
                    (1, 1, 1, '{"overlapEnabled":true,"overlapTokens":0}'),
                    (2, 1, 1, '{"overlapEnabled":true}'),
                    (3, 1, 1, '{"overlapEnabled":true,"overlapTokens":"invalid"}'),
                    (4, 1, 1, '{"overlapEnabled":true,"overlapTokens":0}'),
                    (5, 1, 1, '{"overlapEnabled":true,"overlapTokens":-1}'),
                    (6, 1, 1, '{"overlapEnabled":true,"overlapTokens":513}'),
                    (7, 1, 1, '{"overlapEnabled":true,"overlapTokens":64}'),
                    (8, 1, 1, '{"overlapEnabled":false,"overlapTokens":128}');
                INSERT INTO document_chunk VALUES
                    ('zero', 1, 1, 1, NULL, 0),
                    ('missing', 2, 1, 1, NULL, 0),
                    ('invalid', 3, 1, 1, NULL, 0),
                    ('existing', 4, 1, 1, 'legacy overlap', 7),
                    ('negative', 5, 1, 1, NULL, 0),
                    ('too_large', 6, 1, 1, NULL, 0),
                    ('valid', 7, 1, 1, NULL, 0),
                    ('disabled', 8, 1, 1, NULL, 0);

                %s

                SELECT case_name || '|' || overlap_enabled || '|' || overlap_token_limit
                FROM document_chunk
                ORDER BY case_name;
                ROLLBACK;
                """.formatted(schema, schema, migration);

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
                "disabled|false|128",
                "existing|true|40",
                "invalid|false|40",
                "missing|false|40",
                "negative|false|40",
                "too_large|false|40",
                "valid|true|64",
                "zero|false|40"), output.lines().toList());
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V8__add_per_chunk_overlap_settings.sql")) {
            if (stream == null) {
                throw new IOException("V8 migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
