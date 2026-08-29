package com.starsea.ai.openapi.error;

import org.springframework.http.HttpStatus;

public class ExternalApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String param;

    public ExternalApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ExternalApiException(HttpStatus status, String code, String message, String param) {
        super(message);
        this.status = status;
        this.code = code;
        this.param = param;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getParam() {
        return param;
    }
}
