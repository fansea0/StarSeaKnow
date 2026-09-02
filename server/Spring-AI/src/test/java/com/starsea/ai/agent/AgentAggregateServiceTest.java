package com.starsea.ai.agent;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentKnowledgeMapper;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentModelMapper;
import com.starsea.ai.mapper.KnowledgeMapper;
import com.starsea.ai.mapper.TenantModelProviderMapper;
import com.starsea.ai.model.provider.ModelSuggestion;
import com.starsea.ai.model.provider.TenantModelProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAggregateServiceTest {

    private AgentMapper agents;
    private AgentModelMapper models;
    private AgentKnowledgeMapper agentKnowledge;
    private KnowledgeMapper knowledge;
    private TenantModelProviderMapper providers;
    private AgentAggregateService service;

    @BeforeEach
    void setUp() {
        agents = mock(AgentMapper.class);
        models = mock(AgentModelMapper.class);
        agentKnowledge = mock(AgentKnowledgeMapper.class);
        knowledge = mock(KnowledgeMapper.class);
        providers = mock(TenantModelProviderMapper.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), ZoneOffset.UTC);
        service = new AgentAggregateService(agents, models, agentKnowledge, knowledge, providers, clock);
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71L, 9L, "tenant_admin", "jti"));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void creates_agent_with_an_exclusive_validated_model_and_knowledge_links_atomically() {
        TenantModelProvider provider = provider(30L, 9L, "gpt-4o-mini");
        when(providers.selectOne(any())).thenReturn(provider);
        when(knowledge.selectCount(any())).thenReturn(2L);
        doAnswer(invocation -> {
            Agent row = invocation.getArgument(0);
            row.setId(101L);
            return 1;
        }).when(agents).insert(any());
        doAnswer(invocation -> {
            AgentModel row = invocation.getArgument(0);
            row.setId(201L);
            return 1;
        }).when(models).insert(any());

        AgentWorkbenchApiModels.AgentDetailView result = service.create(command(0L));

        ArgumentCaptor<Agent> agentRow = ArgumentCaptor.forClass(Agent.class);
        ArgumentCaptor<AgentModel> modelRow = ArgumentCaptor.forClass(AgentModel.class);
        verify(agents).insert(agentRow.capture());
        verify(models).insert(modelRow.capture());
        verify(agents).updateById(agentRow.getValue());
        verify(agentKnowledge).insertLink(101L, 501L, 9L);
        verify(agentKnowledge).insertLink(101L, 502L, 9L);
        assertThat(agentRow.getValue().getAgentModelId()).isEqualTo(201L);
        assertThat(agentRow.getValue().getDraftRevision()).isEqualTo(1L);
        assertThat(modelRow.getValue().getTenantModelProviderId()).isEqualTo(30L);
        assertThat(result.status()).isEqualTo("UNPUBLISHED");
    }

    @Test
    void rejects_a_model_that_is_not_in_the_provider_candidate_list() {
        when(providers.selectOne(any())).thenReturn(provider(30L, 9L, "other-model"));
        when(knowledge.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.create(command(0L)))
                .isInstanceOf(AgentWorkbenchException.class)
                .hasMessage("所选模型不在厂商候选列表中");
        verify(agents, never()).insert(any());
        verify(models, never()).insert(any());
    }

    @Test
    void stale_lock_version_is_a_conflict_and_does_not_mutate_the_draft() {
        Agent existing = agent(101L, 9L, 4L, 6L, 0L);
        when(agents.selectForUpdate(101L, 9L)).thenReturn(existing);

        assertThatThrownBy(() -> service.updateDraft(101L, command(5L)))
                .isInstanceOf(AgentWorkbenchException.class)
                .extracting("status", "code")
                .containsExactly(409, "AGENT_DRAFT_CONFLICT");
        verify(agents, never()).updateById(any());
        verify(agentKnowledge, never()).deleteByAgentId(anyLong(), anyLong());
    }

    @Test
    void saves_the_complete_draft_and_increments_revision_and_lock_version_once() {
        Agent existing = agent(101L, 9L, 4L, 6L, 201L);
        when(agents.selectForUpdate(101L, 9L)).thenReturn(existing);
        when(models.selectOne(any())).thenReturn(existingModel(201L));
        when(providers.selectOne(any())).thenReturn(provider(30L, 9L, "gpt-4o-mini"));
        when(knowledge.selectCount(any())).thenReturn(2L);

        AgentWorkbenchApiModels.AgentDetailView result = service.updateDraft(101L, command(4L));

        assertThat(existing.getDraftRevision()).isEqualTo(7L);
        assertThat(existing.getLockVersion()).isEqualTo(5L);
        assertThat(existing.getLastEditedBy()).isEqualTo(71L);
        assertThat(result.status()).isEqualTo("DRAFT_CHANGED");
        verify(agentKnowledge).deleteByAgentId(101L, 9L);
        verify(agentKnowledge).insertLink(101L, 501L, 9L);
        verify(agentKnowledge).insertLink(101L, 502L, 9L);
        verify(models).updateById(any());
        verify(agents).updateById(existing);
    }

    @Test
    void members_never_receive_unpublished_or_changed_drafts() {
        Agent unpublished = agent(101L, 9L, 0L, 1L, null);
        Agent published = agent(102L, 9L, 3L, 3L, 201L);
        Agent changed = agent(103L, 9L, 3L, 4L, 202L);
        published.setCurrentSnapshotId(801L);
        changed.setCurrentSnapshotId(802L);
        when(agents.selectList(any())).thenReturn(List.of(unpublished, published, changed));
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 72L, 9L, "tenant_member", "jti"));

        AgentWorkbenchApiModels.AgentPage result = service.list(
                new AgentWorkbenchApiModels.AgentListQuery(1, 20, null, null, null));

        assertThat(result.items()).extracting(AgentWorkbenchApiModels.AgentListItem::id)
                .containsExactly(102L);
        assertThatThrownBy(() -> service.get(101L))
                .isInstanceOf(AgentWorkbenchException.class)
                .extracting("status")
                .isEqualTo(404);
    }

    @Test
    void soft_deletes_agent_and_exclusive_model_with_the_same_timestamp() {
        Agent existing = agent(101L, 9L, 4L, 6L, 201L);
        AgentModel model = existingModel(201L);
        when(agents.selectForUpdate(101L, 9L)).thenReturn(existing);
        when(models.selectOne(any())).thenReturn(model);

        service.delete(101L);

        OffsetDateTime expected = OffsetDateTime.parse("2026-09-03T01:00:00Z");
        assertThat(existing.getDeletedAt()).isEqualTo(expected);
        assertThat(existing.getDeletedBy()).isEqualTo(71L);
        assertThat(model.getDeletedAt()).isEqualTo(expected);
        assertThat(model.getDeletedBy()).isEqualTo(71L);
        verify(agents).updateById(existing);
        verify(models).updateById(model);
    }

    private AgentWorkbenchApiModels.DraftCommand command(long lockVersion) {
        return new AgentWorkbenchApiModels.DraftCommand(
                "合同助手", "审阅合同", "你好，我可以协助审阅合同。", "你是合同审阅专家。",
                List.of("法务", "审阅"),
                List.of(new AgentWorkbenchApiModels.VariableDefinition("company", "公司名", "星海", true)),
                List.of(501L, 502L), 6, new BigDecimal("0.35"),
                new AgentWorkbenchApiModels.AgentModelCommand(
                        30L, "gpt-4o-mini", new BigDecimal("0.4"), new BigDecimal("0.9"), 2048, 30),
                lockVersion);
    }

    private TenantModelProvider provider(long id, long tenantId, String modelId) {
        TenantModelProvider row = new TenantModelProvider();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setSelectableModels(List.of(new ModelSuggestion(modelId, modelId, 128000)));
        return row;
    }

    private Agent agent(long id, long tenantId, long publishedRevision, long draftRevision, Long modelId) {
        Agent row = new Agent();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setName("Agent " + id);
        row.setDescription("description");
        row.setPrologue("hello");
        row.setSystemPrompt("prompt");
        row.setTags(List.of("tag"));
        row.setVariables(List.of());
        row.setAgentModelId(modelId);
        row.setRetrievalTopK(5);
        row.setRetrievalScoreThreshold(new BigDecimal("0.2"));
        row.setDraftRevision(draftRevision);
        row.setPublishedRevision(publishedRevision);
        if (publishedRevision > 0) row.setCurrentSnapshotId(800L + id);
        row.setLockVersion(4L);
        return row;
    }

    private AgentModel existingModel(long id) {
        AgentModel row = new AgentModel();
        row.setId(id);
        row.setTenantId(9L);
        row.setTenantModelProviderId(30L);
        row.setModelId("gpt-4o-mini");
        row.setTemperature(new BigDecimal("0.4"));
        row.setTopP(new BigDecimal("0.9"));
        row.setMaxTokens(2048);
        row.setTimeoutSeconds(30);
        return row;
    }
}
