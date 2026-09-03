package com.starsea.ai.agent.debug;

import com.starsea.ai.agent.snapshot.AgentSnapshotData;
import java.math.BigDecimal;
import java.util.List;

/** Explicit export allowlist: never serialize an entire provider, draft or execution request. */
public record DebugSessionExport(int schemaVersion, boolean historyTruncated, List<Model> model, List<Turn> turns) {
    public DebugSessionExport { model = List.copyOf(model); turns = List.copyOf(turns); }
    @Override public String toString() { return "DebugSessionExport[content=<redacted>]"; }

    public record ModelSettings(String providerName, String modelId, BigDecimal temperature,
                                BigDecimal topP, int maxTokens, int timeoutSeconds) {
        public static ModelSettings from(AgentSnapshotData.ModelConfiguration model) {
            return model == null ? null : new ModelSettings(model.providerName(), model.modelId(),
                    model.temperature(), model.topP(), model.maxTokens(), model.timeoutSeconds());
        }
    }
    public record Model(String configId, String providerName, String modelId, BigDecimal temperature,
                        BigDecimal topP, int maxTokens, int timeoutSeconds) {
        static Model from(String id, ModelSettings settings) {
            return new Model(id, settings.providerName(), settings.modelId(), settings.temperature(),
                    settings.topP(), settings.maxTokens(), settings.timeoutSeconds());
        }
    }
    public record Reference(String id, String documentTitle, double score, String content) {
        @Override public String toString() { return "Reference[content=<redacted>]"; }
    }
    public record Turn(long turn, String modelConfigId, String question, String answer, List<Reference> references) {
        public Turn { references = List.copyOf(references); }
        @Override public String toString() { return "Turn[content=<redacted>]"; }
    }
    record StoredTurn(long turn, ModelSettings model, String question, String answer, List<Reference> references) {
        StoredTurn { references = List.copyOf(references); }
        @Override public String toString() { return "StoredTurn[content=<redacted>]"; }
    }
    record WeightedTurn(StoredTurn value, int bytes) { }
}
