package com.starsea.ai.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.agent.snapshot.AgentSnapshotData;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.model.provider.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class AgentModelClientFactoryTest {
    private HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final TenantModelProviderMapper providers = mock(TenantModelProviderMapper.class);
    private AesGcmModelProviderSecretCipher cipher;
    private TenantModelProvider provider;
    private AgentModelClientFactory factory;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private int status = 200;
    private CountDownLatch holdResponse;
    private String response = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
            + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}\n\n"
            + "data: [DONE]\n\n";

    @BeforeEach void setup() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_admin", "jti"));
        var properties = new ModelProviderEncryptionProperties();
        properties.setActiveKeyVersion("v1");
        properties.setKeys(Map.of("v1", Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))));
        properties.setEnvironment(new MockEnvironment());
        properties.afterPropertiesSet();
        cipher = new AesGcmModelProviderSecretCipher(properties);
        provider = new TenantModelProvider();
        provider.setId(30L); provider.setTenantId(9L); provider.setAuthType("API_KEY");
        provider.setProtocolType("OPENAI_COMPATIBLE");
        key("sk-test-old");
        when(providers.selectOne(any())).thenReturn(provider);
        factory = new AgentModelClientFactory(providers, cipher, json);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(json.readTree(exchange.getRequestBody()));
            if (holdResponse != null) {
                try { holdResponse.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void cleanup() {
        if (holdResponse != null) holdResponse.countDown();
        server.stop(0); AuthContext.clear();
    }

    @Test void uses_current_encrypted_key_and_snapshot_url_parameters_without_caching_credentials() {
        var source = source(5);
        provider.setBaseUrl("https://current-config-must-not-override-snapshot.invalid/v1");
        var messages = List.of(new ConversationMessage("user", "测试"));
        var result = factory.create(source).stream(messages).collectList().block(Duration.ofSeconds(5));
        assertThat(authorization.get()).isEqualTo("Bearer sk-test-old");
        assertThat(result).extracting(ModelChunk::text).contains("你好");
        assertThat(result).filteredOn(chunk -> chunk.usage() != null).singleElement()
                .satisfies(chunk -> assertThat(chunk.usage().totalTokens()).isEqualTo(12L));
        assertThat(requestBody.get().path("model").asText()).isEqualTo("test-model");
        assertThat(requestBody.get().path("temperature").asDouble()).isEqualTo(0.4);
        assertThat(requestBody.get().path("top_p").asDouble()).isEqualTo(0.9);
        assertThat(requestBody.get().path("max_tokens").asInt()).isEqualTo(2048);
        assertThat(requestBody.get().path("stream").asBoolean()).isTrue();
        key("sk-test-new");
        factory.create(source).stream(messages).collectList().block(Duration.ofSeconds(5));
        assertThat(authorization.get()).isEqualTo("Bearer sk-test-new");
    }

    @Test void rejects_http_auth_errors_without_retaining_upstream_body_in_exception() {
        status = 401; response = "secret request and sk-test-old";
        assertThatThrownBy(() -> factory.create(source(5)).stream(List.of(new ConversationMessage("user", "q")))
                .collectList().block(Duration.ofSeconds(5)))
                .hasMessageNotContaining("sk-test-old").hasMessageNotContaining("secret request")
                .extracting("code").isEqualTo("MODEL_PROVIDER_AUTH_FAILED");
    }

    @Test void rejects_truncated_or_malformed_stream_without_reporting_success() {
        response = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n";
        assertThatThrownBy(() -> factory.create(source(5)).stream(List.of(new ConversationMessage("user", "q")))
                .collectList().block(Duration.ofSeconds(5)))
                .extracting("code").isEqualTo("MODEL_PROVIDER_STREAM_INCOMPLETE");
        response = "data: invalid-private-body\n\n";
        assertThatThrownBy(() -> factory.create(source(5)).stream(List.of(new ConversationMessage("user", "q")))
                .collectList().block(Duration.ofSeconds(5))).hasMessageNotContaining("invalid-private-body");
    }

    @Test void refuses_missing_or_cross_tenant_provider_before_network() {
        provider.setTenantId(8L);
        assertThatThrownBy(() -> factory.create(source(5))).extracting("status").isEqualTo(409);
        when(providers.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> factory.create(source(5))).extracting("status").isEqualTo(409);
        assertThat(requestBody).hasValue(null);
    }

    @Test void absolute_deadline_aborts_a_stalled_upstream() {
        holdResponse = new CountDownLatch(1);
        long start = System.nanoTime();
        assertThatThrownBy(() -> factory.create(source(1)).stream(List.of(new ConversationMessage("user", "q")))
                .collectList().block(Duration.ofSeconds(4)))
                .extracting("code").isEqualTo("MODEL_PROVIDER_TIMEOUT");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
    }

    @Test void does_not_follow_redirects_that_could_leak_authorization() {
        status = 302;
        assertThatThrownBy(() -> factory.create(source(5)).stream(List.of(new ConversationMessage("user", "q")))
                .collectList().block(Duration.ofSeconds(5)))
                .extracting("code").isEqualTo("MODEL_PROVIDER_UNAVAILABLE");
    }

    @Test void accepts_uppercase_scheme_already_accepted_by_connection_validation() {
        var data = source(5).configuration();
        var model = data.model();
        var upperCase = new ExecutionSource(9, 101, 2, ExecutionSource.Mode.PUBLISHED,
                new AgentSnapshotData(data.name(), data.description(), data.prologue(), data.tags(), data.systemPrompt(),
                        data.variables(), data.knowledgeIds(), new AgentSnapshotData.ModelConfiguration(
                        30, model.providerCode(), model.providerName(), model.providerIcon(),
                        model.baseUrl().replace("http:", "HTTP:"), model.protocolType(), model.authType(),
                        model.modelId(), model.temperature(), model.topP(), model.maxTokens(), 5),
                        data.retrievalTopK(), data.retrievalScoreThreshold()));
        var chunks = factory.create(upperCase).stream(List.of(new ConversationMessage("user", "q")))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(chunks).extracting(ModelChunk::text).contains("你好");
    }

    private void key(String key) {
        var encrypted = cipher.encrypt(9, 30, key);
        provider.setApiKeyCiphertext(encrypted.ciphertext()); provider.setApiKeyNonce(encrypted.nonce());
        provider.setApiKeyVersion(encrypted.keyVersion()); provider.setApiKeyLastFour(encrypted.lastFour());
    }

    private ExecutionSource source(int timeout) {
        var data = AgentPromptAssemblerTest.data("prompt", List.of());
        var model = data.model();
        return new ExecutionSource(9, 101, 2, ExecutionSource.Mode.PUBLISHED,
                new AgentSnapshotData(data.name(), data.description(), data.prologue(), data.tags(), data.systemPrompt(),
                        data.variables(), data.knowledgeIds(), new AgentSnapshotData.ModelConfiguration(
                        30, model.providerCode(), model.providerName(), model.providerIcon(),
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", model.protocolType(), model.authType(),
                        model.modelId(), model.temperature(), model.topP(), model.maxTokens(), timeout),
                        data.retrievalTopK(), data.retrievalScoreThreshold()));
    }
}
