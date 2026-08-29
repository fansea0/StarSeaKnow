package com.starsea.ai.auth;

public class AuthException extends RuntimeException {
    private final int code;
    public AuthException(AuthErrorCode code, String msg) {
        super(msg);
        this.code = code.code();
    }
    public AuthException(int code, String msg) {
        super(msg);
        this.code = code;
    }
    public int getCode() { return code; }
}
