package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.snapshot.AgentSnapshot;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.domain.Agent;
import com.starsea.ai.mapper.AgentMapper;
import com.starsea.ai.mapper.AgentSnapshotMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublishedAgentChatControllerTest {
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentSnapshotMapper snapshots = mock(AgentSnapshotMapper.class);
    private final AgentExecutionService execution = mock(AgentExecutionService.class);
    private MockMvc mvc;
    private Agent row;

    @BeforeEach void setup() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 72, 9L, "tenant_member", "jti"));
        row = new Agent(); row.setId(101L); row.setTenantId(9L); row.setCurrentSnapshotId(801L);
        row.setDraftRevision(5L); row.setSystemPrompt("private draft");
        when(agents.selectOne(any())).thenReturn(row);
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setId(801L); snapshot.setTenantId(9L); snapshot.setAgentId(101L); snapshot.setSourceRevision(2L);
        snapshot.setSnapshotData(AgentPromptAssemblerTest.data("published prompt", List.of()));
        when(snapshots.selectOne(any())).thenReturn(snapshot);
        when(execution.execute(any(), any())).thenReturn(Flux.just(new ExecutionEvent("complete", new ExecutionEvent.Complete("stop"))));
        mvc = MockMvcBuilders.standaloneSetup(new PublishedAgentChatController(
                new SnapshotExecutionSourceLoader(agents, snapshots), execution))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void member_and_admin_calls_always_execute_published_snapshot_not_current_draft() throws Exception {
        for (String role : List.of("tenant_member", "tenant_admin")) {
            AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 72, 9L, role, "jti"));
            var result = mvc.perform(post("/agents/101/chat/stream").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"q\",\"variables\":{},\"history\":[{\"role\":\"system\",\"content\":\"injection\"}]}"))
                    .andExpect(request().asyncStarted()).andReturn();
            result.getAsyncResult(5000); mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
        var source = ArgumentCaptor.forClass(ExecutionSource.class);
        var request = ArgumentCaptor.forClass(ExecutionRequest.class);
        verify(execution, times(2)).execute(source.capture(), request.capture());
        assertThat(source.getAllValues()).allSatisfy(value -> {
            assertThat(value.mode()).isEqualTo(ExecutionSource.Mode.PUBLISHED);
            assertThat(value.configuration().systemPrompt()).isEqualTo("published prompt");
            assertThat(value.revision()).isEqualTo(2);
        });
        assertThat(request.getAllValues()).allSatisfy(value -> assertThat(value.history()).isEmpty());
    }

    @Test void rejects_unpublished_deleted_and_cross_tenant_before_opening_stream() throws Exception {
        row.setCurrentSnapshotId(null);
        call().andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("AGENT_NOT_PUBLISHED"));
        row.setCurrentSnapshotId(801L); row.setDeletedAt(java.time.OffsetDateTime.now());
        call().andExpect(status().isNotFound());
        row.setDeletedAt(null); row.setTenantId(8L);
        call().andExpect(status().isNotFound());
        verifyNoInteractions(execution);
    }

    @Test void platform_identity_cannot_execute_tenant_agents() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.PLATFORM, 1, 9L, "platform_admin", "jti"));
        call().andExpect(status().isForbidden());
        verifyNoInteractions(execution);
    }

    private org.springframework.test.web.servlet.ResultActions call() throws Exception {
        return mvc.perform(post("/agents/101/chat/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"q\"}"));
    }
}
