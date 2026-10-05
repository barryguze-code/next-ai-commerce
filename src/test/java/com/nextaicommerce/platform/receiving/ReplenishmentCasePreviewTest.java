package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplenishmentCasePreviewTest {
 @Test void caseEditRecalculatesRecommendationWithoutChangingSnapshot(){
  UUID tenant=UUID.randomUUID(),item=UUID.randomUUID();
  Map<String,Object> row=Map.of("id",item,"lead",0,"low_days",7,"target_days",14,"overstock_days",35,"available",0,"demand",48,"minimum_each",2,"pack",12);
  var snapshot=new ReplenishmentSuggestions.Snapshot(List.of(row),Instant.now());
  var service=new ReplenishmentSuggestions(null,null,null,null){
   @Override public Number caseSize(UUID t,UUID i){assertThat(t).isEqualTo(tenant);assertThat(i).isEqualTo(item);return 6;}
   @Override public Snapshot snapshot(UUID t){return snapshot;}
  };
  try{assertThat(service.caseSizePreview(tenant,item)).containsEntry("pack",6).containsEntry("cases",4L).containsEntry("each",24d);assertThat(row.get("pack")).isEqualTo(12);}
  finally{service.stopWorker();}
 }
 @Test void missingForecastStillReturnsSavedPack(){
  var service=new ReplenishmentSuggestions(null,null,null,null){@Override public Number caseSize(UUID t,UUID i){return 12;}};
  try{assertThat(service.caseSizePreview(UUID.randomUUID(),UUID.randomUUID())).containsOnlyKeys("pack").containsEntry("pack",12);}
  finally{service.stopWorker();}
 }
}
