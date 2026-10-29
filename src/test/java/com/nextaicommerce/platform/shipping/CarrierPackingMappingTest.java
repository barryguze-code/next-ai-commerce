package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

class CarrierPackingMappingTest {
 @Test @SuppressWarnings("unchecked") void nativeOrderPackingUsesFrozenComponentsExactScopeAndActualPackage()throws Exception{
  UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),id=UUID.randomUUID(),line=UUID.randomUUID();String order=id.toString();
  var jdbc=mock(JdbcTemplate.class);var adapter=new CarrierOrderContext(jdbc,null,true,true);
  when(jdbc.query(eq("SELECT channel FROM marketplace_connections WHERE tenant_id=? AND id=? AND status='ACTIVE'"),any(ResultSetExtractor.class),eq(tenant),eq(store))).thenReturn("SHOPIFY");
  when(jdbc.queryForList(eq("SELECT * FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND id=?"),eq(tenant),eq(store),eq(id))).thenReturn(List.of(Map.of("evidence_complete",true,"payment_status","PAID","fulfillment_status","FULFILLED")));
  when(jdbc.queryForList(eq("SELECT * FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? ORDER BY line_gid"),eq(tenant),eq(store),eq(id))).thenReturn(List.of(Map.of("id",line,"seller_sku","00001-EAx99","title","Frozen product","ordered_quantity",3,"unfulfilled_quantity",0,"ownership","MERCHANT")));
  when(jdbc.queryForObject(startsWith("SELECT package_name"),eq(String.class),eq(tenant),eq(store),eq(id))).thenReturn("Medium");
  when(jdbc.queryForList(startsWith("SELECT c.quantity,g.brand FROM shopify_order_components"),eq(tenant),eq(store),eq(line))).thenReturn(List.of(Map.of("quantity",new BigDecimal("2"),"brand","Own brand"),Map.of("quantity",new BigDecimal("0.5"),"brand","Own brand")));
  var rs=mock(ResultSet.class);when(rs.getObject(1,UUID.class)).thenReturn(line);when(rs.getString(2)).thenReturn("00001-EAx99");when(rs.getString(3)).thenReturn("Original product");when(rs.getInt(4)).thenReturn(3);
  when(jdbc.query(startsWith("SELECT id,seller_sku,title,"),any(RowMapper.class),eq(tenant),eq(store),eq(id))).thenAnswer(a->List.of(((RowMapper<CarrierLabelComposer.Line>)a.getArgument(1)).mapRow(rs,0)));
  var result=adapter.packing(tenant,store,order).getFirst();
  assertThat(result.mappedQuantity()).isEqualByComparingTo("7.5");assertThat(result.sku()).isEqualTo("00001-EAx99");assertThat(result.quantity()).isEqualTo(3);assertThat(result.brand()).isEqualTo("Own brand");assertThat(result.packageName()).isEqualTo("Medium");
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void archivedExtraMetadataRemainsCompatibleWithLegacyReader() {
  record Legacy(String brand,String sku,String description,int quantity,List<CarrierLabelComposer.Allocation> allocations){}
  var json=new tools.jackson.databind.ObjectMapper();var row=CarrierLabelComposer.Line.mapped("Brand","00001","Product",2,List.of(new BigDecimal("3")),"Pak",List.of());
  var old=json.readValue(json.writeValueAsString(row),Legacy.class);assertThat(old.quantity()).isEqualTo(2);assertThat(old.sku()).isEqualTo("00001");
 }
}
