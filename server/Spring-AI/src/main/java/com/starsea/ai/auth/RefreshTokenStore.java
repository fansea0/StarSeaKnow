package com.fansea.ai.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {

    record InsertParams(long userId, UUID familyId, String rawToken, String tokenHash,
                        Instant expiresAt, String userAgent, String ip) {}

    record IssueInsert(long userId, UUID familyId, String tokenHash, Instant expiresAt,
                       Long parentId, String userAgent, String ip) {}

    record LookupRow(long id, long userId, UUID familyId,
                     Long parentId, Instant revokedAt, Instant expiresAt) {}

    IssueInsert insert(InsertParams p);
    Optional<LookupRow> findByHash(String tokenHash);
    void revoke(long id);
    void revokeFamily(UUID familyId);
}
