package com.starsea.ai.auth;

import com.starsea.ai.domain.RefreshToken;
import com.starsea.ai.mapper.RefreshTokenMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Component("refreshTokenStore")
public class RefreshTokenStoreImpl implements RefreshTokenStore {

    private final RefreshTokenMapper mapper;

    @Autowired
    public RefreshTokenStoreImpl(RefreshTokenMapper mapper) { this.mapper = mapper; }

    @Override
    public IssueInsert insert(InsertParams p) {
        RefreshToken t = new RefreshToken();
        t.setUserId(p.userId());
        t.setFamilyId(p.familyId());
        t.setTokenHash(p.tokenHash());
        t.setExpiresAt(toOdt(p.expiresAt()));
        t.setUserAgent(p.userAgent());
        t.setIp(p.ip());
        mapper.insert(t);
        return new IssueInsert(p.userId(), p.familyId(), p.tokenHash(),
                p.expiresAt(), null, p.userAgent(), p.ip());
    }

    @Override
    public Optional<LookupRow> findByHash(String tokenHash) {
        return mapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RefreshToken>()
                        .eq("token_hash", tokenHash)
        ).stream().findFirst().map(t -> new LookupRow(
                t.getId(), t.getUserId(), t.getFamilyId(),
                t.getParentId(),
                t.getRevokedAt() == null ? null : t.getRevokedAt().toInstant(),
                t.getExpiresAt().toInstant()
        ));
    }

    @Override public void revoke(long id) { mapper.revokeById(id); }
    @Override public void revokeFamily(UUID familyId) { mapper.revokeByFamily(familyId); }

    private static OffsetDateTime toOdt(Instant i) { return i.atOffset(ZoneOffset.UTC); }
}
