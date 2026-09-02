package com.starsea.ai.agent.debug;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class DebugContextStoreTest {
    private final AtomicLong nanos = new AtomicLong();
    private final DebugContextProperties properties = new DebugContextProperties();
    private final DebugContextStore.Owner owner = new DebugContextStore.Owner(9, 71);
    private DebugContextStore store() { return new DebugContextStore(properties, nanos::get, Clock.systemUTC()); }

    @Test void binds_context_to_tenant_user_and_agent_and_rejects_parallel_generation() {
        var store = store(); var lease = store.open(owner, 101, null);
        UUID id = lease.id();
        assertThat(id.version()).isEqualTo(4);
        assertThatThrownBy(() -> store.open(owner, 101, id)).extracting("status").isEqualTo(409);
        lease.complete("私有问题", "私有回答"); lease.close();
        assertThatThrownBy(() -> store.open(new DebugContextStore.Owner(8, 71), 101, id)).extracting("status").isEqualTo(410);
        assertThatThrownBy(() -> store.open(new DebugContextStore.Owner(9, 72), 101, id)).extracting("status").isEqualTo(410);
        assertThatThrownBy(() -> store.open(owner, 102, id)).extracting("status").isEqualTo(410);
        try (var next = store.open(owner, 101, id)) {
            assertThat(next.history()).extracting(message -> message.content()).containsExactly("私有问题", "私有回答");
            assertThat(next.revision()).isEqualTo(1);
            assertThat(next.toString()).doesNotContain("私有问题", "私有回答");
        }
    }

    @Test void expires_thirty_minutes_after_last_access_and_cannot_restore_after_restart() {
        var store = store(); var first = store.open(owner, 101, null); UUID id = first.id(); first.close();
        nanos.addAndGet(Duration.ofMinutes(20).toNanos()); store.open(owner, 101, id).close();
        nanos.addAndGet(Duration.ofMinutes(20).toNanos()); store.open(owner, 101, id).close();
        nanos.addAndGet(Duration.ofMinutes(31).toNanos());
        assertThatThrownBy(() -> store.open(owner, 101, id)).extracting("code").isEqualTo("DEBUG_CONTEXT_EXPIRED");
        assertThatThrownBy(() -> store().open(owner, 101, id)).extracting("status").isEqualTo(410);
    }

    @Test void enforces_per_user_and_global_limits_without_evicting_inflight_contexts() {
        var store = store(); UUID first = null;
        for (int i = 0; i < 4; i++) { var lease = store.open(owner, 101, null); if (first == null) first = lease.id(); lease.close(); }
        assertThatThrownBy(() -> store.open(owner, 101, null)).extracting("code").isEqualTo("DEBUG_USER_CONTEXT_LIMIT");
        store.delete(owner, 101, first);
        store.open(owner, 101, null).close();
        properties.setMaxContexts(2);
        var small = store();
        small.open(owner, 101, null).close();
        small.open(new DebugContextStore.Owner(9, 72), 101, null).close();
        assertThatThrownBy(() -> small.open(new DebugContextStore.Owner(9, 73), 101, null))
                .extracting("code").isEqualTo("DEBUG_GLOBAL_CONTEXT_LIMIT");
    }

    @Test void keeps_complete_pairs_and_trims_earliest_turns_by_message_and_character_limits() {
        var store = store(); var initial = store.open(owner, 101, null); UUID id = initial.id(); initial.close();
        for (int i = 0; i < 21; i++) try (var lease = store.open(owner, 101, id)) { lease.complete("q" + i, "a" + i); }
        try (var lease = store.open(owner, 101, id)) {
            assertThat(lease.history()).hasSize(40);
            assertThat(lease.history().get(0).content()).isEqualTo("q1");
            assertThat(lease.history().get(39).content()).isEqualTo("a20");
        }
        for (int i = 0; i < 4; i++) try (var lease = store.open(owner, 101, id)) {
            lease.complete("q".repeat(9999) + i, "a".repeat(10000));
        }
        try (var lease = store.open(owner, 101, id)) {
            assertThat(lease.history()).hasSize(6);
            assertThat(lease.history().get(0).content()).endsWith("1");
            assertThat(lease.history().stream().mapToInt(message -> message.content().length()).sum()).isEqualTo(60000);
        }
    }

    @Test void failed_or_cancelled_turn_does_not_mutate_history_and_delete_invalidates_active_lease() {
        var store = store(); var first = store.open(owner, 101, null); UUID id = first.id(); first.close();
        var retry = store.open(owner, 101, id);
        assertThat(retry.history()).isEmpty();
        AtomicBoolean closed = new AtomicBoolean();
        retry.invalidated().subscribe(ignored -> { }, ignored -> { }, () -> closed.set(true));
        store.delete(owner, 101, id);
        assertThat(closed).isTrue();
        retry.complete("不应保存", "不应保存"); retry.close();
        assertThatThrownBy(() -> store.open(owner, 101, id)).extracting("status").isEqualTo(410);
    }

    @Test void simultaneous_requests_can_acquire_only_one_generation_lease() throws Exception {
        var store = store(); var first = store.open(owner, 101, null); UUID id = first.id(); first.close();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> acquire = () -> {
            start.await();
            try { store.open(owner, 101, id); return 200; }
            catch (com.starsea.ai.agent.AgentWorkbenchException exception) { return exception.status(); }
        };
        try {
            var one = executor.submit(acquire); var two = executor.submit(acquire); start.countDown();
            assertThat(List.of(one.get(3, java.util.concurrent.TimeUnit.SECONDS), two.get(3, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { executor.shutdownNow(); store.delete(owner, 101, id); }
    }
}
