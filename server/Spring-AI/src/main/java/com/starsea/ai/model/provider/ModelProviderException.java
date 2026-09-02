package com.starsea.ai.model.provider;

public class ModelProviderException extends RuntimeException {
    private final int status;
    private final String code;

    public ModelProviderException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
