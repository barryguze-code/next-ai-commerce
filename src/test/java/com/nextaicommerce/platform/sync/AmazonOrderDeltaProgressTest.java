package com.nextaicommerce.platform.sync;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class AmazonOrderDeltaProgressTest {
 @Test void serializedCheckpointResumesExactOrderAndItemPage(){
  var first=AmazonOrderDeltaProgress.empty().orders(List.of("A","B","A"),null,2);
  var pause=Instant.parse("2026-10-29T01:00:00Z");
  var partial=first.items("next-item-page",1,pause);
  var mapper=new ObjectMapper();var resumed=mapper.readValue(mapper.writeValueAsString(partial),AmazonOrderDeltaProgress.class);
  assertThat(resumed.orderIds()).containsExactly("A","B");assertThat(resumed.itemIndex()).isZero();assertThat(resumed.nextItemToken()).isEqualTo("next-item-page");assertThat(resumed.nextItemAt()).isEqualTo(pause);
  var done=resumed.items(null,2,pause);assertThat(done.itemIndex()).isEqualTo(1);assertThat(done.nextItemToken()).isNull();assertThat(done.itemPages()).isZero();assertThat(done.records()).isEqualTo(5);
 }
 @Test void repeatsAndPageBoundsDoNotAdvanceSavedPosition(){
  var page=AmazonOrderDeltaProgress.empty().orders(List.of("A"),"next",1);
  assertThatThrownBy(()->page.orders(List.of("B"),"next",1)).isInstanceOf(IllegalArgumentException.class);
  var last=new AmazonOrderDeltaProgress(1,false,"last",99,List.of(),0,null,0,0,null);
  assertThatThrownBy(()->last.orders(List.of(),"more",0)).isInstanceOf(IllegalArgumentException.class);
  assertThat(last.orders(List.of(),null,0).ordersComplete()).isTrue();
 }
}
