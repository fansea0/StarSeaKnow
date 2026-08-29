package com.fansea.ai.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderTest {
    private final PasswordEncoder enc = new BcryptPasswordEncoder();

    @Test
    void hashAndMatch_roundTrip() {
        String h = enc.hash("hunter2!");
        assertTrue(enc.matches("hunter2!", h));
        assertFalse(enc.matches("hunter3!", h));
    }

    @Test
    void hash_producesDifferentOutputEachTime() {
        assertNotEquals(enc.hash("x"), enc.hash("x"));
    }
}
