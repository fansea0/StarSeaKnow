package com.starsea.ai.model.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V10ModelProviderCatalogMigrationPostgresIT {

    @Test
    void creates_constrained_public_catalog_and_seeds_builtin_providers() throws Exception {
        String migration = readMigration();
        String schema = "starseaknow_v10_it_" + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;

                CREATE FUNCTION update_timestamp()
                RETURNS TRIGGER AS $$
                BEGIN
                    NEW.update_time = CURRENT_TIMESTAMP;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;

                %s

                SELECT string_agg(code, ',' ORDER BY code)
                FROM model_provider_catalog;
                SELECT count(*)
                FROM model_provider_catalog
                WHERE jsonb_typeof(suggested_models) = 'array';
                SELECT count(*) = count(DISTINCT item ->> 'modelId')
                FROM model_provider_catalog,
                     jsonb_array_elements(suggested_models) item
                WHERE code = 'OPENAI';
                SELECT auth_type
                FROM model_provider_catalog
                WHERE code = 'OLLAMA';

                DO $$
                BEGIN
                    BEGIN
                        INSERT INTO model_provider_catalog
                            (code, name, icon, default_base_url, protocol_type, auth_type, suggested_models)
                        VALUES ('BAD_PROTOCOL', 'Bad', 'bad', 'https://example.test', 'NATIVE', 'API_KEY', '[]');
                        RAISE EXCEPTION 'protocol constraint was not enforced';
                    EXCEPTION WHEN check_violation THEN
                        NULL;
                    END;
                    BEGIN
                        INSERT INTO model_provider_catalog
                            (code, name, icon, default_base_url, protocol_type, auth_type, suggested_models)
                        VALUES ('BAD_JSON', 'Bad', 'bad', 'https://example.test', 'OPENAI_COMPATIBLE', 'API_KEY', '{}');
                        RAISE EXCEPTION 'JSON array constraint was not enforced';
                    EXCEPTION WHEN check_violation THEN
                        NULL;
                    END;
                    BEGIN
                        INSERT INTO model_provider_catalog
                            (code, name, icon, default_base_url, protocol_type, auth_type, suggested_models)
                        VALUES ('BAD_MODEL', 'Bad', 'bad', 'https://example.test', 'OPENAI_COMPATIBLE', 'API_KEY',
                                '[{"modelId":"missing-fields"}]');
                        RAISE EXCEPTION 'model shape constraint was not enforced';
                    EXCEPTION WHEN check_violation THEN
                        NULL;
                    END;
                    BEGIN
                        INSERT INTO model_provider_catalog
                            (code, name, icon, default_base_url, protocol_type, auth_type, suggested_models)
                        VALUES ('DUPLICATE_MODEL', 'Bad', 'bad', 'https://example.test', 'OPENAI_COMPATIBLE', 'API_KEY',
                                '[{"modelId":"same","displayName":"One","contextWindow":1},
                                  {"modelId":"same","displayName":"Two","contextWindow":2}]');
                        RAISE EXCEPTION 'model ID uniqueness constraint was not enforced';
                    EXCEPTION WHEN check_violation THEN
                        NULL;
                    END;
                END;
                $$;

                SELECT count(*) FROM model_provider_catalog;
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
                "ANTHROPIC,DEEPSEEK,OLLAMA,OPENAI,QWEN,ZHIPU",
                "6",
                "t",
                "NONE",
                "6"), output.lines().toList());
    }

    private String readMigration() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V10__add_model_provider_catalog.sql")) {
            if (stream == null) {
                throw new IOException("V10 migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
