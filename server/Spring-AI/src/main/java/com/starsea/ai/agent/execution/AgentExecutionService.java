package com.starsea.ai.agent.execution;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.RagService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AgentExecutionService {
    private final RagService rag;
    private final AgentPromptAssembler prompts;
    private final AgentModelClientFactory clients;

    public AgentExecutionService(RagService rag, AgentPromptAssembler prompts, AgentModelClientFactory clients) {
        this.rag = rag; this.prompts = prompts; this.clients = clients;
    }

    public Flux<ExecutionEvent> execute(ExecutionSource source, ExecutionRequest request) {
        // Preflight is deliberately synchronous: auth, variables and configuration errors remain HTTP 4xx.
        AuthContext context = AgentExecutionAccess.require(source);
        prompts.validateVariables(source.configuration(), request.variables());
        AgentModelClientFactory.Client client = clients.create(source);
        return Flux.defer(() -> {
            long start = System.nanoTime();
            AtomicReference<ModelChunk.TokenUsage> usage = new AtomicReference<>();
            AtomicReference<String> finish = new AtomicReference<>("stop");
            return Mono.fromCallable(() -> retrieve(source, request, context))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(chunks -> prompts.assemble(source.configuration(), request, chunks))
                    .flatMapMany(prompt -> Flux.concat(
                            Flux.just(new ExecutionEvent("retrieval", new ExecutionEvent.Retrieval(prompt.citations()))),
                            Flux.defer(() -> client.stream(prompt.messages())).concatMap(chunk -> {
                                if (chunk.usage() != null) usage.set(chunk.usage());
                                if (chunk.finishReason() != null) finish.set(chunk.finishReason());
                                return chunk.text() == null || chunk.text().isEmpty() ? Flux.empty()
                                        : Flux.just(new ExecutionEvent("delta", new ExecutionEvent.Delta(chunk.text())));
                            }),
                            Flux.defer(() -> {
                                var tokens = usage.get();
                                List<ExecutionEvent> tail = new ArrayList<>();
                                tail.add(new ExecutionEvent("usage", new ExecutionEvent.Usage(
                                        tokens == null ? null : tokens.inputTokens(), tokens == null ? null : tokens.outputTokens(),
                                        tokens == null ? null : tokens.totalTokens(), (System.nanoTime() - start) / 1_000_000)));
                                tail.add(new ExecutionEvent("complete", new ExecutionEvent.Complete(finish.get())));
                                return Flux.fromIterable(tail);
                            })))
                    .onErrorResume(error -> Flux.just(errorEvent(error)));
        });
    }

    private List<RetrievedChunk> retrieve(ExecutionSource source, ExecutionRequest request, AuthContext captured) {
        if (source.configuration().knowledgeIds().isEmpty()) return List.of();
        AuthContext previous = AuthContext.current();
        try {
            AuthContext.set(captured);
            return rag.retrieve(new RetrievalQuery(request.message(), Set.copyOf(source.configuration().knowledgeIds()),
                    source.configuration().retrievalTopK(), source.configuration().retrievalScoreThreshold().doubleValue()));
        } finally {
            if (previous == null) AuthContext.clear(); else AuthContext.set(previous);
        }
    }

    private ExecutionEvent errorEvent(Throwable error) {
        // Never forward arbitrary exception messages, upstream response bodies or causes to SSE/logs.
        if (error instanceof AgentModelClientFactory.UpstreamException upstream) {
            return new ExecutionEvent("error", new ExecutionEvent.Failure(upstream.code(), upstream.getMessage()));
        }
        return new ExecutionEvent("error", new ExecutionEvent.Failure("AGENT_EXECUTION_FAILED", "智能体执行失败，请稍后重试"));
    }
}
