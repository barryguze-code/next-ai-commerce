package com.nextaicommerce.platform.shopify;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ShopifyInventoryPublicationWorkerTest {
 @Test void localAndMissingAllowlistCannotCreateWorker(){
  var repo=mock(ShopifyInventoryPublications.class);var client=mock(ShopifyInventoryClient.class);
  assertThatThrownBy(()->new ShopifyInventoryPublicationWorker(repo,client,true,true,UUID.randomUUID()+"/"+UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->new ShopifyInventoryPublicationWorker(repo,client,false,false,"")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->new ShopifyInventoryPublicationWorker(repo,client,false,true,"")).isInstanceOf(IllegalStateException.class);
  verifyNoInteractions(repo,client);
 }
 @Test void crashAfterSubmissionResumesWithReadbackOnly(){
  var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var repo=mock(ShopifyInventoryPublications.class);var client=mock(ShopifyInventoryClient.class);
  var op=new ShopifyInventoryPublications.Operation(UUID.randomUUID(),tenant,connection,UUID.randomUUID(),1,UUID.randomUUID(),"hash",new ShopifyInventoryClient.Target(tenant,connection,"gid://shopify/Shop/1","gid://shopify/InventoryItem/2","gid://shopify/Location/3"),4,7,"VERIFYING",1);
  when(repo.next(tenant,connection)).thenReturn(Optional.of(op));
  new ShopifyInventoryPublicationWorker(repo,client,false,true,tenant+"/"+connection).tick();
  verify(repo).verify(op,client);verify(repo,never()).submit(any(),any(),any());verify(repo,never()).markSubmitting(any(),any());verifyNoInteractions(client);
 }
 @Test void failedDurableSubmissionTransitionCannotCallWrite(){
  var tenant=UUID.randomUUID();var connection=UUID.randomUUID();var repo=mock(ShopifyInventoryPublications.class);var client=mock(ShopifyInventoryClient.class);
  var op=new ShopifyInventoryPublications.Operation(UUID.randomUUID(),tenant,connection,UUID.randomUUID(),1,UUID.randomUUID(),"hash",new ShopifyInventoryClient.Target(tenant,connection,"gid://shopify/Shop/1","gid://shopify/InventoryItem/2","gid://shopify/Location/3"),4,7,"READY",0);
  when(repo.next(tenant,connection)).thenReturn(Optional.of(op));
  new ShopifyInventoryPublicationWorker(repo,client,false,true,tenant+"/"+connection).tick();
  verify(repo).markSubmitting(eq(op),any());verify(repo,never()).submit(any(),any(),any());verifyNoInteractions(client);
 }
}
