package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class FbaRecurringSlotTest {
 @Test void rechecksCompetingManualJobOnlyAfterConnectionLock(){
  var jdbc=mock(JdbcTemplate.class);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
  when(jdbc.queryForList(contains("FOR UPDATE"),eq(UUID.class),eq(tenant),eq(store))).thenReturn(List.of(store));
  when(jdbc.queryForObject(contains("count(*)"),eq(Long.class),eq(tenant),eq(store))).thenReturn(1L);
  assertThat(new AmazonRecurringSyncScheduler(jdbc,null,true).lockFbaSlot(tenant,store)).isFalse();
  var order=inOrder(jdbc);order.verify(jdbc).queryForList(contains("FOR UPDATE"),eq(UUID.class),eq(tenant),eq(store));order.verify(jdbc).queryForObject(contains("count(*)"),eq(Long.class),eq(tenant),eq(store));
 }
 @Test void missingConnectionCannotQueueAndIdleConnectionCan(){
  var jdbc=mock(JdbcTemplate.class);UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();var scheduler=new AmazonRecurringSyncScheduler(jdbc,null,true);
  assertThat(scheduler.lockFbaSlot(tenant,store)).isFalse();verify(jdbc,never()).queryForObject(anyString(),eq(Long.class),any(),any());
  when(jdbc.queryForList(contains("FOR UPDATE"),eq(UUID.class),eq(tenant),eq(store))).thenReturn(List.of(store));when(jdbc.queryForObject(contains("count(*)"),eq(Long.class),eq(tenant),eq(store))).thenReturn(0L);
  assertThat(scheduler.lockFbaSlot(tenant,store)).isTrue();
 }
}
