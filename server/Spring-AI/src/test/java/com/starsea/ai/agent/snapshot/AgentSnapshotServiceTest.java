package com.starsea.ai.agent.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.agent.AgentAggregateService;
import com.starsea.ai.agent.AgentModel;
import com.starsea.ai.agent.AgentWorkbenchApiModels;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentKnowledgeMapper;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentModelMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import com.starsea.ai.mapper.ModelProviderCatalogMapper;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.model.provider.ModelProviderCatalog;
import com.starsea.ai.model.provider.TenantModelProvider;
import com.starsea.ai.model.provider.ModelSuggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSnapshotServiceTest {

    private AgentMapper agents;
    private AgentSnapshotMapper snapshots;
    private AgentSnapshotAssembler assembler;
    private AgentAggregateService aggregate;
    private AgentSnapshotService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T02:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        agents = mock(AgentMapper.class);
        snapshots = mock(AgentSnapshotMapper.class);
        assembler = mock(AgentSnapshotAssembler.class);
        aggregate = mock(AgentAggregateService.class);
        service = new AgentSnapshotService(agents, snapshots, assembler, aggregate, clock);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71L, 9L, "tenant_admin", "jti"));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void publishes_an_immutable_snapshot_and_advances_agent_state_atomically() throws Exception {
        Agent agent = agent(101L, 5L, 2L);
        when(agents.selectForUpdate(101L, 9L)).thenReturn(agent);
        AgentSnapshotData data = snapshotData();
        when(assembler.assemble(agent)).thenReturn(data);
        when(snapshots.nextVersion(101L, 9L)).thenReturn(3L);
        doAnswer(invocation -> {
            AgentSnapshot row = invocation.getArgument(0);
            row.setId(801L);
            return 1;
        }).when(snapshots).insert(any());

        AgentSnapshot result = service.publish(101L, new AgentSnapshotService.PublishCommand("完成法务规则", 2L));

        ArgumentCaptor<AgentSnapshot> inserted = ArgumentCaptor.forClass(AgentSnapshot.class);
        verify(snapshots).insert(inserted.capture());
        assertThat(inserted.getValue().getVersionNumber()).isEqualTo(3L);
        assertThat(inserted.getValue().getSourceRevision()).isEqualTo(5L);
        assertThat(inserted.getValue().getRollbackFromSnapshotId()).isNull();
        assertThat(agent.getCurrentSnapshotId()).isEqualTo(801L);
        assertThat(agent.getPublishedRevision()).isEqualTo(5L);
        assertThat(agent.getLockVersion()).isEqualTo(3L);
        verify(agents).updateById(agent);
        assertThat(result).isSameAs(inserted.getValue());

        String serialized = new ObjectMapper().writeValueAsString(inserted.getValue().getSnapshotData());
        assertThat(serialized).doesNotContainIgnoringCase("apiKey", "cipher", "nonce", "secret");
    }

    @Test
    void rejects_publish_when_the_client_lock_version_is_stale() {
        when(agents.selectForUpdate(101L, 9L)).thenReturn(agent(101L, 5L, 4L));

        assertThatThrownBy(() -> service.publish(
                101L, new AgentSnapshotService.PublishCommand("note", 3L)))
                .extracting("status", "code")
                .containsExactly(409, "AGENT_DRAFT_CONFLICT");
        verify(assembler, never()).assemble(any());
        verify(snapshots, never()).insert(any());
    }

    @Test void rejects_oversized_publish_note_before_database_write() {
        when(agents.selectForUpdate(101L, 9L)).thenReturn(agent(101L, 5L, 4L));
        assertThatThrownBy(() -> service.publish(101L, new AgentSnapshotService.PublishCommand("a".repeat(513), 4L)))
                .isInstanceOf(com.starsea.ai.agent.AgentWorkbenchException.class).extracting("status").isEqualTo(422);
        verify(snapshots, never()).insert(any());
    }

    @Test
    void lists_bounded_metadata_without_loading_snapshot_payloads() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "snapshot-test"), AgentSnapshot.class);
        when(agents.selectOne(any())).thenReturn(agent(101L, 5L, 2L));
        when(snapshots.selectCount(any())).thenReturn(120L);
        when(snapshots.selectList(any())).thenReturn(List.of());
        var result = service.list(101L, 3, 20);
        assertThat(result.total()).isEqualTo(120L);
        assertThat(result.page()).isEqualTo(3);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentSnapshot>> query =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(snapshots).selectList(query.capture());
        assertThat(query.getValue().getSqlSelect()).doesNotContain("snapshot_data");
        assertThat(query.getValue().getSqlSegment()).contains("LIMIT 20 OFFSET 40");
    }

    @Test
    void rollback_restores_snapshot_as_a_new_draft_and_creates_a_new_version() {
        AgentSnapshot source = new AgentSnapshot();
        source.setId(701L);
        source.setTenantId(9L);
        source.setAgentId(101L);
        source.setVersionNumber(1L);
        source.setSnapshotData(snapshotData());
        when(snapshots.selectOne(any())).thenReturn(source);
        when(assembler.toDraftCommand(source.getSnapshotData(), 4L))
                .thenReturn(draftCommand(4L));
        AgentWorkbenchApiModels.AgentDetailView restored = detail(101L, 6L, 5L);
        when(aggregate.updateDraft(101L, draftCommand(4L))).thenReturn(restored);
        Agent updated = agent(101L, 6L, 5L);
        when(agents.selectForUpdate(101L, 9L)).thenReturn(updated);
        when(assembler.assemble(updated)).thenReturn(snapshotData());
        when(snapshots.nextVersion(101L, 9L)).thenReturn(4L);
        doAnswer(invocation -> {
            AgentSnapshot row = invocation.getArgument(0);
            row.setId(804L);
            return 1;
        }).when(snapshots).insert(any());

        AgentSnapshot result = service.rollback(
                101L, 1L, new AgentSnapshotService.RollbackCommand("恢复稳定规则", 4L));

        assertThat(result.getVersionNumber()).isEqualTo(4L);
        assertThat(result.getRollbackFromSnapshotId()).isEqualTo(701L);
        assertThat(result.getSourceRevision()).isEqualTo(6L);
        assertThat(updated.getCurrentSnapshotId()).isEqualTo(804L);
        assertThat(updated.getPublishedRevision()).isEqualTo(6L);
    }

    @Test
    void assembler_contains_provider_identity_but_never_secret_material() throws Exception {
        AgentKnowledgeMapper links = mock(AgentKnowledgeMapper.class);
        AgentModelMapper models = mock(AgentModelMapper.class);
        TenantModelProviderMapper providers = mock(TenantModelProviderMapper.class);
        ModelProviderCatalogMapper catalogs = mock(ModelProviderCatalogMapper.class);
        AgentSnapshotAssembler realAssembler = new AgentSnapshotAssembler(links, models, providers, catalogs);
        Agent agent = agent(101L, 5L, 2L);
        agent.setAgentModelId(201L);
        agent.setName("合同助手");
        agent.setSystemPrompt("系统规则");
        AgentModel model = new AgentModel();
        model.setId(201L);
        model.setTenantId(9L);
        model.setTenantModelProviderId(30L);
        model.setModelId("gpt-4o-mini");
        model.setTemperature(new BigDecimal("0.4"));
        model.setTopP(new BigDecimal("0.9"));
        model.setMaxTokens(2048);
        model.setTimeoutSeconds(30);
        TenantModelProvider provider = new TenantModelProvider();
        provider.setId(30L);
        provider.setTenantId(9L);
        provider.setCatalogProviderId(1L);
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setProtocolType("OPENAI_COMPATIBLE");
        provider.setAuthType("API_KEY");
        provider.setSelectableModels(List.of(
                new ModelSuggestion("gpt-4o-mini", "GPT-4o Mini", 128000)));
        provider.setApiKeyCiphertext("cipher-must-not-leak");
        provider.setApiKeyNonce("nonce-must-not-leak");
        ModelProviderCatalog catalog = new ModelProviderCatalog();
        catalog.setId(1L);
        catalog.setCode("OPENAI");
        catalog.setName("OpenAI");
        catalog.setIcon("provider/openai");
        when(links.selectKnowledgeIds(101L, 9L)).thenReturn(List.of(501L));
        when(models.selectOne(any())).thenReturn(model);
        when(providers.selectOne(any())).thenReturn(provider);
        when(catalogs.selectById(1L)).thenReturn(catalog);

        AgentSnapshotData result = realAssembler.assemble(agent);
        String json = new ObjectMapper().writeValueAsString(result);

        assertThat(result.model().providerConnectionId()).isEqualTo(30L);
        assertThat(result.model().providerName()).isEqualTo("OpenAI");
        assertThat(json).doesNotContain("cipher-must-not-leak", "nonce-must-not-leak")
                .doesNotContainIgnoringCase("apiKey");
    }

    private Agent agent(long id, long draftRevision, long lockVersion) {
        Agent row = new Agent();
        row.setId(id);
        row.setTenantId(9L);
        row.setName("Agent");
        row.setSystemPrompt("Prompt");
        row.setTags(List.of());
        row.setVariables(List.of());
        row.setRetrievalTopK(5);
        row.setRetrievalScoreThreshold(new BigDecimal("0.2"));
        row.setDraftRevision(draftRevision);
        row.setPublishedRevision(0L);
        row.setLockVersion(lockVersion);
        return row;
    }

    private AgentSnapshotData snapshotData() {
        return new AgentSnapshotData(
                "合同助手", "审阅合同", "你好", List.of("法务"), "系统规则", List.of(),
                List.of(501L),
                new AgentSnapshotData.ModelConfiguration(
                        30L, "OPENAI", "OpenAI", "provider/openai", "https://api.openai.com/v1",
                        "OPENAI_COMPATIBLE", "API_KEY", "gpt-4o-mini", new BigDecimal("0.4"),
                        new BigDecimal("0.9"), 2048, 30),
                5, new BigDecimal("0.2"));
    }

    private AgentWorkbenchApiModels.DraftCommand draftCommand(long lockVersion) {
        return new AgentWorkbenchApiModels.DraftCommand(
                "合同助手", "审阅合同", "你好", "系统规则", List.of("法务"), List.of(), List.of(501L),
                5, new BigDecimal("0.2"),
                new AgentWorkbenchApiModels.AgentModelCommand(
                        30L, "gpt-4o-mini", new BigDecimal("0.4"), new BigDecimal("0.9"), 2048, 30),
                lockVersion);
    }

    private AgentWorkbenchApiModels.AgentDetailView detail(long id, long draftRevision, long lockVersion) {
        return new AgentWorkbenchApiModels.AgentDetailView(
                id, "合同助手", "审阅合同", "你好", "系统规则", List.of("法务"), List.of(), List.of(501L),
                5, new BigDecimal("0.2"), null, "DRAFT_CHANGED", draftRevision, 5L, 800L,
                lockVersion, null, null, null, true);
    }
}
