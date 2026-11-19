package com.nextaicommerce.platform.fba;
import static org.assertj.core.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;
class FbaWarehouseFeasibilityTest {
 @Test void sharedPhysicalStockIsAllocatedOnceInPriorityOrder(){UUID item=UUID.randomUUID(),warehouse=UUID.randomUUID();var rows=new LinkedHashMap<String,Map<UUID,Long>>();rows.put("first",Map.of(item,2L));rows.put("second",Map.of(item,1L));assertThat(FbaReplenishmentMath.feasibleByWarehouse(Map.of(warehouse,Map.of(item,10L)),rows,Map.of("first",4L,"second",8L))).containsEntry("first",4L).containsEntry("second",2L);}
 @Test void cannotInventCompleteBundlesAcrossDifferentOrigins(){UUID a=UUID.randomUUID(),b=UUID.randomUUID();var rows=new LinkedHashMap<String,Map<UUID,Long>>();rows.put("bundle",Map.of(a,1L,b,1L));assertThat(FbaReplenishmentMath.feasibleByWarehouse(Map.of(UUID.randomUUID(),Map.of(a,10L),UUID.randomUUID(),Map.of(b,10L)),rows,Map.of("bundle",10L))).containsEntry("bundle",0L);}
 @Test void exactWholeSkuUnitsMayComeFromMultipleCompleteOrigins(){UUID item=UUID.randomUUID();var rows=new LinkedHashMap<String,Map<UUID,Long>>();rows.put("pack",Map.of(item,3L));assertThat(FbaReplenishmentMath.feasibleByWarehouse(Map.of(UUID.randomUUID(),Map.of(item,8L),UUID.randomUUID(),Map.of(item,7L)),rows,Map.of("pack",10L))).containsEntry("pack",4L);}
}
