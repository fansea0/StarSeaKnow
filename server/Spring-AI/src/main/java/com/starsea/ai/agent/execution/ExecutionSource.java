package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.snapshot.AgentSnapshotData;

public record ExecutionSource(long tenantId, long agentId, long revision, Mode mode, AgentSnapshotData configuration) {
    public enum Mode { DRAFT, PUBLISHED }
    @Override public String toString() {
        return "ExecutionSource[tenantId=" + tenantId + ", agentId=" + agentId + ", mode=" + mode + ", configuration=<redacted>]";
    }
}
