package com.starsea.ai.model.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleConnectionVerifierTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "{\"data\":[]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void discovers_unique_models_with_bearer_authentication() {
        responseBody = """
                {"data":[{"id":"chat-fast"},{"id":"chat-fast"},{"id":"chat-pro"}]}
                """;

        ModelProviderConnectionVerifier.VerifiedConnection result =
                new OpenAiCompatibleConnectionVerifier().verify(
                        baseUrl + "/", "API_KEY", "secret-value");

        assertThat(authorization.get()).isEqualTo("Bearer secret-value");
        assertThat(result.discoveredModels())
                .extracting(ModelSuggestion::modelId)
                .containsExactly("chat-fast", "chat-pro");
        assertThat(result.discoveredModels())
                .allSatisfy(model -> {
                    assertThat(model.displayName()).isEqualTo(model.modelId());
                    assertThat(model.contextWindow()).isEqualTo(128000);
                });
    }

    @Test
    void maps_authentication_failure_without_exposing_upstream_body_or_key() {
        responseStatus = 401;
        responseBody = "secret-value upstream diagnostic";

        assertThatThrownBy(() -> new OpenAiCompatibleConnectionVerifier().verify(
                baseUrl, "API_KEY", "secret-value"))
                .isInstanceOfSatisfying(ModelProviderException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(422);
                    assertThat(exception.code()).isEqualTo("MODEL_PROVIDER_AUTH_FAILED");
                    assertThat(exception.getMessage())
                            .isEqualTo("厂商认证失败，请检查 API Key")
                            .doesNotContain("secret-value", "upstream diagnostic");
                });
    }

    @Test
    void rejects_non_http_base_url_before_sending_request() {
        assertThatThrownBy(() -> new OpenAiCompatibleConnectionVerifier().verify(
                "file:///tmp/models", "NONE", null))
                .isInstanceOfSatisfying(ModelProviderException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(422);
                    assertThat(exception.code()).isEqualTo("MODEL_PROVIDER_URL_INVALID");
                });
    }

    private void respond(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
