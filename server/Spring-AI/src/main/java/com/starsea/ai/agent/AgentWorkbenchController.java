package com.starsea.ai.agent;

import com.starsea.ai.auth.RequireLogin;
import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.dto.AjaxResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agents")
@RequireLogin
public class AgentWorkbenchController {
    private final AgentAggregateService service;

    public AgentWorkbenchController(AgentAggregateService service) {
        this.service = service;
    }

    @GetMapping
    public AjaxResult list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String keyword) {
        return AjaxResult.success(service.list(
                new AgentWorkbenchApiModels.AgentListQuery(page, pageSize, status, tag, keyword)));
    }

    @GetMapping("/metrics")
    public AjaxResult metrics() {
        return AjaxResult.success(service.metrics());
    }

    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable long id) {
        return AjaxResult.success(service.get(id));
    }

    @PostMapping
    @RequireRole("tenant_admin")
    public AjaxResult create(@RequestBody AgentWorkbenchApiModels.DraftCommand command) {
        return AjaxResult.success(service.create(command));
    }

    @PutMapping("/{id}/draft")
    @RequireRole("tenant_admin")
    public AjaxResult updateDraft(
            @PathVariable long id,
            @RequestBody AgentWorkbenchApiModels.DraftCommand command) {
        return AjaxResult.success(service.updateDraft(id, command));
    }

    @DeleteMapping("/{id}")
    @RequireRole("tenant_admin")
    public AjaxResult delete(@PathVariable long id) {
        service.delete(id);
        return AjaxResult.success();
    }
}
