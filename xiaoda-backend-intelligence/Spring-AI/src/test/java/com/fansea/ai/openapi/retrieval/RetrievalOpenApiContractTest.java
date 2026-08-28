package com.fansea.ai.openapi.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("(?ms)^```([^\\r\\n]*)\\R(.*?)^```\\s*$", Pattern.MULTILINE);
    private static final Pattern OVERLAP_PERIOD_CLAIM = Pattern.compile(
            "(?i)\\b(?:provides|supports|configures)\\s+(?:an?\\s+)?overlap period\\b");
    private static final Pattern CONFIGURED_OVERLAP_PERIOD_CLAIM = Pattern.compile(
            "(?i)\\bconfigured overlap period\\b");
    private static final Pattern RATE_LIMIT_WINDOW_CLAIM = Pattern.compile(
            "(?i)\\b(?:headers?|x-ratelimit-[a-z-]+)\\s+(?:describe|show|report|are)\\s+(?:the\\s+)?"
                    + "(?:current|fixed)\\s+(?:rate-limit\\s+)?window\\b");
    private static final Pattern UNNEGATED_RATE_LIMIT_WINDOW = Pattern.compile(
            "(?i)(?<!not )(?:current|fixed) rate-limit window");

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
        assertErrorResponseReference(root, "400", "BadRequest");
        assertErrorResponseReference(root, "401", "Unauthorized");
        assertErrorResponseReference(root, "403", "Forbidden");
        assertErrorResponseReference(root, "413", "RequestTooLarge");
        assertErrorResponseReference(root, "415", "UnsupportedMediaType");
        assertErrorResponseReference(root, "500", "InternalError");
        assertErrorResponseReference(root, "503", "RetrievalUnavailable");
        assertErrorResponseReference(root, "504", "RetrievalTimeout");
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
        assertReference(root, "#/components/schemas/ErrorResponse", "components", "responses", "RateLimited",
                "content", "application/json", "schema");
        assertHeaderReference(root, "X-Request-ID", "RequestId", "components", "responses", "ErrorResponse",
                "headers");
        assertReference(root, "#/components/schemas/ErrorResponse", "components", "responses", "ErrorResponse",
                "content", "application/json", "schema");
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
        assertNoForbiddenPostRequestField(root, "knowledge_id");
        assertNoForbiddenPostRequestField(root, "metadata_condition");
        assertRequestExample(root);
        assertSuccessExample(root);

        String guide = Files.readString(GUIDE);
        assertThat(guide).contains("cURL", "Java", "Python", "JavaScript", "Key rotation", "HTTP",
                "HTTPS", "400", "401", "403", "429", "503", "504", "knowledge_id", "is unsupported");
        assertThat(guide).contains("immediately revoked", "No overlap is guaranteed", "atomically replace",
                "token bucket", "per-minute refill rate", "available whole tokens", "burst capacity",
                "refills to burst capacity", "Retry-After");
        assertNoObsoleteClaims(guide);
        assertNoObsoleteClaims(Files.readString(OPENAPI));
        assertFencedExamplesAreStrict(guide);
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

    private static void assertErrorResponseReference(Map<String, Object> root, String status, String component) {
        assertReference(root, "#/components/responses/" + component, "paths", "/openapi/v1/retrieval", "post",
                "responses", status);
        assertReference(root, "#/components/responses/ErrorResponse", "components", "responses", component);
    }

    @SuppressWarnings("unchecked")
    private static void assertNullable(Map<String, Object> root, String field, String scalarType) {
        Object type = path(root, "components", "schemas", "RetrievalMetadata", "properties", field, "type");
        assertThat(type).isInstanceOf(List.class);
        assertThat((List<String>) type).containsExactlyInAnyOrder(scalarType, "null");
    }

    private static void assertNoForbiddenPostRequestField(Map<String, Object> root, String field) {
        Object post = path(root, "paths", "/openapi/v1/retrieval", "post");
        assertThat(post).isInstanceOf(Map.class);
        Set<String> visitedReferences = new HashSet<>();
        scanExecutableRequestNode(root, path(map(post), "parameters"), field, visitedReferences, null);
        scanExecutableRequestNode(root, path(map(post), "requestBody"), field, visitedReferences, null);
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

    private static void assertNoObsoleteClaims(String document) {
        assertThat(OVERLAP_PERIOD_CLAIM.matcher(document).find()).isFalse();
        assertThat(CONFIGURED_OVERLAP_PERIOD_CLAIM.matcher(document).find()).isFalse();
        assertThat(RATE_LIMIT_WINDOW_CLAIM.matcher(document).find()).isFalse();
        assertThat(UNNEGATED_RATE_LIMIT_WINDOW.matcher(document).find()).isFalse();
    }

    private static void assertFencedExamplesAreStrict(String guide) throws IOException {
        List<FencedCodeBlock> blocks = fencedCodeBlocks(guide);
        assertThat(blocks).isNotEmpty();
        for (FencedCodeBlock block : blocks) {
            assertThat(block.content()).doesNotContain("knowledge_id", "metadata_condition");
        }

        int requestExamples = 0;
        int responseExamples = 0;
        int errorExamples = 0;
        for (FencedCodeBlock block : blocks) {
            if (!"json".equalsIgnoreCase(block.language())) {
                continue;
            }
            JsonNode payload = JSON.readTree(block.content());
            if (payload.has("query")) {
                assertThat(jsonObjectFields(payload)).containsExactlyInAnyOrder("query", "retrieval_setting");
                assertThat(payload.path("query").asText()).isEqualTo("退款需要哪些材料？");
                assertThat(jsonObjectFields(payload.path("retrieval_setting")))
                        .containsExactlyInAnyOrder("top_k", "score_threshold");
                requestExamples++;
            } else if (payload.has("records")) {
                assertThat(jsonObjectFields(payload)).containsExactly("records");
                assertThat(payload.path("records").isArray()).isTrue();
                assertThat(payload.path("records").size()).isEqualTo(1);
                JsonNode record = payload.path("records").get(0);
                assertThat(jsonObjectFields(record)).containsExactlyInAnyOrder("content", "score", "title", "metadata");
                assertThat(jsonObjectFields(record.path("metadata"))).containsExactlyInAnyOrder(
                        "document_id", "chunk_id", "file_type", "page_number", "chunk_index");
                responseExamples++;
            } else if (payload.has("request_id")) {
                assertThat(jsonObjectFields(payload)).containsExactlyInAnyOrder("request_id", "error");
                assertThat(jsonObjectFields(payload.path("error"))).containsExactlyInAnyOrder("code", "message", "param");
                errorExamples++;
            } else {
                throw new AssertionError("Unexpected JSON fenced example");
            }
        }
        assertThat(requestExamples).isEqualTo(1);
        assertThat(responseExamples).isEqualTo(1);
        assertThat(errorExamples).isEqualTo(1);
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

    @SuppressWarnings("unchecked")
    private static void scanExecutableRequestNode(Map<String, Object> root, Object node, String field,
                                                  Set<String> visitedReferences, String parentKey) {
        if (node instanceof Map<?, ?> nodeMap) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) nodeMap).entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (isNarrativeKey(key)) {
                    continue;
                }
                assertThat(key).isNotEqualTo(field);
                if ("$ref".equals(key) && value instanceof String reference) {
                    assertThat(reference).doesNotContain("/" + field);
                    if (visitedReferences.add(reference)) {
                        scanExecutableRequestNode(root, resolveLocalReference(root, reference), field,
                                visitedReferences, "$ref");
                    }
                } else if ("example".equals(key) && value instanceof String example) {
                    assertThat(example).doesNotContain("\"" + field + "\":", "'" + field + "':");
                } else {
                    scanExecutableRequestNode(root, value, field, visitedReferences, key);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String string) {
                    assertThat(string).isNotEqualTo(field);
                }
                scanExecutableRequestNode(root, item, field, visitedReferences, parentKey);
            }
        } else if (node instanceof String string) {
            if ("$ref".equals(parentKey)) {
                assertThat(string).doesNotContain("/" + field);
            }
            if ("name".equals(parentKey)) {
                assertThat(string).isNotEqualTo(field);
            }
            if ("example".equals(parentKey) || "value".equals(parentKey)) {
                assertThat(string).doesNotContain("\"" + field + "\":", "'" + field + "':");
            }
        }
    }

    private static boolean isNarrativeKey(String key) {
        return "description".equals(key) || "summary".equals(key) || "title".equals(key);
    }

    private static Object resolveLocalReference(Map<String, Object> root, String reference) {
        if (!reference.startsWith("#/")) {
            return null;
        }
        Object current = root;
        for (String segment : reference.substring(2).split("/")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment.replace("~1", "/").replace("~0", "~"));
        }
        return current;
    }

    private static List<FencedCodeBlock> fencedCodeBlocks(String guide) {
        List<FencedCodeBlock> blocks = new ArrayList<>();
        Matcher matcher = FENCED_CODE_BLOCK.matcher(guide);
        while (matcher.find()) {
            blocks.add(new FencedCodeBlock(matcher.group(1).trim(), matcher.group(2)));
        }
        return blocks;
    }

    private record FencedCodeBlock(String language, String content) {
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
