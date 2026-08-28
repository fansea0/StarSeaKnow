package com.fansea.ai.openapi.error;

import com.fansea.ai.openapi.credential.CredentialAuthenticationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.regex.Pattern;

@RestControllerAdvice(basePackages = "com.fansea.ai.openapi")
public class ExternalApiExceptionHandler {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern API_KEY_SHAPE = Pattern.compile("[a-z]+_[a-z0-9-]+_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorEnvelope> external(ExternalApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getParam(), request);
    }

    @ExceptionHandler(CredentialAuthenticationException.class)
    public ResponseEntity<ErrorEnvelope> credential(CredentialAuthenticationException exception,
                                                     HttpServletRequest request) {
        return switch (exception.code()) {
            case "credential_disabled" -> response(HttpStatus.UNAUTHORIZED, "credential_disabled",
                    "Credential is unavailable.", null, request);
            case "credential_type_not_supported" -> response(HttpStatus.UNAUTHORIZED, "credential_type_not_supported",
                    "Credential type is not supported.", null, request);
            default -> response(HttpStatus.UNAUTHORIZED, "authentication_failed", "Authentication failed.", null, request);
        };
    }

    private ResponseEntity<ErrorEnvelope> response(HttpStatus status, String code, String message, String param,
                                                    HttpServletRequest request) {
        String requestId = requestId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Request-ID", requestId);
        return new ResponseEntity<>(new ErrorEnvelope(requestId, new ErrorBody(code, message, param)), headers, status);
    }

    private String requestId(HttpServletRequest request) {
        String supplied = request.getHeader("X-Request-ID");
        return StringUtils.hasText(supplied) && SAFE_REQUEST_ID.matcher(supplied).matches()
                && !API_KEY_SHAPE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    public record ErrorEnvelope(String request_id, ErrorBody error) {
    }

    public record ErrorBody(String code, String message, String param) {
    }
}
