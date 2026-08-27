package com.fangsa.ai.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    public static final String ISS_BUSINESS = "smart-agent";
    public static final String ISS_PLATFORM = "smart-agent-platform";
    public static final String ROLE_PLATFORM_ADMIN = "platform_admin";

    private final SecretKey key;
    private final long accessTtlMillis;

    public JwtService(@Value("${jwt.secret:dev-secret-please-change-32bytes-min}") String secret,
                      @Value("${jwt.access-ttl-millis:900000}") long accessTtlMillis) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret must be >= 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.accessTtlMillis = accessTtlMillis;
    }

    public String signAccess(long userId, long tenantId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISS_BUSINESS)
                .subject("u:" + userId)
                .claim("tid", tenantId)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTtlMillis)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String signPlatformAccess(long platformAdminId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISS_PLATFORM)
                .subject("pa:" + platformAdminId)
                .claim("role", ROLE_PLATFORM_ADMIN)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTtlMillis)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public ParsedClaims verifyAccess(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISS_BUSINESS, ISS_PLATFORM)
                    .build()
                    .parseSignedClaims(token);
            Claims c = jws.getPayload();
            Long tid = c.get("tid", Long.class);
            return new ParsedClaims(
                    c.getIssuer(),
                    c.getSubject(),
                    tid,
                    c.get("role", String.class),
                    c.getId(),
                    c.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new AuthException(AuthErrorCode.EXPIRED, "access token expired");
        } catch (JwtException | IllegalArgumentException e) {
            throw new AuthException(AuthErrorCode.MISSING_TOKEN, "invalid token: " + e.getMessage());
        }
    }
}