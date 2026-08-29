package com.fansea.ai.openapi.credential;

import com.fansea.ai.tenant.TenantStatusChangeNotifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Component
public final class CachedTenantStatusChangeNotifier implements TenantStatusChangeNotifier {
    private final ApiCredentialCache cache;

    public CachedTenantStatusChangeNotifier(ApiCredentialCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public void statusChanged(Long tenantId) {
        if (tenantId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evictTenant(tenantId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evictTenant(tenantId); }
        });
    }
}
