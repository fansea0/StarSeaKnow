package com.starsea.ai.agent.debug;

import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.agent.execution.ExecutionEvent;
import com.starsea.ai.auth.AuthAspect;
import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.config.GlobalExceptionHandler;
import com.starsea.ai.mapper.PlatformAdminMapper;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentDebugControllerTest {
    private final DebugExecutionCoordinator coordinator = mock(DebugExecutionCoordinator.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_admin", "jti"));
        var proxy = new AspectJProxyFactory(new AgentDebugController(coordinator));
        proxy.addAspect(new AuthAspect(mock(PlatformAdminMapper.class)));
        AgentDebugController controller = proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clear() { AuthContext.clear(); }

    @Test void exports_a_json_attachment_without_an_ajax_wrapper() throws Exception {
        UUID id = UUID.randomUUID();
        when(coordinator.export(101, id)).thenReturn(new DebugSessionExport(1, false, java.util.List.of(), java.util.List.of()));
        mvc.perform(get("/agents/101/debug-contexts/" + id + "/export"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.model").isArray())
                .andExpect(jsonPath("$.turns").isArray()).andExpect(jsonPath("$.data").doesNotExist());
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_member", "jti"));
        mvc.perform(get("/agents/101/debug-contexts/" + id + "/export")).andExpect(status().isForbidden());
        verify(coordinator, times(1)).export(101, id);
    }

    @Test void serializes_named_sse_events_and_disables_intermediary_buffering() throws Exception {
        UUID id = UUID.randomUUID();
        when(coordinator.stream(eq(101L), any())).thenReturn(Flux.just(
                new ExecutionEvent("context", new DebugExecutionCoordinator.ContextEvent(id)),
                new ExecutionEvent("delta", new ExecutionEvent.Delta("测试回答")),
                new ExecutionEvent("complete", new ExecutionEvent.Complete("stop"))));
        var result = mvc.perform(post("/agents/101/debug/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\",\"variables\":{}}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Accel-Buffering", "no")).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        String response = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response).contains("event:context", id.toString(), "event:delta", "测试回答", "event:complete");
    }

    @Test void preflight_errors_remain_json_http_errors_even_when_accepting_only_sse() throws Exception {
        when(coordinator.stream(eq(101L), any())).thenThrow(new AgentWorkbenchException(410, "DEBUG_CONTEXT_EXPIRED", "上下文失效"));
        mvc.perform(post("/agents/101/debug/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isGone()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.errorCode").value("DEBUG_CONTEXT_EXPIRED"));
    }

    @Test void member_cannot_start_or_delete_admin_debug_contexts() throws Exception {
        AuthContext.set(new AuthContext(AuthContext.Kind.BUSINESS, 71, 9L, "tenant_member", "jti"));
        mvc.perform(post("/agents/101/debug/stream").accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"q\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/agents/101/debug-contexts/" + UUID.randomUUID())).andExpect(status().isForbidden());
        verifyNoInteractions(coordinator);
    }

    @Test void malformed_request_and_missing_auth_return_json_before_starting_stream() throws Exception {
        mvc.perform(post("/agents/not-a-number/debug/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"q\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(post("/agents/101/debug/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        AuthContext.clear();
        mvc.perform(post("/agents/101/debug/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"q\"}"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        verifyNoInteractions(coordinator);
    }

    @Test void servlet_disconnect_cancels_the_execution_subscription() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        when(coordinator.stream(eq(101L), any())).thenReturn(Flux.<ExecutionEvent>never().doOnCancel(() -> cancelled.set(true)));
        var result = mvc.perform(post("/agents/101/debug/stream").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"q\"}")).andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext context = (MockAsyncContext) result.getRequest().getAsyncContext();
        for (var listener : context.getListeners()) listener.onError(new AsyncEvent(context, new java.io.IOException("client disconnected")));
        assertThat(cancelled).isTrue();
    }
}
