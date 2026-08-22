package com.nextaicommerce.platform.tenant;

import java.util.UUID;

public final class TenantContext implements AutoCloseable {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext(UUID tenantId) { CURRENT.set(tenantId); }

    public static TenantContext open(UUID tenantId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        return new TenantContext(tenantId);
    }

    public static UUID requireTenantId() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) throw new MissingTenantException();
        return tenantId;
    }

    @Override public void close() { CURRENT.remove(); }
}
