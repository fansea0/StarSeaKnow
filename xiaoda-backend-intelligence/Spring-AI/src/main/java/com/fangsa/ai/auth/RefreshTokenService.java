package com.fangsa.ai.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final SecureRandom RNG = new SecureRandom();

    private final RefreshTokenStore store;
    private final long ttlMillis;

    public RefreshTokenService(RefreshTokenStore store,
                               @Value("${jwt.refresh-ttl-millis:2592000000}") long ttlMillis) {
        this.store = store;
        this.ttlMillis = ttlMillis;
    }

    public record IssueResult(String rawToken, Instant expiresAt, UUID familyId) {}
    public record RotateResult(String newRawToken, long userId, UUID familyId) {}

    public IssueResult issue(long userId, String userAgent, String ip) {
        String raw = newRawToken();
        String hash = sha256(raw);
        UUID family = UUID.randomUUID();
        Instant exp = Instant.now().plusMillis(ttlMillis);
        store.insert(new RefreshTokenStore.InsertParams(userId, family, raw, hash, exp, userAgent, ip));
        return new IssueResult(raw, exp, family);
    }

    public RotateResult rotate(String rawToken, String userAgent, String ip) {
        String hash = sha256(rawToken);
        RefreshTokenStore.LookupRow row = store.findByHash(hash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.REFRESH_EXPIRED, "refresh not found"));

        if (row.revokedAt() != null) {
            store.revokeFamily(row.familyId());
            throw new AuthException(AuthErrorCode.REFRESH_REUSE, "refresh reuse detected");
        }
        if (row.expiresAt().isBefore(Instant.now())) {
            throw new AuthException(AuthErrorCode.REFRESH_EXPIRED, "refresh expired");
        }

        store.revoke(row.id());
        String newRaw = newRawToken();
        String newHash = sha256(newRaw);
        Instant newExp = Instant.now().plusMillis(ttlMillis);
        store.insert(new RefreshTokenStore.InsertParams(row.userId(), row.familyId(), newRaw, newHash, newExp, userAgent, ip));
        return new RotateResult(newRaw, row.userId(), row.familyId());
    }

    public void revokeFamily(UUID familyId) { store.revokeFamily(familyId); }

    public java.util.Optional<RefreshTokenStore.LookupRow> findByHash(String hash) {
        return store.findByHash(hash);
    }

    private static String newRawToken() {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}