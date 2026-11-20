package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ShelfSaleWorkerTest {
 final ShelfSaleRepository repo=mock(ShelfSaleRepository.class);final AmazonSpApiClient api=mock(AmazonSpApiClient.class);
 final ObjectMapper json=new ObjectMapper();final UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
 final Instant end=Instant.now().plusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
 ShelfSaleWorker worker(){return new ShelfSaleWorker(repo,mock(TransactionTemplate.class),api,json,false,true,connection.toString());}
 ShelfSaleRepository.Listing row(String owned,String pending){return new ShelfSaleRepository.Listing(tenant,connection,"ATVPDKIKX0DER","sku","seller",owned,pending,"CHECK",0);}
 AmazonSpApiClient.ApiResponse response(String text){return new AmazonSpApiClient.ApiResponse(200,"test",json.readTree(text),text);}
 String listing(String discounted){return "{\"productTypes\":[{\"productType\":\"GROCERY\"}],\"attributes\":{\"purchasable_offer\":[{\"marketplace_id\":\"ATVPDKIKX0DER\",\"currency\":\"USD\",\"audience\":\"ALL\",\"our_price\":[{\"schedule\":[{\"value_with_tax\":39.97}]}]"+(discounted==null?"":",\"discounted_price\":"+discounted)+"}]}}";}
 ShelfSaleRepository.Plan plan(){return new ShelfSaleRepository.Plan(new BigDecimal("10"),end);}
 @Test void localAndDisabledWritesCanNeverPublish(){
  assertThatThrownBy(()->new ShelfSaleWorker(repo,null,api,json,true,true,connection.toString())).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->new ShelfSaleWorker(repo,null,api,json,false,false,connection.toString())).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->new ShelfSaleWorker(repo,null,api,json,false,true,"")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(api);
  assertThat(ShelfSaleWorker.class.getAnnotation(org.springframework.context.annotation.Profile.class).value()).containsExactly("prod & !local");
 }
 @Test void otherConnectionCannotCallAmazon(){worker().process(new ShelfSaleRepository.Listing(tenant,UUID.randomUUID(),"ATVPDKIKX0DER","sku","seller",null,null,"CHECK",0));verifyNoInteractions(api);}
 @Test void idleItemsDoNotCallAmazon(){var row=row(null,null);when(repo.plan(row)).thenReturn(Optional.empty());worker().process(row);verifyNoInteractions(api);}
 @Test void ownershipIsDurableBeforeSendingAndOnlyDiscountChanges(){
  var row=row(null,null);when(repo.plan(any())).thenReturn(Optional.of(plan()));when(api.get(any(),any(),anyString())).thenReturn(response(listing(null)));
  worker().process(row);var pending=org.mockito.ArgumentCaptor.forClass(String.class);verify(repo).result(eq(row),eq("READY"),isNull(),pending.capture(),eq(1),isNull());verify(api,never()).patch(any(),any(),anyString(),anyString());
  assertThat(pending.getValue()).contains("35.97");var ready=row(null,pending.getValue());when(api.patch(any(),any(),anyString(),anyString())).thenReturn(response("{\"status\":\"ACCEPTED\"}"));worker().process(ready);
  var body=org.mockito.ArgumentCaptor.forClass(String.class);verify(api).patch(any(),any(),anyString(),body.capture());assertThat(body.getValue()).contains("\"discounted_price\"","\"op\":\"merge\"","GROCERY").doesNotContain("our_price","fulfillment_availability");verify(repo).result(ready,"VERIFYING",pending.getValue(),pending.getValue(),15,null);
 }
 @Test void existingPromotionIsNeverOverwritten(){var row=row(null,null);when(repo.plan(row)).thenReturn(Optional.of(plan()));when(api.get(any(),any(),anyString())).thenReturn(response(listing("[{\"schedule\":[{\"value_with_tax\":20,\"start_at\":\"2026-01-01\",\"end_at\":\"2027-01-01\"}]}]")));worker().process(row);verify(api,never()).patch(any(),any(),anyString(),anyString());verify(repo).result(eq(row),eq("CONFLICT"),isNull(),isNull(),eq(900),anyString());}
 @Test void exhaustedStockRemovesOnlyOwnedDiscountAndVerifies(){
  var w=worker();var offer=json.readTree(listing(null)).path("attributes").path("purchasable_offer").path(0);String owned=json.writeValueAsString(w.discount(offer,plan()));var row=row(owned,null);
  when(repo.plan(row)).thenReturn(Optional.empty());when(api.get(any(),any(),anyString())).thenReturn(response(listing(owned)));when(api.patch(any(),any(),anyString(),anyString())).thenReturn(response("{\"status\":\"ACCEPTED\"}"));w.process(row);
  var body=org.mockito.ArgumentCaptor.forClass(String.class);verify(api).patch(any(),any(),anyString(),body.capture());assertThat(body.getValue()).contains("\"discounted_price\":null").doesNotContain("our_price");verify(repo).result(row,"VERIFY_REMOVAL",owned,null,15,null);
  when(api.get(any(),any(),anyString())).thenReturn(response(listing(null)));w.process(row);verify(repo).result(row,"REMOVED",null,null,60,null);
 }
 @Test void cancellationBeforeSendNeverAppliesQueuedDiscount(){var row=row(null,"[]");when(repo.plan(row)).thenReturn(Optional.empty());when(api.get(any(),any(),anyString())).thenReturn(response(listing(null)));worker().process(row);verify(api,never()).patch(any(),any(),anyString(),anyString());}
 @Test void rejectedSubmissionIsNotConfirmed(){var w=worker();String pending=json.writeValueAsString(w.discount(json.readTree(listing(null)).path("attributes").path("purchasable_offer").path(0),plan()));var row=row(null,pending);when(repo.plan(row)).thenReturn(Optional.of(plan()));when(api.get(any(),any(),anyString())).thenReturn(response(listing(null)));when(api.patch(any(),any(),anyString(),anyString())).thenReturn(response("{\"status\":\"INVALID\"}"));w.process(row);verify(repo).result(eq(row),eq("RETRY"),isNull(),eq(pending),anyInt(),anyString());}
}
