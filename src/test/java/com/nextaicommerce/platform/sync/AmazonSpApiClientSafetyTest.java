package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class AmazonSpApiClientSafetyTest {
    @Test void localWriteBlockRunsBeforeCredentialsOrNetwork(){
        var credentials=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.MarketplaceCredentialService.class);
        var client=new AmazonSpApiClient(credentials,new tools.jackson.databind.ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(client,"writeEnabled",false);
        var tenant=java.util.UUID.randomUUID();var connection=java.util.UUID.randomUUID();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->client.patch(tenant,connection,"/listings/2021-08-01/items/SKU","{}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No request was sent");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->client.post(tenant,connection,"/mfn/v0/shipments","{}"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No request was sent");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->client.deleteAt(tenant,connection,"https://sellingpartnerapi-na.amazon.com","/mfn/v0/shipments/id"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No request was sent");
        org.mockito.Mockito.verifyNoInteractions(credentials);
    }
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
