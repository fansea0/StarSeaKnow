package com.starsea.ai.agent.debug;

import com.starsea.ai.agent.execution.*;
import com.starsea.ai.agent.snapshot.AgentSnapshotData;
import com.starsea.ai.auth.AuthContext;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DebugExecutionCoordinatorTest {
    private final DebugContextStore store = new DebugContextStore(new DebugContextProperties());
    private final DraftExecutionSourceLoader drafts = mock(DraftExecutionSourceLoader.class);
    private final AgentExecutionService execution = mock(AgentExecutionService.class);
    private final com.starsea.ai.mapper.AgentMapper agents = mock(com.starsea.ai.mapper.AgentMapper.class);
    private final DebugExecutionCoordinator coordinator = new DebugExecutionCoordinator(store, drafts, execution, agents);
    private final DebugContextStore.Owner owner = new DebugContextStore.Owner(9, 71);

    @BeforeEach void setup() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_admin", "jti"));
        when(agents.markDebugged(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(drafts.load(101)).thenReturn(new ExecutionSource(9, 101, 2, ExecutionSource.Mode.DRAFT,
                new AgentSnapshotData("助手", "", "", List.of(), "系统", List.of(), List.of(), null, 5, BigDecimal.ZERO)));
    }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void exports_actual_execution_sources_and_preserves_configuration_changes_without_failed_turns() {
        var clients = mock(AgentModelClientFactory.class);
        var chunk = new com.starsea.ai.openapi.retrieval.RetrievedChunk("完整资料".repeat(120), .88, "手册",
                UUID.randomUUID(), UUID.randomUUID(), "md", 1, 0, List.of(), Map.of());
        var realExecution = new AgentExecutionService(query -> List.of(chunk, chunk), new AgentPromptAssembler(), clients);
        var realCoordinator = new DebugExecutionCoordinator(store, drafts, realExecution, agents);
        when(clients.create(any())).thenReturn(messages -> Flux.just(new ModelChunk("答案[C1]", null, "stop")));
        UUID id = null;
        for (String modelId : List.of("model-a", "model-b")) {
            var config = new AgentSnapshotData.ModelConfiguration(7, "CUSTOM", "测试厂商", "icon",
                    "https://private-provider.example/v1", "OPENAI_COMPATIBLE", "API_KEY", modelId,
                    BigDecimal.ONE, BigDecimal.ONE, 2048, 60);
            when(drafts.load(101)).thenReturn(new ExecutionSource(9, 101, 2, ExecutionSource.Mode.DRAFT,
                    new AgentSnapshotData("助手", "", "", List.of(), "private-system-prompt", List.of(), List.of(5L), config, 5, BigDecimal.ZERO)));
            var events = realCoordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(id, "问题", Map.of()))
                    .collectList().block(Duration.ofSeconds(5));
            if (id == null) id = ((DebugExecutionCoordinator.ContextEvent) events.get(0).data()).debugContextId();
        }
        when(clients.create(any())).thenReturn(messages -> Flux.error(new IllegalStateException("private-upstream-error")));
        realCoordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(id, "失败问题", Map.of()))
                .collectList().block(Duration.ofSeconds(5));
        var agent = new com.starsea.ai.domain.Agent();
        agent.setId(101L); agent.setTenantId(9L);
        when(agents.selectOne(any())).thenReturn(agent);
        when(drafts.load(101)).thenThrow(new com.starsea.ai.agent.AgentWorkbenchException(
                422, "AGENT_MODEL_REQUIRED", "请选择模型"));
        var exported = realCoordinator.export(101, id);
        assertThat(exported.model()).extracting(DebugSessionExport.Model::modelId).containsExactly("model-a", "model-b");
        assertThat(exported.turns()).hasSize(2);
        assertThat(exported.turns().get(0).references()).hasSize(1);
        assertThat(exported.turns().get(0).references().get(0).content()).isEqualTo(chunk.content());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(exported).toString();
        assertThat(json).doesNotContain("private-provider", "private-system-prompt", "失败问题", "private-upstream-error");
        agent.setDeletedAt(java.time.OffsetDateTime.now());
        UUID contextId = id;
        assertThatThrownBy(() -> realCoordinator.export(101, contextId)).extracting("status").isEqualTo(404);
        agent.setDeletedAt(null); agent.setTenantId(10L);
        assertThatThrownBy(() -> realCoordinator.export(101, contextId)).extracting("status").isEqualTo(404);
    }

    @Test void first_request_emits_context_id_and_next_request_uses_only_server_history() {
        when(execution.execute(any(), any(), any())).thenReturn(answer("答一"), answer("答二"));
        var events = coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(null, "问一", Map.of()))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ExecutionEvent::type).containsExactly("context", "delta", "complete");
        verify(agents).markDebugged(eq(101L), eq(9L), eq(71L), any());
        UUID id = ((DebugExecutionCoordinator.ContextEvent) events.get(0).data()).debugContextId();
        coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(id, "问二", Map.of()))
                .collectList().block(Duration.ofSeconds(5));
        var requests = ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(execution, times(2)).execute(any(), requests.capture(), any());
        assertThat(requests.getAllValues().get(1).history()).extracting(ConversationMessage::content).containsExactly("问一", "答一");
        try (var lease = store.open(owner, 101, id)) {
            assertThat(lease.history()).extracting(ConversationMessage::content).containsExactly("问一", "答一", "问二", "答二");
        }
    }

    @Test void cancellation_and_stream_error_discard_partial_reply_and_release_generation_lock() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(execution.execute(any(), any(), any())).thenReturn(Flux.concat(
                Flux.just(new ExecutionEvent("delta", new ExecutionEvent.Delta("partial"))), Flux.<ExecutionEvent>never())
                .doOnCancel(() -> cancelled.set(true)));
        var events = coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(null, "question", Map.of()))
                .take(2).collectList().block(Duration.ofSeconds(5));
        UUID id = ((DebugExecutionCoordinator.ContextEvent) events.get(0).data()).debugContextId();
        assertThat(cancelled).isTrue();
        try (var lease = store.open(owner, 101, id)) { assertThat(lease.history()).isEmpty(); }
        when(execution.execute(any(), any(), any())).thenReturn(Flux.just(new ExecutionEvent("error", new ExecutionEvent.Failure("FAILED", "failed"))));
        coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(id, "retry", Map.of())).collectList().block();
        try (var lease = store.open(owner, 101, id)) { assertThat(lease.history()).isEmpty(); }
    }

    @Test void deleting_a_context_cancels_inflight_generation() {
        var lease = store.open(owner, 101, null); UUID id = lease.id(); lease.close();
        AtomicBoolean cancelled = new AtomicBoolean();
        when(execution.execute(any(), any(), any())).thenReturn(Flux.<ExecutionEvent>never().doOnCancel(() -> cancelled.set(true)));
        var running = coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(id, "question", Map.of())).subscribe();
        coordinator.delete(101, id);
        assertThat(cancelled).isTrue();
        running.dispose();
    }

    @Test void rejects_non_admin_and_oversized_input_before_allocating_a_context() {
        assertThatThrownBy(() -> coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(null, "q".repeat(16001), Map.of())))
                .extracting("status").isEqualTo(422);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_member", "jti"));
        assertThatThrownBy(() -> coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(null, "q", Map.of())))
                .extracting("status").isEqualTo(403);
        verifyNoInteractions(execution);
    }

    @Test void failed_preflight_releases_new_context_capacity_and_preserves_existing_context() {
        when(execution.execute(any(), any(), any())).thenThrow(new com.starsea.ai.agent.AgentWorkbenchException(
                409, "MODEL_PROVIDER_SECRET_UNAVAILABLE", "密钥不可用"));
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(null, "q", Map.of())))
                    .extracting("code").isEqualTo("MODEL_PROVIDER_SECRET_UNAVAILABLE");
        }
        var first = store.open(owner, 101, null); UUID id = first.id(); first.complete("old q", "old a"); first.close();
        assertThatThrownBy(() -> coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(id, "q", Map.of())))
                .extracting("code").isEqualTo("MODEL_PROVIDER_SECRET_UNAVAILABLE");
        try (var lease = store.open(owner, 101, id)) { assertThat(lease.history()).hasSize(2); }
    }

    @Test void oversized_generated_reply_is_cancelled_without_caching_a_partial_turn() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(execution.execute(any(), any(), any())).thenReturn(Flux.concat(
                Flux.just(new ExecutionEvent("delta", new ExecutionEvent.Delta("a".repeat(16001)))), Flux.<ExecutionEvent>never())
                .doOnCancel(() -> cancelled.set(true)));
        var events = coordinator.stream(101, new DebugExecutionCoordinator.DebugCommand(null, "question", Map.of()))
                .collectList().block(Duration.ofSeconds(5));
        UUID id = ((DebugExecutionCoordinator.ContextEvent) events.get(0).data()).debugContextId();
        assertThat(events).extracting(ExecutionEvent::type).containsExactly("context", "error");
        assertThat(cancelled).isTrue();
        try (var lease = store.open(owner, 101, id)) { assertThat(lease.history()).isEmpty(); }
    }

    private Flux<ExecutionEvent> answer(String text) {
        return Flux.just(new ExecutionEvent("delta", new ExecutionEvent.Delta(text)), new ExecutionEvent("complete", new ExecutionEvent.Complete("stop")));
    }
}
