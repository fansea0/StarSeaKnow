package com.fansea.ai.openapi.credential;

public class CredentialAuthenticationException extends RuntimeException {

    private final String code;

    public CredentialAuthenticationException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
