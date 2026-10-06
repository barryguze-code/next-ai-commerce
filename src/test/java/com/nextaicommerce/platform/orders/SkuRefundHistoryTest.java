package com.nextaicommerce.platform.orders;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SkuRefundHistoryTest {
 @Test void emptyPageDoesNotQuery(){
  var jdbc=mock(JdbcTemplate.class);
  assertTrue(new SkuRefundHistory(jdbc,new ObjectMapper()).fourWeeks(UUID.randomUUID(),UUID.randomUUID(),List.of()).isEmpty());
  verifyNoInteractions(jdbc);
 }
 @Test void batchesDistinctSkusAndBindsTenantStore(){
  var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();var store=UUID.randomUUID();
  new SkuRefundHistory(jdbc,new ObjectMapper()).fourWeeks(tenant,store,Arrays.asList("A","B","A",null));
  verify(jdbc).queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  verify(jdbc,times(1)).query(contains("seller_sku IN ("),any(RowCallbackHandler.class),eq(tenant),eq(store),eq("A"),eq("B"));
 }
}
