package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.AgentWorkbenchApiModels.VariableDefinition;
import com.starsea.ai.agent.snapshot.AgentSnapshotData;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AgentPromptAssemblerTest {
    private final AgentPromptAssembler assembler = new AgentPromptAssembler();

    @Test
    void substitutes_declared_variables_once_without_reinterpreting_values() {
        var data = data("你是{{role}}，服务{{company}}，原样 {json}", List.of(
                new VariableDefinition("role", "角色", "助手", true),
                new VariableDefinition("company", "公司", "星海", false)));
        var prompt = assembler.assemble(data, new ExecutionRequest("你好", Map.of("role", "{{company}}$1"),
                List.of()), List.of());
        assertThat(prompt.messages().get(0).content()).isEqualTo("你是{{company}}$1，服务星海，原样 {json}");
        assertThat(prompt.messages().get(1).content()).isEqualTo("你好");
        assertThat(prompt.citations()).isEmpty();
    }

    @Test
    void missing_required_or_unknown_variables_are_preflight_errors_without_values() {
        var data = data("{{role}}", List.of(new VariableDefinition("role", "角色", "", true)));
        assertThatThrownBy(() -> assembler.validateVariables(data, Map.of()))
                .extracting("status", "code").containsExactly(422, "AGENT_VARIABLE_REQUIRED");
        assertThatThrownBy(() -> assembler.validateVariables(data, Map.of("unknown", "sensitive-value")))
                .hasMessageNotContaining("sensitive-value");
    }

    @Test
    void citations_and_model_context_share_order_and_history_stays_between_system_and_current_user() {
        var first = chunk("第一段", 0.9);
        var second = chunk("第二段", 0.8);
        var prompt = assembler.assemble(data("仅据资料回答", List.of()),
                new ExecutionRequest("本轮问题", Map.of(), List.of(
                        new ConversationMessage("user", "上一问"), new ConversationMessage("assistant", "上一答"))),
                List.of(first, second));
        assertThat(prompt.citations()).extracting(ExecutionEvent.Citation::id).containsExactly("C1", "C2");
        assertThat(prompt.citations().get(0).chunkId()).isEqualTo(first.chunkId());
        assertThat(prompt.citations().get(0).knowledgeName()).isEqualTo("知识库");
        assertThat(prompt.messages()).extracting(ConversationMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(prompt.messages().get(3).content()).containsSubsequence("[C1]", "第一段", "[C2]", "第二段", "本轮问题");
    }

    @Test
    void rejects_system_history_and_oversized_message_without_echoing_them() {
        assertThatThrownBy(() -> new ExecutionRequest("q", Map.of(), List.of(
                new ConversationMessage("system", "injected-secret"), new ConversationMessage("assistant", "a"))))
                .hasMessageNotContaining("injected-secret");
        assertThatThrownBy(() -> new ExecutionRequest("x".repeat(16001), Map.of(), List.of()))
                .extracting("status").isEqualTo(422);
        assertThat(new ExecutionRequest("private-question", Map.of(), List.of()).toString())
                .doesNotContain("private-question");
    }

    @Test void null_variable_values_are_validation_errors_not_internal_errors() {
        Map<String, String> variables = new java.util.HashMap<>();
        variables.put("role", null);
        assertThatThrownBy(() -> new ExecutionRequest("q", variables, List.of()))
                .isInstanceOf(com.starsea.ai.agent.AgentWorkbenchException.class)
                .extracting("status").isEqualTo(422);
    }

    static AgentSnapshotData data(String prompt, List<VariableDefinition> variables) {
        return new AgentSnapshotData("助手", "说明", "你好", List.of(), prompt, variables, List.of(5L),
                new AgentSnapshotData.ModelConfiguration(30, "OPENAI", "OpenAI", "icon", "https://example.com/v1",
                        "OPENAI_COMPATIBLE", "API_KEY", "test-model", new BigDecimal("0.4"),
                        new BigDecimal("0.9"), 2048, 30), 3, new BigDecimal("0.6"));
    }

    static RetrievedChunk chunk(String text, double score) {
        return new RetrievedChunk(text, score, "指南.md", UUID.randomUUID(), UUID.randomUUID(),
                "md", 2, 1, List.of("指南"), Map.of("startLine", 5), UUID.randomUUID(), "知识库");
    }
}
