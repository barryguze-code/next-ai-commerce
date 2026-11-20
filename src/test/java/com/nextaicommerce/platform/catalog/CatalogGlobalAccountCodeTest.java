package com.nextaicommerce.platform.catalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class CatalogGlobalAccountCodeTest {
 @Test void selectedAccountIsBoundToBothCountAndPageAndNeverInterpolated(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class); UUID tenant=UUID.randomUUID();
  when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
  new CatalogRepository(jdbc).pageGlobalProducts(tenant,"0000342934",0,25,"product","asc");
  var sql=ArgumentCaptor.forClass(String.class);var args=ArgumentCaptor.forClass(Object[].class);
  verify(jdbc).queryForObject(sql.capture(),eq(Long.class),args.capture());
  assertThat(sql.getValue()).contains("own.tenant_id=CAST(? AS uuid)","offer.tenant_id=own.tenant_id","offer.account_catalog_item_id=own.id").doesNotContain(tenant.toString());
  assertThat(args.getValue()).contains(tenant,"%0000342934%");
  var page=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")).findFirst().orElseThrow();
  assertThat((String)page.getArgument(0)).contains("own_item_codes","own.tenant_id=CAST(? AS uuid)");
  assertThat(Collections.frequency(Arrays.asList(page.getArguments()),tenant)).isEqualTo(2);
 }
 @Test void accountSwitchUsesIndependentQueriesAndNoSharedGlobalCache(){
  JdbcTemplate jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
  var repo=new CatalogRepository(jdbc);UUID first=UUID.randomUUID(),second=UUID.randomUUID();
  repo.pageGlobalProducts(first,"342934",0,25,"product","asc");repo.pageGlobalProducts(second,"342934",0,25,"product","asc");
  var counts=mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("queryForObject")&&i.getArgument(1)==Long.class).toList();
  assertThat(counts).hasSize(2);
  assertThat(Arrays.asList(counts.get(0).getArguments())).contains(first).doesNotContain(second);
  assertThat(Arrays.asList(counts.get(1).getArguments())).contains(second).doesNotContain(first);
 }
}
