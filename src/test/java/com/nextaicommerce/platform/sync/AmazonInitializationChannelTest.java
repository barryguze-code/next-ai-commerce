package com.nextaicommerce.platform.sync;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AmazonInitializationChannelTest {
 @Test void shopifyCannotStartAmazonInitialization(){
  var jdbc=mock(JdbcTemplate.class);UUID tenant=UUID.randomUUID(),id=UUID.randomUUID();
  when(jdbc.queryForObject(contains("SELECT channel"),eq(String.class),eq(tenant),eq(id))).thenReturn("SHOPIFY");
  assertThrows(IllegalArgumentException.class,()->new AmazonInitializationService(jdbc).enqueue(tenant,id));
  verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
}
