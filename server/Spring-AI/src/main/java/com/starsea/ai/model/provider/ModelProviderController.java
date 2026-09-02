package com.starsea.ai.model.provider;

import com.starsea.ai.auth.RequireRole;
import com.starsea.ai.domain.dto.AjaxResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/model-providers")
@RequireRole("tenant_admin")
public class ModelProviderController {

    private final ModelProviderService service;

    public ModelProviderController(ModelProviderService service) {
        this.service = service;
    }

    @GetMapping
    public AjaxResult list() {
        return AjaxResult.success(service.listProviders());
    }

    @PostMapping("/connections/test")
    public AjaxResult testConnection(
            @RequestBody ModelProviderApiModels.ConnectionCommand command) {
        return AjaxResult.success(service.testConnection(command));
    }

    @PostMapping("/connections")
    public AjaxResult createConnection(
            @RequestBody ModelProviderApiModels.ConnectionCommand command) {
        return AjaxResult.success(service.createConnection(command));
    }

    @PutMapping("/connections/{connectionId}")
    public AjaxResult updateConnection(
            @PathVariable long connectionId,
            @RequestBody ModelProviderApiModels.ConnectionCommand command) {
        return AjaxResult.success(service.updateConnection(connectionId, command));
    }

    @PostMapping("/connections/{connectionId}/models")
    public AjaxResult addModel(
            @PathVariable long connectionId,
            @RequestBody ModelSuggestion model) {
        return AjaxResult.success(service.addModel(connectionId, model));
    }

    @PutMapping("/connections/{connectionId}/models")
    public AjaxResult replaceModels(
            @PathVariable long connectionId,
            @RequestBody ModelProviderApiModels.ModelListCommand command) {
        return AjaxResult.success(service.replaceModels(connectionId, command));
    }

    @DeleteMapping("/connections/{connectionId}")
    public AjaxResult deleteConnection(@PathVariable long connectionId) {
        service.deleteConnection(connectionId);
        return AjaxResult.success();
    }
}
