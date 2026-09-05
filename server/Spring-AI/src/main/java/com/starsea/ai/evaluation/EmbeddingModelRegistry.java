package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.autoconfigure.ollama.OllamaConnectionProperties;
import org.springframework.ai.autoconfigure.ollama.OllamaEmbeddingProperties;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tenant model revisions plus a network-free, read-only view of the runtime baseline. */
@Service
public class EmbeddingModelRegistry {
    private final EvaluationRepository repository;
    private final OllamaEmbeddingGateway gateway;
    private final Environment environment;
    private final ObjectMapper json;

    public EmbeddingModelRegistry(EvaluationRepository repository, OllamaEmbeddingGateway gateway,
                                  Environment environment, ObjectMapper json) {
        this.repository = repository;
        this.gateway = gateway;
        this.environment = environment;
        this.json = json;
    }

    public List<ObjectNode> list(long tenantId) {
        List<ObjectNode> result = new ArrayList<>();
        result.add(current());
        for (ObjectNode model : repository.list("model", tenantId, null)) result.add(model.deepCopy());
        return result;
    }

    public ObjectNode get(long tenantId, String id) {
        if ("current".equals(id)) return current();
        validateId(id);
        return repository.get("model", id, tenantId, null, null).deepCopy();
    }

    public ObjectNode test(ObjectNode command) {
        return gateway.inspect(command(command));
    }

    public ObjectNode save(long tenantId, String id, ObjectNode command) {
        rejectCurrent(id);
        int expectedRevision = 0;
        if (id != null) {
            validateId(id);
            JsonNode revision = command == null ? null : command.get("revision");
            if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToInt() || revision.intValue() < 1) {
                throw badRequest("更新模型必须提供有效 revision");
            }
            expectedRevision = revision.intValue();
            ObjectNode previous = repository.get("model", id, tenantId, null, null);
            if (previous.path("revision").asInt() != expectedRevision) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "模型配置已更新，请重新加载后重试");
            }
        }
        ObjectNode verified = test(command);
        String modelId = id == null ? UUID.randomUUID().toString() : id;
        return repository.save("model", modelId, tenantId, null, expectedRevision, verified);
    }

    public void delete(long tenantId, String id) {
        rejectCurrent(id);
        ObjectNode model = get(tenantId, id);
        repository.delete("model", id, tenantId, null, model.path("revision").asInt());
    }

    private ObjectNode current() {
        // Use this project's Spring AI property binder, including the legacy model alias and defaults.
        Binder binder = Binder.get(environment);
        OllamaEmbeddingProperties embedding = binder.bind(OllamaEmbeddingProperties.CONFIG_PREFIX,
                Bindable.of(OllamaEmbeddingProperties.class)).orElseGet(OllamaEmbeddingProperties::new);
        OllamaConnectionProperties connection = binder.bind(OllamaConnectionProperties.CONFIG_PREFIX,
                Bindable.of(OllamaConnectionProperties.class)).orElseGet(OllamaConnectionProperties::new);
        ObjectNode current = json.createObjectNode().put("id", "current").put("revision", 0)
                .put("displayName", "当前知识库配置").put("baseUrl", connection.getBaseUrl())
                .put("modelName", OllamaEmbeddingGateway.modelName(embedding.getModel()))
                .put("queryPrefix", "").put("documentPrefix", "").put("source", "CONFIG").put("readOnly", true);
        // Freeze options as part of the runtime baseline; the gateway always disables truncation.
        current.set("options", json.valueToTree(OllamaOptions.filterNonSupportedFields(embedding.getOptions().toMap())));
        if (embedding.getOptions().getKeepAlive() != null) current.put("keepAlive", embedding.getOptions().getKeepAlive());
        Integer dimensions = environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", Integer.class);
        if (dimensions != null && dimensions > 0) current.put("dimensions", dimensions);
        else current.putNull("dimensions");
        for (String field : List.of("digest", "quantization", "ollamaVersion", "verifiedAt")) current.putNull(field);
        return current;
    }

    private ObjectNode command(ObjectNode command) {
        if (command == null) throw badRequest("模型配置不能为空");
        ObjectNode result = json.createObjectNode();
        for (String field : List.of("displayName", "baseUrl", "modelName")) {
            JsonNode value = command.get(field);
            if (value == null || !value.isTextual() || value.textValue().isBlank()) {
                throw badRequest("缺少有效的模型字段：" + field);
            }
            result.put(field, value.textValue().trim());
        }
        for (String field : List.of("queryPrefix", "documentPrefix")) {
            JsonNode value = command.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) throw badRequest("模型前缀必须是字符串");
            result.put(field, value == null || value.isNull() ? "" : value.textValue());
        }
        if (command.hasNonNull("options")) {
            if (!command.path("options").isObject()) throw badRequest("运行参数必须是 JSON 对象");
            command.path("options").fields().forEachRemaining(entry -> {
                if (List.of("model", "format", "keep_alive", "truncate").contains(entry.getKey()))
                    throw badRequest("运行参数不能覆盖模型标识、截断策略或保活设置");
                JsonNode value = entry.getValue();
                if (!(value.isBoolean() || value.isTextual() || (value.isNumber() && Double.isFinite(value.asDouble()))))
                    throw badRequest("运行参数值必须为字符串、布尔值或有限数字");
            });
            result.set("options", command.path("options").deepCopy());
        }
        if (command.hasNonNull("keepAlive")) {
            JsonNode value = command.path("keepAlive");
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 64)
                throw badRequest("模型保活时间必须为有效字符串");
            result.put("keepAlive", value.asText().strip());
        }
        result.putNull("id");
        return result.put("revision", 0).put("source", "TENANT").put("readOnly", false);
    }

    private static void rejectCurrent(String id) {
        if ("current".equals(id)) throw badRequest("当前知识库配置为只读，不能修改或删除");
    }

    private static void validateId(String id) {
        try {
            if (!UUID.fromString(id).toString().equalsIgnoreCase(id)) throw badRequest("模型 ID 无效");
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw badRequest("模型 ID 无效");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
