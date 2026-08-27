package com.fansea.ai.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookie {

    private final long maxAgeSeconds;

    public RefreshCookie(@Value("${jwt.refresh-ttl-millis:2592000000}") long ttlMillis) {
        this.maxAgeSeconds = ttlMillis / 1000L;
    }

    public void setBusiness(HttpServletResponse resp, String raw, boolean delete) {
        String cookie = "sm_refresh=" + (delete ? "" : raw)
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (delete ? 0 : maxAgeSeconds);
        resp.addHeader("Set-Cookie", cookie);
    }

    public void setPlatform(HttpServletResponse resp, String raw, boolean delete) {
        String cookie = "sm_platform_refresh=" + (delete ? "" : raw)
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (delete ? 0 : maxAgeSeconds);
        resp.addHeader("Set-Cookie", cookie);
    }
}
