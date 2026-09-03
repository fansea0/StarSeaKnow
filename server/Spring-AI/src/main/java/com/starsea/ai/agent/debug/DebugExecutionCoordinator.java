package com.starsea.ai.agent.debug;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.execution.AgentExecutionService;
import com.starsea.ai.agent.execution.DraftExecutionSourceLoader;
import com.starsea.ai.agent.execution.ExecutionEvent;
import com.starsea.ai.agent.execution.ExecutionRequest;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Agent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DebugExecutionCoordinator {
    private final DebugContextStore store;
    private final DraftExecutionSourceLoader drafts;
    private final AgentExecutionService execution;
    private final com.starsea.ai.mapper.AgentMapper agents;
    public DebugExecutionCoordinator(DebugContextStore store, DraftExecutionSourceLoader drafts, AgentExecutionService execution,
                                     com.starsea.ai.mapper.AgentMapper agents) {
        this.store = store; this.drafts = drafts; this.execution = execution; this.agents = agents;
    }

    public Flux<ExecutionEvent> stream(long agentId, DebugCommand command) {
        var owner = owner();
        if (command == null) throw new AgentWorkbenchException(422, "AGENT_MESSAGE_INVALID", "消息不能为空");
        // Validate input before reserving scarce context slots, then load the current draft explicitly.
        new ExecutionRequest(command.message(), command.variables(), List.of());
        var source = drafts.load(agentId);
        var lease = store.open(owner, agentId, command.debugContextId());
        try {
            var references = new java.util.concurrent.atomic.AtomicReference<List<DebugSessionExport.Reference>>();
            var model = DebugSessionExport.ModelSettings.from(source.configuration().model());
            var stream = execution.execute(source, new ExecutionRequest(command.message(), command.variables(), lease.history()),
                    sources -> references.set(sources.stream().map(item -> new DebugSessionExport.Reference(
                            item.id(), item.documentTitle(), item.score(), item.content())).toList()));
            if (agents.markDebugged(agentId, owner.tenantId(), owner.userId(), java.time.OffsetDateTime.now()) != 1) {
                throw new AgentWorkbenchException(404, "AGENT_NOT_FOUND", "智能体不存在或无权访问");
            }
            StringBuilder reply = new StringBuilder();
            AtomicBoolean subscribed = new AtomicBoolean();
            Flux<ExecutionEvent> events = stream.<ExecutionEvent>handle((event, sink) -> {
                if ("delta".equals(event.type())) {
                    String text = ((ExecutionEvent.Delta) event.data()).text();
                    if (reply.length() + text.length() > 16000) {
                        sink.error(new AgentWorkbenchException(422, "DEBUG_MESSAGE_TOO_LONG", "生成内容超过调试消息长度限制，请降低最大输出长度"));
                        return;
                    }
                    reply.append(text);
                }
                if ("complete".equals(event.type())) lease.complete(command.message(), reply.toString(), model, references.get());
                sink.next(event);
            }).takeUntil(event -> "error".equals(event.type()))
                    .onErrorResume(error -> Flux.just(new ExecutionEvent("error", new ExecutionEvent.Failure(
                            error instanceof AgentWorkbenchException known ? known.code() : "AGENT_EXECUTION_FAILED",
                            error instanceof AgentWorkbenchException known ? known.getMessage() : "智能体执行失败，请重试"))));
            if (lease.created()) events = Flux.concat(Flux.just(new ExecutionEvent("context", new ContextEvent(lease.id()))), events);
            Flux<ExecutionEvent> result = events.takeUntilOther(lease.invalidated()).doFinally(ignored -> lease.close());
            return Flux.defer(() -> subscribed.compareAndSet(false, true) ? result
                    : Flux.error(new AgentWorkbenchException(409, "DEBUG_CONTEXT_BUSY", "调试请求不能重复执行")));
        } catch (RuntimeException error) {
            lease.discardIfNew();
            throw error;
        }
    }

    public void delete(long agentId, UUID id) { store.delete(owner(), agentId, id); }

    public DebugSessionExport export(long agentId, UUID id) {
        var owner = owner();
        // Historical exports need access to the agent, not an executable current draft.
        Agent agent = agents.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getId, agentId)
                .eq(Agent::getTenantId, owner.tenantId()).isNull(Agent::getDeletedAt));
        if (agent == null || agent.getDeletedAt() != null || !Objects.equals(agent.getTenantId(), owner.tenantId())
                || !Objects.equals(agent.getId(), agentId)) throw new AgentWorkbenchException(
                404, "AGENT_NOT_FOUND", "智能体不存在或无权访问");
        return store.export(owner, agentId, id);
    }

    private DebugContextStore.Owner owner() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getKind() != AuthContext.Kind.BUSINESS || context.getTenantId() == null
                || !"tenant_admin".equals(context.getRole())) throw new AgentWorkbenchException(
                403, "AGENT_ADMIN_REQUIRED", "需要租户管理员权限");
        return new DebugContextStore.Owner(context.getTenantId(), context.getUserId());
    }

    public record DebugCommand(UUID debugContextId, String message, Map<String, String> variables) {
        @Override public String toString() { return "DebugCommand[content=<redacted>]"; }
    }
    public record ContextEvent(UUID debugContextId) { }
}
