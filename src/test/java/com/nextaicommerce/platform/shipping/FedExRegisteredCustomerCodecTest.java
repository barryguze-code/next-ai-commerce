package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FedExRegisteredCustomerCodecTest {
    final UUID tenant = UUID.randomUUID(), connection = UUID.randomUUID();
    final FedExRegisteredCustomer.Scope scope = new FedExRegisteredCustomer.Scope(tenant, connection, 1, FedExClient.Environment.SANDBOX, "001234567");
    final FedExRegisteredCustomer customer = new FedExRegisteredCustomer(scope, "fixture-child-key", "fixture-child-secret");
    final FedExRegisteredCustomerCodec codec = new FedExRegisteredCustomerCodec(new ShippingLabelCrypto(Base64.getEncoder().encodeToString(new byte[32])), new ObjectMapper());
    @Test void credentialsAreEncryptedAndCanOnlyBeOpenedForExactScope() {
        var encrypted = codec.seal(customer); var opened = codec.open(scope, encrypted);
        assertThat(new String(encrypted.payload(), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("fixture-child-key", "fixture-child-secret");
        assertThat(encrypted.nonce()).hasSize(12); assertThat(opened.scope.account()).isEqualTo("001234567");
        assertThat(opened.childKey).isEqualTo(customer.childKey); assertThat(opened.childSecret).isEqualTo(customer.childSecret);
    }
    @Test void tenantConnectionRevisionEnvironmentAndExactLeadingZeroIdentityAreAuthenticated() {
        var encrypted = codec.seal(customer);
        var wrong = List.of(
            new FedExRegisteredCustomer.Scope(UUID.randomUUID(), connection, 1, FedExClient.Environment.SANDBOX, "001234567"),
            new FedExRegisteredCustomer.Scope(tenant, UUID.randomUUID(), 1, FedExClient.Environment.SANDBOX, "001234567"),
            new FedExRegisteredCustomer.Scope(tenant, connection, 2, FedExClient.Environment.SANDBOX, "001234567"),
            new FedExRegisteredCustomer.Scope(tenant, connection, 1, FedExClient.Environment.PRODUCTION, "001234567"),
            new FedExRegisteredCustomer.Scope(tenant, connection, 1, FedExClient.Environment.SANDBOX, "01234567"));
        for (var value : wrong) assertThatThrownBy(() -> codec.open(value, encrypted)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("fixture-child").hasMessageNotContaining(scope.account());
    }
    @Test void tamperingAndMissingChildCredentialsFailClosed() {
        var encrypted = codec.seal(customer); encrypted.payload()[0] ^= 1;
        assertThatThrownBy(() -> codec.open(scope, encrypted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FedExRegisteredCustomer(scope, "key", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FedExRegisteredCustomer(scope, "", "secret")).isInstanceOf(IllegalArgumentException.class);
        assertThat(scope.toString()).doesNotContain(scope.account());
    }
}
