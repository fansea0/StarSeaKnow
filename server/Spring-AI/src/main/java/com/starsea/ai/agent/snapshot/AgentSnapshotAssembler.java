package com.starsea.ai.agent.snapshot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.agent.AgentModel;
import com.starsea.ai.agent.AgentWorkbenchApiModels;
import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentKnowledgeMapper;
import com.starsea.ai.mapper.AgentModelMapper;
import com.starsea.ai.mapper.ModelProviderCatalogMapper;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.model.provider.ModelProviderCatalog;
import com.starsea.ai.model.provider.TenantModelProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import com.starsea.ai.model.provider.ModelSuggestion;

@Component
public class AgentSnapshotAssembler {
    private final AgentKnowledgeMapper links;
    private final AgentModelMapper models;
    private final TenantModelProviderMapper providers;
    private final ModelProviderCatalogMapper catalogs;

    public AgentSnapshotAssembler(
            AgentKnowledgeMapper links,
            AgentModelMapper models,
            TenantModelProviderMapper providers,
            ModelProviderCatalogMapper catalogs) {
        this.links = links;
        this.models = models;
        this.providers = providers;
        this.catalogs = catalogs;
    }

    public AgentSnapshotData assemble(Agent agent) {
        if (agent.getAgentModelId() == null) {
            throw new AgentWorkbenchException(422, "AGENT_MODEL_REQUIRED", "发布前必须配置模型");
        }
        long tenantId = agent.getTenantId();
        AgentModel model = models.selectOne(new LambdaQueryWrapper<AgentModel>()
                .eq(AgentModel::getId, agent.getAgentModelId())
                .eq(AgentModel::getTenantId, tenantId)
                .isNull(AgentModel::getDeletedAt));
        if (model == null) throw unavailable("AGENT_MODEL_UNAVAILABLE", "智能体模型配置不存在");
        TenantModelProvider provider = providers.selectOne(new LambdaQueryWrapper<TenantModelProvider>()
                .eq(TenantModelProvider::getId, model.getTenantModelProviderId())
                .eq(TenantModelProvider::getTenantId, tenantId));
        if (provider == null) throw unavailable("MODEL_PROVIDER_CONNECTION_NOT_FOUND", "厂商连接不存在");
        boolean selectable = provider.getSelectableModels() != null && provider.getSelectableModels().stream()
                .map(ModelSuggestion::modelId)
                .anyMatch(model.getModelId()::equals);
        if (!selectable) throw unavailable("AGENT_MODEL_UNAVAILABLE", "模型已不在厂商候选列表中");
        ModelProviderCatalog catalog = provider.getCatalogProviderId() == null
                ? null : catalogs.selectById(provider.getCatalogProviderId());
        if (provider.getCatalogProviderId() != null && catalog == null) {
            throw unavailable("MODEL_PROVIDER_NOT_FOUND", "模型厂商不存在");
        }
        String code = catalog == null ? "CUSTOM" : catalog.getCode();
        String name = catalog == null ? provider.getCustomName() : catalog.getName();
        String icon = catalog == null ? provider.getCustomIcon() : catalog.getIcon();
        AgentSnapshotData.ModelConfiguration modelData = new AgentSnapshotData.ModelConfiguration(
                provider.getId(), code, name, icon, provider.getBaseUrl(), provider.getProtocolType(),
                provider.getAuthType(), model.getModelId(), model.getTemperature(), model.getTopP(),
                model.getMaxTokens(), model.getTimeoutSeconds());
        List<Long> knowledgeIds = links.selectKnowledgeIds(agent.getId(), tenantId);
        if (knowledgeIds == null) knowledgeIds = List.of();
        return new AgentSnapshotData(
                agent.getName(), agent.getDescription(), agent.getPrologue(), agent.getTags(),
                agent.getSystemPrompt(), agent.getVariables(), knowledgeIds, modelData,
                agent.getRetrievalTopK(), agent.getRetrievalScoreThreshold());
    }

    public AgentWorkbenchApiModels.DraftCommand toDraftCommand(AgentSnapshotData data, long lockVersion) {
        AgentSnapshotData.ModelConfiguration model = data.model();
        AgentWorkbenchApiModels.AgentModelCommand modelCommand = model == null ? null
                : new AgentWorkbenchApiModels.AgentModelCommand(
                        model.providerConnectionId(), model.modelId(), model.temperature(), model.topP(),
                        model.maxTokens(), model.timeoutSeconds());
        return new AgentWorkbenchApiModels.DraftCommand(
                data.name(), data.description(), data.prologue(), data.systemPrompt(), data.tags(),
                data.variables(), data.knowledgeIds(), data.retrievalTopK(),
                data.retrievalScoreThreshold(), modelCommand, lockVersion);
    }

    private AgentWorkbenchException unavailable(String code, String message) {
        return new AgentWorkbenchException(409, code, message);
    }
}
