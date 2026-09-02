package com.starsea.ai.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.snapshot.AgentSnapshotData.ModelConfiguration;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.model.provider.EncryptedProviderSecret;
import com.starsea.ai.model.provider.ModelProviderSecretCipher;
import com.starsea.ai.model.provider.TenantModelProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Stateless OpenAI-compatible SSE adapter. No ChatMemory, advisors, request logging or credential cache. */
@Component
public class AgentModelClientFactory {
    private final TenantModelProviderMapper providers;
    private final ModelProviderSecretCipher cipher;
    private final ObjectMapper json;
    private final JdkClientHttpConnector connector = new JdkClientHttpConnector(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());

    public AgentModelClientFactory(TenantModelProviderMapper providers, ModelProviderSecretCipher cipher, ObjectMapper json) {
        this.providers = providers; this.cipher = cipher; this.json = json;
    }

    public Client create(ExecutionSource source) {
        AgentExecutionAccess.require(source);
        ModelConfiguration model = source.configuration().model();
        if (model == null) throw invalid("AGENT_MODEL_REQUIRED", "请先配置智能体模型");
        if (!"OPENAI_COMPATIBLE".equals(model.protocolType())) throw invalid("AGENT_MODEL_PROTOCOL_UNSUPPORTED", "暂不支持该模型协议");
        URI uri = completionsUri(model.baseUrl());
        TenantModelProvider provider = providers.selectOne(new LambdaQueryWrapper<TenantModelProvider>()
                .eq(TenantModelProvider::getId, model.providerConnectionId())
                .eq(TenantModelProvider::getTenantId, source.tenantId()));
        if (provider == null || !Objects.equals(provider.getId(), model.providerConnectionId())
                || !Objects.equals(provider.getTenantId(), source.tenantId())) {
            throw invalid("MODEL_PROVIDER_CONNECTION_NOT_FOUND", "厂商连接不存在，请重新配置");
        }
        if (!Objects.equals(model.authType(), provider.getAuthType())) {
            throw invalid("MODEL_PROVIDER_AUTH_CHANGED", "厂商认证方式已变更，请重新配置智能体");
        }
        WebClient.Builder builder = WebClient.builder().clientConnector(connector)
                .codecs(codecs -> {
                    codecs.defaultCodecs().maxInMemorySize(1024 * 1024);
                    codecs.defaultCodecs().enableLoggingRequestDetails(false);
                });
        if ("API_KEY".equals(provider.getAuthType())) {
            try {
                String key = cipher.decrypt(source.tenantId(), provider.getId(), new EncryptedProviderSecret(
                        provider.getApiKeyCiphertext(), provider.getApiKeyNonce(), provider.getApiKeyVersion(), provider.getApiKeyLastFour()));
                if (key == null || key.isBlank() || key.contains("\r") || key.contains("\n")) throw new IllegalArgumentException();
                builder.defaultHeaders(headers -> headers.setBearerAuth(key));
            } catch (RuntimeException ignored) {
                throw invalid("MODEL_PROVIDER_SECRET_UNAVAILABLE", "厂商密钥无法解密，请重新配置");
            }
        } else if (!"NONE".equals(provider.getAuthType())) {
            throw invalid("MODEL_PROVIDER_AUTH_UNSUPPORTED", "暂不支持该认证方式");
        }
        WebClient client = builder.build();
        return messages -> Flux.defer(() -> {
            AtomicBoolean finished = new AtomicBoolean();
            AtomicInteger responseCharacters = new AtomicInteger();
            Map<String, Object> body = Map.of("model", model.modelId(), "temperature", model.temperature(),
                    "top_p", model.topP(), "max_tokens", model.maxTokens(), "stream", true,
                    "stream_options", Map.of("include_usage", true), "messages", messages);
            Flux<ModelChunk> stream = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM).bodyValue(body)
                    .exchangeToFlux(response -> {
                        int status = response.statusCode().value();
                        if (status < 200 || status >= 300) {
                            // Release but never parse, retain or log an upstream error body.
                            return response.releaseBody().thenMany(Flux.error(httpError(status)));
                        }
                        if (!response.headers().contentType().map(MediaType.TEXT_EVENT_STREAM::isCompatibleWith).orElse(false)) {
                            return response.releaseBody().thenMany(Flux.error(invalidResponse()));
                        }
                        return response.bodyToFlux(String.class)
                                .takeUntil("[DONE]"::equals)
                                .map(payload -> {
                                    if ("[DONE]".equals(payload)) { finished.set(true); return new ModelChunk("", null, null); }
                                    if (responseCharacters.addAndGet(payload.length()) > 2_000_000) throw invalidResponse();
                                    return parse(payload, finished);
                                });
                    })
                    .concatWith(Flux.defer(() -> finished.get() ? Flux.empty()
                            : Flux.error(new UpstreamException("MODEL_PROVIDER_STREAM_INCOMPLETE", "模型响应中断，请重试"))));
            // Absolute deadline, not an idle-only timeout that resets forever on every token.
            return stream.takeUntilOther(Mono.delay(Duration.ofSeconds(model.timeoutSeconds()))
                            .flatMap(ignored -> Mono.error(new TimeoutException())))
                    .onErrorMap(error -> error instanceof UpstreamException ? error
                            : error instanceof TimeoutException ? new UpstreamException("MODEL_PROVIDER_TIMEOUT", "模型调用超时，请重试")
                            : new UpstreamException("MODEL_PROVIDER_UNAVAILABLE", "模型调用失败，请稍后重试"));
        });
    }

    private ModelChunk parse(String payload, AtomicBoolean finished) {
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject() || root.has("error") || !root.path("choices").isArray()) throw invalidResponse();
            String text = "";
            String reason = null;
            for (JsonNode choice : root.path("choices")) {
                if (choice.path("index").asInt(0) != 0) continue;
                JsonNode content = choice.path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull() && !content.isTextual()) throw invalidResponse();
                text = content.asText("");
                if (choice.path("finish_reason").isTextual()) {
                    String value = choice.path("finish_reason").asText();
                    reason = Set.of("stop", "length", "content_filter", "tool_calls", "function_call").contains(value) ? value : "other";
                    finished.set(true);
                }
                break;
            }
            ModelChunk.TokenUsage usage = root.path("usage").isObject() ? new ModelChunk.TokenUsage(
                    token(root.path("usage").path("prompt_tokens")), token(root.path("usage").path("completion_tokens")),
                    token(root.path("usage").path("total_tokens"))) : null;
            return new ModelChunk(text, usage, reason);
        } catch (Exception ignored) {
            throw invalidResponse();
        }
    }

    private Long token(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0 ? node.asLong() : null; }
    private URI completionsUri(String baseUrl) {
        try {
            URI base = URI.create(baseUrl);
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme())) || base.getHost() == null
                    || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException();
            String normalized = base.getScheme().toLowerCase(java.util.Locale.ROOT) + baseUrl.substring(base.getScheme().length());
            return URI.create(normalized.replaceAll("/+$", "") + "/chat/completions");
        } catch (RuntimeException ignored) { throw invalid("MODEL_PROVIDER_URL_INVALID", "厂商 URL 不合法"); }
    }
    private AgentWorkbenchException invalid(String code, String message) { return new AgentWorkbenchException(409, code, message); }
    private UpstreamException invalidResponse() { return new UpstreamException("MODEL_PROVIDER_RESPONSE_INVALID", "模型响应格式异常，请重试"); }
    private UpstreamException httpError(int status) {
        if (status == 401 || status == 403) return new UpstreamException("MODEL_PROVIDER_AUTH_FAILED", "厂商认证失败，请检查 API Key");
        if (status == 429) return new UpstreamException("MODEL_PROVIDER_RATE_LIMITED", "厂商请求过于频繁，请稍后重试");
        return new UpstreamException("MODEL_PROVIDER_UNAVAILABLE", "模型调用失败，请稍后重试");
    }

    @FunctionalInterface public interface Client { Flux<ModelChunk> stream(List<ConversationMessage> messages); }
    public static final class UpstreamException extends RuntimeException {
        private final String code;
        private UpstreamException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
