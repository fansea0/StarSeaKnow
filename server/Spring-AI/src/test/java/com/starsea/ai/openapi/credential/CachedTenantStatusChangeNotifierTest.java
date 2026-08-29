package com.starsea.ai.openapi.credential;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import java.util.List;
import java.util.Set;

class CachedTenantStatusChangeNotifierTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void evictsTenantOnlyAfterStatusTransactionCommits() {
        ApiCredentialCache cache = mock(ApiCredentialCache.class);
        CachedTenantStatusChangeNotifier notifier = new CachedTenantStatusChangeNotifier(cache);
        TransactionSynchronizationManager.initSynchronization();

        notifier.statusChanged(22L);

        verify(cache, never()).evictTenant(22L);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        verify(cache).evictTenant(22L);
    }

    @Test
    void cachedCredentialBecomesUnavailableImmediatelyAfterCommit() {
        ApiCredentialCache cache = new CaffeineApiCredentialCache();
        cache.putValid("cached-key", new ApiCredentialResolver.CachedCredential(
                41L, 22L, CredentialType.RAG_RETRIEVAL, "test", "digest", "v1", "active",
                null, List.of(), 60, 10, 5, 1L, new RagKnowledgeScopeSnapshot(Set.of(11L))));
        CachedTenantStatusChangeNotifier notifier = new CachedTenantStatusChangeNotifier(cache);
        TransactionSynchronizationManager.initSynchronization();

        notifier.statusChanged(22L);
        assertThat(cache.get("cached-key")).isInstanceOf(CachedCredentialResult.Hit.class);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(cache.get("cached-key")).isInstanceOf(CachedCredentialResult.NotCached.class);
    }

    @Test
    void cacheFailureAfterCommitIsContained() {
        ApiCredentialCache cache = mock(ApiCredentialCache.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("cache unavailable"))
                .when(cache).evictTenant(22L);
        CachedTenantStatusChangeNotifier notifier = new CachedTenantStatusChangeNotifier(cache);
        TransactionSynchronizationManager.initSynchronization();
        notifier.statusChanged(22L);

        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        verify(cache).evictTenant(22L);
    }
}
