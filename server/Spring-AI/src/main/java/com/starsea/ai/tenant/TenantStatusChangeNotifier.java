package com.fansea.ai.tenant;

/** Neutral application port for security state derived from tenant availability. */
public interface TenantStatusChangeNotifier {
    void statusChanged(Long tenantId);
}
