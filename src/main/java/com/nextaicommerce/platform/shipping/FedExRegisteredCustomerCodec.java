package com.nextaicommerce.platform.shipping;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;

/** Reuses existing AES-GCM with authenticated scope; deliberately has no storage or HTTP dependency. */
public final class FedExRegisteredCustomerCodec {
    private final ShippingLabelCrypto crypto;
    private final ObjectMapper json;
    public FedExRegisteredCustomerCodec(ShippingLabelCrypto crypto, ObjectMapper json) { this.crypto = crypto; this.json = json; }
    public ShippingLabelCrypto.Encrypted seal(FedExRegisteredCustomer customer) {
        byte[] plain = json.createObjectNode().put("version", 1).put("childKey", customer.childKey)
            .put("childSecret", customer.childSecret).toString().getBytes(StandardCharsets.UTF_8);
        try { return crypto.encrypt(plain, customer.scope.context()); }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
    public FedExRegisteredCustomer open(FedExRegisteredCustomer.Scope expected, ShippingLabelCrypto.Encrypted encrypted) {
        byte[] plain = null;
        try {
            plain = crypto.decrypt(encrypted.payload(), encrypted.nonce(), expected.context());
            var node = json.readTree(plain);
            if (node.path("version").asInt() != 1) throw new IllegalStateException();
            return new FedExRegisteredCustomer(expected, node.path("childKey").asText(""), node.path("childSecret").asText(""));
        } catch (Exception ex) { throw new IllegalStateException("Registered FedEx credentials could not be opened for this connection."); }
        finally { if (plain != null) java.util.Arrays.fill(plain, (byte) 0); }
    }
}
