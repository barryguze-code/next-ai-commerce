package com.nextaicommerce.platform.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.config.*;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;

class PictureReadCacheTransactionTest {
 @Test void pictureReadsKeepDisplayCacheButPictureSaveInvalidatesIt() throws Exception {
  var physical=mock(Connection.class);when(physical.getAutoCommit()).thenReturn(true);
  var source=mock(DataSource.class);when(source.getConnection()).thenReturn(physical);
  var cache=new PlatformReadCache(true,Duration.ofSeconds(10),16);
  try(var beans=new AnnotationConfigApplicationContext()){
   beans.register(ReadCacheConfiguration.class);
   beans.registerBean(PlatformReadCache.class,()->cache);
   beans.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));beans.refresh();
   var manager=beans.getBean(DataSourceTransactionManager.class);var tx=new TransactionTemplate(manager);
   UUID tenant=UUID.randomUUID(),store=UUID.randomUUID(),user=UUID.randomUUID();
   var jdbc=new JdbcTemplate(){
    @SuppressWarnings("unchecked") @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){return (T)(type==UUID.class?user:"");}
    @SuppressWarnings("unchecked") @Override public <T>T query(String sql,ResultSetExtractor<T> mapper,Object...args){
     return (T)((sql.startsWith("SELECT item.seller_sku")||sql.startsWith("SELECT seller_sku,asin"))?new OrderPictureController.Context(tenant,store,"SKU","ASIN","US"):ResponseEntity.ok(new byte[]{1}));
    }
    @Override public int update(String sql,Object...args){return 1;}
   };
   var access=mock(WorkspaceAccessRepository.class);
   var session=new MockHttpSession();session.setAttribute("selectedTenantId",tenant);session.setAttribute("selectedStoreId",store);
   var auth=new UsernamePasswordAuthenticationToken("reader@example.test","");
   var record=proxy(new RecordPictureController(jdbc,tx,access),manager);
   var orderTarget=new OrderPictureController(jdbc,tx,access,null);var order=proxy(orderTarget,manager);
   var mapping=proxy(new MappingPictureController(orderTarget,null),manager);
   var loads=new AtomicInteger();assertThat(cache.get(tenant,"warehouse-display","shelf",loads::incrementAndGet)).isEqualTo(1);
   record.ownPicture(session,auth);record.get("USER",user,session,auth);order.picture(UUID.randomUUID(),session,auth);mapping.picture("SKU",session,auth);
   assertThat(cache.get(tenant,"warehouse-display","shelf",loads::incrementAndGet)).as("GET picture transactions must not invalidate shelf display data").isEqualTo(1);
   orderTarget.save(new OrderPictureController.Context(tenant,store,"SKU","ASIN","US"),auth,new byte[]{1},"image/png",null);
   assertThat(cache.get(tenant,"warehouse-display","shelf",loads::incrementAndGet)).as("a real picture save must still invalidate display models").isEqualTo(2);
  }
 }
 @SuppressWarnings("unchecked") private static <T>T proxy(T target,DataSourceTransactionManager manager){
  var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
  factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
  return (T)factory.getProxy();
 }
}
