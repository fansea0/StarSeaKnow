package com.fansea.ai.auth;

public interface PasswordEncoder {
    String hash(String raw);
    boolean matches(String raw, String hash);
}
