package com.starsea.ai.agent.debug;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.AgentDeletedEvent;
import com.starsea.ai.agent.execution.ConversationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class DebugContextStore {
    private final Cache<Key, DebugContext> contexts;
    private final int maxContexts;
    private final int maxExportBytesPerContext;
    private static final com.fasterxml.jackson.databind.ObjectMapper EXPORT_JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Clock clock;

    @Autowired
    public DebugContextStore(DebugContextProperties properties) {
        this(properties, Ticker.systemTicker(), Clock.systemUTC());
    }

    DebugContextStore(DebugContextProperties properties, Ticker ticker, Clock clock) {
        this.clock = clock; this.maxContexts = properties.getMaxContexts();
        this.maxExportBytesPerContext = properties.getMaxExportBytesPerContext();
        contexts = Caffeine.newBuilder().maximumSize(maxContexts).expireAfterAccess(Duration.ofMinutes(30))
                .ticker(ticker).scheduler(Scheduler.systemScheduler())
                .<Key, DebugContext>removalListener((key, value, cause) -> { if (value != null) value.invalidate(); }).build();
    }

    public synchronized Lease open(Owner owner, long agentId, UUID id) {
        contexts.cleanUp();
        boolean created = id == null;
        Key key;
        DebugContext context;
        if (created) {
            long userCount = contexts.asMap().keySet().stream().filter(item -> item.owner().equals(owner)).count();
            if (userCount >= 4) throw error(429, "DEBUG_USER_CONTEXT_LIMIT", "最多同时打开 4 个调试上下文，请关闭其他调试页");
            if (contexts.estimatedSize() >= maxContexts) throw error(429, "DEBUG_GLOBAL_CONTEXT_LIMIT", "调试资源已满，请稍后重试");
            key = new Key(owner, agentId, UUID.randomUUID());
            context = new DebugContext(clock.instant());
            contexts.put(key, context);
        } else {
            key = new Key(owner, agentId, id);
            context = contexts.getIfPresent(key);
            if (context == null || context.invalid) throw expired();
        }
        if (context.busy) throw error(409, "DEBUG_CONTEXT_BUSY", "当前上下文正在生成，请等待完成或停止生成");
        context.busy = true; context.lastAccessAt = clock.instant();
        return new Lease(key, context, created, List.copyOf(context.messages), context.revision);
    }

    public void delete(Owner owner, long agentId, UUID id) {
        DebugContext removed;
        synchronized (this) {
            // Scoped key lookup prevents unauthorized callers from extending another user's access TTL.
            Key key = new Key(owner, agentId, id);
            removed = contexts.asMap().remove(key);
            if (removed == null) throw expired();
        }
        // Do not run cancellation callbacks under the store or Caffeine eviction locks.
        removed.invalidate();
    }

    public synchronized DebugSessionExport export(Owner owner, long agentId, UUID id) {
        DebugContext context = contexts.getIfPresent(new Key(owner, agentId, id));
        if (context == null || context.invalid) throw expired();
        if (context.busy) throw error(409, "DEBUG_CONTEXT_BUSY", "当前上下文正在生成，请等待完成或停止生成");
        if (context.revision == 0) throw error(409, "DEBUG_EXPORT_EMPTY", "没有可导出的已完成对话");
        if (context.exportUnavailable || context.exportTurns.isEmpty()) {
            throw error(409, "DEBUG_EXPORT_UNAVAILABLE", "导出数据缺失或已超出缓存限制，请新建会话后重试");
        }
        var configurations = new java.util.LinkedHashMap<DebugSessionExport.ModelSettings, String>();
        var turns = new java.util.ArrayList<DebugSessionExport.Turn>();
        for (var weighted : context.exportTurns) {
            var turn = weighted.value();
            String modelId = configurations.computeIfAbsent(turn.model(), ignored -> "M" + (configurations.size() + 1));
            turns.add(new DebugSessionExport.Turn(turn.turn(), modelId, turn.question(), turn.answer(), turn.references()));
        }
        var models = configurations.entrySet().stream().map(entry -> DebugSessionExport.Model.from(entry.getValue(), entry.getKey())).toList();
        return new DebugSessionExport(1, context.revision > turns.size(), models, turns);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void agentDeleted(AgentDeletedEvent event) {
        List<DebugContext> removed = new java.util.ArrayList<>();
        synchronized (this) {
            var keys = contexts.asMap().keySet().stream().filter(key -> key.owner().tenantId() == event.tenantId()
                    && key.agentId() == event.agentId()).toList();
            for (Key key : keys) {
                DebugContext context = contexts.asMap().remove(key);
                if (context != null) removed.add(context);
            }
        }
        removed.forEach(DebugContext::invalidate);
    }

    private AgentWorkbenchException expired() { return error(410, "DEBUG_CONTEXT_EXPIRED", "调试上下文已失效，请重新开始"); }
    private AgentWorkbenchException error(int status, String code, String message) { return new AgentWorkbenchException(status, code, message); }

    public record Owner(long tenantId, long userId) { }
    private record Key(Owner owner, long agentId, UUID id) { }

    public final class Lease implements AutoCloseable {
        private final Key key;
        private final DebugContext context;
        private final boolean created;
        private final List<ConversationMessage> history;
        private final long revision;
        private boolean closed;
        private boolean committed;
        private Lease(Key key, DebugContext context, boolean created, List<ConversationMessage> history, long revision) {
            this.key = key; this.context = context; this.created = created; this.history = history; this.revision = revision;
        }
        public UUID id() { return key.id(); }
        public boolean created() { return created; }
        public List<ConversationMessage> history() { return history; }
        public long revision() { return revision; }
        public Mono<Void> invalidated() { return context.invalidated.asMono(); }

        public void complete(String user, String assistant) {
            complete(user, assistant, null, null);
        }

        public void complete(String user, String assistant, DebugSessionExport.ModelSettings model,
                             List<DebugSessionExport.Reference> references) {
            synchronized (DebugContextStore.this) {
                if (closed || committed || context.invalid) return;
                if (user == null || user.isBlank() || assistant == null || user.length() > 16000 || assistant.length() > 16000) {
                    throw error(422, "DEBUG_MESSAGE_TOO_LONG", "调试消息超过长度限制");
                }
                context.messages.addLast(new ConversationMessage("user", user));
                context.messages.addLast(new ConversationMessage("assistant", assistant));
                int characters = context.messages.stream().mapToInt(message -> message.content().length()).sum();
                while (context.messages.size() > 40 || characters > 64000) {
                    characters -= context.messages.removeFirst().content().length();
                    characters -= context.messages.removeFirst().content().length();
                }
                context.revision++;
                if (model == null || references == null) {
                    context.exportUnavailable = true;
                } else {
                    var turn = new DebugSessionExport.StoredTurn(context.revision, model, user, assistant, references);
                    try {
                        int bytes = EXPORT_JSON.writeValueAsBytes(turn).length;
                        context.exportTurns.addLast(new DebugSessionExport.WeightedTurn(turn, bytes));
                        context.exportBytes += bytes;
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                        context.exportUnavailable = true;
                    }
                }
                // Export is a bounded subset of completed history; never trim individual reference bodies.
                long firstRetainedTurn = context.revision - context.messages.size() / 2 + 1;
                while (!context.exportTurns.isEmpty() && (context.exportBytes > maxExportBytesPerContext
                        || context.exportTurns.getFirst().value().turn() < firstRetainedTurn)) {
                    context.exportBytes -= context.exportTurns.removeFirst().bytes();
                }
                committed = true;
            }
        }

        public void discardIfNew() {
            synchronized (DebugContextStore.this) {
                if (created) contexts.invalidate(key);
                close();
            }
        }

        @Override public void close() {
            synchronized (DebugContextStore.this) {
                if (!closed) { context.busy = false; closed = true; }
            }
        }
        @Override public String toString() { return "DebugContextLease[content=<redacted>]"; }
    }
}
