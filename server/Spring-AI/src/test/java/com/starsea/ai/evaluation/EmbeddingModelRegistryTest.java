package com.starsea.ai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddingModelRegistryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EvaluationRepository repository = mock(EvaluationRepository.class);
    private final OllamaEmbeddingGateway gateway = mock(OllamaEmbeddingGateway.class);
    private final MockEnvironment environment = new MockEnvironment();
    private EmbeddingModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new EmbeddingModelRegistry(repository, gateway, environment, json);
        when(gateway.inspect(any(ObjectNode.class))).thenAnswer(invocation -> {
            ObjectNode value = ((ObjectNode) invocation.getArgument(0)).deepCopy();
            return value.put("dimensions", 2).put("digest", "a".repeat(64)).put("quantization", "Q8_0")
                    .put("ollamaVersion", "0.11.0").put("verifiedAt", "2026-09-04T00:00:00Z");
        });
        when(repository.save(eq("model"), anyString(), eq(7L), isNull(), anyInt(), any(ObjectNode.class)))
                .thenAnswer(invocation -> ((ObjectNode) invocation.getArgument(5)).deepCopy()
                        .put("id", (String) invocation.getArgument(1))
                        .put("revision", ((Integer) invocation.getArgument(4)) + 1));
    }

    @Test
    void tenant_configuration_preserves_explicit_runtime_options_and_rejects_ignored_fields() {
        ObjectNode input = command().put("keepAlive", "10m");
        input.putObject("options").put("num_ctx", 1024).put("num_thread", 4);
        ObjectNode saved = registry.save(7L, null, input);
        assertThat(saved.path("options")).isEqualTo(input.path("options"));
        assertThat(saved.path("keepAlive").asText()).isEqualTo("10m");
        input.with("options").put("truncate", true);
        assertStatus(() -> registry.save(7L, null, input), 400);
    }

    @Test
    void list_has_readonly_runtime_baseline_and_tenant_models_without_network() {
        environment.withProperty("spring.ai.ollama.base-url", "http://192.168.1.9:11434")
                .withProperty("spring.ai.ollama.embedding.model", "local/legacy:latest")
                .withProperty("spring.ai.ollama.embedding.options.model", "runtime/model:exact")
                .withProperty("spring.ai.vectorstore.pgvector.dimensions", "768");
        ObjectNode saved = command().put("id", UUID.randomUUID().toString()).put("revision", 2);
        when(repository.list("model", 7L, null)).thenReturn(List.of(saved));

        List<ObjectNode> models = registry.list(7L);

        assertThat(models).hasSize(2);
        ObjectNode current = models.get(0);
        assertThat(current.path("id").asText()).isEqualTo("current");
        assertThat(current.path("baseUrl").asText()).isEqualTo("http://192.168.1.9:11434");
        assertThat(current.path("modelName").asText()).isEqualTo("runtime/model:exact");
        assertThat(current.path("dimensions").asInt()).isEqualTo(768);
        assertThat(current.path("queryPrefix").asText()).isEmpty();
        assertThat(current.path("documentPrefix").asText()).isEmpty();
        assertThat(current.path("readOnly").asBoolean()).isTrue();
        assertThat(current.path("source").asText()).isEqualTo("CONFIG");
        assertThat(current.get("verifiedAt").isNull()).isTrue();
        assertThat(current.get("digest").isNull()).isTrue();
        assertThat(models.get(1)).isEqualTo(saved);
        verifyNoInteractions(gateway);
    }

    @Test
    void current_supports_legacy_model_property_and_spring_ai_defaults() {
        environment.withProperty("spring.ai.ollama.embedding.model", "quentinz/bge-base-zh-v1.5");
        assertThat(registry.get(7L, "current").path("modelName").asText())
                .isEqualTo("quentinz/bge-base-zh-v1.5:latest");
        environment.getPropertySources().remove("mockProperties");
        ObjectNode current = registry.get(7L, "current");
        assertThat(current.path("baseUrl").asText()).isEqualTo("http://localhost:11434");
        assertThat(current.path("modelName").asText()).isEqualTo("mxbai-embed-large:latest");
        assertThat(current.get("dimensions").isNull()).isTrue();
        verifyNoInteractions(repository, gateway);
    }

    @Test
    void current_cannot_be_updated_or_deleted_even_with_forged_flags() {
        assertStatus(() -> registry.save(7L, "current", command().put("readOnly", false)), 400);
        assertStatus(() -> registry.delete(7L, "current"), 400);
        verifyNoInteractions(repository, gateway);
    }

    @Test
    void current_freezes_bound_runtime_options_for_reproducible_encoding() {
        environment.withProperty("spring.ai.ollama.embedding.options.num-ctx", "512")
                .withProperty("spring.ai.ollama.embedding.options.num-thread", "4")
                .withProperty("spring.ai.ollama.embedding.options.keep-alive", "3m")
                .withProperty("spring.ai.ollama.embedding.options.truncate", "true");
        ObjectNode current = registry.get(7L, "current");
        assertThat(current.path("options").path("num_ctx").asInt()).isEqualTo(512);
        assertThat(current.path("options").path("num_thread").asInt()).isEqualTo(4);
        assertThat(current.path("options").has("model")).isFalse();
        assertThat(current.path("options").has("truncate")).isFalse();
        assertThat(current.path("keepAlive").asText()).isEqualTo("3m");
        verifyNoInteractions(repository, gateway);
    }

    @Test
    void test_returns_unsaved_verified_model_and_ignores_forged_identity() {
        ObjectNode input = command().put("id", "current").put("readOnly", true).put("source", "CONFIG")
                .put("digest", "forged").put("dimensions", 999).put("apiKey", "private-key");
        ObjectNode result = registry.test(input);
        assertThat(result.get("id").isNull()).isTrue();
        assertThat(result.path("revision").asInt()).isZero();
        assertThat(result.path("readOnly").asBoolean()).isFalse();
        assertThat(result.path("source").asText()).isEqualTo("TENANT");
        assertThat(result.path("digest").asText()).isEqualTo("a".repeat(64));
        assertThat(result.path("dimensions").asInt()).isEqualTo(2);
        assertThat(result.has("apiKey")).isFalse();
        assertThat(input.path("digest").asText()).isEqualTo("forged");
        verifyNoInteractions(repository);
    }

    @Test
    void create_saves_verified_config_in_tenant_scope_with_zero_expected_revision() {
        ObjectNode result = registry.save(7L, null, command().put("revision", 99));
        assertThat(UUID.fromString(result.path("id").asText())).isNotNull();
        assertThat(result.path("revision").asInt()).isEqualTo(1);
        assertThat(result.path("digest").asText()).isEqualTo("a".repeat(64));
        assertThat(result.path("queryPrefix").asText()).isEqualTo("检索: ");
        verify(repository).save(eq("model"), eq(result.path("id").asText()), eq(7L), isNull(), eq(0),
                any(ObjectNode.class));
    }

    @Test
    void get_and_delete_are_tenant_scoped_and_delete_uses_current_revision() {
        String id = UUID.randomUUID().toString();
        ObjectNode stored = command().put("id", id).put("revision", 3);
        when(repository.get("model", id, 7L, null, null)).thenReturn(stored);
        assertThat(registry.get(7L, id)).isEqualTo(stored);
        registry.delete(7L, id);
        verify(repository).delete("model", id, 7L, null, 3);
        verifyNoInteractions(gateway);
    }

    @Test
    void update_uses_explicit_revision_and_retains_snapshot_owned_by_repository() {
        String id = UUID.randomUUID().toString();
        ObjectNode previous = command().put("id", id).put("revision", 2).put("digest", "old");
        when(repository.get("model", id, 7L, null, null)).thenReturn(previous);
        ObjectNode result = registry.save(7L, id, command().put("revision", 2).put("displayName", "新版"));
        assertThat(result.path("revision").asInt()).isEqualTo(3);
        assertThat(result.path("displayName").asText()).isEqualTo("新版");
        assertThat(previous.path("digest").asText()).isEqualTo("old");
        verify(repository).save(eq("model"), eq(id), eq(7L), isNull(), eq(2), any(ObjectNode.class));
    }

    @Test
    void update_rejects_missing_or_stale_revision_before_network() {
        String id = UUID.randomUUID().toString();
        when(repository.get("model", id, 7L, null, null)).thenReturn(command().put("revision", 3));
        assertStatus(() -> registry.save(7L, id, command()), 400);
        assertStatus(() -> registry.save(7L, id, command().put("revision", 2)), 409);
        assertStatus(() -> registry.save(7L, id, command().put("revision", "3")), 400);
        verifyNoInteractions(gateway);
        verify(repository, never()).save(anyString(), anyString(), eq(7L), isNull(), anyInt(), any());
    }

    @Test
    void failed_verification_does_not_persist() {
        when(gateway.inspect(any())).thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "模型不可用"));
        assertStatus(() -> registry.save(7L, null, command()), 422);
        verifyNoInteractions(repository);
    }

    @Test
    void cross_tenant_missing_model_fails_before_testing_or_saving() {
        String id = UUID.randomUUID().toString();
        when(repository.get("model", id, 9L, null, null))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
        assertStatus(() -> registry.get(9L, id), 404);
        assertStatus(() -> registry.save(9L, id, command().put("revision", 1)), 404);
        assertStatus(() -> registry.delete(9L, id), 404);
        verifyNoInteractions(gateway);
    }

    @Test
    void rejects_invalid_command_fields_without_network() {
        assertStatus(() -> registry.test(null), 400);
        assertStatus(() -> registry.test(command().put("displayName", " ")), 400);
        assertStatus(() -> registry.test(command().put("queryPrefix", 123)), 400);
        assertStatus(() -> registry.get(7L, "not-an-id"), 400);
        verifyNoInteractions(gateway, repository);
    }

    private ObjectNode command() {
        return json.createObjectNode().put("displayName", "本地候选").put("baseUrl", "http://localhost:11434")
                .put("modelName", "quentinz/bge-base-zh-v1.5:latest").put("queryPrefix", "检索: ")
                .put("documentPrefix", "");
    }

    private void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }
}
