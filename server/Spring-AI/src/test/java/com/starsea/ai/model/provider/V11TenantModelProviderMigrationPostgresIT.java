package com.starsea.ai.model.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V11TenantModelProviderMigrationPostgresIT {

    @Test
    void creates_tenant_isolated_connections_with_secret_and_model_constraints() throws Exception {
        String schema = "starseaknow_v11_it_" + UUID.randomUUID().toString().replace("-", "");
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

                CREATE TABLE tenant (id BIGINT PRIMARY KEY);
                CREATE TABLE app_user (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id)
                );
                INSERT INTO tenant VALUES (1), (2);
                INSERT INTO app_user VALUES (10, 1), (20, 2);

                %s
                %s

                INSERT INTO tenant_model_provider
                    (id, tenant_id, catalog_provider_id, base_url, protocol_type, auth_type,
                     selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                     api_key_last_four, last_verified_at, created_by)
                SELECT 100, 1, id, default_base_url, protocol_type, auth_type,
                       suggested_models, 'cipher', 'nonce', 'v1', '1234', CURRENT_TIMESTAMP, 10
                FROM model_provider_catalog WHERE code = 'OPENAI';

                INSERT INTO tenant_model_provider
                    (id, tenant_id, catalog_provider_id, base_url, protocol_type, auth_type,
                     selectable_models, last_verified_at, created_by)
                SELECT 101, 1, id, default_base_url, protocol_type, auth_type,
                       suggested_models, CURRENT_TIMESTAMP, 10
                FROM model_provider_catalog WHERE code = 'OLLAMA';

                INSERT INTO tenant_model_provider
                    (id, tenant_id, custom_name, custom_icon, base_url, protocol_type, auth_type,
                     selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                     api_key_last_four, last_verified_at, created_by)
                VALUES
                    (102, 1, 'Acme AI', 'provider/custom', 'https://models.acme.test/v1',
                     'OPENAI_COMPATIBLE', 'API_KEY',
                     '[{"modelId":"acme-chat","displayName":"Acme Chat","contextWindow":8192}]',
                     'cipher', 'nonce', 'v1', '5678', CURRENT_TIMESTAMP, 10);

                DO $$
                BEGIN
                    BEGIN
                        INSERT INTO tenant_model_provider
                            (tenant_id, catalog_provider_id, base_url, protocol_type, auth_type,
                             selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                             api_key_last_four, last_verified_at, created_by)
                        SELECT 1, id, default_base_url, protocol_type, auth_type,
                               '[]', 'c', 'n', 'v1', '1111', CURRENT_TIMESTAMP, 10
                        FROM model_provider_catalog WHERE code = 'OPENAI';
                        RAISE EXCEPTION 'duplicate built-in provider was accepted';
                    EXCEPTION WHEN unique_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO tenant_model_provider
                            (tenant_id, custom_name, custom_icon, base_url, protocol_type, auth_type,
                             selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                             api_key_last_four, last_verified_at, created_by)
                        VALUES (1, 'acme ai', 'provider/custom', 'https://other.test/v1',
                                'OPENAI_COMPATIBLE', 'API_KEY', '[]',
                                'c', 'n', 'v1', '2222', CURRENT_TIMESTAMP, 10);
                        RAISE EXCEPTION 'case-insensitive custom name uniqueness was not enforced';
                    EXCEPTION WHEN unique_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO tenant_model_provider
                            (tenant_id, custom_name, custom_icon, base_url, protocol_type, auth_type,
                             selectable_models, last_verified_at, created_by)
                        VALUES (1, 'Missing Key', 'provider/custom', 'https://missing.test/v1',
                                'OPENAI_COMPATIBLE', 'API_KEY', '[]', CURRENT_TIMESTAMP, 10);
                        RAISE EXCEPTION 'API key fields were not required';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO tenant_model_provider
                            (tenant_id, catalog_provider_id, base_url, protocol_type, auth_type,
                             selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                             api_key_last_four, last_verified_at, created_by)
                        SELECT 1, id, default_base_url, protocol_type, 'NONE', '[]',
                               'c', 'n', 'v1', '3333', CURRENT_TIMESTAMP, 10
                        FROM model_provider_catalog WHERE code = 'OLLAMA';
                        RAISE EXCEPTION 'NONE authentication accepted secret fields';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO tenant_model_provider
                            (tenant_id, custom_name, custom_icon, base_url, protocol_type, auth_type,
                             selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                             api_key_last_four, last_verified_at, created_by)
                        VALUES (1, 'Bad Models', 'provider/custom', 'https://bad.test/v1',
                                'OPENAI_COMPATIBLE', 'API_KEY', '{}',
                                'c', 'n', 'v1', '4444', CURRENT_TIMESTAMP, 10);
                        RAISE EXCEPTION 'malformed selectable models were accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO tenant_model_provider
                            (tenant_id, custom_name, custom_icon, base_url, protocol_type, auth_type,
                             selectable_models, api_key_ciphertext, api_key_nonce, api_key_version,
                             api_key_last_four, last_verified_at, created_by)
                        VALUES (1, 'Wrong Creator', 'provider/custom', 'https://wrong.test/v1',
                                'OPENAI_COMPATIBLE', 'API_KEY', '[]',
                                'c', 'n', 'v1', '5555', CURRENT_TIMESTAMP, 20);
                        RAISE EXCEPTION 'cross-tenant creator was accepted';
                    EXCEPTION WHEN foreign_key_violation THEN NULL;
                    END;
                END;
                $$;

                SELECT count(*) FROM tenant_model_provider;
                SELECT string_agg(auth_type, ',' ORDER BY id) FROM tenant_model_provider;
                SELECT count(*) FROM tenant_model_provider WHERE tenant_id = 1;
                ROLLBACK;
                """.formatted(schema, schema, readMigration("V10__add_model_provider_catalog.sql"),
                readMigration("V11__add_tenant_model_provider.sql"));

        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(List.of("3", "API_KEY,NONE,API_KEY", "3"), output.lines().toList());
    }

    private String readMigration(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/" + name)) {
            if (stream == null) {
                throw new IOException(name + " migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
