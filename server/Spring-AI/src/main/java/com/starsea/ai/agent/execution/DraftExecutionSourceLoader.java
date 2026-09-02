package com.starsea.ai.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.snapshot.AgentSnapshotAssembler;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
public class DraftExecutionSourceLoader {
    private final AgentMapper agents;
    private final AgentSnapshotAssembler assembler;
    public DraftExecutionSourceLoader(AgentMapper agents, AgentSnapshotAssembler assembler) {
        this.agents = agents; this.assembler = assembler;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExecutionSource load(long agentId) {
        long tenantId = AgentExecutionAccess.require(true).getTenantId();
        Agent agent = agents.selectOne(new LambdaQueryWrapper<Agent>().eq(Agent::getId, agentId)
                .eq(Agent::getTenantId, tenantId).isNull(Agent::getDeletedAt));
        if (agent == null || agent.getDeletedAt() != null || !Objects.equals(agent.getTenantId(), tenantId)
                || !Objects.equals(agent.getId(), agentId)) throw new AgentWorkbenchException(
                404, "AGENT_NOT_FOUND", "智能体不存在或无权访问");
        return new ExecutionSource(tenantId, agentId, agent.getDraftRevision(), ExecutionSource.Mode.DRAFT, assembler.assemble(agent));
    }
}
