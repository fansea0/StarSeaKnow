package com.starsea.ai.agent.debug;

import com.starsea.ai.agent.execution.ConversationMessage;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/** Memory only. Deliberately has no persistence mapping and no content-bearing toString. */
final class DebugContext {
    final Deque<ConversationMessage> messages = new ArrayDeque<>();
    final Deque<DebugSessionExport.WeightedTurn> exportTurns = new ArrayDeque<>();
    long exportBytes;
    boolean exportUnavailable;
    final Instant createdAt;
    Instant lastAccessAt;
    long revision;
    boolean busy;
    volatile boolean invalid;
    final Sinks.Empty<Void> invalidated = Sinks.empty();

    DebugContext(Instant now) { createdAt = now; lastAccessAt = now; }
    void invalidate() { invalid = true; invalidated.tryEmitEmpty(); }
    @Override public String toString() { return "DebugContext[content=<redacted>]"; }
}
