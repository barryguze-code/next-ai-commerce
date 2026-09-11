package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AmazonSpApiClientSafetyTest {
    @Test void localReadPolicyAllowsQueriesAndBlocksMarketplaceChanges(){
        assertThat(AmazonSpApiClient.readOnlyOperation("GET","/orders/v0/orders")).isTrue();
        assertThat(AmazonSpApiClient.readOnlyOperation("POST","/reports/2021-06-30/reports")).isTrue();
        assertThat(AmazonSpApiClient.readOnlyOperation("POST","/batches/products/pricing/v0/itemOffers")).isTrue();
        assertThat(AmazonSpApiClient.readOnlyOperation("POST","/mfn/v0/eligibleShippingServices")).isTrue();
        assertThat(AmazonSpApiClient.readOnlyOperation("PATCH","/listings/2021-08-01/items/SKU")).isFalse();
        assertThat(AmazonSpApiClient.readOnlyOperation("POST","/mfn/v0/shipments")).isFalse();
        assertThat(AmazonSpApiClient.readOnlyOperation("DELETE","/mfn/v0/shipments/id")).isFalse();
    }
}
