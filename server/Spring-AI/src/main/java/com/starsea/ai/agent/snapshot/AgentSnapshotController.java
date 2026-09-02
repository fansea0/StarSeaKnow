package com.starsea.ai.agent.snapshot;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.dto.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/agents/{agentId}")
@RequireLogin
@RequireRole("tenant_admin")
public class AgentSnapshotController {
    private final AgentSnapshotService service;

    public AgentSnapshotController(AgentSnapshotService service) {
        this.service = service;
    }

    @GetMapping("/snapshots")
    public AjaxResult list(@PathVariable long agentId,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int pageSize) {
        return AjaxResult.success(service.list(agentId, page, pageSize));
    }

    @GetMapping("/snapshots/{version}")
    public AjaxResult get(@PathVariable long agentId, @PathVariable long version) {
        return AjaxResult.success(service.get(agentId, version));
    }

    @PostMapping("/publish")
    public AjaxResult publish(
            @PathVariable long agentId,
            @RequestBody AgentSnapshotService.PublishCommand command) {
        return AjaxResult.success(service.publish(agentId, command));
    }

    @PostMapping("/snapshots/{version}/rollback")
    public AjaxResult rollback(
            @PathVariable long agentId,
            @PathVariable long version,
            @RequestBody AgentSnapshotService.RollbackCommand command) {
        return AjaxResult.success(service.rollback(agentId, version, command));
    }
}
