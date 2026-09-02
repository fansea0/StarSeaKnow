package com.starsea.ai.model.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.mapper.ModelProviderCatalogMapper;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.mapper.AgentModelMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import com.starsea.ai.agent.AgentModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelProviderService {

    private final ModelProviderCatalogMapper catalogs;
    private final TenantModelProviderMapper connections;
    private final ModelProviderSecretCipher cipher;
    private final ModelProviderConnectionVerifier verifier;
    private final AgentModelMapper agentModels;
    private final AgentSnapshotMapper snapshots;

    public ModelProviderService(
            ModelProviderCatalogMapper catalogs,
            TenantModelProviderMapper connections,
            ModelProviderSecretCipher cipher,
            ModelProviderConnectionVerifier verifier,
            AgentModelMapper agentModels,
            AgentSnapshotMapper snapshots) {
        this.catalogs = catalogs;
        this.connections = connections;
        this.cipher = cipher;
        this.verifier = verifier;
        this.agentModels = agentModels;
        this.snapshots = snapshots;
    }

    public List<ModelProviderApiModels.ProviderView> listProviders() {
        long tenantId = tenantId();
        List<ModelProviderCatalog> catalogRows = catalogs.selectList(
                new LambdaQueryWrapper<ModelProviderCatalog>().orderByAsc(ModelProviderCatalog::getId));
        List<TenantModelProvider> connectionRows = connections.selectList(
                new LambdaQueryWrapper<TenantModelProvider>()
                        .eq(TenantModelProvider::getTenantId, tenantId)
                        .orderByAsc(TenantModelProvider::getId));

        Map<Long, TenantModelProvider> byCatalog = new LinkedHashMap<>();
        List<TenantModelProvider> custom = new ArrayList<>();
        for (TenantModelProvider row : connectionRows) {
            if (row.getCatalogProviderId() == null) {
                custom.add(row);
            } else {
                byCatalog.put(row.getCatalogProviderId(), row);
            }
        }

        List<ModelProviderApiModels.ProviderView> views = new ArrayList<>();
        for (ModelProviderCatalog catalog : catalogRows) {
            views.add(toView(catalog, byCatalog.get(catalog.getId())));
        }
        custom.stream()
                .sorted(Comparator.comparing(TenantModelProvider::getId))
                .map(row -> toView(null, row))
                .forEach(views::add);
        return List.copyOf(views);
    }

    public ModelProviderApiModels.ConnectionTestResult testConnection(
            ModelProviderApiModels.ConnectionCommand command) {
        ResolvedConnection resolved = resolve(command);
        validateUniqueModelList(command.selectableModels());
        ModelProviderConnectionVerifier.VerifiedConnection verified = verifier.verify(
                resolved.baseUrl(), resolved.authType(), resolved.apiKey());
        return new ModelProviderApiModels.ConnectionTestResult(true, verified.discoveredModels());
    }

    @Transactional
    public ModelProviderApiModels.ProviderView createConnection(
            ModelProviderApiModels.ConnectionCommand command) {
        long tenantId = tenantId();
        long userId = userId();
        ResolvedConnection resolved = resolve(command);
        validateUniqueModelList(command.selectableModels());
        ModelProviderConnectionVerifier.VerifiedConnection verified = verifier.verify(
                resolved.baseUrl(), resolved.authType(), resolved.apiKey());
        List<ModelSuggestion> models = mergeModels(
                resolved.catalog() == null ? List.of() : resolved.catalog().getSuggestedModels(),
                command.selectableModels(), verified.discoveredModels());
        if (resolved.catalog() == null && models.isEmpty()) {
            throw invalid("自定义厂商至少需要一个可选模型");
        }

        long id = connections.nextId();
        TenantModelProvider row = new TenantModelProvider();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setCatalogProviderId(resolved.catalog() == null ? null : resolved.catalog().getId());
        row.setCustomName(resolved.catalog() == null ? resolved.name() : null);
        row.setCustomIcon(resolved.catalog() == null ? resolved.icon() : null);
        row.setBaseUrl(resolved.baseUrl());
        row.setProtocolType(resolved.protocolType());
        row.setAuthType(resolved.authType());
        row.setSelectableModels(models);
        row.setLastVerifiedAt(OffsetDateTime.now());
        row.setCreatedBy(userId);
        if ("API_KEY".equals(resolved.authType())) {
            applyEncryptedSecret(row, cipher.encrypt(tenantId, id, resolved.apiKey()));
        }
        connections.insert(row);
        return toView(resolved.catalog(), row);
    }

    @Transactional
    public ModelProviderApiModels.ProviderView updateConnection(
            long connectionId,
            ModelProviderApiModels.ConnectionCommand command) {
        long tenantId = tenantId();
        TenantModelProvider existing = ownedConnection(connectionId, tenantId);
        ModelProviderCatalog catalog = existing.getCatalogProviderId() == null
                ? null : catalogs.selectById(existing.getCatalogProviderId());
        if (existing.getCatalogProviderId() != null && catalog == null) {
            throw new ModelProviderException(404, "MODEL_PROVIDER_NOT_FOUND", "模型厂商不存在");
        }
        if (command != null && command.catalogProviderId() != null
                && !command.catalogProviderId().equals(existing.getCatalogProviderId())) {
            throw invalid("不能更改厂商连接所属的厂商");
        }

        String baseUrl = command != null && StringUtils.hasText(command.baseUrl())
                ? command.baseUrl().trim() : existing.getBaseUrl();
        String apiKey = resolveUpdateApiKey(existing, command);
        ModelProviderConnectionVerifier.VerifiedConnection verified = verifier.verify(
                baseUrl, existing.getAuthType(), apiKey);
        List<ModelSuggestion> requested = command == null ? List.of() : command.selectableModels();
        List<ModelSuggestion> models = requested.isEmpty()
                ? mergeModels(existing.getSelectableModels(), verified.discoveredModels())
                : mergeModels(requested, verified.discoveredModels());
        if (existing.getCatalogProviderId() == null && models.isEmpty()) {
            throw invalid("自定义厂商至少需要一个可选模型");
        }

        existing.setBaseUrl(baseUrl);
        existing.setSelectableModels(models);
        existing.setLastVerifiedAt(OffsetDateTime.now());
        if (existing.getCatalogProviderId() == null && command != null) {
            if (StringUtils.hasText(command.customName())) {
                existing.setCustomName(command.customName().trim());
            }
            if (StringUtils.hasText(command.customIcon())) {
                existing.setCustomIcon(command.customIcon().trim());
            }
        }
        if ("API_KEY".equals(existing.getAuthType())
                && command != null && StringUtils.hasText(command.apiKey())) {
            applyEncryptedSecret(existing, cipher.encrypt(tenantId, connectionId, command.apiKey().trim()));
        }
        connections.updateById(existing);
        return toView(catalog, existing);
    }

    @Transactional
    public ModelProviderApiModels.ProviderView replaceModels(
            long connectionId,
            ModelProviderApiModels.ModelListCommand command) {
        TenantModelProvider existing = ownedConnection(connectionId, tenantId());
        List<ModelSuggestion> models = command == null ? List.of() : command.models();
        validateUniqueModelList(models);
        if (models.isEmpty()) {
            throw invalid("厂商至少需要一个可选模型");
        }
        rejectRemovalOfModelsInUse(existing, models);
        existing.setSelectableModels(List.copyOf(models));
        connections.updateById(existing);
        ModelProviderCatalog catalog = existing.getCatalogProviderId() == null
                ? null : catalogs.selectById(existing.getCatalogProviderId());
        return toView(catalog, existing);
    }

    @Transactional
    public ModelProviderApiModels.ProviderView addModel(long connectionId, ModelSuggestion model) {
        TenantModelProvider existing = ownedConnection(connectionId, tenantId());
        validateModel(model);
        List<ModelSuggestion> current = existing.getSelectableModels() == null
                ? List.of() : existing.getSelectableModels();
        if (current.stream().anyMatch(item -> item.modelId().equals(model.modelId()))) {
            throw invalid("同一厂商的模型 ID 不能重复");
        }
        List<ModelSuggestion> updated = new ArrayList<>(current);
        updated.add(model);
        existing.setSelectableModels(List.copyOf(updated));
        connections.updateById(existing);
        ModelProviderCatalog catalog = existing.getCatalogProviderId() == null
                ? null : catalogs.selectById(existing.getCatalogProviderId());
        return toView(catalog, existing);
    }

    @Transactional
    public void deleteConnection(long connectionId) {
        TenantModelProvider existing = ownedConnection(connectionId, tenantId());
        long used = agentModels.selectCount(new LambdaQueryWrapper<AgentModel>()
                .eq(AgentModel::getTenantModelProviderId, connectionId)
                .isNull(AgentModel::getDeletedAt));
        long publishedUsage = snapshots.countActiveProviderUsage(tenantId(), connectionId);
        if (used > 0 || publishedUsage > 0) {
            throw new ModelProviderException(409, "MODEL_PROVIDER_IN_USE", "厂商连接正在被智能体使用，不能删除");
        }
        connections.deleteById(existing.getId());
    }

    private void rejectRemovalOfModelsInUse(
            TenantModelProvider existing, List<ModelSuggestion> replacement) {
        List<String> retainedIds = replacement.stream().map(ModelSuggestion::modelId).toList();
        List<String> removedIds = existing.getSelectableModels().stream()
                .map(ModelSuggestion::modelId)
                .filter(modelId -> !retainedIds.contains(modelId))
                .toList();
        if (removedIds.isEmpty()) return;
        long used = agentModels.selectCount(new LambdaQueryWrapper<AgentModel>()
                .eq(AgentModel::getTenantModelProviderId, existing.getId())
                .in(AgentModel::getModelId, removedIds)
                .isNull(AgentModel::getDeletedAt));
        long publishedUsage = snapshots.countActiveModelUsage(
                existing.getTenantId(), existing.getId(), removedIds);
        if (used > 0 || publishedUsage > 0) {
            throw new ModelProviderException(409, "MODEL_PROVIDER_MODEL_IN_USE", "模型正在被智能体使用，不能移除");
        }
    }

    private ResolvedConnection resolve(ModelProviderApiModels.ConnectionCommand command) {
        if (command == null) {
            throw invalid("厂商连接参数不能为空");
        }
        if (command.catalogProviderId() != null) {
            ModelProviderCatalog catalog = catalogs.selectById(command.catalogProviderId());
            if (catalog == null) {
                throw new ModelProviderException(404, "MODEL_PROVIDER_NOT_FOUND", "模型厂商不存在");
            }
            String baseUrl = StringUtils.hasText(command.baseUrl())
                    ? command.baseUrl().trim() : catalog.getDefaultBaseUrl();
            requireSecretWhenNeeded(catalog.getAuthType(), command.apiKey());
            return new ResolvedConnection(catalog, catalog.getName(), catalog.getIcon(), baseUrl,
                    catalog.getProtocolType(), catalog.getAuthType(), trimToNull(command.apiKey()));
        }

        if (!StringUtils.hasText(command.customName()) || !StringUtils.hasText(command.customIcon())
                || !StringUtils.hasText(command.baseUrl())) {
            throw invalid("自定义厂商名称、图标和 Base URL 不能为空");
        }
        requireSecretWhenNeeded("API_KEY", command.apiKey());
        return new ResolvedConnection(null, command.customName().trim(), command.customIcon().trim(),
                command.baseUrl().trim(), "OPENAI_COMPATIBLE", "API_KEY", command.apiKey().trim());
    }

    private void requireSecretWhenNeeded(String authType, String apiKey) {
        if ("API_KEY".equals(authType) && !StringUtils.hasText(apiKey)) {
            throw invalid("API Key 不能为空");
        }
    }

    @SafeVarargs
    private final List<ModelSuggestion> mergeModels(List<ModelSuggestion>... sources) {
        Map<String, ModelSuggestion> merged = new LinkedHashMap<>();
        for (List<ModelSuggestion> source : sources) {
            if (source == null) continue;
            for (ModelSuggestion model : source) {
                validateModel(model);
                merged.putIfAbsent(model.modelId(), model);
            }
        }
        return List.copyOf(merged.values());
    }

    private void validateModel(ModelSuggestion model) {
        if (model == null || !StringUtils.hasText(model.modelId())
                || !StringUtils.hasText(model.displayName()) || model.contextWindow() <= 0) {
            throw invalid("模型必须包含有效的模型 ID、显示名和上下文长度");
        }
    }

    private void validateUniqueModelList(List<ModelSuggestion> models) {
        Map<String, ModelSuggestion> unique = new LinkedHashMap<>();
        for (ModelSuggestion model : models) {
            validateModel(model);
            if (unique.putIfAbsent(model.modelId(), model) != null) {
                throw invalid("同一厂商的模型 ID 不能重复");
            }
        }
    }

    private String resolveUpdateApiKey(
            TenantModelProvider existing,
            ModelProviderApiModels.ConnectionCommand command) {
        if (!"API_KEY".equals(existing.getAuthType())) {
            return null;
        }
        if (command != null && StringUtils.hasText(command.apiKey())) {
            return command.apiKey().trim();
        }
        return cipher.decrypt(existing.getTenantId(), existing.getId(), new EncryptedProviderSecret(
                existing.getApiKeyCiphertext(), existing.getApiKeyNonce(),
                existing.getApiKeyVersion(), existing.getApiKeyLastFour()));
    }

    private TenantModelProvider ownedConnection(long connectionId, long tenantId) {
        TenantModelProvider row = connections.selectOne(
                new LambdaQueryWrapper<TenantModelProvider>()
                        .eq(TenantModelProvider::getId, connectionId)
                        .eq(TenantModelProvider::getTenantId, tenantId));
        if (row == null) {
            throw new ModelProviderException(404, "MODEL_PROVIDER_CONNECTION_NOT_FOUND", "厂商连接不存在");
        }
        return row;
    }

    private void applyEncryptedSecret(TenantModelProvider row, EncryptedProviderSecret secret) {
        row.setApiKeyCiphertext(secret.ciphertext());
        row.setApiKeyNonce(secret.nonce());
        row.setApiKeyVersion(secret.keyVersion());
        row.setApiKeyLastFour(secret.lastFour());
    }

    private ModelProviderApiModels.ProviderView toView(
            ModelProviderCatalog catalog,
            TenantModelProvider connection) {
        boolean configured = connection != null;
        boolean custom = catalog == null;
        return new ModelProviderApiModels.ProviderView(
                catalog == null ? null : catalog.getId(),
                configured ? connection.getId() : null,
                catalog == null ? "CUSTOM" : catalog.getCode(),
                catalog == null ? connection.getCustomName() : catalog.getName(),
                catalog == null ? connection.getCustomIcon() : catalog.getIcon(),
                configured ? connection.getBaseUrl() : catalog.getDefaultBaseUrl(),
                configured ? connection.getProtocolType() : catalog.getProtocolType(),
                configured ? connection.getAuthType() : catalog.getAuthType(),
                configured ? connection.getSelectableModels() : catalog.getSuggestedModels(),
                configured,
                custom,
                configured && "API_KEY".equals(connection.getAuthType()),
                configured ? connection.getApiKeyLastFour() : null);
    }

    private long tenantId() {
        AuthContext context = AuthContext.current();
        if (context == null || context.getTenantId() == null) {
            throw new ModelProviderException(403, "TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context.getTenantId();
    }

    private long userId() {
        AuthContext context = AuthContext.current();
        if (context == null) {
            throw new ModelProviderException(403, "TENANT_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context.getUserId();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private ModelProviderException invalid(String message) {
        return new ModelProviderException(422, "MODEL_PROVIDER_INVALID", message);
    }

    private record ResolvedConnection(
            ModelProviderCatalog catalog,
            String name,
            String icon,
            String baseUrl,
            String protocolType,
            String authType,
            String apiKey) {
    }
}
