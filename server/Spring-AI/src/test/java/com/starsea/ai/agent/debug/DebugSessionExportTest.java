package com.starsea.ai.agent.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class DebugSessionExportTest {
    private final AtomicLong nanos = new AtomicLong();
    private final DebugContextProperties properties = new DebugContextProperties();
    private final DebugContextStore.Owner owner = new DebugContextStore.Owner(9, 71);
    private DebugContextStore store() { return new DebugContextStore(properties, nanos::get, Clock.systemUTC()); }
    static DebugSessionExport.ModelSettings model(String id) {
        return new DebugSessionExport.ModelSettings("厂商", id, new BigDecimal("0.4"), BigDecimal.ONE, 2048, 60);
    }
    static List<DebugSessionExport.Reference> refs(String content) {
        return List.of(new DebugSessionExport.Reference("C1", "手册", .89, content));
    }

    @Test void exports_snapshot_models_and_full_references_with_shared_root_configurations() throws Exception {
        var store = store(); var lease = store.open(owner, 101, null); UUID id = lease.id();
        String fullContent = "原文\n".repeat(150);
        lease.complete("问一", "答一[C1]", model("model-a"), refs(fullContent)); lease.close();
        var firstExport = store.export(owner, 101, id);
        try (var second = store.open(owner, 101, id)) { second.complete("问二", "答二", model("model-b"), List.of()); }
        try (var third = store.open(owner, 101, id)) { third.complete("问三", "答三", model("model-a"), List.of()); }
        var json = new ObjectMapper().valueToTree(store.export(owner, 101, id));
        assertThat(json.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(json.path("historyTruncated").asBoolean()).isFalse();
        assertThat(json.path("model").size()).isEqualTo(2);
        assertThat(json.at("/model/0/configId").asText()).isEqualTo("M1");
        assertThat(json.at("/model/1/modelId").asText()).isEqualTo("model-b");
        assertThat(json.at("/turns/2/modelConfigId").asText()).isEqualTo("M1");
        assertThat(json.at("/turns/0/references/0/content").asText()).isEqualTo(fullContent);
        assertThat(json.at("/turns/0/references/0/score").asDouble()).isEqualTo(.89);
        assertThat(json.toString()).doesNotContain("citedInAnswer", "apiKey", "baseUrl", "systemPrompt", "variables");
        assertThat(firstExport.turns()).hasSize(1);
        assertThat(firstExport.toString()).doesNotContain("问一", fullContent);
    }

    @Test void rejects_busy_empty_foreign_expired_and_missing_metadata_without_recreating_contexts() {
        var store = store(); var lease = store.open(owner, 101, null); UUID id = lease.id();
        assertThatThrownBy(() -> store.export(owner, 101, id)).extracting("code").isEqualTo("DEBUG_CONTEXT_BUSY");
        lease.close();
        assertThatThrownBy(() -> store.export(owner, 101, id)).extracting("code").isEqualTo("DEBUG_EXPORT_EMPTY");
        for (var foreign : List.of(new DebugContextStore.Owner(8, 71), new DebugContextStore.Owner(9, 72))) {
            assertThatThrownBy(() -> store.export(foreign, 101, id)).extracting("status").isEqualTo(410);
        }
        assertThatThrownBy(() -> store.export(owner, 102, id)).extracting("status").isEqualTo(410);
        try (var old = store.open(owner, 101, id)) { old.complete("旧问题", "旧回答"); }
        assertThatThrownBy(() -> store.export(owner, 101, id)).extracting("code").isEqualTo("DEBUG_EXPORT_UNAVAILABLE");
        nanos.addAndGet(Duration.ofMinutes(31).toNanos());
        assertThatThrownBy(() -> store.export(owner, 101, id)).extracting("status").isEqualTo(410);
    }

    @Test void trims_whole_export_turns_without_truncating_references_or_changing_llm_history() {
        properties.setMaxExportBytesPerContext(1024);
        var store = store(); var lease = store.open(owner, 101, null); UUID id = lease.id(); lease.close();
        for (int i = 1; i <= 3; i++) try (var turn = store.open(owner, 101, id)) {
            turn.complete("q" + i, "a" + i, model("model"), refs("x".repeat(600)));
        }
        var exported = store.export(owner, 101, id);
        assertThat(exported.historyTruncated()).isTrue();
        assertThat(exported.turns()).hasSize(1);
        assertThat(exported.turns().get(0).turn()).isEqualTo(3);
        assertThat(exported.turns().get(0).references().get(0).content()).hasSize(600);
        try (var open = store.open(owner, 101, id)) { assertThat(open.history()).hasSize(6); }
        store.delete(owner, 101, id);
        assertThatThrownBy(() -> store.export(owner, 101, id)).extracting("status").isEqualTo(410);
    }

    @Test void drops_export_turns_when_conversation_history_is_trimmed() {
        var store = store(); var initial = store.open(owner, 101, null); UUID id = initial.id(); initial.close();
        for (int i = 1; i <= 21; i++) try (var turn = store.open(owner, 101, id)) {
            turn.complete("q" + i, "a" + i, model("model"), List.of());
        }
        var exported = store.export(owner, 101, id);
        assertThat(exported.historyTruncated()).isTrue();
        assertThat(exported.turns()).hasSize(20);
        assertThat(exported.turns().get(0).turn()).isEqualTo(2);
        assertThat(exported.turns().get(19).turn()).isEqualTo(21);
    }

    @Test void oversized_reference_drops_the_whole_turn_but_later_small_turns_can_be_exported() {
        properties.setMaxExportBytesPerContext(1024);
        var store = store(); var first = store.open(owner, 101, null); UUID id = first.id();
        first.complete("q", "a", model("model"), refs("资料".repeat(1000))); first.close();
        assertThatThrownBy(() -> store.export(owner, 101, id)).extracting("code").isEqualTo("DEBUG_EXPORT_UNAVAILABLE");
        try (var next = store.open(owner, 101, id)) { next.complete("q2", "a2", model("model"), refs("原文")); }
        var exported = store.export(owner, 101, id);
        assertThat(exported.historyTruncated()).isTrue();
        assertThat(exported.turns()).hasSize(1);
        assertThat(exported.turns().get(0).turn()).isEqualTo(2);
    }
}
