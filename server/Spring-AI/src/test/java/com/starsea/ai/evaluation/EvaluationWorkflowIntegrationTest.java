package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.config.ChunkingConfiguration;
import com.starsea.ai.chunking.context.DefaultChunkContextEnricher;
import com.starsea.ai.chunking.spi.TokenCounter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real controller, corpus, repository, executor/worker, metrics, policy, reports and
 * PostgreSQL/pgvector code. Only the Ollama network gateway is mocked. Standalone MVC receives
 * the AuthContext normally installed by the login interceptor; the worker still reauthorizes
 * against real app_user/tenant rows. No application bootstrap or live Ollama is involved.
 *
 * Run: mvn -Dtest=EvaluationWorkflowIntegrationTest test
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationWorkflowIntegrationTest {
    private static final String CAL_ORIGINAL = "How many years does the direct-sale warranty cover?";
    private static final String CAL_VARIANT = "When does the warranty for a direct purchase expire?";
    private static final String CAL_MISSING = "Does the warranty cover a house on Mars?";
    private static final String ACCEPT_ORIGINAL = "How long do direct buyers have to request a refund?";
    private static final String ACCEPT_VARIANT = "What is the return deadline for a direct purchase?";
    private static final String ACCEPT_MISSING = "=2+2 Where is the lunar refund office?";
    private static final String ANSWER = "Direct purchases: refunds within 30 days; warranty lasts two years.";
    private static final String HARD_NEGATIVE = "Reseller purchases: refunds within 3 days; warranty lasts six months.";
    private static final String OPPOSITE = "<script>alert(1)</script> This page only describes the office cafeteria.";
    private static final String ORTHOGONAL = "Delivery tracking numbers are sent by email.";
    private static final double EPSILON = 1e-6; // pgvector stores float32 vectors.

    private final ObjectMapper json = new ObjectMapper();
    private EvaluationTestDatabase database;
    private TokenCounter tokenizer;
    private JdbcTemplate jdbc;
    private EvaluationRepository repository;
    private EvaluationCorpusService corpus;
    private EvaluationVectorStore vectors;
    private EvaluationService service;
    private MockMvc mvc;
    private Fixture owner;
    private ObjectNode model;
    private final Map<String, double[]> documentVectors = new ConcurrentHashMap<>();
    private final Map<String, double[]> queryVectors = new ConcurrentHashMap<>();
    private final Set<String> failedQueries = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Gate> nextBuild = new AtomicReference<>();
    private final AtomicReference<Thread> embeddingThread = new AtomicReference<>();
    private final List<Gate> gates = new ArrayList<>();

    @BeforeAll
    void createIsolatedDatabase() {
        database = EvaluationTestDatabase.create();
        jdbc = database.jdbc();
        tokenizer = new ChunkingConfiguration().bgeTokenCounter(
                "tokenizer/bge-base-zh-v1.5-tokenizer.json",
                "7dfbf1966ebf99d471c3796e9b457329d2b2182b817e144f1e904b957745c839");
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(database.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success AND version='16'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class))
                .isNotBlank();
    }

    @BeforeEach
    void wireRealPipeline() throws Exception {
        documentVectors.clear();
        queryVectors.clear();
        failedQueries.clear();
        gates.clear();
        nextBuild.set(null);
        embeddingThread.set(null);
        for (String query : List.of(CAL_ORIGINAL, ACCEPT_ORIGINAL)) queryVectors.put(query, new double[]{1, 0, 0});
        for (String query : List.of(CAL_VARIANT, ACCEPT_VARIANT)) queryVectors.put(query, new double[]{.96, .28, 0});
        for (String query : List.of(CAL_MISSING, ACCEPT_MISSING)) queryVectors.put(query, new double[]{0, 0, 1});

        repository = new EvaluationRepository(jdbc, json, database.transactions());
        corpus = new EvaluationCorpusService(jdbc, json, repository,
                new DefaultChunkContextEnricher(tokenizer), database.transactions(), 1000);
        vectors = new EvaluationVectorStore(jdbc, database.transactions());
        OllamaEmbeddingGateway gateway = mock(OllamaEmbeddingGateway.class);
        when(gateway.inspect(any(ObjectNode.class))).thenAnswer(invocation -> {
            ObjectNode config = invocation.getArgument(0);
            boolean candidate = config.path("modelName").asText().equals("candidate:fixed");
            return config.deepCopy().put("dimensions", candidate ? 4 : 3)
                    .put("digest", "sha256:" + (candidate ? "b" : "a").repeat(64))
                    .put("quantization", "F32").put("ollamaVersion", "fixture-1")
                    .put("verifiedAt", "2026-09-04T00:00:00Z");
        });
        when(gateway.selfSimilarity(any(ObjectNode.class), anyString())).thenReturn(1.0);
        when(gateway.embed(any(ObjectNode.class), anyList(), anyBoolean())).thenAnswer(invocation -> {
            ObjectNode config = invocation.getArgument(0);
            List<String> inputs = invocation.getArgument(1);
            boolean query = invocation.getArgument(2);
            embeddingThread.set(Thread.currentThread());
            if (!query) {
                Gate gate = nextBuild.getAndSet(null);
                if (gate != null) {
                    gate.entered.countDown();
                    if (!gate.release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out");
                }
            }
            List<double[]> embedded = new ArrayList<>();
            for (String input : inputs) {
                if (query && failedQueries.contains(input)) throw new IllegalArgumentException("Fixture query encoding failure");
                double[] vector = (query ? queryVectors : documentVectors).get(input);
                if (vector == null) throw new IllegalArgumentException("Unexpected complete embedding input: " + input);
                if (query && config.path("queryPrefix").asText().equals("bad-ranking:")) vector = new double[]{0, 1, 0};
                embedded.add(Arrays.copyOf(vector, config.path("dimensions").asInt()));
            }
            return embedded;
        });
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("evaluation-fixture", Map.of(
                "spring.ai.ollama.base-url", "http://127.0.0.1:1",
                "spring.ai.ollama.embedding.options.model", "fixture:fixed")));
        EmbeddingModelRegistry registry = new EmbeddingModelRegistry(repository, gateway, environment, json);
        EvaluationWorker worker = new EvaluationWorker(repository, corpus, vectors, gateway, json, jdbc, 40);
        service = new EvaluationService(repository, corpus, registry, worker, json, 16);
        mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(registry, corpus, service,
                new EvaluationReports(json))).build();
        owner = fixture();
        authorize(owner);
        model = data(post("/embedding-evaluation/models").contentType(MediaType.APPLICATION_JSON)
                .content(modelCommand("fixture:fixed", "").toString()));
    }

    @AfterEach
    void stopOnlyThisTestsWorker() throws InterruptedException {
        gates.forEach(gate -> gate.release.countDown());
        AuthContext.clear();
        if (service != null) {
            service.shutdown();
            // Lifecycle-only reflection: await this service's private executor before dropping its database.
            ThreadPoolExecutor executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "executor");
            assertThat(executor).isNotNull();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Evaluation worker did not stop");
        }
    }

    @AfterAll
    void removeOnlyOwnedDatabase() throws Exception {
        try {
            if (tokenizer instanceof AutoCloseable closeable) closeable.close();
        } finally {
            if (database != null) database.close();
        }
    }

    @Test
    void capturesCorpusCalibratesAndAcceptsFrozenDatasetWithSignedScoresAndExports() throws Exception {
        ObjectNode snapshot = capture();
        assertThat(snapshot.path("scope").asText()).isEqualTo("ALL");
        assertThat(snapshot.path("chunks")).hasSize(4);
        JsonNode captured = find(snapshot.path("chunks"), "id", owner.answer());
        assertThat(captured.path("content").asText()).isEqualTo(ANSWER);
        assertThat(captured.path("indexContent").asText()).isEqualTo(indexText(ANSWER));
        assertThat(captured.path("contentHash").asText()).hasSize(64);
        ObjectNode dataset = dataset(snapshot, questions(owner), true);

        Gate gate = blockNextBuild();
        ObjectNode queued = start(command(dataset, "CALIBRATION", "EXACT", null));
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED");
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "Async worker did not start");
        assertThat(embeddingThread.get()).isNotSameAs(Thread.currentThread());
        // The worker must retain its own authorization after the request thread has finished.
        AuthContext.clear();
        gate.release.countDown();
        ObjectNode calibration = finish(queued);
        authorize(owner);
        assertCompleted(calibration, "CALIBRATION");
        assertThat(calibration.path("questions")).hasSize(3);
        JsonNode calibrated = result(calibration);
        assertThat(calibrated.path("preparedChunks").asInt()).isEqualTo(4);
        assertThat(calibrated.path("cacheHit").asBoolean()).isFalse();
        assertMetric(calibrated.path("metrics"), "hit1", 1);
        assertMetric(calibrated.path("metrics"), "mrr10", 1);
        assertMetric(calibrated.path("metrics"), "ndcg5", 1);
        assertMetric(calibrated.path("metrics"), "hardNegativeWinRate", 1);
        assertMetric(calibrated.path("metrics"), "variantGroupHitRate", 1);
        assertMetric(calibrated.path("metrics"), "noAnswerFalsePositiveRate", 1);
        assertMetric(calibrated.path("calibration").get(0), "noAnswerFalsePositiveRate", 1);
        assertThat(calibrated.path("calibration").get(0).path("threshold").isNull()).isTrue();
        JsonNode zeroThreshold = calibrationAt(calibrated, 0);
        assertMetric(zeroThreshold, "noAnswerFalsePositiveRate", 0);
        assertMetric(zeroThreshold, "evidenceRetentionRate", 1);
        JsonNode upperBoundary = calibrationAt(calibrated, 1);
        assertMetric(upperBoundary, "evidenceRetentionRate", 0);
        assertMetric(upperBoundary, "answerableEmptyRate", 1);

        ObjectNode acceptance = finish(start(command(dataset, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(acceptance, "PASS");
        assertThat(acceptance.path("datasetRevision").asInt()).isEqualTo(1);
        assertThat(acceptance.path("snapshotHash")).isEqualTo(snapshot.path("hash"));
        assertThat(acceptance.path("calibrationRunId")).isEqualTo(calibration.path("id"));
        assertThat(acceptance.path("progress").path("completed")).isEqualTo(acceptance.path("progress").path("total"));
        assertThat(result(acceptance).path("cacheHit").asBoolean()).isTrue();
        JsonNode metrics = result(acceptance).path("metrics");
        assertThat(metrics.path("questionCount").asInt()).isEqualTo(3);
        assertThat(metrics.path("answerableCount").asInt()).isEqualTo(2);
        assertThat(metrics.path("unanswerableCount").asInt()).isEqualTo(1);
        assertThat(metrics.path("unknownScoringCount").asInt()).isZero();
        assertMetric(metrics, "noAnswerFalsePositiveRate", 0);
        assertMetric(metrics, "evidenceRetentionRate", 1);
        assertThat(metrics.path("denominators").path("hardNegativeWinRate").asInt()).isEqualTo(2);
        assertThat(metrics.path("variantEligibleGroupCount").asInt()).isEqualTo(1);
        JsonNode original = query(acceptance, "a-original");
        assertThat(ids(original.path("hits"), "chunkId")).containsExactly(owner.answer(), owner.hardNegative(),
                owner.orthogonal(), owner.opposite());
        assertMetric(find(original.path("hits"), "chunkId", owner.answer()), "score", 1);
        assertMetric(find(original.path("hits"), "chunkId", owner.hardNegative()), "score", .8);
        assertMetric(find(original.path("hits"), "chunkId", owner.opposite()), "score", -1);
        assertMetric(query(acceptance, "a-variant").path("metrics"), "bestAnswerScore", .96);
        assertMetric(query(acceptance, "a-variant").path("metrics"), "bestHardNegativeScore", .936);
        assertThat(query(acceptance, "a-missing").path("metrics").path("returnedCount").asInt()).isZero();
        String buildKey = result(acceptance).path("buildKey").asText();
        Double postgresScore = jdbc.queryForObject("""
                SELECT 1-(embedding <=> '[1,0,0]'::vector) FROM embedding_eval_vector
                WHERE tenant_id=? AND build_key=? AND chunk_id=?::uuid
                """, Double.class, owner.tenant(), buildKey, owner.opposite());
        assertThat(postgresScore).isEqualTo(-1d);
        assertThat(vectors.ready(owner.tenant(), buildKey, 4, 3)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_eval_vector WHERE tenant_id=? AND build_key=?",
                Integer.class, owner.tenant(), buildKey)).isEqualTo(4);
        assertExports(acceptance, snapshot);
    }

    @Test
    void thresholdFailuresRemainDistinctFromRankingQualityAndUseStrictGreaterThan() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        ObjectNode calibration = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        ObjectNode loose = command(dataset, "ACCEPTANCE", "EXACT", calibration);
        loose.with("thresholds").put(modelId(), -.1);
        ObjectNode falsePositive = finish(start(loose));
        assertCompleted(falsePositive, "FAIL");
        assertMetric(result(falsePositive).path("metrics"), "hit5", 1);
        assertMetric(result(falsePositive).path("metrics"), "noAnswerFalsePositiveRate", 1);
        assertThat(falsePositive.path("verdictReasons").toString()).contains("无答案误召回率");
        ObjectNode strict = command(dataset, "ACCEPTANCE", "EXACT", calibration);
        strict.with("thresholds").put(modelId(), 1);
        ObjectNode empty = finish(start(strict));
        assertCompleted(empty, "FAIL");
        assertMetric(result(empty).path("metrics"), "hit5", 1);
        assertMetric(result(empty).path("metrics"), "evidenceRetentionRate", 0);
        assertMetric(result(empty).path("metrics"), "answerableEmptyRate", 1);
        assertThat(query(empty, "a-original").path("metrics").path("returnedCount").asInt()).isZero();
        assertThat(query(empty, "a-original").path("hits")).hasSize(4);
    }

    @Test
    void bindsDatasetRevisionsAndRejectsCalibrationFromChangedQuestions() throws Exception {
        ObjectNode snapshot = capture();
        ObjectNode first = dataset(snapshot, questions(owner), true);
        ObjectNode calibration = finish(start(command(first, "CALIBRATION", "EXACT", null)));
        ArrayNode revisedQuestions = questions(owner);
        String revisedQuery = "What duration is the direct-sale product guarantee?";
        ((ObjectNode) revisedQuestions.get(0)).put("query", revisedQuery);
        queryVectors.put(revisedQuery, new double[]{1, 0, 0});
        ObjectNode update = datasetCommand(snapshot, revisedQuestions, true).put("revision", 1).put("name", "Revised calibration");
        ObjectNode second = data(put(root() + "/datasets/" + first.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content(update.toString()));
        assertThat(second.path("revision").asInt()).isEqualTo(2);
        mvc.perform(put(root() + "/datasets/" + first.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON).content(update.toString())).andExpect(status().isConflict());
        ObjectNode oldVersion = data(get(root() + "/datasets/" + first.path("id").asText()).param("revision", "1"));
        assertThat(oldVersion.path("questions")).isEqualTo(first.path("questions"));
        assertThat(data(get(root() + "/datasets/" + first.path("id").asText())).path("revision").asInt()).isEqualTo(2);
        ObjectNode oldAcceptance = finish(start(command(first, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(oldAcceptance, "PASS");
        assertThat(oldAcceptance.path("datasetRevision").asInt()).isEqualTo(1);
        ObjectNode mismatched = finish(start(command(second, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(mismatched, "INSUFFICIENT");
        assertThat(mismatched.path("calibrationHash")).isNotEqualTo(calibration.path("calibrationHash"));
        assertThat(mismatched.path("verdictReasons").toString()).contains("校准语料或问题版本");
        assertThat(data(get(root() + "/runs/" + calibration.path("id").asText())).path("datasetRevision").asInt()).isEqualTo(1);
    }

    @Test
    void modelRevisionChangeInvalidatesCalibrationButRetryKeepsOriginalModelSnapshot() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        ObjectNode calibration = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        ObjectNode first = finish(start(command(dataset, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(first, "PASS");
        ObjectNode update = modelCommand("fixture:fixed", "changed-prefix:").put("revision", 1);
        ObjectNode changed = data(put("/embedding-evaluation/models/" + modelId())
                .contentType(MediaType.APPLICATION_JSON).content(update.toString()));
        assertThat(changed.path("revision").asInt()).isEqualTo(2);
        ObjectNode incompatible = finish(start(command(dataset, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(incompatible, "INSUFFICIENT");
        assertThat(incompatible.path("verdictReasons").toString()).contains("模型配置与校准运行不一致");
        assertThat(result(incompatible).path("buildKey")).isNotEqualTo(result(first).path("buildKey"));
        ObjectNode retry = finish(data(post(root() + "/runs/" + first.path("id").asText() + "/retry")));
        assertCompleted(retry, "PASS");
        assertThat(retry.path("retryOf")).isEqualTo(first.path("id"));
        assertThat(retry.path("models").get(0).path("revision").asInt()).isEqualTo(1);
        assertThat(retry.path("models").get(0).path("queryPrefix").asText()).isEmpty();
        assertThat(retry.path("modelFingerprints")).isEqualTo(first.path("modelFingerprints"));
        assertThat(result(retry).path("buildKey")).isEqualTo(result(first).path("buildKey"));
    }

    @Test
    void frozenSnapshotsSurviveSourceEditsAndSelectedCaptureRebuildsCompleteIndexText() throws Exception {
        ObjectNode snapshot = capture();
        String edited = "Direct purchases now allow returns within 45 days.";
        jdbc.update("UPDATE document_chunk SET content=?,is_modified=true,lock_version=lock_version+1 WHERE public_id=?::uuid",
                edited, owner.answer());
        mvc.perform(post(root() + "/snapshots").contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"ALL\"}"))
                .andExpect(status().isUnprocessableEntity());
        ObjectNode selectedCommand = json.createObjectNode().put("scope", "SELECTED");
        selectedCommand.putArray("chunkIds").add(owner.answer());
        ObjectNode selected = data(post(root() + "/snapshots").contentType(MediaType.APPLICATION_JSON)
                .content(selectedCommand.toString()));
        assertThat(selected.path("chunks")).hasSize(1);
        JsonNode newChunk = selected.path("chunks").get(0);
        assertThat(newChunk.path("content").asText()).isEqualTo(edited);
        assertThat(newChunk.path("indexContent").asText()).isEqualTo(indexText(edited));
        assertThat(newChunk.path("lockVersion").asInt()).isEqualTo(1);
        assertThat(newChunk.path("contentHash")).isNotEqualTo(find(snapshot.path("chunks"), "id", owner.answer()).path("contentHash"));
        assertThat(data(get(root() + "/snapshots/" + snapshot.path("id").asText())).path("chunks"))
                .isEqualTo(snapshot.path("chunks"));
        assertThat(jdbc.queryForObject("SELECT index_content FROM document_chunk WHERE public_id=?::uuid",
                String.class, owner.answer())).isEqualTo(indexText(ANSWER));
        ObjectNode dataset = dataset(snapshot, questions(owner), true);
        ObjectNode frozenRun = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        assertCompleted(frozenRun, "CALIBRATION");
        assertThat(query(frozenRun, "c-original").path("hits").get(0).path("content").asText()).isEqualTo(ANSWER);
        jdbc.update("DELETE FROM knowledge_file WHERE tenant_id=? AND knowledge_id=? AND file_id=?",
                owner.tenant(), owner.knowledge(), owner.answerFile());
        mvc.perform(get(root() + "/snapshots/" + snapshot.path("id").asText())).andExpect(status().isGone());
        mvc.perform(get(root() + "/runs/" + frozenRun.path("id").asText() + "/export")).andExpect(status().isGone());
    }

    @Test
    void tenantAndKnowledgeScopesProtectSnapshotsDatasetsModelsRunsExportsAndVectors() throws Exception {
        ObjectNode snapshot = capture();
        ObjectNode dataset = dataset(snapshot, questions(owner), true);
        ObjectNode run = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        Fixture other = fixture();
        authorize(other);
        String otherRoot = root(other);
        for (String endpoint : List.of("/snapshots/" + snapshot.path("id").asText(),
                "/datasets/" + dataset.path("id").asText(), "/runs/" + run.path("id").asText(),
                "/runs/" + run.path("id").asText() + "/export")) {
            mvc.perform(get(otherRoot + endpoint)).andExpect(status().isNotFound());
            mvc.perform(get(root() + endpoint)).andExpect(status().isNotFound());
        }
        assertThat(dataNode(get(otherRoot + "/datasets"))).isEmpty();
        assertThat(dataNode(get(otherRoot + "/runs"))).isEmpty();
        assertThat(ids(dataNode(get("/embedding-evaluation/models")), "id")).containsExactly("current");
        mvc.perform(put("/embedding-evaluation/models/" + modelId()).contentType(MediaType.APPLICATION_JSON)
                .content(modelCommand("fixture:fixed", "").put("revision", 1).toString())).andExpect(status().isNotFound());
        ObjectNode forged = json.createObjectNode().put("scope", "SELECTED");
        forged.putArray("chunkIds").add(owner.answer());
        mvc.perform(post(otherRoot + "/snapshots").contentType(MediaType.APPLICATION_JSON).content(forged.toString()))
                .andExpect(status().isUnprocessableEntity());
        String buildKey = result(run).path("buildKey").asText();
        assertThat(vectors.exact(other.tenant(), buildKey, new double[]{1, 0, 0})).isEmpty();
        assertThat(vectors.ready(other.tenant(), buildKey, 4, 3)).isFalse();

        // Even with the same build key and higher scores in another tenant, owner retrieval stays scoped.
        ObjectNode otherSnapshot = data(post(otherRoot + "/snapshots").contentType(MediaType.APPLICATION_JSON).content("{}"));
        vectors.begin(other.tenant(), other.knowledge(), buildKey, otherSnapshot, model);
        List<JsonNode> chunks = new ArrayList<>();
        otherSnapshot.path("chunks").forEach(chunks::add);
        vectors.append(other.tenant(), buildKey, chunks, chunks.stream().map(c -> new double[]{1, 0, 0}).toList(), 3);
        vectors.complete(other.tenant(), buildKey);
        assertThat(vectors.exact(owner.tenant(), buildKey, new double[]{1, 0, 0}))
                .extracting(EvaluationVectorStore.Score::chunkId)
                .containsExactly(owner.answer(), owner.hardNegative(), owner.orthogonal(), owner.opposite());
        authorize(owner);
        long anotherKnowledge = jdbc.queryForObject("INSERT INTO knowledge(name,tenant_id) VALUES('Other scope',?) RETURNING id",
                Long.class, owner.tenant());
        mvc.perform(get("/knowledge/" + anotherKnowledge + "/embedding-evaluation/runs/" + run.path("id").asText()))
                .andExpect(status().isNotFound());
        assertThat(vectors.production(owner.tenant(), anotherKnowledge, buildKey, new double[]{1, 0, 0}, 10, null, 40)).isEmpty();
    }

    @Test
    void postgresqlHnswSearchPreservesSignedScoresAndFiltersLiveSources() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        ObjectNode calibration = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        assertCompleted(calibration, "CALIBRATION");
        String buildKey = result(calibration).path("buildKey").asText();
        vectors.prepareProductionIndex(owner.tenant(), buildKey, 3);
        List<String> indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename='embedding_eval_vector' AND indexdef LIKE ?",
                String.class, "%" + buildKey + "%");
        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0)).contains("USING hnsw", "vector_cosine_ops", "vector(3)");
        String plan = new TransactionTemplate(database.transactions()).execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan=off");
            return String.join("\n", jdbc.queryForList("EXPLAIN SELECT chunk_id FROM embedding_eval_vector WHERE tenant_id=? AND build_key=? "
                    + "ORDER BY embedding::vector(3) <=> '[1,0,0]'::vector(3) LIMIT 10", String.class, owner.tenant(), buildKey));
        });
        assertThat(plan).contains("Index Scan", "eval_hnsw_");
        List<EvaluationVectorStore.Score> found = vectors.production(owner.tenant(), owner.knowledge(), buildKey,
                new double[]{1, 0, 0}, 10, null, 40);
        assertThat(found).containsExactlyElementsOf(vectors.exact(owner.tenant(), buildKey, new double[]{1, 0, 0}));
        assertThat(found.get(3).score()).isEqualTo(-1d);
        assertThat(vectors.production(owner.tenant(), owner.knowledge(), buildKey, new double[]{1, 0, 0}, 10, 1d, 40)).isEmpty();
        jdbc.update("UPDATE file SET status=0 WHERE id=? AND tenant_id=?", owner.answerFile(), owner.tenant());
        jdbc.update("UPDATE document_chunk SET status=0 WHERE public_id=?::uuid", owner.hardNegative());
        assertThat(vectors.production(owner.tenant(), owner.knowledge(), buildKey, new double[]{1, 0, 0}, 10, null, 40))
                .extracting(EvaluationVectorStore.Score::chunkId).containsExactly(owner.orthogonal(), owner.opposite());
        assertThat(vectors.production(owner.tenant(), owner.knowledge(), buildKey, new double[]{1, 0, 0}, 10, -.5, 40))
                .extracting(EvaluationVectorStore.Score::chunkId).containsExactly(owner.orthogonal());
        assertThat(vectors.exact(owner.tenant(), buildKey, new double[]{1, 0, 0})).hasSize(4);
    }

    @Test
    void productionDeliveryBuildsHnswAndRejectsChangedLiveCorpus() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        ObjectNode calibration = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        ObjectNode delivery = finish(start(command(dataset, "DELIVERY", "PRODUCTION", calibration)));
        assertCompleted(delivery, "PASS");
        assertThat(delivery.path("retrievalSettings").path("indexType").asText()).isEqualTo("HNSW");
        String buildKey = result(delivery).path("buildKey").asText();
        assertMetric(query(delivery, "a-original").path("exactReferenceMetrics"), "hit1", 1);
        assertMetric(find(query(delivery, "a-original").path("hits"), "chunkId", owner.opposite()), "score", -1);
        assertThat(vectors.production(owner.tenant(), owner.knowledge(), buildKey, new double[]{1, 0, 0}, 10, 1d, 40)).isEmpty();

        jdbc.update("UPDATE document_chunk SET status=0 WHERE public_id=?::uuid", owner.answer());
        ObjectNode afterChunkDisabled = finish(start(command(dataset, "DELIVERY", "PRODUCTION", calibration)));
        assertThat(afterChunkDisabled.path("status").asText()).isEqualTo("FAILED");
        assertThat(afterChunkDisabled.path("verdict").asText()).isEqualTo("INSUFFICIENT");
        assertThat(afterChunkDisabled.path("error").asText()).contains("生产语料已发生变化");
        assertThat(vectors.production(owner.tenant(), owner.knowledge(), buildKey, new double[]{1, 0, 0}, 10, null, 40))
                .extracting(EvaluationVectorStore.Score::chunkId).doesNotContain(owner.answer());
        jdbc.update("UPDATE document_chunk SET status=2 WHERE public_id=?::uuid", owner.answer());
        jdbc.update("UPDATE file SET status=0 WHERE id=? AND tenant_id=?", owner.answerFile(), owner.tenant());
        assertThat(vectors.production(owner.tenant(), owner.knowledge(), buildKey, new double[]{1, 0, 0}, 10, null, 40))
                .extracting(EvaluationVectorStore.Score::chunkId).doesNotContain(owner.answer());
        assertThat(vectors.exact(owner.tenant(), buildKey, new double[]{1, 0, 0})).hasSize(4);
    }

    @Test
    void productionScoresJudgedHardNegativeOutsideTheTopTenWithoutDroppingIt() throws Exception {
        List<String> extraIds = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            extraIds.add(insertChunk(owner, owner.otherFile(), i + 10, "Unrelated nearby candidate " + i,
                    new double[]{.9, Math.sqrt(.19), 0}));
        }
        ObjectNode snapshot = capture();
        ArrayNode questions = json.createArrayNode();
        ObjectNode question = question(owner, "single", ACCEPT_ORIGINAL, "single", "CALIBRATION", true);
        extraIds.forEach(id -> question.with("labels").put(id, 0));
        questions.add(question);
        ObjectNode quick = quick(snapshot, questions, "PRODUCTION").put("topK", 10);
        ObjectNode run = finish(start(quick));
        assertCompleted(run, "DIAGNOSTIC");
        JsonNode scored = query(run, "single");
        assertThat(scored.path("hits")).hasSize(10);
        assertThat(ids(scored.path("hits"), "chunkId")).doesNotContain(owner.hardNegative(), owner.opposite());
        assertThat(scored.path("judgedScores")).hasSize(16);
        assertMetric(find(scored.path("judgedScores"), "chunkId", owner.hardNegative()), "score", .8);
        assertMetric(find(scored.path("judgedScores"), "chunkId", owner.opposite()), "score", -1);
        assertMetric(scored.path("metrics"), "hardNegativeWin", 1);
        assertMetric(result(run).path("metrics"), "hardNegativeWinRate", 1);
    }

    @Test
    void unknownLabelsAndUnfrozenDatasetsCannotPassEvenWithPerfectAnswerHits() throws Exception {
        ObjectNode snapshot = capture();
        ObjectNode complete = dataset(snapshot, questions(owner), true);
        ObjectNode calibration = finish(start(command(complete, "CALIBRATION", "EXACT", null)));
        ArrayNode incompleteQuestions = questions(owner);
        for (JsonNode question : incompleteQuestions) {
            if (question.path("split").asText().equals("ACCEPTANCE")) ((ObjectNode) question.path("labels")).remove(owner.opposite());
        }
        ObjectNode incomplete = dataset(snapshot, incompleteQuestions, true);
        ObjectNode run = finish(start(command(incomplete, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(run, "INSUFFICIENT");
        assertMetric(result(run).path("metrics"), "hit5", 1);
        assertThat(result(run).path("metrics").path("unknownScoringCount").asInt()).isEqualTo(3);
        assertThat(run.path("verdictReasons").toString()).contains("未知标签");
        assertThat(find(query(run, "a-original").path("hits"), "chunkId", owner.opposite()).has("label")).isFalse();
        ObjectNode unfrozen = dataset(snapshot, questions(owner), false);
        ObjectNode notFrozen = finish(start(command(unfrozen, "ACCEPTANCE", "EXACT", calibration)));
        assertCompleted(notFrozen, "INSUFFICIENT");
        assertThat(notFrozen.path("verdictReasons").toString()).contains("尚未冻结");
    }

    @Test
    void queryFailurePreservesDenominatorsAndRetryUsesSameInputs() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        ObjectNode calibration = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        failedQueries.add(ACCEPT_VARIANT);
        ObjectNode failed = finish(start(command(dataset, "ACCEPTANCE", "EXACT", calibration)));
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("verdict").asText()).isEqualTo("INSUFFICIENT");
        assertThat(result(failed).path("queries")).hasSize(3);
        assertThat(query(failed, "a-variant").path("status").asText()).isEqualTo("FAILED");
        JsonNode metrics = result(failed).path("metrics");
        assertThat(metrics.path("questionCount").asInt()).isEqualTo(3);
        assertThat(metrics.path("completedCount").asInt()).isEqualTo(2);
        assertThat(metrics.path("failedCount").asInt()).isEqualTo(1);
        assertThat(metrics.path("denominators").path("hit5").asInt()).isEqualTo(2);
        assertMetric(metrics, "hit5", .5);
        assertMetric(metrics, "failureRate", 1d / 3);
        failedQueries.clear();
        ObjectNode retry = finish(data(post(root() + "/runs/" + failed.path("id").asText() + "/retry")));
        assertCompleted(retry, "PASS");
        for (String field : List.of("datasetId", "datasetRevision", "snapshotId", "snapshotHash", "questions", "models", "thresholds")) {
            assertThat(retry.path(field)).as(field).isEqualTo(failed.path(field));
        }
        assertThat(data(get(root() + "/runs/" + failed.path("id").asText())).path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void comparesDifferentModelDimensionsUsingRealRankingsAndPreservesSharedCorpus() throws Exception {
        ObjectNode snapshot = capture();
        ObjectNode worse = data(post("/embedding-evaluation/models").contentType(MediaType.APPLICATION_JSON)
                .content(modelCommand("fixture:fixed", "bad-ranking:").toString()));
        ObjectNode candidate = data(post("/embedding-evaluation/models").contentType(MediaType.APPLICATION_JSON)
                .content(modelCommand("candidate:fixed", "").toString()));
        ArrayNode questions = json.createArrayNode()
                .add(question(owner, "original", ACCEPT_ORIGINAL, "refund", "CALIBRATION", true))
                .add(question(owner, "variant", ACCEPT_VARIANT, "refund", "CALIBRATION", true));
        ObjectNode command = quick(snapshot, questions, "EXACT").put("topK", 1)
                .put("baselineModelId", worse.path("id").asText());
        command.putArray("modelIds").add(worse.path("id").asText()).add(candidate.path("id").asText());
        ObjectNode run = finish(start(command));
        assertCompleted(run, "DIAGNOSTIC");
        JsonNode baselineResult = find(run.path("modelResults"), "modelId", worse.path("id").asText());
        JsonNode candidateResult = find(run.path("modelResults"), "modelId", candidate.path("id").asText());
        assertMetric(baselineResult.path("metrics"), "hit1", 0);
        assertMetric(baselineResult.path("metrics"), "hardNegativeWinRate", 0);
        assertMetric(baselineResult.path("metrics"), "variantGroupHitRate", 0);
        assertMetric(candidateResult.path("metrics"), "hit1", 1);
        assertMetric(candidateResult.path("metrics"), "hardNegativeWinRate", 1);
        assertMetric(candidateResult.path("metrics"), "variantGroupHitRate", 1);
        assertThat(candidateResult.path("dimensions").asInt()).isEqualTo(4);
        assertThat(baselineResult.path("dimensions").asInt()).isEqualTo(3);
        assertThat(candidateResult.path("comparison").path("pairedQuestionCount").asInt()).isEqualTo(2);
        assertMetric(candidateResult.path("comparison").path("metrics").path("hit1"), "delta", 1);
        assertThat(candidateResult.path("comparison").path("metrics").path("hit1").path("evidence").asText()).isEqualTo("INSUFFICIENT");
        for (JsonNode result : run.path("modelResults")) {
            assertThat(result.path("preparedChunks").asInt()).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT vector_dims(embedding)) FROM embedding_eval_vector WHERE tenant_id=? AND build_key=?",
                    Integer.class, owner.tenant(), result.path("buildKey").asText())).isEqualTo(1);
        }
    }

    @Test
    void queuedWorkerRechecksUserRoleAndTenantStatusBeforeEncoding() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        Gate gate = blockNextBuild();
        ObjectNode first = start(command(dataset, "CALIBRATION", "EXACT", null));
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        ObjectNode queued = start(command(dataset, "CALIBRATION", "EXACT", null));
        assertThat(queued.path("status").asText()).isEqualTo("QUEUED");
        jdbc.update("UPDATE app_user SET role='member' WHERE id=? AND tenant_id=?", owner.user(), owner.tenant());
        gate.release.countDown();
        finish(first);
        ObjectNode denied = finish(queued);
        assertThat(denied.path("status").asText()).isEqualTo("FAILED");
        assertThat(denied.path("verdict").asText()).isEqualTo("INSUFFICIENT");
        assertThat(result(denied).path("preparedChunks").asInt()).isZero();
        assertThat(result(denied).path("queries")).isEmpty();
        jdbc.update("UPDATE app_user SET role='tenant_admin' WHERE id=? AND tenant_id=?", owner.user(), owner.tenant());
        jdbc.update("UPDATE tenant SET status=0 WHERE id=?", owner.tenant());
        ObjectNode suspended = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        assertThat(suspended.path("status").asText()).isEqualTo("FAILED");
        assertThat(result(suspended).path("preparedChunks").asInt()).isZero();
        jdbc.update("UPDATE tenant SET status=1 WHERE id=?", owner.tenant());
        jdbc.update("UPDATE app_user SET status=0 WHERE id=? AND tenant_id=?", owner.user(), owner.tenant());
        ObjectNode disabled = finish(start(command(dataset, "CALIBRATION", "EXACT", null)));
        assertThat(disabled.path("status").asText()).isEqualTo("FAILED");
        assertThat(result(disabled).path("preparedChunks").asInt()).isZero();
    }

    @Test
    void cancellingQueuedRunCannotBeOverwrittenAndRetryCompletesOriginalVersion() throws Exception {
        ObjectNode dataset = dataset(capture(), questions(owner), true);
        Gate gate = blockNextBuild();
        ObjectNode blocker = start(command(dataset, "CALIBRATION", "EXACT", null));
        assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
        ObjectNode queued = start(command(dataset, "CALIBRATION", "EXACT", null));
        ObjectNode cancelled = data(post(root() + "/runs/" + queued.path("id").asText() + "/cancel"));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        gate.release.countDown();
        assertCompleted(finish(blocker), "CALIBRATION");
        ObjectNode retry = finish(data(post(root() + "/runs/" + queued.path("id").asText() + "/retry")));
        assertCompleted(retry, "CALIBRATION");
        assertThat(retry.path("retryOf")).isEqualTo(queued.path("id"));
        assertThat(retry.path("snapshotHash")).isEqualTo(queued.path("snapshotHash"));
        assertThat(data(get(root() + "/runs/" + queued.path("id").asText())).path("status").asText()).isEqualTo("CANCELLED");
    }

    private Fixture fixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long tenant = jdbc.queryForObject("INSERT INTO tenant(code,name,status) VALUES(?,?,1) RETURNING id",
                Long.class, "evaluation-" + suffix, "Integration tenant");
        long user = jdbc.queryForObject("INSERT INTO app_user(tenant_id,username,password_hash,role,status) VALUES(?,?,'unused-fixture','tenant_admin',1) RETURNING id",
                Long.class, tenant, "evaluation-" + suffix);
        long knowledge = jdbc.queryForObject("INSERT INTO knowledge(name,tenant_id) VALUES('Evaluation fixture',?) RETURNING id", Long.class, tenant);
        long answerFile = insertFile(tenant, knowledge, "<script>.md");
        long otherFile = insertFile(tenant, knowledge, "other.md");
        Fixture empty = new Fixture(tenant, user, knowledge, answerFile, otherFile, null, null, null, null);
        String answer = insertChunk(empty, answerFile, 0, ANSWER, new double[]{1, 0, 0});
        String hard = insertChunk(empty, otherFile, 0, HARD_NEGATIVE, new double[]{.8, .6, 0});
        String opposite = insertChunk(empty, otherFile, 1, OPPOSITE, new double[]{-1, 0, 0});
        String orthogonal = insertChunk(empty, otherFile, 2, ORTHOGONAL, new double[]{0, 1, 0});
        return new Fixture(tenant, user, knowledge, answerFile, otherFile, answer, hard, opposite, orthogonal);
    }

    private long insertFile(long tenant, long knowledge, String name) {
        long file = jdbc.queryForObject("INSERT INTO file(tenant_id,file_name,size,status,type,path) VALUES(?,?,100,1,'md','fixture-only') RETURNING id",
                Long.class, tenant, name);
        jdbc.update("INSERT INTO knowledge_file(knowledge_id,file_id,tenant_id) VALUES(?,?,?)", knowledge, file, tenant);
        jdbc.update("INSERT INTO file_processing(file_id,tenant_id,knowledge_id,pipeline_state,progress,policy_snapshot) VALUES(?,?,?,6,100,'{\"maxTokens\":512}'::jsonb)",
                file, tenant, knowledge);
        return file;
    }

    private String insertChunk(Fixture fixture, long file, int position, String body, double[] vector) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO document_chunk(public_id,tenant_id,knowledge_id,file_id,position,content,index_content,
                    section_path,source_locator,boundary_reason,token_count,content_hash,status,is_modified,lock_version)
                VALUES(?::uuid,?,?,?,?,?,?,'["Policy","Direct sales"]'::jsonb,'{"type":"PARAGRAPH"}'::jsonb,
                    '{"start":"H2_SECTION","end":"PARAGRAPH_END"}'::jsonb,?,?,2,false,0)
                """, id, fixture.tenant(), fixture.knowledge(), file, position, body, indexText(body), tokenizer.count(body), EvaluationJson.hash(body));
        documentVectors.put(indexText(body), vector);
        return id;
    }

    private static String indexText(String body) {
        return "标题：Policy > Direct sales\n\n" + body;
    }

    private ObjectNode modelCommand(String name, String prefix) {
        return json.createObjectNode().put("displayName", "Fixture " + name).put("baseUrl", "http://127.0.0.1:1")
                .put("modelName", name).put("queryPrefix", prefix).put("documentPrefix", "doc:");
    }

    private ObjectNode capture() throws Exception {
        return data(post(root() + "/snapshots").contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"ALL\"}"));
    }

    private ObjectNode dataset(ObjectNode snapshot, ArrayNode questions, boolean frozen) throws Exception {
        return data(post(root() + "/datasets").contentType(MediaType.APPLICATION_JSON)
                .content(datasetCommand(snapshot, questions, frozen).toString()));
    }

    private ObjectNode datasetCommand(ObjectNode snapshot, ArrayNode questions, boolean frozen) {
        ObjectNode command = json.createObjectNode().put("name", "Refund and warranty benchmark")
                .put("snapshotId", snapshot.path("id").asText()).put("frozen", frozen);
        return command.set("questions", questions);
    }

    private ArrayNode questions(Fixture fixture) {
        return json.createArrayNode()
                .add(question(fixture, "c-original", CAL_ORIGINAL, "warranty", "CALIBRATION", true))
                .add(question(fixture, "c-variant", CAL_VARIANT, "warranty", "CALIBRATION", true))
                .add(question(fixture, "c-missing", CAL_MISSING, "mars", "CALIBRATION", false))
                .add(question(fixture, "a-original", ACCEPT_ORIGINAL, "refund", "ACCEPTANCE", true))
                .add(question(fixture, "a-variant", ACCEPT_VARIANT, "refund", "ACCEPTANCE", true))
                .add(question(fixture, "a-missing", ACCEPT_MISSING, "moon", "ACCEPTANCE", false));
    }

    private ObjectNode question(Fixture fixture, String id, String query, String group, String split, boolean answerable) {
        ObjectNode question = json.createObjectNode().put("id", id).put("query", query).put("intentGroup", group)
                .put("category", "Policies").put("split", split).put("answerable", answerable).put("reviewed", true);
        question.putObject("labels").put(fixture.answer(), answerable ? 2 : 0).put(fixture.hardNegative(), 0)
                .put(fixture.opposite(), 0).put(fixture.orthogonal(), 0);
        ArrayNode negatives = question.putArray("hardNegativeIds");
        if (answerable) negatives.add(fixture.hardNegative());
        return question;
    }

    private ObjectNode command(ObjectNode dataset, String phase, String mode, ObjectNode calibration) {
        ObjectNode command = json.createObjectNode().put("datasetId", dataset.path("id").asText())
                .put("datasetRevision", dataset.path("revision").asInt()).put("phase", phase).put("retrievalMode", mode)
                .put("topK", 5).put("baselineModelId", modelId());
        command.putArray("modelIds").add(modelId());
        if (calibration != null) command.put("calibrationRunId", calibration.path("id").asText());
        if (!phase.equals("CALIBRATION")) {
            command.putObject("thresholds").put(modelId(), .9);
            command.putObject("requirements").put("minQuestions", 3).put("minUnanswerable", 1)
                    .put("minHardNegativeQuestions", 2).put("minVariantGroups", 1).put("hit5Min", 1)
                    .put("evidenceRetentionMin", 1).put("noAnswerFalsePositiveMax", 0).put("p95MaxMs", 10000)
                    .put("hardNegativeWinMin", 1).put("variantGroupHitMin", 1);
        }
        return command;
    }

    private ObjectNode quick(ObjectNode snapshot, ArrayNode questions, String mode) {
        ObjectNode command = json.createObjectNode().put("snapshotId", snapshot.path("id").asText())
                .put("phase", "QUICK").put("retrievalMode", mode).put("topK", 5).put("baselineModelId", modelId());
        command.putArray("modelIds").add(modelId());
        return command.set("questions", questions);
    }

    private ObjectNode start(ObjectNode command) throws Exception {
        return data(post(root() + "/runs").contentType(MediaType.APPLICATION_JSON).content(command.toString()));
    }

    private ObjectNode finish(ObjectNode queued) {
        AtomicReference<ObjectNode> last = new AtomicReference<>(queued);
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(15))
                .alias("terminal evaluation run " + queued.path("id").asText()).until(() -> {
                    ObjectNode run = repository.getRun(owner.tenant(), owner.knowledge(), queued.path("id").asText());
                    last.set(run);
                    return Set.of("COMPLETED", "FAILED", "PARTIAL", "CANCELLED").contains(run.path("status").asText());
                });
        return last.get();
    }

    private void assertCompleted(ObjectNode run, String verdict) {
        assertThat(run.path("status").asText()).withFailMessage("Run failed: %s", run.toPrettyString()).isEqualTo("COMPLETED");
        assertThat(run.path("verdict").asText()).withFailMessage("Unexpected verdict: %s", run.toPrettyString()).isEqualTo(verdict);
    }

    private void assertExports(ObjectNode run, ObjectNode snapshot) throws Exception {
        String endpoint = root() + "/runs/" + run.path("id").asText() + "/export";
        JsonNode exported = null;
        for (String format : List.of("json", "csv", "html")) {
            MvcResult response = mvc.perform(get(endpoint).param("format", format)).andExpect(status().isOk()).andReturn();
            assertThat(response.getResponse().getHeader("Content-Disposition"))
                    .contains("attachment;", run.path("id").asText() + "." + format);
            assertThat(response.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            String body = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (format.equals("json")) {
                exported = json.readTree(body);
                assertThat(exported.path("snapshot").path("chunks")).isEqualTo(snapshot.path("chunks"));
                assertThat(exported.path("datasetRevision")).isEqualTo(run.path("datasetRevision"));
                assertThat(exported.path("modelResults")).isEqualTo(run.path("modelResults"));
                assertThat(exported.path("verdict").asText()).isEqualTo("PASS");
            } else if (format.equals("csv")) {
                assertThat(body).contains("questionId", owner.opposite(), "-1", "'=2+2", "PASS");
            } else {
                assertThat(body).contains("&lt;script&gt;", "PASS").doesNotContain("<script>alert(1)</script>");
            }
        }
        assertThat(exported).isNotNull();
        assertThat(data(get(root() + "/runs/" + run.path("id").asText()))).isEqualTo(run);
    }

    private JsonNode calibrationAt(JsonNode result, double threshold) {
        for (JsonNode point : result.path("calibration")) {
            if (point.path("threshold").isNumber() && Math.abs(point.path("threshold").asDouble() - threshold) < EPSILON) return point;
        }
        throw new AssertionError("No calibration point at " + threshold + ": " + result.path("calibration"));
    }

    private static void assertMetric(JsonNode metrics, String field, double value) {
        assertThat(metrics.path(field).isNumber()).withFailMessage("Missing metric %s: %s", field, metrics).isTrue();
        assertThat(metrics.path(field).asDouble()).as(field).isCloseTo(value, within(EPSILON));
    }

    private static JsonNode result(JsonNode run) {
        assertThat(run.path("modelResults")).hasSize(1);
        return run.path("modelResults").get(0);
    }

    private static JsonNode query(JsonNode run, String id) {
        return find(result(run).path("queries"), "questionId", id);
    }

    private static JsonNode find(JsonNode values, String field, String id) {
        for (JsonNode value : values) if (value.path(field).asText().equals(id)) return value;
        throw new AssertionError("Missing " + field + "=" + id + " in " + values);
    }

    private static List<String> ids(JsonNode values, String field) {
        List<String> ids = new ArrayList<>();
        values.forEach(value -> ids.add(value.path(field).asText()));
        return ids;
    }

    private ObjectNode data(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode node = dataNode(request);
        assertThat(node.isObject()).as("object API payload").isTrue();
        return (ObjectNode) node;
    }

    private JsonNode dataNode(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult response = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode envelope = json.readTree(response.getResponse().getContentAsByteArray());
        assertThat(envelope.path("code").asInt()).isEqualTo(200);
        return envelope.path("data");
    }

    private Gate blockNextBuild() {
        Gate gate = new Gate(new CountDownLatch(1), new CountDownLatch(1));
        gates.add(gate);
        nextBuild.set(gate);
        return gate;
    }

    private static void authorize(Fixture fixture) {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, fixture.user(), fixture.tenant(), "tenant_admin", "fixture"));
    }

    private String root() { return root(owner); }
    private static String root(Fixture fixture) { return "/knowledge/" + fixture.knowledge() + "/embedding-evaluation"; }
    private String modelId() { return model.path("id").asText(); }

    private record Fixture(long tenant, long user, long knowledge, long answerFile, long otherFile,
                           String answer, String hardNegative, String opposite, String orthogonal) {}
    private record Gate(CountDownLatch entered, CountDownLatch release) {}
}
