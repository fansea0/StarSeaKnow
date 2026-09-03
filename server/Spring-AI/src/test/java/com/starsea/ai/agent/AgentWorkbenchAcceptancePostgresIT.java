package com.starsea.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.agent.debug.DebugExecutionCoordinator;
import com.starsea.ai.agent.execution.*;
import com.starsea.ai.agent.snapshot.AgentSnapshotService;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.model.provider.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/** Explicit local acceptance suite: owns a fresh PostgreSQL database and never touches the application database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "logging.config=classpath:logback-acceptance.xml")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentWorkbenchAcceptancePostgresIT {
    private static final String DATABASE = "starsea_agent_it_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        run("createdb", "-O", "postgres", DATABASE);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { run("dropdb", "--if-exists", DATABASE); } catch (Exception ignored) { }
        }));
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/" + DATABASE);
        registry.add("logging.file.path", () -> System.getProperty("java.io.tmpdir") + "/" + DATABASE);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource datasource;
    @Autowired AgentAggregateService agents;
    @Autowired ModelProviderService providers;
    @Autowired DebugExecutionCoordinator debug;
    @Autowired AgentSnapshotService snapshots;
    @Autowired SnapshotExecutionSourceLoader published;
    @Autowired AgentExecutionService execution;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private HttpServer server;
    private final List<String> modelRequests = new CopyOnWriteArrayList<>();
    private long adminId;

    @BeforeAll void setup() throws Exception {
        adminId = jdbc.queryForObject("INSERT INTO app_user(tenant_id,username,password_hash,role) VALUES (1,'acceptance-admin','not-a-login-hash','tenant_admin') RETURNING id", Long.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = "{\"data\":[{\"id\":\"acceptance-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/v1/chat/completions", exchange -> {
            modelRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"回答\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }

    @Test void fresh_migration_and_provider_draft_debug_publish_member_rollback_delete_flow() throws Exception {
        admin();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isGreaterThanOrEqualTo(13);
        String apiKey = "acceptance-only-not-a-real-key";
        var provider = providers.createConnection(new ModelProviderApiModels.ConnectionCommand(null, "验收厂商", "验",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey,
                List.of(new ModelSuggestion("acceptance-model", "验收模型", 128000))));
        assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM tenant_model_provider WHERE id=?", String.class, provider.connectionId())).isNotEqualTo(apiKey);
        var model = new AgentWorkbenchApiModels.AgentModelCommand(provider.connectionId(), "acceptance-model", new java.math.BigDecimal("0.4"), java.math.BigDecimal.ONE, 256, 15);
        var original = new AgentWorkbenchApiModels.DraftCommand("验收 Agent", "描述", "欢迎", "你是 {{company}} 的助手", List.of("验收"),
                List.of(new AgentWorkbenchApiModels.VariableDefinition("company", "公司", "星海", true)), List.of(), 5, new java.math.BigDecimal("0.7"), model, null);
        var draft = agents.create(original);
        assertThat(agents.get(draft.id()).variables()).hasSize(1);
        var cleared = agents.updateDraft(draft.id(), command(original, null, null, draft.lockVersion()));
        assertThat(jdbc.queryForObject("SELECT agent_model_id FROM agent WHERE id=?", Long.class, draft.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT description FROM agent WHERE id=?", String.class, draft.id())).isNull();
        draft = agents.updateDraft(draft.id(), command(original, model, "恢复", cleared.lockVersion()));
        var first = debug.stream(draft.id(), new DebugExecutionCoordinator.DebugCommand(null, "第一问", Map.of())).collectList().block(Duration.ofSeconds(20));
        assertThat(first).extracting(ExecutionEvent::type).contains("context", "delta", "complete").doesNotContain("error");
        UUID context = ((DebugExecutionCoordinator.ContextEvent) first.get(0).data()).debugContextId();
        var second = debug.stream(draft.id(), new DebugExecutionCoordinator.DebugCommand(context, "第二问", Map.of())).collectList().block(Duration.ofSeconds(20));
        assertThat(second).extracting(ExecutionEvent::type).contains("complete");
        assertThat(new ObjectMapper().readTree(modelRequests.get(1)).get("messages").size()).isEqualTo(4);
        var exported = debug.export(draft.id(), context);
        assertThat(exported.turns()).hasSize(2);
        assertThat(exported.model()).hasSize(1);
        assertThat(exported.turns().get(0).question()).isEqualTo("第一问");
        assertThat(exported.turns().get(1).modelConfigId()).isEqualTo(exported.model().get(0).configId());
        var version1 = snapshots.publish(draft.id(), new AgentSnapshotService.PublishCommand("首次发布", draft.lockVersion()));
        var changed = agents.updateDraft(draft.id(), command(original, model, "只改草稿", agents.get(draft.id()).lockVersion()));
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, adminId, 1L, "tenant_member", "acceptance"));
        assertThat(agents.get(draft.id()).description()).isEqualTo("恢复");
        assertThat(execution.execute(published.load(draft.id()), new ExecutionRequest("正式调用", Map.of(), List.of())).collectList().block(Duration.ofSeconds(20)))
                .extracting(ExecutionEvent::type).contains("complete").doesNotContain("error");
        admin();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> publish = () -> {
                admin();
                try {
                    start.await();
                    snapshots.publish(changed.id(), new AgentSnapshotService.PublishCommand("并发发布", changed.lockVersion()));
                    return "published";
                } catch (AgentWorkbenchException conflict) { return conflict.code(); }
                finally { AuthContext.clear(); }
            };
            var a = pool.submit(publish); var b = pool.submit(publish); start.countDown();
            assertThat(List.of(a.get(10, java.util.concurrent.TimeUnit.SECONDS), b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("published", "AGENT_DRAFT_CONFLICT");
        } finally { pool.shutdownNow(); }
        var rolled = snapshots.rollback(draft.id(), 1, new AgentSnapshotService.RollbackCommand("恢复首版", agents.get(draft.id()).lockVersion()));
        assertThat(rolled.getVersionNumber()).isEqualTo(3L);
        assertThat(rolled.getRollbackFromSnapshotId()).isEqualTo(version1.getId());
        agents.delete(draft.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_snapshot WHERE agent_id=? AND deleted_at IS NULL", Integer.class, draft.id())).isZero();
        assertThatThrownBy(() -> debug.stream(changed.id(), new DebugExecutionCoordinator.DebugCommand(context, "删除后", Map.of())))
                .isInstanceOf(AgentWorkbenchException.class);
        providers.deleteConnection(provider.connectionId());
        assertThat(providers.listProviders()).noneMatch(p -> provider.connectionId().equals(p.connectionId()));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM tenant_model_provider WHERE id=?", Boolean.class, provider.connectionId())).isTrue();
        var replacement = providers.createConnection(new ModelProviderApiModels.ConnectionCommand(null, "验收厂商", "验",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", apiKey,
                List.of(new ModelSuggestion("acceptance-model", "验收模型", 128000))));
        assertThat(replacement.connectionId()).isNotEqualTo(provider.connectionId());
        var replacementModel = new AgentWorkbenchApiModels.AgentModelCommand(replacement.connectionId(), "acceptance-model", new java.math.BigDecimal("0.4"), java.math.BigDecimal.ONE, 256, 15);
        assertConcurrentDeleteRejectsNewReference(replacement.connectionId(), command(original, replacementModel, "并发创建", 0));
    }

    private void assertConcurrentDeleteRejectsNewReference(long providerId, AgentWorkbenchApiModels.DraftCommand command) throws Exception {
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var deletion = datasource.getConnection()) {
            deletion.setAutoCommit(false);
            try (var statement = deletion.prepareStatement("UPDATE tenant_model_provider SET deleted_at=CURRENT_TIMESTAMP WHERE id=?")) {
                statement.setLong(1, providerId); statement.executeUpdate();
            }
            var entered = new java.util.concurrent.CountDownLatch(1);
            var future = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx -> {
                admin();
                try {
                    jdbc.execute("SET LOCAL application_name = 'starsea-agent-race'");
                    entered.countDown();
                    return agents.create(command);
                } finally { AuthContext.clear(); }
            }));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            boolean blocked = false;
            for (int attempt = 0; attempt < 500 && !future.isDone(); attempt++) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND application_name='starsea-agent-race' AND wait_event_type='Lock')", Boolean.class));
                if (blocked) break;
                Thread.sleep(10);
            }
            deletion.commit();
            assertThatThrownBy(() -> future.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class).hasCauseInstanceOf(AgentWorkbenchException.class);
            assertThat(blocked).as("reference creation must wait for the deleting provider transaction").isTrue();
        } finally { pool.shutdownNow(); }
    }
    private AgentWorkbenchApiModels.DraftCommand command(AgentWorkbenchApiModels.DraftCommand original, AgentWorkbenchApiModels.AgentModelCommand model, String description, long lock) {
        return new AgentWorkbenchApiModels.DraftCommand(original.name(), description, original.prologue(), original.systemPrompt(), original.tags(), original.variables(), original.knowledgeIds(), original.retrievalTopK(), original.retrievalScoreThreshold(), model, lock);
    }
    private void admin() { AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, adminId, 1L, "tenant_admin", "acceptance")); }
    @AfterAll void cleanup() throws Exception {
        AuthContext.clear();
        if (server != null) server.stop(0);
        if (datasource instanceof com.zaxxer.hikari.HikariDataSource hikari) hikari.close();
        run("dropdb", DATABASE);
    }
    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }
}
