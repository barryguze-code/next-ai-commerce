package com.nextaicommerce.platform.fba;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class FbaShipmentSearchTest {
 @Test void accountSkuAndUpcUseScopedExistsWithoutMultiplyingShipmentReferences(){
  var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();var store=UUID.randomUUID();var repository=new FbaReadRepository(jdbc);var sql=ArgumentCaptor.forClass(String.class);var args=ArgumentCaptor.forClass(Object[].class);
  repository.matchingShipmentReferences(tenant,store,"  001%_!  ","planner","SHIPPED",null,false);
  verify(jdbc).queryForList(sql.capture(),eq(String.class),args.capture());String query=sql.getValue();
  assertThat(query).startsWith("SELECT s.shipment_id").contains("a.account_sku ILIKE ?","gi.identifier_value ILIKE ?","m.tenant_id=i.tenant_id","m.marketplace_connection_id=i.marketplace_connection_id","m.marketplace_sku=i.seller_sku","m.status='ACTIVE'","a.tenant_id=m.tenant_id","c.tenant_id=m.tenant_id","coalesce(c.account_catalog_item_id,m.account_catalog_item_id)").doesNotContain("FROM amazon_fba_shipments s JOIN");
  assertThat(args.getValue()[0]).isEqualTo(tenant);assertThat(args.getValue()[1]).isEqualTo(store);assertThat(args.getValue()[2]).isEqualTo("001%_!");assertThat(args.getValue()).contains("%001!%!_!!%");assertThat(query.chars().filter(c->c=='?').count()).isEqualTo(args.getValue().length);
 }
}
