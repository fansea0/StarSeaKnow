package com.fansea.ai.openapi.retrieval;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalOpenApiContractTest {

    private static final Path OPENAPI = Path.of("../../docs/openapi/retrieval-api.yaml");
    private static final Path GUIDE = Path.of("../../docs/openapi/retrieval-api.md");

    @Test
    void publishedContractDescribesTheStrictRetrievalApi() throws IOException {
        Map<String, Object> root;
        try (var reader = Files.newBufferedReader(OPENAPI)) {
            root = new Yaml().load(reader);
        }

        assertThat(root.get("openapi")).isEqualTo("3.1.0");
        assertThat(path(root, "paths", "/openapi/v1/retrieval", "post")).isNotNull();
        assertThat(path(root, "components", "securitySchemes", "RagApiKey", "scheme"))
                .isEqualTo("bearer");
        assertThat(mapKeys(path(root, "paths", "/openapi/v1/retrieval", "post", "responses")))
                .containsExactlyInAnyOrder("200", "400", "401", "403", "413", "415", "429", "500", "503", "504");
        assertThat(schemaProperties(root, "RetrievalRequest"))
                .containsExactlyInAnyOrder("query", "retrieval_setting");
        assertThat(path(root, "components", "schemas", "RetrievalRequest", "additionalProperties"))
                .isEqualTo(false);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "additionalProperties"))
                .isEqualTo(false);
        assertThat(path(root, "components", "schemas", "RetrievalRequest", "properties", "query", "maxLength"))
                .isEqualTo(250);
        assertThat(path(root, "components", "schemas", "RetrievalRequest", "properties", "query", "minLength"))
                .isEqualTo(1);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "top_k", "minimum"))
                .isEqualTo(1);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "top_k", "maximum"))
                .isEqualTo(20);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "top_k", "default"))
                .isEqualTo(5);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "score_threshold", "minimum"))
                .isEqualTo(0);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "score_threshold", "maximum"))
                .isEqualTo(1);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "score_threshold", "default"))
                .isEqualTo(0);
        assertThat(schemaProperties(root, "RetrievalResponse")).containsExactly("records");
        assertThat(schemaProperties(root, "RetrievalRecord"))
                .containsExactlyInAnyOrder("content", "score", "title", "metadata");
        assertThat(schemaProperties(root, "RetrievalMetadata"))
                .containsExactlyInAnyOrder("document_id", "chunk_id", "file_type", "page_number", "chunk_index");
        assertNullable(root, "document_id", "string");
        assertNullable(root, "chunk_id", "string");
        assertNullable(root, "file_type", "string");
        assertNullable(root, "page_number", "integer");
        assertNullable(root, "chunk_index", "integer");
        assertThat(mapKeys(path(root, "paths", "/openapi/v1/retrieval", "post", "responses", "200", "headers")))
                .containsExactlyInAnyOrder("X-Request-ID", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                        "X-RateLimit-Reset", "Cache-Control");
        assertThat(mapKeys(path(root, "components", "responses", "RateLimited", "headers")))
                .containsExactlyInAnyOrder("X-Request-ID", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                        "X-RateLimit-Reset", "Retry-After");
        assertThat(String.valueOf(path(root, "components", "headers", "RateLimitLimit", "description")))
                .contains("per-minute refill rate");
        assertThat(String.valueOf(path(root, "components", "headers", "RateLimitRemaining", "description")))
                .contains("available whole tokens", "burst capacity");
        assertThat(String.valueOf(path(root, "components", "headers", "RateLimitReset", "description")))
                .contains("refills to burst capacity");
        assertThat(String.valueOf(path(root, "components", "responses", "Unauthorized", "description")))
                .contains("Invalid, expired, and revoked credentials use authentication_failed");
        assertThat(String.valueOf(path(root, "components", "responses", "Forbidden", "description")))
                .contains("Disabled credentials return 403");
        assertNoUnsupportedRequestField(root, "knowledge_id");
        assertNoUnsupportedRequestField(root, "metadata_condition");

        String guide = Files.readString(GUIDE);
        assertThat(guide).contains("cURL", "Java", "Python", "JavaScript", "Key rotation", "HTTP",
                "HTTPS", "400", "401", "403", "429", "503", "504", "knowledge_id", "is unsupported");
        assertThat(guide).contains("immediately revoked", "no overlap", "atomically replace",
                "token bucket", "per-minute refill rate", "available whole tokens", "burst capacity",
                "refills to burst capacity", "Retry-After");
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> root, String... segments) {
        Object current = root;
        for (String segment : segments) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> schemaProperties(Map<String, Object> root, String schemaName) {
        Object properties = path(root, "components", "schemas", schemaName, "properties");
        assertThat(properties).isInstanceOf(Map.class);
        return ((Map<String, Object>) properties).keySet();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> mapKeys(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return ((Map<String, Object>) value).keySet();
    }

    @SuppressWarnings("unchecked")
    private static void assertNullable(Map<String, Object> root, String field, String scalarType) {
        Object type = path(root, "components", "schemas", "RetrievalMetadata", "properties", field, "type");
        assertThat(type).isInstanceOf(List.class);
        assertThat((List<String>) type).containsExactlyInAnyOrder(scalarType, "null");
    }

    private static void assertNoUnsupportedRequestField(Map<String, Object> root, String field) {
        assertThat(schemaProperties(root, "RetrievalRequest")).doesNotContain(field);
        assertThat(containsKey(path(root, "components", "schemas", "RetrievalRequest"), field)).isFalse();
        assertThat(containsKey(path(root, "components", "schemas", "RetrievalSetting"), field)).isFalse();
        assertThat(path(root, "paths", "/openapi/v1/retrieval", "post", "requestBody", "content",
                "application/json", "examples")).isInstanceOf(Map.class);
        assertThat(containsKey(path(root, "paths", "/openapi/v1/retrieval", "post", "requestBody", "content",
                "application/json", "examples"), field)).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static boolean containsKey(Object value, String target) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) {
                if (target.equals(entry.getKey()) || containsKey(entry.getValue(), target)) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (containsKey(item, target)) {
                    return true;
                }
            }
        }
        return false;
    }
}
