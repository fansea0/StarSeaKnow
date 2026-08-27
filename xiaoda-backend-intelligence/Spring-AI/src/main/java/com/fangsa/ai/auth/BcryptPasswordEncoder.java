package com.fangsa.ai.auth;

import org.springframework.security.crypto.bcrypt.BCrypt;

public class BcryptPasswordEncoder implements PasswordEncoder {
    @Override public String hash(String raw) { return BCrypt.hashpw(raw, BCrypt.gensalt(10)); }
    @Override public boolean matches(String raw, String hash) { return BCrypt.checkpw(raw, hash); }
}