package com.nextaicommerce.platform.collaboration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.nextaicommerce.platform.fba.FbaShipmentParents;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class FbaParentSubjectTest {
 private Object resolve(CollaborationRepository repo,UUID tenant,String key)throws Throwable{var method=CollaborationRepository.class.getDeclaredMethod("sourceSubjectStore",UUID.class,String.class,String.class);method.setAccessible(true);try{return method.invoke(repo,tenant,"FBA_PLAN",key);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}}
 @Test void onlyVerifiedParentInAuthorizedStoreResolves()throws Throwable{var jdbc=mock(JdbcTemplate.class);var parents=mock(FbaShipmentParents.class);var repo=new CollaborationRepository(jdbc,parents);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();when(jdbc.queryForList(contains("SELECT sv.marketplace_connection_id"),eq(UUID.class),eq(tenant),eq(store))).thenReturn(List.of(store));when(jdbc.queryForList(startsWith("SELECT shipment_id"),eq(String.class),eq(tenant),eq(store))).thenReturn(List.of("child"));when(parents.read(tenant,store,List.of("child"))).thenReturn(new FbaShipmentParents.Projection(Map.of("child",new FbaShipmentParents.Parent("parent","Plan",null,null,"child")),Set.of()));assertThat(resolve(repo,tenant,store+"|parent")).isEqualTo(store);assertThatThrownBy(()->resolve(repo,tenant,store+"|other")).isInstanceOf(IllegalArgumentException.class);}
 @Test void foreignOrRestrictedStoreRejectsBeforeProjectionRead(){var jdbc=mock(JdbcTemplate.class);var parents=mock(FbaShipmentParents.class);var repo=new CollaborationRepository(jdbc,parents);assertThatThrownBy(()->resolve(repo,UUID.randomUUID(),UUID.randomUUID()+"|parent")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(parents);}
}
