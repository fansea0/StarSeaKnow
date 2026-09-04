package com.starsea.ai.chunking.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V17FileTextExtractionMigrationPostgresIT {

    @Test
    void creates_tenant_scoped_cache_with_composite_file_cascade() throws Exception {
        String schema = "starseaknow_v17_it_" + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;
                CREATE TABLE file (id BIGINT, tenant_id BIGINT NOT NULL,
                    CONSTRAINT file_pk PRIMARY KEY(id), CONSTRAINT uk_file_id_tenant UNIQUE(id, tenant_id));
                CREATE FUNCTION update_timestamp() RETURNS TRIGGER AS $$
                BEGIN NEW.update_time = CURRENT_TIMESTAMP; RETURN NEW; END;
                $$ LANGUAGE plpgsql;
                INSERT INTO file VALUES (9, 1), (10, 2);
                %s
                INSERT INTO file_text_extraction
                    (tenant_id, file_id, source_hash, extractor_id, extractor_version,
                     media_type, managed_text_path, source_map_path, character_count)
                VALUES (1, 9, repeat('a', 64), 'plain-text', '1', 'text/plain',
                        '/cache/1/9/text.txt', '/cache/1/9/source-map.json', 12);
                DO $$
                BEGIN
                    BEGIN
                        INSERT INTO file_text_extraction
                            (tenant_id, file_id, source_hash, extractor_id, extractor_version,
                             media_type, managed_text_path, source_map_path, character_count)
                        VALUES (1, 10, repeat('b', 64), 'plain-text', '1', 'text/plain',
                                '/cache/1/10/text.txt', '/cache/1/10/source-map.json', 12);
                        RAISE EXCEPTION 'cross-tenant file reference accepted';
                    EXCEPTION WHEN foreign_key_violation THEN NULL;
                    END;
                END $$;
                DELETE FROM file WHERE id = 9 AND tenant_id = 1;
                SELECT count(*) FROM file_text_extraction;
                SELECT column_name FROM information_schema.columns
                  WHERE table_schema = current_schema() AND table_name = 'file_text_extraction'
                  ORDER BY ordinal_position;
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
        assertEquals(List.of("0", "tenant_id", "file_id", "source_hash", "extractor_id",
                "extractor_version", "media_type", "managed_text_path", "source_map_path",
                "character_count", "create_time", "update_time"), output.lines().toList());
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V17__add_file_text_extraction_cache.sql")) {
            if (stream == null) throw new IOException("V17 migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
