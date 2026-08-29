package com.fansea.ai.openapi.credential;

import com.fansea.ai.tenant.TenantStatusChangeNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Component
public final class CachedTenantStatusChangeNotifier implements TenantStatusChangeNotifier {
    private static final Logger log = LoggerFactory.getLogger(CachedTenantStatusChangeNotifier.class);
    private final ApiCredentialCache cache;

    public CachedTenantStatusChangeNotifier(ApiCredentialCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public void statusChanged(Long tenantId) {
        if (tenantId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safeEvict(tenantId, "immediate");
            return;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { safeEvict(tenantId, "after_commit"); }
            });
        } catch (RuntimeException exception) {
            log.warn("event=tenant_security_cache_invalidation_failed stage=register tenant_id={}",
                    tenantId, exception);
            safeEvict(tenantId, "register_fallback");
        }
    }

    private void safeEvict(Long tenantId, String stage) {
        try {
            cache.evictTenant(tenantId);
        } catch (RuntimeException exception) {
            log.warn("event=tenant_security_cache_invalidation_failed stage={} tenant_id={}",
                    stage, tenantId, exception);
        }
    }
}
