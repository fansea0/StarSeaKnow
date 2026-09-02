package com.starsea.ai.agent.execution;

import com.starsea.ai.agent.AgentWorkbenchException;
import com.starsea.ai.auth.RequireLogin;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/agents/{agentId}/chat")
@RequireLogin
public class PublishedAgentChatController {
    private final SnapshotExecutionSourceLoader published;
    private final AgentExecutionService execution;
    public PublishedAgentChatController(SnapshotExecutionSourceLoader published, AgentExecutionService execution) {
        this.published = published; this.execution = execution;
    }

    /** Stateless invocation; never accepts draft debug contexts or caller-supplied system/history messages. */
    @PostMapping("/stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable long agentId, @RequestBody ChatCommand command) {
        if (command == null) throw new AgentWorkbenchException(422, "AGENT_MESSAGE_INVALID", "消息不能为空");
        ExecutionRequest request = new ExecutionRequest(command.message(), command.variables(), List.of());
        var events = execution.execute(published.load(agentId), request);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-store").header("X-Accel-Buffering", "no")
                .body(AgentSseStreams.open(events));
    }

    public record ChatCommand(String message, Map<String, String> variables) {
        @Override public String toString() { return "PublishedChatCommand[content=<redacted>]"; }
    }
}
