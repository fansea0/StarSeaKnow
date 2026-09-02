package com.starsea.ai.agent.snapshot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.agent.AgentAggregateService;
import com.starsea.ai.agent.AgentWorkbenchApiModels;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class AgentSnapshotService {
    private final AgentMapper agents;
    private final AgentSnapshotMapper snapshots;
    private final AgentSnapshotAssembler assembler;
    private final AgentAggregateService aggregate;
    private final Clock clock;

    @Autowired
    public AgentSnapshotService(
            AgentMapper agents,
            AgentSnapshotMapper snapshots,
            AgentSnapshotAssembler assembler,
            AgentAggregateService aggregate) {
        this(agents, snapshots, assembler, aggregate, Clock.systemDefaultZone());
    }

    AgentSnapshotService(
            AgentMapper agents,
            AgentSnapshotMapper snapshots,
            AgentSnapshotAssembler assembler,
            AgentAggregateService aggregate,
            Clock clock) {
        this.agents = agents;
        this.snapshots = snapshots;
        this.assembler = assembler;
        this.aggregate = aggregate;
        this.clock = clock;
    }

    public SnapshotPage list(long agentId, int requestedPage, int requestedSize) {
        requireAdmin();
        owned(agentId);
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(100, requestedSize));
        long total = snapshots.selectCount(new LambdaQueryWrapper<AgentSnapshot>()
                .eq(AgentSnapshot::getAgentId, agentId).eq(AgentSnapshot::getTenantId, tenantId())
                .isNull(AgentSnapshot::getDeletedAt));
        List<AgentSnapshot> rows = snapshots.selectList(new LambdaQueryWrapper<AgentSnapshot>()
                .select(AgentSnapshot::getId, AgentSnapshot::getVersionNumber, AgentSnapshot::getPublishNote,
                        AgentSnapshot::getSourceRevision, AgentSnapshot::getRollbackFromSnapshotId,
                        AgentSnapshot::getCreatedBy, AgentSnapshot::getCreateTime)
                .eq(AgentSnapshot::getAgentId, agentId)
                .eq(AgentSnapshot::getTenantId, tenantId())
                .isNull(AgentSnapshot::getDeletedAt)
                .orderByDesc(AgentSnapshot::getVersionNumber)
                .last("LIMIT " + size + " OFFSET " + ((long) (page - 1) * size)));
        return new SnapshotPage(rows.stream().map(row -> new SnapshotSummary(row.getId(), row.getVersionNumber(),
                row.getPublishNote(), row.getSourceRevision(), row.getRollbackFromSnapshotId(),
                row.getCreatedBy(), row.getCreateTime())).toList(), page, size, total);
    }

    public AgentSnapshot get(long agentId, long version) {
        requireAdmin();
        owned(agentId);
        return version(agentId, version);
    }

    @Transactional
    public AgentSnapshot publish(long agentId, PublishCommand command) {
        requireAdmin();
        if (command == null || !StringUtils.hasText(command.publishNote())) {
            throw invalid("发布说明不能为空");
        }
        Agent row = lockOwned(agentId);
        requireLock(row, command.lockVersion());
        return createSnapshot(row, command.publishNote().trim(), null);
    }

    @Transactional
    public AgentSnapshot rollback(long agentId, long version, RollbackCommand command) {
        requireAdmin();
        if (command == null || command.lockVersion() == null) {
            throw conflict();
        }
        AgentSnapshot source = version(agentId, version);
        AgentWorkbenchApiModels.DraftCommand draft = assembler.toDraftCommand(
                source.getSnapshotData(), command.lockVersion());
        AgentWorkbenchApiModels.AgentDetailView restored = aggregate.updateDraft(agentId, draft);
        Agent row = lockOwned(agentId);
        if (row.getDraftRevision() == null || row.getDraftRevision() != restored.draftRevision()) {
            throw conflict();
        }
        String note = StringUtils.hasText(command.publishNote())
                ? command.publishNote().trim() : "回滚自 v" + version;
        return createSnapshot(row, note, source.getId());
    }

    private AgentSnapshot createSnapshot(Agent row, String note, Long rollbackFromId) {
        AgentSnapshotData data = assembler.assemble(row);
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setTenantId(tenantId());
        snapshot.setAgentId(row.getId());
        snapshot.setVersionNumber(snapshots.nextVersion(row.getId(), tenantId()));
        snapshot.setPublishNote(note);
        snapshot.setSnapshotData(data);
        snapshot.setSourceRevision(row.getDraftRevision());
        snapshot.setRollbackFromSnapshotId(rollbackFromId);
        snapshot.setCreatedBy(userId());
        snapshot.setCreateTime(OffsetDateTime.now(clock));
        snapshots.insert(snapshot);
        row.setCurrentSnapshotId(snapshot.getId());
        row.setPublishedRevision(row.getDraftRevision());
        row.setLockVersion(value(row.getLockVersion()) + 1);
        agents.updateById(row);
        return snapshot;
    }

    private AgentSnapshot version(long agentId, long version) {
        AgentSnapshot row = snapshots.selectOne(new LambdaQueryWrapper<AgentSnapshot>()
                .eq(AgentSnapshot::getAgentId, agentId)
                .eq(AgentSnapshot::getTenantId, tenantId())
                .eq(AgentSnapshot::getVersionNumber, version)
                .isNull(AgentSnapshot::getDeletedAt));
        if (row == null) throw new AgentWorkbenchException(
                404, "AGENT_SNAPSHOT_NOT_FOUND", "智能体快照不存在或无权访问");
        return row;
    }

    private Agent owned(long agentId) {
        Agent row = agents.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getId, agentId)
                .eq(Agent::getTenantId, tenantId())
                .isNull(Agent::getDeletedAt));
        if (row == null) throw notFound();
        return row;
    }

    private Agent lockOwned(long agentId) {
        Agent row = agents.selectForUpdate(agentId, tenantId());
        if (row == null) throw notFound();
        return row;
    }

    private void requireLock(Agent row, Long lockVersion) {
        if (lockVersion == null || !Objects.equals(row.getLockVersion(), lockVersion)) throw conflict();
    }

    private void requireAdmin() {
        AuthContext context = AuthContext.current();
        if (context == null || !"tenant_admin".equals(context.getRole())) {
            throw new AgentWorkbenchException(403, "AGENT_ADMIN_REQUIRED", "需要租户管理员权限");
        }
    }

    private long tenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) throw new AgentWorkbenchException(
                403, "TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
        return context.getTenantId();
    }

    private long userId() {
        return AuthContext.current().getUserId();
    }

    private long value(Long number) {
        return number == null ? 0L : number;
    }

    private AgentWorkbenchException conflict() {
        return new AgentWorkbenchException(409, "AGENT_DRAFT_CONFLICT", "草稿已被其他修改覆盖，请刷新后重试");
    }

    private AgentWorkbenchException invalid(String message) {
        return new AgentWorkbenchException(422, "AGENT_PUBLISH_INVALID", message);
    }

    private AgentWorkbenchException notFound() {
        return new AgentWorkbenchException(404, "AGENT_NOT_FOUND", "智能体不存在或无权访问");
    }

    public record PublishCommand(String publishNote, Long lockVersion) {
    }

    public record RollbackCommand(String publishNote, Long lockVersion) {
    }

    public record SnapshotSummary(Long id, Long versionNumber, String publishNote, Long sourceRevision,
                                  Long rollbackFromSnapshotId, Long createdBy, OffsetDateTime createTime) { }
    public record SnapshotPage(List<SnapshotSummary> items, int page, int pageSize, long total) { }
}
