package com.nextaicommerce.platform.catalog;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.math.BigDecimal;
class CatalogCaseImportPersistenceTest {
 @Test void approvedCaseValuesReachInsertAndOnlyNewGlobalsCanReceiveThem() throws Exception {
  UUID actor=UUID.randomUUID(),tenant=UUID.randomUUID(),vendor=UUID.randomUUID();
  var sql=new ArrayList<String>();var batches=new ArrayList<List<Object[]>>();
  JdbcTemplate jdbc=mock(JdbcTemplate.class,inv->{Object[] args=inv.getArguments();if(args.length>0&&args[0] instanceof String s){sql.add(s);if(inv.getMethod().getName().equals("batchUpdate")&&args[1] instanceof List<?> list){@SuppressWarnings("unchecked") var batch=(List<Object[]>)list;batches.add(batch);}}
   if(inv.getMethod().getName().equals("queryForObject")){if(args[1]==UUID.class)return actor;if(args[1]==Integer.class)return 0;}return RETURNS_DEFAULTS.answer(inv);});
  var service=new CatalogImportService(jdbc,new ObjectMapper(),null);
  Class<?> rowType=Class.forName(CatalogImportService.class.getName()+"$SourceRow"),vendorType=Class.forName(CatalogImportService.class.getName()+"$ImportVendor");
  var rowCtor=rowType.getDeclaredConstructors()[0];rowCtor.setAccessible(true);var vendorCtor=vendorType.getDeclaredConstructors()[0];vendorCtor.setAccessible(true);
  var fields=new HashMap<String,String>();fields.put("productName","Name");fields.put("vendorItemCode","Code");fields.put("listCost","Cost");fields.put("identifier","UPC");fields.put("caseLength","L");fields.put("caseWeight","W");fields.put("caseDimensionUnitDefault","cm");fields.put("caseWeightUnitDefault","kg");
  Object row=rowCtor.newInstance(UUID.randomUUID(),2,Map.of("Name","Tea","Code","001","Cost","3.00","UPC","042100005264","L","42","W","8.5"));
  Object source=vendorCtor.newInstance(vendor,"USD",BigDecimal.ZERO,"Vendor","V","");
  var method=CatalogImportService.class.getDeclaredMethod("bulkImport",UUID.class,String.class,UUID.class,Map.class,vendorType,List.class);method.setAccessible(true);
  assertEquals(1,method.invoke(service,tenant,"fixture@example.test",UUID.randomUUID(),fields,source,List.of(row)));
  Object[] values=batches.getFirst().getFirst();assertEquals(26,values.length);assertEquals(new BigDecimal("42"),values[20]);assertNull(values[21]);assertNull(values[22]);assertEquals("cm",values[23]);assertEquals(new BigDecimal("8.5"),values[24]);assertEquals("kg",values[25]);
  String write=sql.stream().filter(s->s.contains("UPDATE global_catalog_products product SET category")).findFirst().orElseThrow();assertTrue(write.contains("AND work.is_new"));assertTrue(write.contains("length=coalesce(work.case_length,product.length)"));assertTrue(write.contains("net_weight=coalesce(work.case_weight,product.net_weight)"));
 }
}
