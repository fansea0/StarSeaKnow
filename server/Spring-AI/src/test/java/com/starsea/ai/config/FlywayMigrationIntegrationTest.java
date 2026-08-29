package com.fansea.ai.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class FlywayMigrationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private String schema;

    @AfterEach
    void dropSchema() {
        if (schema != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void freshSchemaRunsAllMigrationsAndRestrictsKnowledgeCredentialsToRagRetrieval() {
        schema = "migration_it_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".platform_invitation", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'agent' AND column_name IN ('model_url', 'model_api_key', 'model_id')", Integer.class, schema))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".api_credential", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name='api_credential' "
                + "AND column_name IN ('credential_type','secret_digest','pepper_version','deleted_at')",
                Integer.class, schema)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=? AND table_name IN ('knowledge','file') "
                + "AND column_name='public_id'", Integer.class, schema)).isEqualTo(2);

        Long tenantId = jdbc.queryForObject("INSERT INTO " + schema
                + ".tenant (code, name) VALUES ('credential-test', 'Credential Test') RETURNING id", Long.class);
        Long userId = jdbc.queryForObject("INSERT INTO " + schema
                + ".app_user (tenant_id, username, password_hash, role) VALUES (?, 'credential-user', 'hash', 'ADMIN') RETURNING id",
                Long.class, tenantId);
        Long knowledgeId = jdbc.queryForObject("INSERT INTO " + schema
                + ".knowledge (tenant_id, name) VALUES (?, 'Knowledge') RETURNING id", Long.class, tenantId);
        Long credentialId = jdbc.queryForObject("INSERT INTO " + schema + ".api_credential "
                + "(tenant_id, credential_type, name, key_id, secret_digest, pepper_version, environment, status, "
                + "requests_per_minute, burst_capacity, max_concurrency, display_prefix, display_last_four, created_by) "
                + "VALUES (?, 'AGENT_INVOKE', 'Agent credential', 'agent-key', '0123456789012345678901234567890123456789012345678901234567890123', "
                + "'v1', 'test', 'active', 60, 60, 10, 'ag', '1234', ?) RETURNING id",
                Long.class, tenantId, userId);

        assertThatThrownBy(() -> jdbc.update("UPDATE " + schema
                + ".api_credential SET credential_type = 'RAG_RETRIEVAL' WHERE id = ?", credentialId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + schema
                + ".api_credential_knowledge (tenant_id, credential_id, knowledge_id) VALUES (?, ?, ?)",
                tenantId, credentialId, knowledgeId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
