package com.starsea.ai.agent.snapshot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class V13AgentSnapshotMigrationPostgresIT {

    @Test
    void creates_tenant_scoped_immutable_versioned_snapshots() throws Exception {
        String schema = "starseaknow_v13_it_" + UUID.randomUUID().toString().replace("-", "");
        String sql = """
                BEGIN;
                CREATE SCHEMA %s;
                SET LOCAL search_path TO %s;

                CREATE TABLE tenant (id BIGINT PRIMARY KEY);
                CREATE TABLE app_user (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
                    UNIQUE (id, tenant_id)
                );
                CREATE TABLE agent (
                    id BIGINT PRIMARY KEY,
                    tenant_id BIGINT NOT NULL REFERENCES tenant(id),
                    current_snapshot_id BIGINT,
                    UNIQUE (id, tenant_id)
                );
                INSERT INTO tenant VALUES (1), (2);
                INSERT INTO app_user VALUES (10, 1), (20, 2);
                INSERT INTO agent VALUES (301, 1, NULL), (401, 2, NULL);

                %s

                INSERT INTO agent_snapshot
                    (id, tenant_id, agent_id, version_number, publish_note, snapshot_data,
                     source_revision, created_by)
                VALUES (701, 1, 301, 1, '首次发布',
                        '{"name":"Agent A","model":{"providerConnectionId":100,"modelId":"gpt-4o-mini"}}',
                        3, 10);
                UPDATE agent SET current_snapshot_id = 701 WHERE id = 301;

                DO $$
                BEGIN
                    BEGIN
                        INSERT INTO agent_snapshot
                            (tenant_id, agent_id, version_number, publish_note, snapshot_data,
                             source_revision, created_by)
                        VALUES (1, 301, 1, '重复版本', '{}', 3, 10);
                        RAISE EXCEPTION 'duplicate version was accepted';
                    EXCEPTION WHEN unique_violation THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO agent_snapshot
                            (tenant_id, agent_id, version_number, publish_note, snapshot_data,
                             source_revision, created_by)
                        VALUES (1, 401, 2, '跨租户', '{}', 3, 10);
                        RAISE EXCEPTION 'cross-tenant agent was accepted';
                    EXCEPTION WHEN foreign_key_violation THEN NULL;
                    END;

                    BEGIN
                        UPDATE agent_snapshot SET snapshot_data = '{"tampered":true}' WHERE id = 701;
                        RAISE EXCEPTION 'immutable payload was updated';
                    EXCEPTION WHEN raise_exception THEN NULL;
                    END;

                    BEGIN
                        INSERT INTO agent_snapshot
                            (tenant_id, agent_id, version_number, publish_note, snapshot_data,
                             source_revision, rollback_from_snapshot_id, created_by)
                        VALUES (2, 401, 1, '错误回滚', '{}', 1, 701, 20);
                        RAISE EXCEPTION 'cross-tenant rollback source was accepted';
                    EXCEPTION WHEN foreign_key_violation THEN NULL;
                    END;
                END;
                $$;

                UPDATE agent_snapshot
                SET deleted_at = CURRENT_TIMESTAMP, deleted_by = 10
                WHERE id = 701;

                SELECT count(*) FROM agent_snapshot WHERE agent_id = 301;
                SELECT current_snapshot_id FROM agent WHERE id = 301;
                SELECT (deleted_at IS NOT NULL AND deleted_by = 10)::text FROM agent_snapshot WHERE id = 701;
                ROLLBACK;
                """.formatted(schema, schema, readMigration("V13__add_agent_snapshot.sql"));

        Process process = new ProcessBuilder(
                "psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", "postgres")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
        assertEquals(List.of("1", "701", "true"), output.lines().toList());
    }

    private String readMigration(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/" + name)) {
            if (stream == null) throw new IOException(name + " migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
