package com.nextaicommerce.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantContextTest {
    @Test void refusesWorkWithoutTenant() {
        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(MissingTenantException.class);
    }

    @Test void scopesAndClearsTenant() {
        UUID tenantId = UUID.randomUUID();
        try (TenantContext ignored = TenantContext.open(tenantId)) {
            assertThat(TenantContext.requireTenantId()).isEqualTo(tenantId);
        }
        assertThatThrownBy(TenantContext::requireTenantId)
            .isInstanceOf(MissingTenantException.class);
    }

    @Test void rejectsNullTenant() {
        assertThatThrownBy(() -> TenantContext.open(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
