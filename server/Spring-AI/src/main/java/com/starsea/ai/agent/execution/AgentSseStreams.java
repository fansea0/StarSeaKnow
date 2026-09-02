package com.starsea.ai.agent.execution;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;

/** Bridges Reactor cancellation to servlet disconnect/timeout without logging conversation content. */
public final class AgentSseStreams {
    private AgentSseStreams() { }

    public static SseEmitter open(Flux<ExecutionEvent> events) {
        // Agent model timeout is at most 600s; allow a small margin for retrieval/serialization.
        SseEmitter emitter = new SseEmitter(610_000L);
        Disposable.Swap subscription = Disposables.swap();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> { subscription.dispose(); emitter.complete(); });
        emitter.onError(ignored -> subscription.dispose());
        subscription.update(events.subscribe(event -> {
            try { emitter.send(SseEmitter.event().name(event.type()).data(event.data())); }
            catch (Exception disconnected) { subscription.dispose(); emitter.complete(); }
        }, ignored -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        new ExecutionEvent.Failure("AGENT_EXECUTION_FAILED", "智能体执行失败，请重试")));
            } catch (Exception disconnected) { /* Already disconnected; only cancel and release resources. */ }
            finally { subscription.dispose(); emitter.complete(); }
        }, emitter::complete));
        return emitter;
    }
}
