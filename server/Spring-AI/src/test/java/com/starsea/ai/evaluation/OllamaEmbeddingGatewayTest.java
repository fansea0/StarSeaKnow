package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaEmbeddingGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> embeddingRequests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> methods = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private OllamaEmbeddingGateway gateway;
    private String modelName = "quentinz/bge-base-zh-v1.5:latest";
    private String digest = "a".repeat(64);
    private String capabilities = "[\"embedding\"]";
    private String vectors = "[[3,4]]";
    private int dimensions = 2;
    private int embedStatus = 200;
    private String embedBody;
    private String tagsBody;
    private long delayMillis;
    private boolean alternateVector;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        gateway = new OllamaEmbeddingGateway(json);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void inspect_copies_config_and_records_verified_identity_from_exact_tag() {
        ObjectNode command = config().put("modelName", "quentinz/bge-base-zh-v1.5");
        ObjectNode result = gateway.inspect(command);

        assertThat(result).isNotSameAs(command);
        assertThat(command.has("digest")).isFalse();
        assertThat(result.path("modelName").asText()).isEqualTo(modelName);
        assertThat(result.path("digest").asText()).isEqualTo(digest);
        assertThat(result.path("dimensions").asInt()).isEqualTo(2);
        assertThat(result.path("quantization").asText()).isEqualTo("Q8_0");
        assertThat(result.path("ollamaVersion").asText()).isEqualTo("0.11.0");
        assertThat(Instant.parse(result.path("verifiedAt").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(paths).containsExactly("/api/tags", "/api/show", "/api/version", "/api/embed");
        assertThat(methods).containsExactly("GET", "POST", "GET", "POST");
        assertThat(embeddingRequests.get(0).path("truncate").asBoolean(true)).isFalse();
    }

    @Test
    void embed_preserves_complete_inputs_and_role_prefixes_without_truncation_or_auth() {
        String text = "完整文本\n" + "内容".repeat(600);
        ObjectNode command = config().put("queryPrefix", "query: ").put("documentPrefix", "passage: ")
                .put("dimensions", 2);
        vectors = "[[3,4],[-3,4]]";
        List<double[]> result = gateway.embed(command, List.of(text, "第二段"), true);
        assertThat(result).hasSize(2);
        assertThat(result.get(1)).containsExactly(-3, 4);
        JsonNode request = embeddingRequests.get(0);
        assertThat(request.path("input").get(0).asText()).isEqualTo("query: " + text);
        assertThat(request.path("input").get(1).asText()).isEqualTo("query: 第二段");
        assertThat(request.path("truncate").asBoolean(true)).isFalse();
        assertThat(request.path("model").asText()).isEqualTo(modelName);
        vectors = "[[3,4]]";
        gateway.embed(command, List.of(text), false);
        assertThat(embeddingRequests.get(1).path("input").get(0).asText()).isEqualTo("passage: " + text);
        assertThat(paths).containsOnly("/api/embed");
    }

    @Test
    void base_url_path_is_preserved_for_reverse_proxy() {
        gateway.embed(config().put("baseUrl", baseUrl + "/ollama/"), List.of("text"), false);
        assertThat(paths).containsExactly("/ollama/api/embed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/models", "ftp://localhost", "http://user:secret@localhost",
            "http://localhost?token=secret", "http://localhost#secret", "http://localhost?", "http://localhost#",
            "http:///missing-host", "http://localhost:0", "http://localhost:99999", "//localhost:11434"})
    void rejects_invalid_addresses_before_network(String url) {
        assertStatus(() -> gateway.inspect(config().put("baseUrl", url)), 400);
        assertThat(paths).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bge:", "bge:latest:extra", "bge?secret", "bge#fragment", "bge\nlatest"})
    void rejects_invalid_model_names_before_network(String name) {
        assertStatus(() -> gateway.inspect(config().put("modelName", name)), 400);
        assertThat(paths).isEmpty();
    }

    @Test
    void never_substitutes_a_similar_model_or_another_tag() {
        assertStatus(() -> gateway.inspect(config().put("modelName", "bge-base-zh-v1.5:latest")), 422);
        assertThat(paths).containsExactly("/api/tags");
        paths.clear();
        assertStatus(() -> gateway.inspect(config().put("modelName", "quentinz/bge-base-zh-v1.5:q8")), 422);
        assertThat(paths).containsExactly("/api/tags");
    }

    @Test
    void explicit_embedding_capability_is_required() {
        capabilities = "[\"completion\"]";
        assertStatus(() -> gateway.inspect(config()), 422);
        assertThat(embeddingRequests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "sha256:not-a-digest"})
    void identity_must_have_a_valid_digest(String invalidDigest) {
        digest = invalidDigest;
        assertStatus(() -> gateway.inspect(config()), 502);
        assertThat(embeddingRequests).isEmpty();
    }

    @Test
    void rejects_pinned_identity_change_before_encoding() {
        assertStatus(() -> gateway.inspect(config().put("digest", "b".repeat(64))), 409);
        assertThat(embeddingRequests).isEmpty();
    }

    @Test
    void rejects_ambiguous_tag_identity() {
        tagsBody = "{\"models\":[" + tag().toString() + "," + tag().put("digest", "b".repeat(64)) + "]}";
        assertStatus(() -> gateway.inspect(config()), 502);
    }

    @Test
    void inspect_rejects_declared_dimension_disagreement() {
        dimensions = 3;
        assertStatus(() -> gateway.inspect(config()), 502);
    }

    @Test
    void embed_rejects_configured_dimension_disagreement() {
        assertStatus(() -> gateway.embed(config().put("dimensions", 3), List.of("text"), false), 502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[[1,2],[3,4]]", "[[]]", "[[0,0]]", "[[1,\"NaN\"]]",
            "[[1,null]]", "[[1,1e999]]", "[[\"1\",2]]", "null"})
    void rejects_bad_vector_counts_values_or_shapes(String invalidVectors) {
        vectors = invalidVectors;
        assertStatus(() -> gateway.embed(config(), List.of("private input"), false), 502);
    }

    @Test
    void all_vectors_in_batch_must_have_equal_dimensions() {
        vectors = "[[1,2],[1,2,3]]";
        assertStatus(() -> gateway.embed(config(), List.of("first", "second"), false), 502);
    }

    @Test
    void rejects_different_model_in_embedding_response() {
        embedBody = "{\"model\":\"other:latest\",\"embeddings\":[[1,2]]}";
        assertStatus(() -> gateway.embed(config(), List.of("text"), false), 502);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"embeddings\":[[1,2]]}", "{\"model\":null,\"embeddings\":[[1,2]]}"})
    void rejects_embedding_response_without_model_identity(String response) {
        embedBody = response;
        assertStatus(() -> gateway.embed(config(), List.of("text"), false), 502);
    }

    @Test
    void frozen_runtime_options_are_preserved_without_enabling_truncation() {
        ObjectNode config = config();
        config.putObject("options").put("num_ctx", 512).put("num_thread", 4).put("truncate", true);
        config.put("keepAlive", "3m").put("truncate", true);
        gateway.embed(config, List.of("text"), false);
        JsonNode request = embeddingRequests.get(0);
        assertThat(request.path("options").path("num_ctx").asInt()).isEqualTo(512);
        assertThat(request.path("options").path("num_thread").asInt()).isEqualTo(4);
        assertThat(request.path("options").has("truncate")).isFalse();
        assertThat(request.path("keep_alive").asText()).isEqualTo("3m");
        assertThat(request.path("truncate").asBoolean(true)).isFalse();
        assertThat(config.path("options").path("truncate").asBoolean()).isTrue();
    }

    @Test
    void self_similarity_encodes_identical_role_twice_and_preserves_negative_cosine() {
        alternateVector = true;
        double score = gateway.selfSimilarity(config().put("queryPrefix", "query: ")
                .put("documentPrefix", "document: "), "same text");
        assertThat(score).isCloseTo(-1, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(embeddingRequests).hasSize(2);
        assertThat(embeddingRequests.get(0)).isEqualTo(embeddingRequests.get(1));
        String input = embeddingRequests.get(0).path("input").get(0).asText();
        assertThat(input).isIn("query: same text", "document: same text");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[[1e308,1e308]]", "[[1e-300,1e-300]]"})
    void finite_nonzero_vectors_have_stable_cosine_without_overflow_or_underflow(String values) {
        vectors = values;
        assertThat(gateway.selfSimilarity(config(), "text")).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-12));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 404, 500})
    void sanitizes_upstream_http_errors_and_never_retries_or_truncates(int status) {
        embedStatus = status;
        embedBody = "{\"error\":\"private input secret echoed by upstream\"}";
        assertThatThrownBy(() -> gateway.embed(config(), List.of("private input"), false))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(status == 400 ? 422 : 502);
                    assertThat(error.getReason()).contains("HTTP " + status).doesNotContain("private input", "secret");
                    assertThat(error.getCause()).isNull();
                });
        assertThat(embeddingRequests).hasSize(1);
        assertThat(embeddingRequests.get(0).path("truncate").asBoolean(true)).isFalse();
    }

    @Test
    void refuses_redirects() {
        embedStatus = 307;
        assertStatus(() -> gateway.embed(config(), List.of("private input"), false), 502);
        assertThat(paths).containsExactly("/api/embed");
    }

    @Test
    void rejects_malformed_upstream_json_without_exposing_body() {
        embedBody = "private input is not JSON";
        assertThatThrownBy(() -> gateway.embed(config(), List.of("private input"), false))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(502);
                    assertThat(error.getMessage()).doesNotContain("private input");
                    assertThat(error.getCause()).isNull();
                });
    }

    @Test
    void request_deadline_is_bounded() {
        delayMillis = 300;
        gateway = new OllamaEmbeddingGateway(json, Duration.ofSeconds(1), Duration.ofMillis(50));
        assertStatus(() -> gateway.embed(config(), List.of("text"), false), 504);
    }

    @Test
    void validates_input_elements_before_sending_any_content() {
        List<String> invalid = new ArrayList<>();
        invalid.add("valid");
        invalid.add(null);
        assertStatus(() -> gateway.embed(config(), invalid, false), 400);
        assertThat(gateway.embed(config(), Collections.emptyList(), false)).isEmpty();
        assertThat(paths).isEmpty();
    }

    private ObjectNode config() {
        return json.createObjectNode().put("displayName", "Fixture model").put("baseUrl", baseUrl)
                .put("modelName", modelName).put("queryPrefix", "").put("documentPrefix", "");
    }

    private ObjectNode tag() {
        ObjectNode tag = json.createObjectNode().put("name", modelName).put("model", modelName).put("digest", digest);
        tag.putObject("details").put("quantization_level", "Q8_0");
        return tag;
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        methods.add(exchange.getRequestMethod());
        if (exchange.getRequestHeaders().containsKey("Authorization")) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }
        int status = 200;
        String body;
        if (path.endsWith("/api/tags")) {
            body = tagsBody == null ? "{\"models\":[" + tag() + "]}" : tagsBody;
        } else if (path.endsWith("/api/show")) {
            JsonNode request = json.readTree(exchange.getRequestBody());
            if (!modelName.equals(request.path("model").asText())) {
                status = 404;
            }
            body = "{\"capabilities\":" + capabilities + ",\"details\":{\"quantization_level\":\"Q8_0\"},"
                    + "\"model_info\":{\"general.architecture\":\"bert\",\"bert.embedding_length\":" + dimensions + "}}";
        } else if (path.endsWith("/api/version")) {
            body = "{\"version\":\"0.11.0\"}";
        } else if (path.endsWith("/api/embed")) {
            embeddingRequests.add(json.readTree(exchange.getRequestBody()));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            String responseVectors = alternateVector && embeddingRequests.size() == 2 ? "[[-3,-4]]" : vectors;
            body = embedBody == null ? "{\"model\":\"" + modelName + "\",\"embeddings\":" + responseVectors + "}" : embedBody;
            status = embedStatus;
            if (status == 307) {
                exchange.getResponseHeaders().set("Location", baseUrl + "/redirected");
            }
        } else {
            status = 404;
            body = "{}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private void assertStatus(Runnable action, int expectedStatus) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(expectedStatus));
    }
}
