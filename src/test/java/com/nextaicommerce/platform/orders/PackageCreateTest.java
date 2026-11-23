package com.nextaicommerce.platform.orders;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PackageCreateTest {
 JdbcTemplate database(){var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForObject(eq("SELECT packing_package_key(?)"),eq(String.class),anyString())).thenAnswer(call->PackingPackageNames.clean(call.getArgument(2)).toLowerCase(Locale.ROOT));return jdbc;}

 @Test void createsOnlyAccountChoiceAndAuditWithoutOrderAssignmentOrPricing(){var jdbc=database();var tenant=UUID.randomUUID();var service=new PackingPackageNames(jdbc);assertThat(service.create(tenant,"  New   carton ","planner")).isEqualTo("New carton");var sql=ArgumentCaptor.forClass(String.class);verify(jdbc,times(2)).update(sql.capture(),any(Object[].class));assertThat(sql.getAllValues()).anyMatch(s->s.contains("packing_package_aliases")).anyMatch(s->s.contains("PACKING_PACKAGE_CREATED")).noneMatch(s->s.contains("temporary_order_packaging_lookup")||s.contains("UPDATE shopify_orders")||s.contains("package_rates"));}
 @Test void duplicateCaseInsensitiveNameAndInvalidInputNeverWrite(){var jdbc=database();var service=new PackingPackageNames(jdbc);var tenant=UUID.randomUUID();when(jdbc.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of("Existing carton"));assertThatThrownBy(()->service.create(tenant,"existing CARTON","planner")).hasMessageContaining("already exists");for(String name:List.of(" ","__recipe","__custom","uPs","fedex_small","FEDEX_XSMALL","FEDEX_PAK","FEDEX_MEDIUM","FEDEX_LARGE","FEDEX_XLARGE","x".repeat(121)))assertThatThrownBy(()->service.create(tenant,name,"planner")).isInstanceOf(IllegalArgumentException.class);verify(jdbc,never()).update(anyString(),any(Object[].class));}
}
