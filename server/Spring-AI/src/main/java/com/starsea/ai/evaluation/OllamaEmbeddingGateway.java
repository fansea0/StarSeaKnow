package com.starsea.ai.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Isolated Ollama evaluation client: no authentication, redirects, downloads, or truncation. */
@Component
public class OllamaEmbeddingGateway {
    private final ObjectMapper json;
    private final HttpClient client;
    private final Duration requestTimeout;

    @Autowired
    public OllamaEmbeddingGateway(ObjectMapper json) {
        this(json, Duration.ofSeconds(5), Duration.ofSeconds(120));
    }

    OllamaEmbeddingGateway(ObjectMapper json, Duration connectTimeout, Duration requestTimeout) {
        this.json = json;
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Inspect one installed, exact model identity and measure its embedding dimensions. */
    public ObjectNode inspect(ObjectNode config) {
        ObjectNode verified = normalized(config);
        String model = verified.path("modelName").asText();
        JsonNode models = request(verified, "/api/tags", null).path("models");
        if (!models.isArray()) throw upstream("Ollama 模型列表格式无效");
        JsonNode matched = null;
        for (JsonNode candidate : models) {
            if (model.equals(candidate.path("name").asText())) {
                if (matched != null) throw upstream("Ollama 模型标识不唯一");
                matched = candidate;
            }
        }
        if (matched == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Ollama 未安装指定名称和标签的模型，请先在服务端安装");
        }
        if (matched.hasNonNull("model") && !model.equals(matched.path("model").asText())) {
            throw upstream("Ollama 模型标识不一致");
        }
        String digest = digest(matched.get("digest"), HttpStatus.BAD_GATEWAY);
        if (verified.hasNonNull("digest")
                && !digest.equals(digest(verified.get("digest"), HttpStatus.BAD_REQUEST))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ollama 模型版本已变化，请重新验证配置");
        }
        JsonNode show = request(verified, "/api/show", json.createObjectNode().put("model", model));
        boolean embedding = false;
        JsonNode capabilities = show.path("capabilities");
        if (capabilities.isArray()) {
            for (JsonNode capability : capabilities) {
                if (capability.isTextual() && "embedding".equals(capability.textValue())) embedding = true;
            }
        }
        if (!embedding) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "指定模型未声明 embedding 能力");
        }
        JsonNode version = request(verified, "/api/version", null).get("version");
        if (version == null || !version.isTextual() || version.textValue().isBlank()) {
            throw upstream("Ollama 未返回有效版本信息");
        }
        int measured = embed(verified, List.of("向量验证"), false).get(0).length;
        JsonNode modelInfo = show.path("model_info");
        String architecture = modelInfo.path("general.architecture").asText();
        JsonNode declared = modelInfo.get(architecture + ".embedding_length");
        if (declared != null && (!declared.isIntegralNumber() || !declared.canConvertToInt()
                || declared.intValue() != measured)) {
            throw upstream("Ollama 声明维度与实际向量维度不一致");
        }
        JsonNode quantization = show.path("details").get("quantization_level");
        if (quantization == null || quantization.isNull()) {
            quantization = matched.path("details").get("quantization_level");
        }
        verified.put("digest", digest).put("dimensions", measured).put("ollamaVersion", version.textValue())
                .put("verifiedAt", Instant.now().toString());
        if (quantization != null && quantization.isTextual() && !quantization.textValue().isBlank()) {
            verified.put("quantization", quantization.textValue());
        } else {
            verified.putNull("quantization");
        }
        return verified;
    }

    /** Encode complete strings with the explicitly configured role prefix. */
    public List<double[]> embed(ObjectNode config, List<String> inputs, boolean query) {
        ObjectNode normalized = normalized(config);
        if (inputs == null || inputs.stream().anyMatch(value -> value == null)) {
            throw badRequest("入模文本必须是字符串列表且不能包含 null");
        }
        if (inputs.isEmpty()) return List.of();
        ObjectNode body = json.createObjectNode().put("model", normalized.path("modelName").asText())
                .put("truncate", false);
        if (normalized.hasNonNull("options")) {
            if (!(normalized.get("options") instanceof ObjectNode options)) throw badRequest("模型运行参数必须是对象");
            ObjectNode runtimeOptions = options.deepCopy();
            runtimeOptions.remove(List.of("model", "format", "keep_alive", "truncate"));
            body.set("options", runtimeOptions);
        }
        if (normalized.hasNonNull("keepAlive")) {
            body.put("keep_alive", requiredText(normalized, "keepAlive"));
        }
        ArrayNode input = body.putArray("input");
        String prefix = normalized.path(query ? "queryPrefix" : "documentPrefix").asText();
        for (String value : inputs) input.add(prefix + value);
        JsonNode response = request(normalized, "/api/embed", body);
        if (!response.path("model").isTextual() || !normalized.path("modelName").asText()
                .equals(response.path("model").textValue())) {
            throw upstream("Ollama 返回的向量模型标识缺失或不一致");
        }
        JsonNode vectors = response.path("embeddings");
        if (!vectors.isArray() || vectors.size() != inputs.size()) {
            throw upstream("Ollama 返回的向量数量与输入数量不一致");
        }
        int dimensions = normalized.path("dimensions").asInt(0);
        List<double[]> result = new ArrayList<>(inputs.size());
        for (JsonNode vector : vectors) {
            if (!vector.isArray() || vector.isEmpty()) throw upstream("Ollama 返回空向量或无效向量");
            if (dimensions == 0) dimensions = vector.size();
            if (vector.size() != dimensions) throw upstream("Ollama 返回的向量维度不一致");
            double[] values = new double[dimensions];
            boolean nonzero = false;
            for (int i = 0; i < dimensions; i++) {
                JsonNode value = vector.get(i);
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                    throw upstream("Ollama 返回的向量包含无效数值");
                }
                values[i] = value.doubleValue();
                nonzero |= values[i] != 0;
            }
            if (!nonzero) throw upstream("Ollama 返回零向量");
            result.add(values);
        }
        return result;
    }

    /** Independently encode the same document-role input twice; keep the cosine sign. */
    public double selfSimilarity(ObjectNode config, String text) {
        if (text == null) throw badRequest("自相似检查文本不能为空");
        double[] first = embed(config, List.of(text), false).get(0);
        double[] second = embed(config, List.of(text), false).get(0);
        if (first.length != second.length) throw upstream("Ollama 两次编码的向量维度不一致");
        double firstScale = 0;
        double secondScale = 0;
        for (int i = 0; i < first.length; i++) {
            firstScale = Math.max(firstScale, Math.abs(first[i]));
            secondScale = Math.max(secondScale, Math.abs(second[i]));
        }
        double dot = 0;
        double firstNorm = 0;
        double secondNorm = 0;
        for (int i = 0; i < first.length; i++) {
            double left = first[i] / firstScale;
            double right = second[i] / secondScale;
            dot += left * right;
            firstNorm += left * left;
            secondNorm += right * right;
        }
        return Math.max(-1, Math.min(1, dot / Math.sqrt(firstNorm) / Math.sqrt(secondNorm)));
    }

    private ObjectNode normalized(ObjectNode config) {
        if (config == null) throw badRequest("模型配置不能为空");
        ObjectNode result = config.deepCopy();
        result.put("baseUrl", baseUrl(requiredText(config, "baseUrl")));
        result.put("modelName", modelName(requiredText(config, "modelName")));
        for (String field : List.of("queryPrefix", "documentPrefix")) {
            JsonNode prefix = config.get(field);
            if (prefix != null && !prefix.isNull() && !prefix.isTextual()) {
                throw badRequest("模型前缀必须是字符串");
            }
            result.put(field, prefix == null || prefix.isNull() ? "" : prefix.textValue());
        }
        JsonNode dimensions = config.get("dimensions");
        if (dimensions != null && !dimensions.isNull() && (!dimensions.isIntegralNumber()
                || !dimensions.canConvertToInt() || dimensions.intValue() <= 0)) {
            throw badRequest("模型维度必须是正整数");
        }
        if (config.hasNonNull("digest")) digest(config.get("digest"), HttpStatus.BAD_REQUEST);
        return result;
    }

    static String modelName(String value) {
        String name = value.trim();
        if (name.isEmpty() || name.length() > 512 || !name.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw badRequest("模型名称或标签无效");
        }
        int slash = name.lastIndexOf('/');
        String leaf = name.substring(slash + 1);
        if (!leaf.matches("[A-Za-z0-9][A-Za-z0-9._-]*(?::[A-Za-z0-9_][A-Za-z0-9._-]*)?")) {
            throw badRequest("模型名称或标签无效");
        }
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length - 1; i++) {
            String pattern = i == 0 ? "[A-Za-z0-9][A-Za-z0-9._-]*(?::[0-9]+)?" : "[A-Za-z0-9][A-Za-z0-9._-]*";
            if (!parts[i].matches(pattern)) throw badRequest("模型名称或标签无效");
        }
        return leaf.contains(":") ? name : name + ":latest";
    }

    private static String baseUrl(String value) {
        try {
            URI uri = new URI(value.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535
                    || uri.getPort() < -1) {
                throw badRequest("Ollama 地址必须是无凭据、查询参数和片段的 HTTP(S) 地址");
            }
            String address = uri.toASCIIString();
            while (address.endsWith("/")) address = address.substring(0, address.length() - 1);
            return address;
        } catch (URISyntaxException ex) {
            throw badRequest("Ollama 地址格式无效");
        }
    }

    private static String requiredText(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw badRequest("缺少有效的模型字段：" + field);
        }
        return node.textValue();
    }

    private static String digest(JsonNode value, HttpStatus status) {
        if (value == null || !value.isTextual() || !value.textValue().matches("(?:sha256:)?[a-fA-F0-9]{64}")) {
            throw new ResponseStatusException(status, "模型 digest 缺失或无效");
        }
        String digest = value.textValue().toLowerCase(Locale.ROOT);
        return digest.startsWith("sha256:") ? digest.substring(7) : digest;
    }

    private JsonNode request(ObjectNode config, String path, ObjectNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.path("baseUrl").asText() + path))
                .timeout(requestTimeout).header("Accept", "application/json");
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                HttpStatus mapped = status == 400 || status == 413 || status == 422
                        ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY;
                throw new ResponseStatusException(mapped,
                        "Ollama " + path + " 请求失败（HTTP " + status + "），请检查模型、服务和文本长度；未截断或重试");
            }
            JsonNode value = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
            if (value == null || !value.isObject() || value.hasNonNull("error")) {
                throw upstream("Ollama " + path + " 返回无效响应");
            }
            return value;
        } catch (HttpTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Ollama 请求超时");
        } catch (JsonProcessingException ex) {
            throw upstream("Ollama 返回无效 JSON");
        } catch (IOException ex) {
            throw upstream("无法连接 Ollama 服务或读取响应");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ollama 请求已中断");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException upstream(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
