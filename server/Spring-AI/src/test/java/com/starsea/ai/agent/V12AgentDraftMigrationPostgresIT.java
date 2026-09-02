package com.starsea.ai.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V12AgentDraftMigrationPostgresIT {

    @Test
    void enforces_exclusive_models_tenant_references_json_and_draft_ranges() throws Exception {
        String schema = "starseaknow_v12_it_" + UUID.randomUUID().toString().replace("-", "");
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
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
                    UNIQUE (id, tenant_id)
                );
                CREATE TABLE tenant_model_provider (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
                    selectable_models JSONB NOT NULL,
                    UNIQUE (id, tenant_id)
                );
                CREATE TABLE agent (
                    id BIGSERIAL PRIMARY KEY,
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
                    name VARCHAR(32) NOT NULL,
                    description VARCHAR(512),
                    prologue VARCHAR(512),
                    role_description VARCHAR(512),
                    model_url VARCHAR(512),
                    model_api_key VARCHAR(1024),
                    model_id VARCHAR(128),
                    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                CREATE TABLE knowledge (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
                    UNIQUE (id, tenant_id)
                );
                CREATE TABLE agent_knowledge (
                    agent_id BIGINT NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
                    knowledge_id BIGINT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE,
                    tenant_id BIGINT NOT NULL,
                    create_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (agent_id, knowledge_id)
                );

                INSERT INTO tenant VALUES (1), (2);
                INSERT INTO app_user VALUES (10, 1), (20, 2);
                INSERT INTO tenant_model_provider VALUES
                    (100, 1, '[{"modelId":"gpt-4o-mini","displayName":"GPT-4o Mini","contextWindow":128000}]'),
                    (200, 2, '[{"modelId":"other","displayName":"Other","contextWindow":8192}]');
                INSERT INTO knowledge VALUES (501, 1), (502, 2);
                INSERT INTO agent (id, tenant_id, name, role_description) VALUES
                    (301, 1, 'Agent A', 'legacy prompt'),
                    (302, 1, 'Agent B', NULL),
                    (401, 2, 'Agent C', NULL);

                %s

                INSERT INTO agent_model
                    (id, tenant_id, tenant_model_provider_id, model_id, temperature,
                     top_p, max_tokens, timeout_seconds)
                VALUES (601, 1, 100, 'gpt-4o-mini', 0.4, 0.9, 2048, 30);
                UPDATE agent SET agent_model_id = 601 WHERE id = 301;
                INSERT INTO agent_knowledge (agent_id, knowledge_id, tenant_id) VALUES (301, 501, 1);

                DO $$
                BEGIN
                    BEGIN
                        UPDATE agent SET agent_model_id = 601 WHERE id = 302;
                        RAISE EXCEPTION 'agent_model was shared by two agents';
                    EXCEPTION WHEN unique_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO agent_model
                            (tenant_id, tenant_model_provider_id, model_id, temperature,
                             top_p, max_tokens, timeout_seconds)
                        VALUES (1, 200, 'other', 0.4, 0.9, 2048, 30);
                        RAISE EXCEPTION 'cross-tenant provider was accepted';
                    EXCEPTION WHEN foreign_key_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO agent_model
                            (tenant_id, tenant_model_provider_id, model_id, temperature,
                             top_p, max_tokens, timeout_seconds)
                        VALUES (1, 100, 'gpt-4o-mini', 2.1, 0.9, 2048, 30);
                        RAISE EXCEPTION 'invalid model parameters were accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO agent_knowledge (agent_id, knowledge_id, tenant_id)
                        VALUES (301, 502, 1);
                        RAISE EXCEPTION 'cross-tenant knowledge was accepted';
                    EXCEPTION WHEN foreign_key_violation THEN NULL;
                    END;

                    BEGIN
                        UPDATE agent SET tags = '{}'::jsonb WHERE id = 301;
                        RAISE EXCEPTION 'object tags were accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;

                    BEGIN
                        UPDATE agent SET retrieval_top_k = 21 WHERE id = 301;
                        RAISE EXCEPTION 'invalid top-k was accepted';
                    EXCEPTION WHEN check_violation THEN NULL;
                    END;
                END;
                $$;

                SELECT system_prompt FROM agent WHERE id = 301;
                SELECT draft_revision || ',' || published_revision || ',' || lock_version FROM agent WHERE id = 301;
                SELECT count(*) FROM agent_model WHERE id = 601;
                SELECT count(*) FROM agent_knowledge WHERE agent_id = 301 AND tenant_id = 1;
                ROLLBACK;
                """.formatted(schema, schema, readMigration("V12__add_agent_draft_and_model.sql"));

        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(List.of("legacy prompt", "1,0,0", "1", "1"), output.lines().toList());
    }

    private String readMigration(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/" + name)) {
            if (stream == null) throw new IOException(name + " migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
