package com.fansea.ai.openapi.credential;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.auth.AuthErrorCode;
import com.fansea.ai.auth.AuthException;
import com.fansea.ai.auth.RequireLogin;
import com.fansea.ai.auth.RequireRole;
import com.fansea.ai.domain.dto.AjaxResult;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/tenant/api-credentials")
@RequireLogin
@RequireRole("tenant_admin")
public class TenantApiCredentialController {

    private final ApiCredentialService service;

    public TenantApiCredentialController(ApiCredentialService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AjaxResult> create(@RequestBody CreateCredentialRequest request) {
        validateCreateRequest(request);
        ApiCredentialService.CreatedCredential created = service.create(
                new ApiCredentialService.CreateCredentialCommand(
                        request.name(), request.credentialType(), request.environment(), request.knowledgeIds(),
                        request.allowedIpCidrs(), request.requestsPerMinute(), request.burstCapacity(),
                        request.maxConcurrency(), request.expiresAt()),
                AuthContext.current());
        CreateCredentialResponse response = new CreateCredentialResponse(created.credential(), created.apiKey());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(AjaxResult.success(response));
    }

    @GetMapping
    public AjaxResult list() {
        return AjaxResult.success(service.list(AuthContext.current()));
    }

    @GetMapping("/{credentialId}")
    public AjaxResult get(@PathVariable UUID credentialId) {
        return AjaxResult.success(service.get(credentialId, AuthContext.current()));
    }

    @PatchMapping("/{credentialId}")
    public AjaxResult update(@PathVariable UUID credentialId,
                             @RequestBody PatchCredentialRequest request) {
        if (request == null) {
            throw invalid("credential update is required");
        }
        ApiCredentialService.UpdateCredentialCommand command = new ApiCredentialService.UpdateCredentialCommand(
                request.name, request.description, request.allowedIpCidrs, request.requestsPerMinute,
                request.burstCapacity, request.maxConcurrency, request.expiresAtPresent, request.expiresAt,
                request.status);
        return AjaxResult.success(service.update(credentialId, command, AuthContext.current()));
    }

    @PutMapping("/{credentialId}/knowledge-bases")
    public AjaxResult replaceKnowledgeBases(@PathVariable UUID credentialId,
                                            @RequestBody ReplaceKnowledgeBasesRequest request) {
        if (request == null || request.knowledgeIds() == null) {
            throw invalid("knowledgeIds is required");
        }
        return AjaxResult.success(service.replaceKnowledgeBases(
                credentialId, request.knowledgeIds(), AuthContext.current()));
    }

    @PostMapping("/{credentialId}/rotate")
    public ResponseEntity<AjaxResult> rotate(@PathVariable UUID credentialId) {
        ApiCredentialService.RotatedCredential rotated = service.rotate(credentialId, AuthContext.current());
        RotateCredentialResponse response = new RotateCredentialResponse(rotated.credential(), rotated.apiKey());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AjaxResult.success(response));
    }

    @PostMapping("/{credentialId}/revoke")
    public AjaxResult revoke(@PathVariable UUID credentialId) {
        return AjaxResult.success(service.revoke(credentialId, AuthContext.current()));
    }

    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> delete(@PathVariable UUID credentialId) {
        service.delete(credentialId, AuthContext.current());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<AjaxResult> invalidManagementRequest() {
        return ResponseEntity.badRequest().body(AjaxResult.error(
                AuthErrorCode.REGISTRATION_INVALID.code(), "invalid credential management request"));
    }

    private void validateCreateRequest(CreateCredentialRequest request) {
        if (request == null || isBlank(request.name()) || isBlank(request.credentialType())
                || isBlank(request.environment())) {
            throw invalid("name, credentialType and environment are required");
        }
        if (request.requestsPerMinute() < 1 || request.burstCapacity() < 1
                || request.maxConcurrency() < 1) {
            throw invalid("credential limits must be positive");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private AuthException invalid(String message) {
        return new AuthException(AuthErrorCode.REGISTRATION_INVALID, message);
    }

    public record CreateCredentialRequest(
            String name,
            String credentialType,
            String environment,
            Set<UUID> knowledgeIds,
            List<String> allowedIpCidrs,
            int requestsPerMinute,
            int burstCapacity,
            int maxConcurrency,
            Instant expiresAt) {
    }

    public static final class PatchCredentialRequest {
        public String name;
        public String description;
        public List<String> allowedIpCidrs;
        public Integer requestsPerMinute;
        public Integer burstCapacity;
        public Integer maxConcurrency;
        public String status;
        public Instant expiresAt;
        public boolean expiresAtPresent;

        public void setExpiresAt(Instant expiresAt) {
            this.expiresAtPresent = true;
            this.expiresAt = expiresAt;
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object ignored) {
            throw new IllegalArgumentException("unknown field: " + field);
        }
    }

    public record ReplaceKnowledgeBasesRequest(Set<UUID> knowledgeIds) {
    }

    public record CreateCredentialResponse(ApiCredentialService.ApiCredentialView credential, String apiKey) {
    }

    public record RotateCredentialResponse(ApiCredentialService.ApiCredentialView credential, String apiKey) {
    }
}
