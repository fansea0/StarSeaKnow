package com.starsea.ai.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.snapshot.AgentSnapshot;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
public class SnapshotExecutionSourceLoader {
    private final AgentMapper agents;
    private final AgentSnapshotMapper snapshots;
    public SnapshotExecutionSourceLoader(AgentMapper agents, AgentSnapshotMapper snapshots) {
        this.agents = agents; this.snapshots = snapshots;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExecutionSource load(long agentId) {
        long tenantId = AgentExecutionAccess.require(false).getTenantId();
        Agent agent = agents.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getId, agentId)
                .eq(Agent::getTenantId, tenantId).isNull(Agent::getDeletedAt));
        if (agent == null || agent.getDeletedAt() != null || !Objects.equals(agent.getTenantId(), tenantId)
                || !Objects.equals(agent.getId(), agentId)) throw new AgentWorkbenchException(
                404, "AGENT_NOT_FOUND", "智能体不存在或无权访问");
        if (agent.getCurrentSnapshotId() == null) throw new AgentWorkbenchException(
                409, "AGENT_NOT_PUBLISHED", "智能体尚未发布");
        AgentSnapshot snapshot = snapshots.selectOne(new LambdaQueryWrapper<AgentSnapshot>()
                .eq(AgentSnapshot::getId, agent.getCurrentSnapshotId()).eq(AgentSnapshot::getAgentId, agentId)
                .eq(AgentSnapshot::getTenantId, tenantId).isNull(AgentSnapshot::getDeletedAt));
        if (snapshot == null || snapshot.getDeletedAt() != null || snapshot.getSnapshotData() == null
                || !Objects.equals(snapshot.getTenantId(), tenantId) || !Objects.equals(snapshot.getAgentId(), agentId)
                || !Objects.equals(snapshot.getId(), agent.getCurrentSnapshotId())) {
            throw new AgentWorkbenchException(409, "AGENT_SNAPSHOT_UNAVAILABLE", "发布快照不可用，请联系管理员");
        }
        return new ExecutionSource(tenantId, agentId, snapshot.getSourceRevision(), ExecutionSource.Mode.PUBLISHED, snapshot.getSnapshotData());
    }
}
