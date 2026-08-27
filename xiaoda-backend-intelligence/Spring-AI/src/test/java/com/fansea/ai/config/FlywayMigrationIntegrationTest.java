package com.fansea.ai.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    void freshSchemaRunsV1ThenV2ExactlyOnce() {
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

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE success", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema + ".platform_invitation", Integer.class))
                .isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
