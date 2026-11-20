package com.nextaicommerce.platform.shipping;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Offline foundation only: no registration, persistence or connection-service activation. */
public final class FedExRegisteredCustomer {
    public record Scope(UUID tenant, UUID connection, long revision, FedExClient.Environment environment, String account) {
        public Scope {
            Objects.requireNonNull(tenant); Objects.requireNonNull(connection); Objects.requireNonNull(environment);
            if (revision < 1 || account == null || !account.matches("[0-9]{8,12}"))
                throw new IllegalArgumentException("A scoped FedEx registration identity is required.");
        }
        byte[] context() {
            return ("fedex-registered-customer:v1:" + tenant + ":" + connection + ":" + revision + ":" + environment + ":" + account)
                .getBytes(StandardCharsets.UTF_8);
        }
        @Override public String toString() { return "FedEx registration scope [protected]"; }
    }
    final Scope scope;
    final String childKey, childSecret;
    public FedExRegisteredCustomer(Scope scope, String childKey, String childSecret) {
        this.scope = Objects.requireNonNull(scope);
        if (childKey == null || childKey.isBlank() || childSecret == null || childSecret.isBlank())
            throw new IllegalArgumentException("Both registered FedEx customer credentials are required.");
        this.childKey = childKey; this.childSecret = childSecret;
    }
    void requireScope(UUID tenant, UUID connection, long revision, FedExClient.Environment environment) {
        if (!scope.tenant().equals(tenant) || !scope.connection().equals(connection) || scope.revision() != revision || scope.environment() != environment)
            throw new IllegalArgumentException("FedEx registration does not match this account connection and revision.");
    }
    void requireAccount(String account) {
        if (!scope.account().equals(account)) throw new IllegalArgumentException("FedEx registration does not match this shipping account.");
    }
    @Override public String toString() { return "FedEx registered customer [protected]"; }
}
