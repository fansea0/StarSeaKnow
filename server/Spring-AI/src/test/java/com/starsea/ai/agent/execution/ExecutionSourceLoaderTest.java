package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.snapshot.AgentSnapshot;
import com.starsea.ai.agent.snapshot.AgentSnapshotAssembler;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import org.junit.jupiter.api.*;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ExecutionSourceLoaderTest {
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentSnapshotMapper snapshots = mock(AgentSnapshotMapper.class);
    private final AgentSnapshotAssembler assembler = mock(AgentSnapshotAssembler.class);
    private final DraftExecutionSourceLoader drafts = new DraftExecutionSourceLoader(agents, assembler);
    private final SnapshotExecutionSourceLoader published = new SnapshotExecutionSourceLoader(agents, snapshots);

    @BeforeEach void auth() { AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_admin", "jti")); }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void draft_loader_uses_draft_even_when_a_published_snapshot_exists() {
        Agent agent = agent();
        when(agents.selectOne(any())).thenReturn(agent);
        when(assembler.assemble(agent)).thenReturn(AgentPromptAssemblerTest.data("草稿", List.of()));
        var source = drafts.load(101);
        assertThat(source.mode()).isEqualTo(ExecutionSource.Mode.DRAFT);
        assertThat(source.revision()).isEqualTo(5L);
        assertThat(source.configuration().systemPrompt()).isEqualTo("草稿");
    }

    @Test void member_published_loader_uses_only_current_snapshot_not_changed_draft() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_member", "jti"));
        when(agents.selectOne(any())).thenReturn(agent());
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setId(801L); snapshot.setTenantId(9L); snapshot.setAgentId(101L); snapshot.setSourceRevision(2L);
        snapshot.setSnapshotData(AgentPromptAssemblerTest.data("发布版", List.of()));
        when(snapshots.selectOne(any())).thenReturn(snapshot);
        var source = published.load(101);
        assertThat(source.mode()).isEqualTo(ExecutionSource.Mode.PUBLISHED);
        assertThat(source.revision()).isEqualTo(2L);
        assertThat(source.configuration().systemPrompt()).isEqualTo("发布版");
        assertThatThrownBy(() -> drafts.load(101)).extracting("status").isEqualTo(403);
        verifyNoInteractions(assembler);
    }

    @Test void refuses_unpublished_deleted_cross_tenant_and_wrong_agent_snapshot() {
        Agent row = agent(); row.setCurrentSnapshotId(null);
        when(agents.selectOne(any())).thenReturn(row);
        assertThatThrownBy(() -> published.load(101)).extracting("code").isEqualTo("AGENT_NOT_PUBLISHED");
        row.setCurrentSnapshotId(801L); row.setDeletedAt(OffsetDateTime.now());
        assertThatThrownBy(() -> drafts.load(101)).extracting("status").isEqualTo(404);
        row.setDeletedAt(null); row.setTenantId(8L);
        assertThatThrownBy(() -> published.load(101)).extracting("status").isEqualTo(404);
        row.setTenantId(9L);
        AgentSnapshot wrong = new AgentSnapshot();
        wrong.setId(801L); wrong.setTenantId(9L); wrong.setAgentId(999L);
        wrong.setSnapshotData(AgentPromptAssemblerTest.data("不能读取", List.of()));
        when(snapshots.selectOne(any())).thenReturn(wrong);
        assertThatThrownBy(() -> published.load(101)).extracting("status").isEqualTo(409);
    }

    private Agent agent() {
        Agent row = new Agent(); row.setId(101L); row.setTenantId(9L); row.setDraftRevision(5L);
        row.setCurrentSnapshotId(801L); row.setSystemPrompt("未发布的草稿"); return row;
    }
}
