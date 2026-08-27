package com.fangsa.ai.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenServiceTest {

    private RefreshTokenStore store;
    private RefreshTokenService svc;
    private final long userId = 42L;
    private final long ttlMillis = 30L * 24 * 3600 * 1000;

    @BeforeEach
    void setUp() {
        store = mock(RefreshTokenStore.class);
        svc = new RefreshTokenService(store, ttlMillis);
    }

    @Test
    void issue_returnsRaw_andPersistsHash() {
        RefreshTokenStore.IssueInsert insert = new RefreshTokenStore.IssueInsert(
                userId, UUID.randomUUID(), "hash-xyz", Instant.now().plusMillis(ttlMillis),
                null, null, null);
        when(store.insert(any())).thenReturn(insert);

        RefreshTokenService.IssueResult r = svc.issue(userId, "ua", "ip");
        assertNotNull(r.rawToken());

        ArgumentCaptor<RefreshTokenStore.InsertParams> cap = ArgumentCaptor.forClass(RefreshTokenStore.InsertParams.class);
        verify(store).insert(cap.capture());
        RefreshTokenStore.InsertParams p = cap.getValue();
        assertEquals(userId, p.userId());
        assertTrue(p.rawToken().length() >= 43, "raw token must be at least 43 chars base64");
        assertEquals(p.expiresAt(), r.expiresAt());
        assertTrue(p.tokenHash().matches("^[a-f0-9]{64}$"), "hash must be SHA-256 hex");
        assertNotEquals(p.rawToken(), p.tokenHash());
    }

    @Test
    void rotate_happyPath_issuesNewAndRevokesOld() {
        String raw = "old-raw-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        UUID family = UUID.randomUUID();
        RefreshTokenStore.LookupRow oldRow = new RefreshTokenStore.LookupRow(
                100L, userId, family, null, null, Instant.now().plusSeconds(60));
        when(store.findByHash(any())).thenReturn(Optional.of(oldRow));

        RefreshTokenStore.IssueInsert newInsert = new RefreshTokenStore.IssueInsert(
                userId, family, "newhash", Instant.now().plusSeconds(60), 100L, null, null);
        when(store.insert(any())).thenReturn(newInsert);

        RefreshTokenService.RotateResult r = svc.rotate(raw, "ua", "ip");
        assertEquals(userId, r.userId());
        assertNotEquals(raw, r.newRawToken());

        verify(store).revoke(100L);
        verify(store).insert(any());
    }

    @Test
    void rotate_revokedOldToken_throws40103_andRevokesFamily() {
        String raw = "stolen-raw-token";
        UUID family = UUID.randomUUID();
        RefreshTokenStore.LookupRow oldRow = new RefreshTokenStore.LookupRow(
                100L, userId, family, 99L, Instant.now(), Instant.now().plusSeconds(60));
        when(store.findByHash(any())).thenReturn(Optional.of(oldRow));

        AuthException ex = assertThrows(AuthException.class, () -> svc.rotate(raw, "ua", "ip"));
        assertEquals(40103, ex.getCode());
        verify(store).revokeFamily(family);
    }

    @Test
    void rotate_expiredToken_throws40102() {
        RefreshTokenStore.LookupRow oldRow = new RefreshTokenStore.LookupRow(
                100L, userId, UUID.randomUUID(), null, null, Instant.now().minusSeconds(1));
        when(store.findByHash(any())).thenReturn(Optional.of(oldRow));
        AuthException ex = assertThrows(AuthException.class, () -> svc.rotate("x", null, null));
        assertEquals(40102, ex.getCode());
    }

    @Test
    void rotate_unknownToken_throws40102() {
        when(store.findByHash(any())).thenReturn(Optional.empty());
        AuthException ex = assertThrows(AuthException.class, () -> svc.rotate("x", null, null));
        assertEquals(40102, ex.getCode());
    }

    @Test
    void revokeFamily_callsStore() {
        UUID f = UUID.randomUUID();
        svc.revokeFamily(f);
        verify(store).revokeFamily(f);
    }
}