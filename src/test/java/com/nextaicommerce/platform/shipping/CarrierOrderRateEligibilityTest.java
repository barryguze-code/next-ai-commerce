package com.nextaicommerce.platform.shipping;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
class CarrierOrderRateEligibilityTest {
 @Test void currentShopifyShippedOrLocallyHandedOffStatusStopsBeforeReadingLines(){
  var tenant=UUID.randomUUID();var store=UUID.randomUUID();var id=UUID.randomUUID();
  for(boolean local:List.of(false,true)){var jdbc=mock(JdbcTemplate.class);var c=spy(new CarrierOrderContext(jdbc,mock(BuyShippingService.class),true,true));doReturn(true).when(c).shopify(tenant,store);
   when(jdbc.queryForList("SELECT * FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,id)).thenReturn(List.of(Map.of("fulfillment_status",local?"UNFULFILLED":"FULFILLED","platform_waiting_for_pickup",local)));
   assertThatThrownBy(()->c.rateOnlyContext(tenant,store,id.toString())).isInstanceOf(IllegalStateException.class).hasMessageContaining("Shipped");
   verify(jdbc,never()).queryForList(contains("shopify_order_lines"),any(),any(),any());
  }
 }
 @Test void fixtureOrForeignOrderCannotEnterImportedRatePath(){var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();var store=UUID.randomUUID();var id=UUID.randomUUID();var c=spy(new CarrierOrderContext(jdbc,mock(BuyShippingService.class),true,true));doReturn(true).when(c).shopify(tenant,store);when(jdbc.queryForList("SELECT * FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,id)).thenReturn(List.of());assertThatThrownBy(()->c.rateOnlyContext(tenant,store,id.toString())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->c.rateOnlyContext(tenant,store,LocalShippingFixtures.PREFIX+id)).isInstanceOf(IllegalArgumentException.class);}
}
