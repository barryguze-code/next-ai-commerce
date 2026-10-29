package com.nextaicommerce.platform.sync;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryPublicationSimulationTest {
 @Test void oneFailedAccountDoesNotStarveLaterAccounts(){
  var repo=mock(InventoryPublicationRepository.class);UUID first=UUID.randomUUID(),second=UUID.randomUUID();when(repo.tenants()).thenReturn(List.of(first,second));
  when(repo.plan(first)).thenThrow(new IllegalStateException("fixture"));var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
  new InventoryPublicationSimulation(repo,new TransactionTemplate(manager),new MockEnvironment()).tick();
  verify(repo).plan(second);verify(repo).simulate(second);verify(repo,never()).simulate(first);
 }
 @Test void externalWriteConfigurationsAreRejected(){
  for(String key:List.of("app.amazon.write-enabled","app.amazon.inventory-publication-enabled","app.shopify.inventory.write-enabled","app.shopify.prices.write-enabled","app.shopify.sale-prices.write-enabled"))
   assertThatThrownBy(()->new InventoryPublicationSimulation(mock(InventoryPublicationRepository.class),mock(TransactionTemplate.class),new MockEnvironment().withProperty(key,"true"))).isInstanceOf(IllegalStateException.class);
 }
}
