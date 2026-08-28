package com.fansea.ai.openapi.retrieval;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(schemaProperties(root, "RetrievalRequest"))
                .containsExactlyInAnyOrder("query", "retrieval_setting");
        assertThat(path(root, "components", "schemas", "RetrievalRequest", "additionalProperties"))
                .isEqualTo(false);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "additionalProperties"))
                .isEqualTo(false);
        assertThat(path(root, "components", "schemas", "RetrievalRequest", "properties", "query", "maxLength"))
                .isEqualTo(250);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "top_k", "minimum"))
                .isEqualTo(1);
        assertThat(path(root, "components", "schemas", "RetrievalSetting", "properties", "top_k", "maximum"))
                .isEqualTo(20);

        String guide = Files.readString(GUIDE);
        assertThat(guide).contains("cURL", "Java", "Python", "JavaScript", "Key rotation", "HTTP",
                "HTTPS", "400", "401", "403", "429", "503", "504", "knowledge_id", "is unsupported");
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
}
