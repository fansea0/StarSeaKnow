package com.fansea.ai.openapi.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalOpenApiContractTest {

    private static final Path OPENAPI = Path.of("../../docs/openapi/retrieval-api.yaml");
    private static final Path GUIDE = Path.of("../../docs/openapi/retrieval-api.md");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("```[^\\r\\n]*\\R(.*?)\\R```", Pattern.DOTALL);

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
        assertReference(root, "#/components/schemas/RetrievalResponse", "paths", "/openapi/v1/retrieval",
                "post", "responses", "200", "content", "application/json", "schema");
        assertReference(root, "#/components/schemas/RetrievalRecord", "components", "schemas",
                "RetrievalResponse", "properties", "records", "items");
        assertReference(root, "#/components/schemas/RetrievalMetadata", "components", "schemas",
                "RetrievalRecord", "properties", "metadata");
        assertReference(root, "#/components/responses/RateLimited", "paths", "/openapi/v1/retrieval",
                "post", "responses", "429");
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
        assertHeaderReference(root, "X-Request-ID", "RequestId", "paths", "/openapi/v1/retrieval", "post",
                "responses", "200", "headers");
        assertHeaderReference(root, "X-RateLimit-Limit", "RateLimitLimit", "paths", "/openapi/v1/retrieval",
                "post", "responses", "200", "headers");
        assertHeaderReference(root, "X-RateLimit-Remaining", "RateLimitRemaining", "paths", "/openapi/v1/retrieval",
                "post", "responses", "200", "headers");
        assertHeaderReference(root, "X-RateLimit-Reset", "RateLimitReset", "paths", "/openapi/v1/retrieval",
                "post", "responses", "200", "headers");
        assertHeaderReference(root, "Cache-Control", "NoStore", "paths", "/openapi/v1/retrieval", "post",
                "responses", "200", "headers");
        assertThat(mapKeys(path(root, "components", "responses", "RateLimited", "headers")))
                .containsExactlyInAnyOrder("X-Request-ID", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                        "X-RateLimit-Reset", "Retry-After");
        assertHeaderReference(root, "X-Request-ID", "RequestId", "components", "responses", "RateLimited", "headers");
        assertHeaderReference(root, "X-RateLimit-Limit", "RateLimitLimit", "components", "responses", "RateLimited",
                "headers");
        assertHeaderReference(root, "X-RateLimit-Remaining", "RateLimitRemaining", "components", "responses",
                "RateLimited", "headers");
        assertHeaderReference(root, "X-RateLimit-Reset", "RateLimitReset", "components", "responses", "RateLimited",
                "headers");
        assertHeaderReference(root, "Retry-After", "RetryAfter", "components", "responses", "RateLimited", "headers");
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
        assertRequestExample(root);
        assertSuccessExample(root);

        String guide = Files.readString(GUIDE);
        assertThat(guide).contains("cURL", "Java", "Python", "JavaScript", "Key rotation", "HTTP",
                "HTTPS", "400", "401", "403", "429", "503", "504", "knowledge_id", "is unsupported");
        assertThat(guide).contains("immediately revoked", "No overlap is guaranteed", "atomically replace",
                "token bucket", "per-minute refill rate", "available whole tokens", "burst capacity",
                "refills to burst capacity", "Retry-After");
        assertNoObsoleteProse(guide);
        assertNoObsoleteProse(Files.readString(OPENAPI));
        assertMarkdownRequestBlocksAreStrict(guide);
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

    private static void assertReference(Map<String, Object> root, String expected, String... segments) {
        String[] referencePath = new String[segments.length + 1];
        System.arraycopy(segments, 0, referencePath, 0, segments.length);
        referencePath[segments.length] = "$ref";
        assertThat(path(root, referencePath)).isEqualTo(expected);
    }

    private static void assertHeaderReference(Map<String, Object> root, String header, String target, String... parent) {
        String[] headerPath = new String[parent.length + 1];
        System.arraycopy(parent, 0, headerPath, 0, parent.length);
        headerPath[parent.length] = header;
        assertReference(root, "#/components/headers/" + target, headerPath);
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

    private static void assertRequestExample(Map<String, Object> root) {
        Object example = path(root, "paths", "/openapi/v1/retrieval", "post", "requestBody", "content",
                "application/json", "examples", "refund-materials", "value");
        assertThat(mapKeys(example)).containsExactlyInAnyOrder("query", "retrieval_setting");
        assertThat(path(map(example), "query")).isEqualTo("退款需要哪些材料？");
        assertThat(mapKeys(path(map(example), "retrieval_setting")))
                .containsExactlyInAnyOrder("top_k", "score_threshold");
        assertThat(path(map(example), "retrieval_setting", "top_k")).isEqualTo(5);
        assertThat(path(map(example), "retrieval_setting", "score_threshold")).isEqualTo(0.5);
        assertNoForbiddenField(example);
    }

    private static void assertSuccessExample(Map<String, Object> root) {
        Object example = path(root, "paths", "/openapi/v1/retrieval", "post", "responses", "200", "content",
                "application/json", "examples", "matched-record", "value");
        assertThat(mapKeys(example)).containsExactly("records");
        List<Object> records = list(path(map(example), "records"));
        assertThat(records).hasSize(1);
        Map<String, Object> record = map(records.get(0));
        assertThat(record).containsEntry("content", "退款申请需要提交订单号、付款凭证以及退款原因。")
                .containsEntry("score", 0.92).containsEntry("title", "售后服务说明.pdf");
        assertThat(record.keySet()).containsExactlyInAnyOrder("content", "score", "title", "metadata");
        assertThat(map(record.get("metadata"))).containsEntry("document_id", "65c28997-ad56-4812-8a50-e00e804b45cc")
                .containsEntry("chunk_id", "a3c05b1e-c40b-4eb6-8a81-26f9874f6f7b")
                .containsEntry("file_type", "pdf").containsEntry("page_number", 3).containsEntry("chunk_index", 12);
        assertThat(map(record.get("metadata")).keySet())
                .containsExactlyInAnyOrder("document_id", "chunk_id", "file_type", "page_number", "chunk_index");
        assertNoForbiddenField(example);
    }

    private static void assertNoObsoleteProse(String document) {
        assertThat(document).doesNotContain("overlap period", "overlap guarantee", "current rate-limit window",
                "current window", "fixed window", "fixed-window");
    }

    private static void assertMarkdownRequestBlocksAreStrict(String guide) throws IOException {
        Matcher matcher = FENCED_CODE_BLOCK.matcher(guide);
        int requestBlockCount = 0;
        while (matcher.find()) {
            String block = matcher.group(1);
            if (!block.contains("query")) {
                continue;
            }
            requestBlockCount++;
            assertThat(block).doesNotContain("knowledge_id", "metadata_condition");
            if (block.stripLeading().startsWith("{")) {
                JsonNode request = JSON.readTree(block);
                assertThat(request.isObject()).isTrue();
                assertThat(jsonObjectFields(request)).containsExactlyInAnyOrder("query", "retrieval_setting");
                assertThat(request.path("query").asText()).isEqualTo("退款需要哪些材料？");
                assertThat(jsonObjectFields(request.path("retrieval_setting")))
                        .containsExactlyInAnyOrder("top_k", "score_threshold");
            }
        }
        assertThat(requestBlockCount).isGreaterThanOrEqualTo(5);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }

    private static void assertNoForbiddenField(Object value) {
        assertThat(containsKey(value, "knowledge_id")).isFalse();
        assertThat(containsKey(value, "metadata_condition")).isFalse();
    }

    private static Set<String> jsonObjectFields(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
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
