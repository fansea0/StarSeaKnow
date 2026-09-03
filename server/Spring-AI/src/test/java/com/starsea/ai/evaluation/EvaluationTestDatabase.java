package com.starsea.ai.evaluation;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

/**
 * A disposable database, never the application's configured database. Requires local PostgreSQL,
 * CREATEDB and an available pgvector extension. Override evaluation.test.pg.{host,port,user,password}
 * if necessary. Missing PostgreSQL is a test failure, not an unnoticed skipped integration test.
 */
final class EvaluationTestDatabase implements AutoCloseable {
    private final String name = "eval_workflow_it_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DriverManagerDataSource dataSource;
    private boolean created;

    private EvaluationTestDatabase() {
        String host = System.getProperty("evaluation.test.pg.host", "localhost");
        String port = System.getProperty("evaluation.test.pg.port", "5432");
        String user = System.getProperty("evaluation.test.pg.user", System.getProperty("user.name"));
        String password = System.getProperty("evaluation.test.pg.password", "");
        String base = "jdbc:postgresql://" + host + ":" + port + "/";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + "postgres", user, password));
        dataSource = new DriverManagerDataSource(base + name, user, password);
    }

    static EvaluationTestDatabase create() {
        EvaluationTestDatabase database = new EvaluationTestDatabase();
        // Mark ownership only after CREATE succeeds. No existing database can become a cleanup target.
        database.admin.execute("CREATE DATABASE " + database.identifier() + " TEMPLATE template0 ENCODING 'UTF8'");
        database.created = true;
        try {
            Flyway.configure().dataSource(database.dataSource).locations("classpath:db")
                    .cleanDisabled(true).load().migrate();
            return database;
        } catch (RuntimeException | Error failure) {
            try {
                database.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    DataSourceTransactionManager transactions() {
        return new DataSourceTransactionManager(dataSource);
    }

    String name() {
        return name;
    }

    private String identifier() {
        if (!name.matches("eval_workflow_it_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing to manage an unowned test database");
        }
        return '"' + name + '"';
    }

    @Override
    public void close() {
        if (created) {
            // No wildcard, shared-schema cleanup, Flyway clean, or session termination.
            admin.execute("DROP DATABASE " + identifier());
            created = false;
            Integer remaining = admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname=?",
                    Integer.class, name);
            if (remaining == null || remaining != 0) {
                throw new IllegalStateException("Disposable evaluation database was not removed: " + name);
            }
        }
    }
}
