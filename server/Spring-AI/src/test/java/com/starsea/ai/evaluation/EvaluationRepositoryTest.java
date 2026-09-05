package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationRepositoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private EvaluationRepository repository;
    private String schema;

    @BeforeEach void setup() throws Exception {
        schema = "eval_repo_" + UUID.randomUUID().toString().replace("-", "");
        var ds = new DriverManagerDataSource("jdbc:postgresql://localhost:5432/postgres",
                System.getProperty("user.name"), "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA " + schema);
        ds.setUrl("jdbc:postgresql://localhost:5432/postgres?currentSchema=" + schema + ",public");
        jdbc.execute("CREATE TABLE tenant(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE knowledge(id BIGINT, tenant_id BIGINT, PRIMARY KEY(id,tenant_id))");
        jdbc.execute("INSERT INTO tenant VALUES(1),(2)");
        jdbc.execute("INSERT INTO knowledge VALUES(10,1),(20,2)");
        try (var sql = new ClassPathResource("db/V21__add_embedding_evaluation.sql").getInputStream()) {
            jdbc.execute(new String(sql.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        repository = new EvaluationRepository(jdbc, json, new DataSourceTransactionManager(ds));
    }

    @AfterEach void cleanup() {
        if (jdbc != null && schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void isolatesTenantsAndKnowledgeScopes() {
        String id = UUID.randomUUID().toString();
        repository.save("dataset", id, 1, 10L, 0, json.createObjectNode().put("name", "评测集"));
        assertEquals("评测集", repository.get("dataset", id, 1, 10L, null).path("name").asText());
        assertThrows(ResponseStatusException.class, () -> repository.get("dataset", id, 2, 20L, null));
        assertThrows(ResponseStatusException.class, () -> repository.get("dataset", id, 1, 20L, null));
        assertTrue(repository.list("dataset", 2, 20L).isEmpty());
    }

    @Test void keepsImmutableVersionsAndRejectsStaleWrites() {
        String id = UUID.randomUUID().toString();
        repository.save("model", id, 1, null, 0, json.createObjectNode().put("modelName", "a"));
        repository.save("model", id, 1, null, 1, json.createObjectNode().put("modelName", "b"));
        assertEquals("a", repository.get("model", id, 1, null, 1).path("modelName").asText());
        assertEquals("b", repository.get("model", id, 1, null, null).path("modelName").asText());
        var conflict = assertThrows(ResponseStatusException.class,
                () -> repository.save("model", id, 1, null, 1, json.createObjectNode()));
        assertEquals(409, conflict.getStatusCode().value());
        assertThrows(Exception.class, () -> jdbc.update("UPDATE embedding_eval_entity SET payload='{}' WHERE public_id=?::uuid", id));
        repository.delete("model", id, 1, null, 2);
        assertTrue(repository.list("model", 1, null).isEmpty());
        assertThrows(ResponseStatusException.class, () -> repository.get("model", id, 1, null, null));
        assertEquals("b", repository.get("model", id, 1, null, 2).path("modelName").asText());
    }

    @Test void runProgressUsesOptimisticConcurrencyAndPreservesOtherTenant() {
        ObjectNode run = repository.createRun(1, 10, json.createObjectNode().put("status", "QUEUED"));
        String id = run.path("id").asText();
        ObjectNode updated = repository.updateRun(1, 10, id, 1, run.deepCopy().put("status", "CANCELLED"));
        assertEquals(2, updated.path("revision").asInt());
        assertThrows(ResponseStatusException.class, () -> repository.updateRun(1, 10, id, 1, run));
        assertThrows(ResponseStatusException.class, () -> repository.getRun(2, 20, id));
        assertEquals("CANCELLED", repository.getRun(1, 10, id).path("status").asText());
    }
}
