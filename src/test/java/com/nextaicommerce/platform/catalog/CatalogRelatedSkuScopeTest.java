package com.nextaicommerce.platform.catalog;
import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.core.RowMapper;import java.util.*;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;import static org.assertj.core.api.Assertions.*;
class CatalogRelatedSkuScopeTest {
 @Test void ownAccountProjectionRequiresCurrentSameConnectionShopifyEvidence(){
  var jdbc=mock(JdbcTemplate.class);var tenant=UUID.fromString("00000000-0000-0000-0000-000000000001");var item=UUID.fromString("00000000-0000-0000-0000-000000000002");
  when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{String sql=call.getArgument(0);assertThat(sql).contains("connection.tenant_id=mapping.tenant_id","variant.tenant_id=mapping.tenant_id","variant.marketplace_connection_id=mapping.marketplace_connection_id","mapping.shopify_mapping_hash=variant.mapping_hash","connection.channel='AMAZON'","variant.id IS NOT NULL AND product.id IS NOT NULL");Object[] args=(Object[])call.getRawArguments()[2];assertThat(args).containsExactly(tenant,tenant,tenant,item);return List.of();});
  assertThat(new CatalogRepository(jdbc).listMarketplaceSkus(tenant,List.of(item))).isEmpty();verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
}
