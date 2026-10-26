package com.nextaicommerce.platform.catalog;
import java.util.*;import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.*;import org.springframework.transaction.support.*;import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
class CatalogPictureServiceTest {
 @Test void freshSyncDoesNotRequireAnExistingImageAndCannotUseAnotherAccount(){
  var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);var access=mock(WorkspaceAccessRepository.class);var service=new CatalogPictureService(jdbc,tx,mock(CatalogRepository.class),access);UUID tenant=UUID.randomUUID(),item=UUID.randomUUID(),connection=UUID.randomUUID();
  when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
  when(access.canOperateAccount(tenant,"editor")).thenReturn(true);
  when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<CatalogPictureService.Source>>any(),any(Object[].class))).thenReturn(List.of(new CatalogPictureService.Source(tenant,connection,UUID.randomUUID(),item,UUID.randomUUID(),"0000123","B000000001",null,false)));
  when(jdbc.queryForObject(contains("marketplace_connection_credentials"),eq(Boolean.class),eq(tenant),eq(connection))).thenReturn(true);
  assertThat(service.canSync(tenant,"editor",item)).isTrue();verify(jdbc).queryForObject(contains("marketplace_connection_credentials"),eq(Boolean.class),eq(tenant),eq(connection));
  assertThatThrownBy(()->service.canSync(UUID.randomUUID(),"editor",item)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
 }
 @Test void sharedPublicPictureAdapterRejectsAnUnauthorizedAccountBeforeScanningSources(){var jdbc=mock(JdbcTemplate.class);var tx=mock(TransactionTemplate.class);when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));var service=new CatalogPictureService(jdbc,tx,mock(CatalogRepository.class),mock(WorkspaceAccessRepository.class));assertThatThrownBy(()->service.publicProductFallback(UUID.randomUUID(),"viewer",UUID.randomUUID())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);verifyNoInteractions(jdbc);}

}
