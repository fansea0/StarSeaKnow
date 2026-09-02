package com.starsea.ai.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.domain.Knowledge;
import com.starsea.ai.mapper.AgentKnowledgeMapper;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentModelMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import com.starsea.ai.mapper.KnowledgeMapper;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.model.provider.ModelSuggestion;
import com.starsea.ai.model.provider.TenantModelProvider;
import com.starsea.ai.agent.snapshot.AgentSnapshot;
import com.starsea.ai.agent.snapshot.AgentSnapshotData;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class AgentAggregateService {
    private final AgentMapper agents;
    private final AgentModelMapper models;
    private final AgentKnowledgeMapper agentKnowledge;
    private final KnowledgeMapper knowledge;
    private final TenantModelProviderMapper providers;
    private final AgentSnapshotMapper snapshots;
    private final Clock clock;

    @Autowired
    public AgentAggregateService(
            AgentMapper agents,
            AgentModelMapper models,
            AgentKnowledgeMapper agentKnowledge,
            KnowledgeMapper knowledge,
            TenantModelProviderMapper providers,
            AgentSnapshotMapper snapshots) {
        this(agents, models, agentKnowledge, knowledge, providers, snapshots, Clock.systemDefaultZone());
    }

    AgentAggregateService(
            AgentMapper agents,
            AgentModelMapper models,
            AgentKnowledgeMapper agentKnowledge,
            KnowledgeMapper knowledge,
            TenantModelProviderMapper providers,
            AgentSnapshotMapper snapshots,
            Clock clock) {
        this.agents = agents;
        this.models = models;
        this.agentKnowledge = agentKnowledge;
        this.knowledge = knowledge;
        this.providers = providers;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional
    public AgentWorkbenchApiModels.AgentDetailView create(AgentWorkbenchApiModels.DraftCommand command) {
        requireAdmin();
        ValidatedDraft draft = validate(command, tenantId());
        long tenantId = tenantId();
        Agent row = new Agent();
        row.setTenantId(tenantId);
        applyDraft(row, draft, false);
        row.setDraftRevision(1L);
        row.setPublishedRevision(0L);
        row.setLockVersion(0L);
        row.setLastEditedBy(userId());
        agents.insert(row);

        AgentModel model = null;
        if (draft.model() != null) {
            model = newModel(tenantId, draft.model());
            models.insert(model);
            row.setAgentModelId(model.getId());
            agents.updateById(row);
        }
        replaceKnowledge(row.getId(), tenantId, draft.knowledgeIds());
        return toDetail(row, model, draft.knowledgeIds(), true);
    }

    @Transactional
    public AgentWorkbenchApiModels.AgentDetailView updateDraft(
            long agentId, AgentWorkbenchApiModels.DraftCommand command) {
        requireAdmin();
        long tenantId = tenantId();
        Agent row = lockOwned(agentId, tenantId);
        if (command == null || command.lockVersion() == null
                || !Objects.equals(row.getLockVersion(), command.lockVersion())) {
            throw conflict("AGENT_DRAFT_CONFLICT", "草稿已被其他修改覆盖，请刷新后重试");
        }
        ValidatedDraft draft = validate(command, tenantId);
        AgentModel model = replaceModel(row, draft.model(), tenantId);
        applyDraft(row, draft, true);
        row.setDraftRevision(value(row.getDraftRevision(), 1L) + 1);
        row.setLockVersion(value(row.getLockVersion(), 0L) + 1);
        row.setLastEditedBy(userId());
        replaceKnowledge(agentId, tenantId, draft.knowledgeIds());
        agents.updateById(row);
        return toDetail(row, model, draft.knowledgeIds(), true);
    }

    public AgentWorkbenchApiModels.AgentDetailView get(long agentId) {
        long tenantId = tenantId();
        Agent row = owned(agentId, tenantId);
        boolean admin = isAdmin();
        if (!admin && !"PUBLISHED".equals(status(row))) {
            if (row.getCurrentSnapshotId() == null) throw notFound();
        }
        if (!admin) {
            AgentSnapshot snapshot = currentSnapshot(row);
            AgentSnapshotData data = snapshot.getSnapshotData();
            AgentSnapshotData.ModelConfiguration model = data.model();
            return new AgentWorkbenchApiModels.AgentDetailView(
                    row.getId(), data.name(), data.description(), data.prologue(), null,
                    data.tags(), List.of(), data.knowledgeIds(), data.retrievalTopK(),
                    data.retrievalScoreThreshold(), snapshotModelView(model), "PUBLISHED",
                    value(row.getDraftRevision(), 1L), value(row.getPublishedRevision(), 0L),
                    row.getCurrentSnapshotId(), value(row.getLockVersion(), 0L),
                    row.getLastDebuggedAt(), row.getLastDebuggedBy(), row.getUpdateTime(), false);
        }
        List<Long> knowledgeIds = safeList(agentKnowledge.selectKnowledgeIds(agentId, tenantId));
        AgentModel model = activeModel(row.getAgentModelId(), tenantId);
        return toDetail(row, model, knowledgeIds, true);
    }

    public AgentWorkbenchApiModels.AgentPage list(AgentWorkbenchApiModels.AgentListQuery query) {
        AgentWorkbenchApiModels.AgentListQuery request = query == null
                ? new AgentWorkbenchApiModels.AgentListQuery(1, 20, null, null, null) : query;
        List<Agent> rows = agents.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId())
                .isNull(Agent::getDeletedAt)
                .orderByDesc(Agent::getUpdateTime));
        String keyword = trimLower(request.keyword());
        String requestedStatus = trimUpper(request.status());
        String tag = trimToNull(request.tag());
        boolean admin = isAdmin();
        List<Agent> filtered = rows.stream()
                .filter(row -> admin || row.getCurrentSnapshotId() != null)
                .filter(row -> requestedStatus == null || requestedStatus.equals(status(row)))
                .filter(row -> tag == null || safeList(row.getTags()).contains(tag))
                .filter(row -> keyword == null || contains(row.getName(), keyword) || contains(row.getDescription(), keyword))
                .sorted(Comparator.comparing(Agent::getUpdateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int page = request.normalizedPage();
        int pageSize = request.normalizedPageSize();
        int from = Math.min(filtered.size(), (page - 1) * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        List<AgentWorkbenchApiModels.AgentListItem> items = filtered.subList(from, to).stream()
                .map(this::toListItem)
                .toList();
        return new AgentWorkbenchApiModels.AgentPage(items, page, pageSize, filtered.size());
    }

    public AgentWorkbenchApiModels.AgentMetrics metrics() {
        List<Agent> rows = agents.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getTenantId, tenantId())
                .isNull(Agent::getDeletedAt));
        if (!isAdmin()) rows = rows.stream().filter(row -> row.getCurrentSnapshotId() != null).toList();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime weekStart = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(clock.getZone()).toOffsetDateTime();
        long published = rows.stream().filter(row -> row.getCurrentSnapshotId() != null).count();
        long debugged = rows.stream().filter(row -> row.getLastDebuggedAt() != null
                && !row.getLastDebuggedAt().isBefore(weekStart)).count();
        long changed = rows.stream().filter(row -> "DRAFT_CHANGED".equals(status(row))).count();
        return new AgentWorkbenchApiModels.AgentMetrics(rows.size(), published, debugged, changed);
    }

    @Transactional
    public void delete(long agentId) {
        requireAdmin();
        long tenantId = tenantId();
        Agent row = lockOwned(agentId, tenantId);
        OffsetDateTime deletedAt = OffsetDateTime.now(clock);
        long deletedBy = userId();
        if (row.getAgentModelId() != null) {
            AgentModel model = activeModel(row.getAgentModelId(), tenantId);
            if (model != null) {
                model.setDeletedAt(deletedAt);
                model.setDeletedBy(deletedBy);
                models.updateById(model);
            }
        }
        List<AgentSnapshot> agentSnapshots = safeList(snapshots.selectList(new LambdaQueryWrapper<AgentSnapshot>()
                .eq(AgentSnapshot::getAgentId, agentId)
                .eq(AgentSnapshot::getTenantId, tenantId)
                .isNull(AgentSnapshot::getDeletedAt)));
        for (AgentSnapshot snapshot : agentSnapshots) {
            snapshot.setDeletedAt(deletedAt);
            snapshot.setDeletedBy(deletedBy);
            snapshots.updateById(snapshot);
        }
        row.setDeletedAt(deletedAt);
        row.setDeletedBy(deletedBy);
        agents.updateById(row);
    }

    private ValidatedDraft validate(AgentWorkbenchApiModels.DraftCommand command, long tenantId) {
        if (command == null || !StringUtils.hasText(command.name()) || command.name().trim().length() > 32) {
            throw invalid("智能体名称不能为空且不能超过 32 个字符");
        }
        if (!StringUtils.hasText(command.systemPrompt())) {
            throw invalid("系统提示词不能为空");
        }
        if (command.retrievalTopK() == null || command.retrievalTopK() < 1 || command.retrievalTopK() > 20) {
            throw invalid("Top-K 必须在 1 到 20 之间");
        }
        if (command.retrievalScoreThreshold() == null
                || command.retrievalScoreThreshold().compareTo(BigDecimal.ZERO) < 0
                || command.retrievalScoreThreshold().compareTo(BigDecimal.ONE) > 0) {
            throw invalid("相似度阈值必须在 0 到 1 之间");
        }
        List<String> tags = normalizeTags(command.tags());
        validateVariables(command.variables());
        List<Long> knowledgeIds = new ArrayList<>(new LinkedHashSet<>(command.knowledgeIds()));
        if (knowledgeIds.size() != command.knowledgeIds().size() || knowledgeIds.stream().anyMatch(Objects::isNull)) {
            throw invalid("知识库 ID 不能重复或为空");
        }
        if (!knowledgeIds.isEmpty()) {
            long count = knowledge.selectCount(new LambdaQueryWrapper<Knowledge>()
                    .eq(Knowledge::getTenantId, tenantId)
                    .in(Knowledge::getId, knowledgeIds));
            if (count != knowledgeIds.size()) throw notFound("AGENT_KNOWLEDGE_NOT_FOUND", "知识库不存在或无权访问");
        }
        AgentWorkbenchApiModels.AgentModelCommand model = validateModel(command.model(), tenantId);
        return new ValidatedDraft(
                command.name().trim(), trimToNull(command.description()), trimToNull(command.prologue()),
                command.systemPrompt().trim(), tags, command.variables(), knowledgeIds,
                command.retrievalTopK(), command.retrievalScoreThreshold(), model);
    }

    private AgentWorkbenchApiModels.AgentModelCommand validateModel(
            AgentWorkbenchApiModels.AgentModelCommand command, long tenantId) {
        if (command == null) return null;
        if (command.providerConnectionId() == null || !StringUtils.hasText(command.modelId())) {
            throw invalid("厂商连接和模型 ID 不能为空");
        }
        requireRange(command.temperature(), BigDecimal.ZERO, new BigDecimal("2"), "Temperature");
        requireRange(command.topP(), BigDecimal.ZERO, BigDecimal.ONE, "Top P");
        if (command.maxTokens() == null || command.maxTokens() < 1 || command.maxTokens() > 200000) {
            throw invalid("最大 Token 必须在 1 到 200000 之间");
        }
        if (command.timeoutSeconds() == null || command.timeoutSeconds() < 1 || command.timeoutSeconds() > 600) {
            throw invalid("超时时间必须在 1 到 600 秒之间");
        }
        TenantModelProvider provider = providers.selectOne(new LambdaQueryWrapper<TenantModelProvider>()
                .eq(TenantModelProvider::getId, command.providerConnectionId())
                .eq(TenantModelProvider::getTenantId, tenantId));
        if (provider == null) throw notFound("MODEL_PROVIDER_CONNECTION_NOT_FOUND", "厂商连接不存在");
        boolean exists = safeList(provider.getSelectableModels()).stream()
                .map(ModelSuggestion::modelId)
                .anyMatch(command.modelId().trim()::equals);
        if (!exists) throw invalid("所选模型不在厂商候选列表中");
        return new AgentWorkbenchApiModels.AgentModelCommand(
                command.providerConnectionId(), command.modelId().trim(), command.temperature(),
                command.topP(), command.maxTokens(), command.timeoutSeconds());
    }

    private void requireRange(BigDecimal value, BigDecimal min, BigDecimal max, String field) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw invalid(field + " 参数超出允许范围");
        }
    }

    private void validateVariables(List<AgentWorkbenchApiModels.VariableDefinition> variables) {
        Set<String> names = new LinkedHashSet<>();
        for (AgentWorkbenchApiModels.VariableDefinition variable : variables) {
            if (variable == null || !StringUtils.hasText(variable.name()) || !StringUtils.hasText(variable.label())) {
                throw invalid("变量名称和标签不能为空");
            }
            if (!names.add(variable.name().trim())) throw invalid("变量名称不能重复");
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (!StringUtils.hasText(tag)) throw invalid("标签不能为空");
            normalized.add(tag.trim());
        }
        if (normalized.size() != tags.size()) throw invalid("标签不能重复");
        return List.copyOf(normalized);
    }

    private void applyDraft(Agent row, ValidatedDraft draft, boolean preserveIdentity) {
        row.setName(draft.name());
        row.setDescription(draft.description());
        row.setPrologue(draft.prologue());
        row.setSystemPrompt(draft.systemPrompt());
        row.setTags(draft.tags());
        row.setVariables(draft.variables());
        row.setRetrievalTopK(draft.retrievalTopK());
        row.setRetrievalScoreThreshold(draft.retrievalScoreThreshold());
        if (!preserveIdentity) row.setRoleDescription(null);
    }

    private AgentModel replaceModel(
            Agent row, AgentWorkbenchApiModels.AgentModelCommand command, long tenantId) {
        AgentModel existing = activeModel(row.getAgentModelId(), tenantId);
        if (command == null) {
            if (existing != null) {
                existing.setDeletedAt(OffsetDateTime.now(clock));
                existing.setDeletedBy(userId());
                models.updateById(existing);
            }
            row.setAgentModelId(null);
            return null;
        }
        if (existing == null) {
            AgentModel created = newModel(tenantId, command);
            models.insert(created);
            row.setAgentModelId(created.getId());
            return created;
        }
        applyModel(existing, command);
        models.updateById(existing);
        return existing;
    }

    private AgentModel newModel(long tenantId, AgentWorkbenchApiModels.AgentModelCommand command) {
        AgentModel row = new AgentModel();
        row.setTenantId(tenantId);
        applyModel(row, command);
        return row;
    }

    private void applyModel(AgentModel row, AgentWorkbenchApiModels.AgentModelCommand command) {
        row.setTenantModelProviderId(command.providerConnectionId());
        row.setModelId(command.modelId());
        row.setTemperature(command.temperature());
        row.setTopP(command.topP());
        row.setMaxTokens(command.maxTokens());
        row.setTimeoutSeconds(command.timeoutSeconds());
    }

    private void replaceKnowledge(long agentId, long tenantId, List<Long> knowledgeIds) {
        agentKnowledge.deleteByAgentId(agentId, tenantId);
        for (Long knowledgeId : knowledgeIds) agentKnowledge.insertLink(agentId, knowledgeId, tenantId);
    }

    private Agent owned(long id, long tenantId) {
        Agent row = agents.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getId, id)
                .eq(Agent::getTenantId, tenantId)
                .isNull(Agent::getDeletedAt));
        if (row == null) throw notFound();
        return row;
    }

    private Agent lockOwned(long id, long tenantId) {
        Agent row = agents.selectForUpdate(id, tenantId);
        if (row == null) throw notFound();
        return row;
    }

    private AgentModel activeModel(Long id, long tenantId) {
        if (id == null) return null;
        return models.selectOne(new LambdaQueryWrapper<AgentModel>()
                .eq(AgentModel::getId, id)
                .eq(AgentModel::getTenantId, tenantId)
                .isNull(AgentModel::getDeletedAt));
    }

    private AgentWorkbenchApiModels.AgentDetailView toDetail(
            Agent row, AgentModel model, List<Long> knowledgeIds, boolean editable) {
        return new AgentWorkbenchApiModels.AgentDetailView(
                row.getId(), row.getName(), row.getDescription(), row.getPrologue(), row.getSystemPrompt(),
                row.getTags(), row.getVariables(), knowledgeIds, row.getRetrievalTopK(),
                row.getRetrievalScoreThreshold(), toModelView(model), status(row),
                value(row.getDraftRevision(), 1L), value(row.getPublishedRevision(), 0L),
                row.getCurrentSnapshotId(), value(row.getLockVersion(), 0L), row.getLastDebuggedAt(),
                row.getLastDebuggedBy(), row.getUpdateTime(), editable);
    }

    private AgentWorkbenchApiModels.AgentModelView toModelView(AgentModel model) {
        if (model == null) return null;
        return new AgentWorkbenchApiModels.AgentModelView(
                model.getId(), model.getTenantModelProviderId(), model.getModelId(), model.getTemperature(),
                model.getTopP(), model.getMaxTokens(), model.getTimeoutSeconds());
    }

    private AgentWorkbenchApiModels.AgentListItem toListItem(Agent row) {
        List<Long> ids = safeList(agentKnowledge.selectKnowledgeIds(row.getId(), tenantId()));
        if (!isAdmin() && row.getCurrentSnapshotId() != null) {
            AgentSnapshot snapshot = currentSnapshot(row);
            AgentSnapshotData data = snapshot.getSnapshotData();
            return new AgentWorkbenchApiModels.AgentListItem(
                    row.getId(), data.name(), data.description(), data.tags(), "PUBLISHED",
                    data.knowledgeIds().size(), snapshot.getVersionNumber(), data.model() != null,
                    row.getLastDebuggedAt(), row.getLastDebuggedBy(), row.getUpdateTime());
        }
        AgentSnapshot snapshot = row.getCurrentSnapshotId() == null ? null : currentSnapshot(row);
        return new AgentWorkbenchApiModels.AgentListItem(
                row.getId(), row.getName(), row.getDescription(), row.getTags(), status(row), ids.size(),
                snapshot == null ? null : snapshot.getVersionNumber(),
                row.getAgentModelId() != null, row.getLastDebuggedAt(), row.getLastDebuggedBy(), row.getUpdateTime());
    }

    private AgentSnapshot currentSnapshot(Agent row) {
        AgentSnapshot snapshot = snapshots.selectOne(new LambdaQueryWrapper<AgentSnapshot>()
                .eq(AgentSnapshot::getId, row.getCurrentSnapshotId())
                .eq(AgentSnapshot::getAgentId, row.getId())
                .eq(AgentSnapshot::getTenantId, tenantId())
                .isNull(AgentSnapshot::getDeletedAt));
        if (snapshot == null) throw notFound("AGENT_SNAPSHOT_NOT_FOUND", "智能体发布快照不存在");
        return snapshot;
    }

    private AgentWorkbenchApiModels.AgentModelView snapshotModelView(
            AgentSnapshotData.ModelConfiguration model) {
        if (model == null) return null;
        return new AgentWorkbenchApiModels.AgentModelView(
                null, model.providerConnectionId(), model.modelId(), model.temperature(), model.topP(),
                model.maxTokens(), model.timeoutSeconds());
    }

    public static String status(Agent row) {
        if (row.getCurrentSnapshotId() == null) return "UNPUBLISHED";
        return value(row.getDraftRevision(), 1L) > value(row.getPublishedRevision(), 0L)
                ? "DRAFT_CHANGED" : "PUBLISHED";
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String trimLower(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private String trimUpper(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private static <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private long tenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) throw new AgentWorkbenchException(
                403, "TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
        return context.getTenantId();
    }

    private long userId() {
        AuthContext context = AuthContext.current();
        if (context == null) throw new AgentWorkbenchException(403, "TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
        return context.getUserId();
    }

    private boolean isAdmin() {
        AuthContext context = AuthContext.current();
        return context != null && "tenant_admin".equals(context.getRole());
    }

    private void requireAdmin() {
        if (!isAdmin()) throw new AgentWorkbenchException(403, "AGENT_ADMIN_REQUIRED", "需要租户管理员权限");
    }

    private AgentWorkbenchException invalid(String message) {
        return new AgentWorkbenchException(422, "AGENT_DRAFT_INVALID", message);
    }

    private AgentWorkbenchException conflict(String code, String message) {
        return new AgentWorkbenchException(409, code, message);
    }

    private AgentWorkbenchException notFound() {
        return notFound("AGENT_NOT_FOUND", "智能体不存在或无权访问");
    }

    private AgentWorkbenchException notFound(String code, String message) {
        return new AgentWorkbenchException(404, code, message);
    }

    private record ValidatedDraft(
            String name, String description, String prologue, String systemPrompt, List<String> tags,
            List<AgentWorkbenchApiModels.VariableDefinition> variables, List<Long> knowledgeIds,
            int retrievalTopK, BigDecimal retrievalScoreThreshold,
            AgentWorkbenchApiModels.AgentModelCommand model) {
    }
}
