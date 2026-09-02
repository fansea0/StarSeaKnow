package com.starsea.ai.agent.snapshot;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.config.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentSnapshotControllerTest {
    private AgentSnapshotService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentSnapshotService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentSnapshotController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void snapshot_routes_require_logged_in_tenant_admin() {
        assertThat(AgentSnapshotController.class.getAnnotation(RequireLogin.class)).isNotNull();
        assertThat(AgentSnapshotController.class.getAnnotation(RequireRole.class).value())
                .isEqualTo("tenant_admin");
    }

    @Test
    void publishes_and_returns_the_new_version() throws Exception {
        AgentSnapshot snapshot = new AgentSnapshot();
        snapshot.setId(801L);
        snapshot.setVersionNumber(3L);
        when(service.publish(eq(101L), any())).thenReturn(snapshot);

        mockMvc.perform(post("/agents/101/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publishNote\":\"完成配置\",\"lockVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNumber").value(3));
    }
}
