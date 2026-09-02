package com.starsea.ai.agent.execution;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.service.RagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentExecutionServiceTest {
    private final AgentModelClientFactory factory = mock(AgentModelClientFactory.class);
    private final ExecutionSource source = new ExecutionSource(9, 101, 2, ExecutionSource.Mode.DRAFT,
            AgentPromptAssemblerTest.data("系统", List.of()));

    @BeforeEach void auth() { AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_admin", "jti")); }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test
    void retrieves_only_latest_message_with_configured_parameters_and_preserves_tenant_after_request_thread_exits() {
        AtomicReference<List<ConversationMessage>> sent = new AtomicReference<>();
        RagService rag = query -> {
            assertThat(query.query()).isEqualTo("本轮问题");
            assertThat(query.knowledgeIds()).isEqualTo(Set.of(5L));
            assertThat(query.topK()).isEqualTo(3);
            assertThat(query.scoreThreshold()).isEqualTo(0.6);
            assertThat(AuthContext.current().getTenantId()).isEqualTo(9L);
            return List.of(AgentPromptAssemblerTest.chunk("资料", 0.8));
        };
        when(factory.create(source)).thenReturn(messages -> {
            sent.set(messages);
            return Flux.just(new ModelChunk("回答", null, null),
                    new ModelChunk("", new ModelChunk.TokenUsage(10L, 2L, 12L), "stop"));
        });
        var service = new AgentExecutionService(rag, new AgentPromptAssembler(), factory);
        var stream = service.execute(source, new ExecutionRequest("本轮问题", Map.of(), List.of()));
        AuthContext.clear();
        var events = stream.collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ExecutionEvent::type).containsExactly("retrieval", "delta", "usage", "complete");
        assertThat(sent.get().get(1).content()).contains("[C1]", "资料", "本轮问题");
        assertThat(((ExecutionEvent.Usage) events.get(2).data()).totalTokens()).isEqualTo(12L);
        assertThat(AuthContext.current()).isNull();
    }

    @Test
    void no_results_is_normal_and_upstream_failure_is_sanitized_without_complete() {
        when(factory.create(source)).thenReturn(messages -> Flux.error(new IllegalStateException("sk-secret upstream body")));
        var service = new AgentExecutionService(query -> List.of(), new AgentPromptAssembler(), factory);
        var events = service.execute(source, new ExecutionRequest("question", Map.of(), List.of()))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(events).extracting(ExecutionEvent::type).containsExactly("retrieval", "error");
        assertThat(events.toString()).doesNotContain("sk-secret", "upstream body");
        assertThat(((ExecutionEvent.Retrieval) events.get(0).data()).citations()).isEmpty();
    }

    @Test
    void cross_tenant_or_member_draft_execution_fails_before_stream_is_built() {
        var service = new AgentExecutionService(query -> List.of(), new AgentPromptAssembler(), factory);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 8L, "tenant_admin", "jti"));
        assertThatThrownBy(() -> service.execute(source, new ExecutionRequest("question", Map.of(), List.of())))
                .extracting("status").isEqualTo(404);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_member", "jti"));
        assertThatThrownBy(() -> service.execute(source, new ExecutionRequest("question", Map.of(), List.of())))
                .extracting("status").isEqualTo(403);
        verifyNoInteractions(factory);
    }

    @Test
    void client_cancellation_cancels_model_subscription() {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(factory.create(source)).thenReturn(messages -> Flux.concat(
                Flux.just(new ModelChunk("first", null, null)), Flux.<ModelChunk>never())
                .doOnCancel(() -> cancelled.set(true)));
        var service = new AgentExecutionService(query -> List.of(), new AgentPromptAssembler(), factory);
        service.execute(source, new ExecutionRequest("question", Map.of(), List.of()))
                .take(2).collectList().block(Duration.ofSeconds(5));
        assertThat(cancelled).isTrue();
    }
}
