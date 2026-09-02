package com.starsea.ai.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class OpenAiCompatibleConnectionVerifier implements ModelProviderConnectionVerifier {

    private static final int DEFAULT_CONTEXT_WINDOW = 128_000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_DISCOVERED_MODELS = 1000;

    private final HttpClient client;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleConnectionVerifier() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public VerifiedConnection verify(String baseUrl, String authType, String apiKey) {
        URI modelsUri = modelsUri(baseUrl);
        HttpRequest.Builder request = HttpRequest.newBuilder(modelsUri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET();
        if ("API_KEY".equals(authType)) {
            if (!StringUtils.hasText(apiKey)) {
                throw invalid("API Key 不能为空");
            }
            request.header("Authorization", "Bearer " + apiKey.trim());
        }

        try {
            HttpResponse<InputStream> response = client.send(
                    request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status == 401 || status == 403) {
                    throw new ModelProviderException(
                            422, "MODEL_PROVIDER_AUTH_FAILED", "厂商认证失败，请检查 API Key");
                }
                if (status == 429) {
                    throw new ModelProviderException(
                            429, "MODEL_PROVIDER_RATE_LIMITED", "厂商请求过于频繁，请稍后重试");
                }
                if (status < 200 || status >= 300) {
                    throw new ModelProviderException(
                            502, "MODEL_PROVIDER_UNAVAILABLE", "厂商连接失败");
                }
                byte[] payload = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (payload.length > MAX_RESPONSE_BYTES) {
                    throw new ModelProviderException(
                            502, "MODEL_PROVIDER_RESPONSE_INVALID", "厂商模型列表响应过大");
                }
                return new VerifiedConnection(parseModels(payload));
            }
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ModelProviderException(504, "MODEL_PROVIDER_TIMEOUT", "厂商连接超时");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelProviderException(503, "MODEL_PROVIDER_INTERRUPTED", "厂商连接已取消");
        } catch (IOException exception) {
            throw new ModelProviderException(502, "MODEL_PROVIDER_UNAVAILABLE", "厂商连接失败");
        }
    }

    private List<ModelSuggestion> parseModels(byte[] payload) {
        try {
            JsonNode data = objectMapper.readTree(payload).path("data");
            if (!data.isArray()) {
                throw invalidResponse();
            }
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode item : data) {
                String id = item.path("id").asText("").trim();
                if (!id.isEmpty()) {
                    ids.add(id);
                    if (ids.size() > MAX_DISCOVERED_MODELS) {
                        throw invalidResponse();
                    }
                }
            }
            List<ModelSuggestion> models = new ArrayList<>(ids.size());
            for (String id : ids) {
                models.add(new ModelSuggestion(id, id, DEFAULT_CONTEXT_WINDOW));
            }
            return List.copyOf(models);
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (IOException exception) {
            throw invalidResponse();
        }
    }

    private URI modelsUri(String baseUrl) {
        try {
            URI base = URI.create(baseUrl == null ? "" : baseUrl.trim());
            String scheme = base.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw invalidUrl();
            }
            String normalized = base.toString().replaceAll("/+$", "");
            return URI.create(normalized + "/models");
        } catch (IllegalArgumentException exception) {
            throw invalidUrl();
        }
    }

    private ModelProviderException invalidUrl() {
        return new ModelProviderException(422, "MODEL_PROVIDER_URL_INVALID", "Base URL 必须是有效的 HTTP(S) 地址");
    }

    private ModelProviderException invalidResponse() {
        return new ModelProviderException(502, "MODEL_PROVIDER_RESPONSE_INVALID", "厂商模型列表响应格式无效");
    }

    private ModelProviderException invalid(String message) {
        return new ModelProviderException(422, "MODEL_PROVIDER_INVALID", message);
    }
}
