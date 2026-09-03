package com.starsea.ai.agent.debug;

import com.starsea.ai.agent.execution.AgentSseStreams;
import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.dto.AjaxResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/agents/{agentId}")
@RequireLogin
@RequireRole("tenant_admin")
public class AgentDebugController {
    private final DebugExecutionCoordinator coordinator;
    public AgentDebugController(DebugExecutionCoordinator coordinator) { this.coordinator = coordinator; }

    @PostMapping("/debug/stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable long agentId,
                                            @RequestBody DebugExecutionCoordinator.DebugCommand command) {
        var events = coordinator.stream(agentId, command);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(AgentSseStreams.open(events));
    }

    @DeleteMapping("/debug-contexts/{debugContextId}")
    public AjaxResult delete(@PathVariable long agentId, @PathVariable UUID debugContextId) {
        coordinator.delete(agentId, debugContextId);
        return AjaxResult.success();
    }

    @GetMapping("/debug-contexts/{debugContextId}/export")
    public ResponseEntity<DebugSessionExport> export(@PathVariable long agentId, @PathVariable UUID debugContextId) {
        var result = coordinator.export(agentId, debugContextId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=\"agent-" + agentId + "-session.json\"")
                .body(result);
    }
}
