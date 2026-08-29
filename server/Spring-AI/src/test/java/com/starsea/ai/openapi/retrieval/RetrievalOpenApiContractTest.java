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
    private static final Pattern INVALID_JSON_POINTER_ESCAPE = Pattern.compile("~(?![01])");
    private static final String NEGATED_AUXILIARY =
            "(?:cannot|can\\s+not|can['’]t|does\\s+not|doesn['’]t|do\\s+not|don['’]t|"
                    + "did\\s+not|didn['’]t|could\\s+not|couldn['’]t|will\\s+not|won['’]t|"
                    + "would\\s+not|wouldn['’]t)";
    private static final String NOMINAL_NEGATION =
            "(?:(?:no|not|never)\\s+|without(?:\\s+an?)?\\s+)";
    private static final Pattern ROTATION_OVERLAP_CLAIM = Pattern.compile(
            "(?i)\\b(?:key\\s+)?rotation\\b[^.!?]{0,120}?"
                    + "\\b(?<claim>(?:" + NEGATED_AUXILIARY + "\\s+)?"
                    + "(?:guarantee(?:s|d)?|provid(?:e|es|ed)|support(?:s|ed)?|offer(?:s|ed)?|"
                    + "configur(?:e|es|ed)))\\b"
                    + "[^.!?]{0,60}?\\b(?:an?\\s+)?overlap\\s+period\\b");
    private static final Pattern CONFIGURED_OVERLAP_CLAIM = Pattern.compile(
            "(?i)\\b(?<claim>(?:" + NOMINAL_NEGATION + ")?configured)\\s+overlap\\s+period\\b");
    private static final Pattern OVERLAP_GUARANTEE_CLAIM = Pattern.compile(
            "(?i)\\b(?<claim>(?:" + NOMINAL_NEGATION + ")?overlap\\s+guarantee)\\b");
    private static final Pattern RATE_LIMIT_WINDOW_CLAIM = Pattern.compile(
            "(?i)\\b(?:the\\s+)?(?:rate-limit\\s+)?(?:headers?|x-ratelimit-[a-z-]+)\\b[^.!?]{0,80}?"
                    + "\\b(?<claim>(?:" + NEGATED_AUXILIARY + "\\s+)?"
                    + "(?:reflect(?:s|ed)?|use(?:s|d)?|represent(?:s|ed)?|describe(?:s|d)?|"
                    + "show(?:s|ed)?|report(?:s|ed)?|are|is)(?:\\s+not)?)\\b"
                    + "[^.!?]{0,40}?\\b(?:an?\\s+|the\\s+)?(?:fixed|current)"
                    + "(?:[- ]window|\\s+rate-limit\\s+window)\\b");
    private static final Pattern LOCAL_NEGATION = Pattern.compile(
            "(?i)\\b(?:no|not|never|without|cannot)\\b|n['’]t\\b");

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
        assertRequestIdParameter(root);
        assertExecutableReferencesResolve(root);
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
        assertStrictObjectSchema(root, "RetrievalRequest", List.of("query"), "query", "retrieval_setting");
        assertSchemaPropertyType(root, "RetrievalRequest", "query", "string");
        assertReference(root, "#/components/schemas/RetrievalSetting", "components", "schemas",
                "RetrievalRequest", "properties", "retrieval_setting");
        assertStrictObjectSchema(root, "RetrievalSetting", List.of(), "top_k", "score_threshold");
        assertSchemaPropertyType(root, "RetrievalSetting", "top_k", "integer");
        assertSchemaPropertyType(root, "RetrievalSetting", "score_threshold", "number");
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
        assertStrictObjectSchema(root, "RetrievalResponse", List.of("records"), "records");
        assertSchemaPropertyType(root, "RetrievalResponse", "records", "array");
        assertStrictObjectSchema(root, "RetrievalRecord", List.of("content", "score", "title", "metadata"),
                "content", "score", "title", "metadata");
        assertSchemaPropertyType(root, "RetrievalRecord", "content", "string");
        assertSchemaPropertyType(root, "RetrievalRecord", "score", "number");
        assertThat(path(root, "components", "schemas", "RetrievalRecord", "properties", "score", "minimum"))
                .isEqualTo(0);
        assertThat(path(root, "components", "schemas", "RetrievalRecord", "properties", "score", "maximum"))
                .isEqualTo(1);
        assertSchemaPropertyType(root, "RetrievalRecord", "title", "string");
        assertStrictObjectSchema(root, "RetrievalMetadata",
                List.of("document_id", "chunk_id", "file_type", "page_number", "chunk_index"),
                "document_id", "chunk_id", "file_type", "page_number", "chunk_index");
        assertNullable(root, "document_id", "string");
        assertNullable(root, "chunk_id", "string");
        assertThat(path(root, "components", "schemas", "RetrievalMetadata", "properties", "document_id", "format"))
                .isEqualTo("uuid");
        assertThat(path(root, "components", "schemas", "RetrievalMetadata", "properties", "chunk_id", "format"))
                .isEqualTo("uuid");
        assertNullable(root, "file_type", "string");
        assertNullable(root, "page_number", "integer");
        assertNullable(root, "chunk_index", "integer");
        assertErrorResponseSchema(root);
        assertHeaderComponentSchemas(root);
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

    private static void assertRequestIdParameter(Map<String, Object> root) {
        List<Object> parameters = list(path(root, "paths", "/openapi/v1/retrieval", "post", "parameters"));
        assertThat(parameters).hasSize(1);
        assertExactMap(parameters.get(0), "$ref", "#/components/parameters/RequestId");

        Map<String, Object> requestId = map(path(root, "components", "parameters", "RequestId"));
        assertThat(requestId).containsEntry("name", "X-Request-ID")
                .containsEntry("in", "header")
                .containsEntry("required", false);
        assertExactMap(requestId.get("schema"),
                "type", "string", "pattern", "^[A-Za-z0-9._-]{1,64}$");
    }

    private static void assertStrictObjectSchema(Map<String, Object> root, String schemaName,
                                                 List<String> required, String... properties) {
        Map<String, Object> schema = map(path(root, "components", "schemas", schemaName));
        assertStrictObjectSchema(schema, required, properties);
    }

    private static void assertStrictObjectSchema(Map<String, Object> schema, List<String> required,
                                                 String... properties) {
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(mapKeys(schema.get("properties"))).containsExactlyInAnyOrder(properties);
        assertThat(requiredFields(schema)).containsExactlyInAnyOrderElementsOf(required);
    }

    private static Set<String> requiredFields(Map<String, Object> schema) {
        Object required = schema.get("required");
        if (required == null) {
            return Set.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        for (Object value : list(required)) {
            assertThat(value).isInstanceOf(String.class);
            assertThat(fields.add((String) value)).as("required fields must not contain duplicates").isTrue();
        }
        return fields;
    }

    private static void assertSchemaPropertyType(Map<String, Object> root, String schemaName,
                                                 String property, String type) {
        assertThat(path(root, "components", "schemas", schemaName, "properties", property, "type"))
                .isEqualTo(type);
    }

    private static void assertErrorResponseSchema(Map<String, Object> root) {
        assertStrictObjectSchema(root, "ErrorResponse", List.of("request_id", "error"), "request_id", "error");
        assertSchemaPropertyType(root, "ErrorResponse", "request_id", "string");
        Map<String, Object> error = map(path(root, "components", "schemas", "ErrorResponse", "properties", "error"));
        assertStrictObjectSchema(error, List.of("code", "message", "param"), "code", "message", "param");
        assertThat(path(error, "properties", "code", "type")).isEqualTo("string");
        assertThat(path(error, "properties", "message", "type")).isEqualTo("string");
        assertNullableType(path(error, "properties", "param", "type"), "string");
    }

    private static void assertHeaderComponentSchemas(Map<String, Object> root) {
        assertExactMap(path(root, "components", "headers", "RequestId", "schema"), "type", "string");
        assertExactMap(path(root, "components", "headers", "RateLimitLimit", "schema"),
                "type", "integer", "minimum", 1);
        assertExactMap(path(root, "components", "headers", "RateLimitRemaining", "schema"),
                "type", "integer", "minimum", 0);
        assertExactMap(path(root, "components", "headers", "RateLimitReset", "schema"),
                "type", "integer", "format", "int64");
        assertExactMap(path(root, "components", "headers", "RetryAfter", "schema"),
                "type", "integer", "minimum", 1);
        assertExactMap(path(root, "components", "headers", "NoStore", "schema"),
                "type", "string", "const", "no-store");
    }

    private static void assertExactMap(Object value, Object... entries) {
        Map<String, Object> actual = map(value);
        assertThat(entries.length).isEven();
        assertThat(actual).hasSize(entries.length / 2);
        for (int index = 0; index < entries.length; index += 2) {
            assertThat(actual).containsEntry((String) entries[index], entries[index + 1]);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertNullable(Map<String, Object> root, String field, String scalarType) {
        Object type = path(root, "components", "schemas", "RetrievalMetadata", "properties", field, "type");
        assertNullableType(type, scalarType);
    }

    @SuppressWarnings("unchecked")
    private static void assertNullableType(Object type, String scalarType) {
        assertThat(type).isInstanceOf(List.class);
        assertThat((List<String>) type).containsExactlyInAnyOrder(scalarType, "null");
    }

    private static void assertExecutableReferencesResolve(Map<String, Object> root) {
        Map<String, Object> post = map(path(root, "paths", "/openapi/v1/retrieval", "post"));
        Set<String> expandedReferences = new HashSet<>();
        scanReferences(root, post.get("parameters"), expandedReferences);
        scanReferences(root, post.get("requestBody"), expandedReferences);
        scanReferences(root, post.get("responses"), expandedReferences);
    }

    @SuppressWarnings("unchecked")
    private static void scanReferences(Map<String, Object> root, Object node, Set<String> expandedReferences) {
        if (node instanceof Map<?, ?> nodeMap) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) nodeMap).entrySet()) {
                if ("$ref".equals(entry.getKey())) {
                    assertThat(entry.getValue()).as("$ref value").isInstanceOf(String.class);
                    String reference = (String) entry.getValue();
                    Object target = resolveRequiredLocalReference(root, reference);
                    if (expandedReferences.add(reference)) {
                        scanReferences(root, target, expandedReferences);
                    }
                } else {
                    scanReferences(root, entry.getValue(), expandedReferences);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                scanReferences(root, item, expandedReferences);
            }
        }
    }

    private static Object resolveRequiredLocalReference(Map<String, Object> root, String reference) {
        assertThat(reference).as("executable $ref").startsWith("#/");
        assertThat(reference).as("executable $ref").doesNotContainPattern(INVALID_JSON_POINTER_ESCAPE);
        Object current = root;
        for (String segment : reference.substring(2).split("/", -1)) {
            assertThat(current).as("parent of local reference %s", reference).isInstanceOf(Map.class);
            current = ((Map<?, ?>) current).get(segment.replace("~1", "/").replace("~0", "~"));
            assertThat(current).as("resolved local reference %s", reference).isNotNull();
        }
        return current;
    }

    private static void assertNoForbiddenPostRequestField(Map<String, Object> root, String field) {
        Object post = path(root, "paths", "/openapi/v1/retrieval", "post");
        assertThat(post).isInstanceOf(Map.class);
        Set<String> visitedReferences = new HashSet<>();
        scanExecutableRequestNode(root, path(map(post), "parameters"), field, visitedReferences, null, false);
        scanExecutableRequestNode(root, path(map(post), "requestBody"), field, visitedReferences, null, false);
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
        String normalized = document.replaceAll("\\s+", " ");
        assertEveryClaimIsNegated(normalized, ROTATION_OVERLAP_CLAIM);
        assertEveryClaimIsNegated(normalized, CONFIGURED_OVERLAP_CLAIM);
        assertEveryClaimIsNegated(normalized, OVERLAP_GUARANTEE_CLAIM);
        assertEveryClaimIsNegated(normalized, RATE_LIMIT_WINDOW_CLAIM);
    }

    private static void assertEveryClaimIsNegated(String document, Pattern claimPattern) {
        Matcher matcher = claimPattern.matcher(document);
        while (matcher.find()) {
            assertThat(isNegatedClaim(matcher))
                    .as("obsolete affirmative claim: %s", matcher.group())
                    .isTrue();
        }
    }

    private static boolean isNegatedClaim(Matcher claim) {
        return LOCAL_NEGATION.matcher(claim.group("claim")).find();
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
                                                  Set<String> visitedReferences, String parentKey,
                                                  boolean insideExample) {
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
                        scanExecutableRequestNode(root, resolveRequiredLocalReference(root, reference), field,
                                visitedReferences, "$ref", false);
                    }
                } else {
                    boolean childInsideExample = insideExample
                            || "example".equals(key)
                            || "value".equals(key)
                            || ("examples".equals(key) && value instanceof List<?>);
                    scanExecutableRequestNode(root, value, field, visitedReferences, key, childInsideExample);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String string) {
                    assertThat(string).isNotEqualTo(field);
                }
                scanExecutableRequestNode(root, item, field, visitedReferences, parentKey, insideExample);
            }
        } else if (node instanceof String string) {
            if ("$ref".equals(parentKey)) {
                assertThat(string).doesNotContain("/" + field);
            }
            if ("name".equals(parentKey)) {
                assertThat(string).isNotEqualTo(field);
            }
            if (insideExample) {
                assertStringExampleHasNoForbiddenField(string, field);
            }
        }
    }

    private static void assertStringExampleHasNoForbiddenField(String example, String field) {
        Object structured = parseStructuredExample(example);
        if (structured instanceof Map<?, ?> || structured instanceof List<?>) {
            assertExampleValueHasNoForbiddenField(structured, field);
            return;
        }
        Pattern key = Pattern.compile(
                "(?m)(?<![A-Za-z0-9_])['\"\\\\]*" + Pattern.quote(field) + "['\"\\\\]*\\s*:");
        assertThat(key.matcher(example).find()).as("forbidden field %s in string example", field).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static void assertExampleValueHasNoForbiddenField(Object value, String field) {
        if (value instanceof Map<?, ?> valueMap) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) valueMap).entrySet()) {
                assertThat(entry.getKey()).as("field in structured example").isNotEqualTo(field);
                assertExampleValueHasNoForbiddenField(entry.getValue(), field);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                assertExampleValueHasNoForbiddenField(item, field);
            }
        } else if (value instanceof String string) {
            assertStringExampleHasNoForbiddenField(string, field);
        }
    }

    private static Object parseStructuredExample(String example) {
        try {
            Object parsed = JSON.readValue(example, Object.class);
            if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
                return parsed;
            }
        } catch (IOException ignored) {
            // Try YAML next because OpenAPI examples may use either representation.
        }
        try {
            Object parsed = new Yaml().load(example);
            if (parsed instanceof Map<?, ?> || parsed instanceof List<?>) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Fall through to formatting-independent key recognition.
        }
        return null;
    }

    private static boolean isNarrativeKey(String key) {
        return "description".equals(key) || "summary".equals(key) || "title".equals(key);
    }

    private static List<FencedCodeBlock> fencedCodeBlocks(String guide) {
        List<FencedCodeBlock> blocks = new ArrayList<>();
        String[] lines = guide.split("\\R", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            FenceOpening opening = fenceOpening(lines[lineIndex]);
            if (opening == null) {
                continue;
            }
            StringBuilder content = new StringBuilder();
            boolean closed = false;
            boolean firstContentLine = true;
            for (int contentLine = lineIndex + 1; contentLine < lines.length; contentLine++) {
                if (isFenceClosing(lines[contentLine], opening)) {
                    blocks.add(new FencedCodeBlock(fenceLanguage(opening.info()), content.toString()));
                    lineIndex = contentLine;
                    closed = true;
                    break;
                }
                if (!firstContentLine) {
                    content.append('\n');
                }
                content.append(lines[contentLine]);
                firstContentLine = false;
            }
            if (!closed) {
                blocks.add(new FencedCodeBlock(fenceLanguage(opening.info()), content.toString()));
                break;
            }
        }
        return blocks;
    }

    private static FenceOpening fenceOpening(String line) {
        int indent = leadingSpaces(line);
        if (indent > 3 || indent == line.length()) {
            return null;
        }
        char marker = line.charAt(indent);
        if (marker != '`' && marker != '~') {
            return null;
        }
        int markerEnd = indent;
        while (markerEnd < line.length() && line.charAt(markerEnd) == marker) {
            markerEnd++;
        }
        int markerLength = markerEnd - indent;
        if (markerLength < 3) {
            return null;
        }
        String info = line.substring(markerEnd);
        if (marker == '`' && info.indexOf('`') >= 0) {
            return null;
        }
        return new FenceOpening(marker, markerLength, info);
    }

    private static boolean isFenceClosing(String line, FenceOpening opening) {
        int indent = leadingSpaces(line);
        if (indent > 3 || indent == line.length() || line.charAt(indent) != opening.marker()) {
            return false;
        }
        int markerEnd = indent;
        while (markerEnd < line.length() && line.charAt(markerEnd) == opening.marker()) {
            markerEnd++;
        }
        if (markerEnd - indent < opening.length()) {
            return false;
        }
        return line.substring(markerEnd).isBlank();
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private static String fenceLanguage(String info) {
        String trimmed = info.trim();
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isWhitespace(trimmed.charAt(index))) {
                return trimmed.substring(0, index);
            }
        }
        return trimmed;
    }

    private record FencedCodeBlock(String language, String content) {
    }

    private record FenceOpening(char marker, int length, String info) {
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
