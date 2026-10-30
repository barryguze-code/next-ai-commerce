package com.nextaicommerce.platform.shopify;

import java.time.Instant;
import java.sql.Timestamp;
import static org.mockito.ArgumentMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopifyInventoryScopeTest {
 @Test void canaryRejectsMalformedScopeAndAcceptsOnlyExactUuidTriples(){
  var repository=new ShopifyInventoryPublications(mock(JdbcTemplate.class),mock(org.springframework.transaction.support.TransactionTemplate.class));
  repository.configureVariants("");
  repository.configureVariants(UUID.randomUUID()+"/"+UUID.randomUUID()+"/"+UUID.randomUUID());
  assertThatThrownBy(()->repository.configureVariants("all")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->repository.configureVariants("tenant/connection/variant' OR true")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void emptyScopeAndOtherAccountOrConnectionFailClosed(){
  var jdbc=mock(JdbcTemplate.class);var inventory=new ShopifyOrderInventory(jdbc,true,true);
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();
  assertThat(inventory.enabled(tenant,connection)).isFalse();
  ReflectionTestUtils.setField(inventory,"inventoryScopes",tenant+"/"+connection);
  assertThat(inventory.enabled(tenant,connection)).isTrue();
  assertThat(inventory.enabled(UUID.randomUUID(),connection)).isFalse();
  assertThat(inventory.enabled(tenant,UUID.randomUUID())).isFalse();
  assertThat(inventory.cutoff(tenant,UUID.randomUUID())).isNull();
  verifyNoInteractions(jdbc);
 }
 @Test void disabledMasterFlagCannotBeOverriddenByScope(){
  var inventory=new ShopifyOrderInventory(mock(JdbcTemplate.class),false,true);
  UUID t=UUID.randomUUID(),c=UUID.randomUUID();ReflectionTestUtils.setField(inventory,"inventoryScopes",t+"/"+c);
  assertThat(inventory.enabled(t,c)).isFalse();
 }
 @Test void absentBootstrapApprovalDoesNotReadOrWidenWindow(){
  var jdbc=mock(JdbcTemplate.class);var inventory=new ShopifyOrderInventory(jdbc,true,true);
  UUID t=UUID.randomUUID(),c=UUID.randomUUID();ReflectionTestUtils.setField(inventory,"inventoryScopes",t+"/"+c);
  Instant start=Instant.parse("2026-10-30T20:19:00Z");
  assertThat(inventory.bootstrapStart(t,c,start)).isEqualTo(start);verifyNoInteractions(jdbc);
 }
 @Test void approvedCatchupIncludesOlderOrderAndDoesNotMoveNormalWindowForward(){
  var jdbc=mock(JdbcTemplate.class);var inventory=new ShopifyOrderInventory(jdbc,true,true);
  UUID t=UUID.randomUUID(),c=UUID.randomUUID();
  ReflectionTestUtils.setField(inventory,"inventoryScopes",t+"/"+c);
  ReflectionTestUtils.setField(inventory,"bootstrapScopes",t+"/"+c);
  Instant normal=Instant.parse("2026-10-30T20:19:00Z");
  when(jdbc.queryForObject(anyString(),eq(Timestamp.class),eq(t),eq(c)))
      .thenReturn(Timestamp.from(Instant.parse("2026-10-30T17:54:13Z")))
      .thenReturn(Timestamp.from(Instant.parse("2026-10-30T20:24:29Z")))
      .thenReturn(null);
  assertThat(inventory.bootstrapStart(t,c,normal)).isEqualTo(Instant.parse("2026-10-30T17:49:13Z"));
  assertThat(inventory.bootstrapStart(t,c,normal)).isEqualTo(normal);
  assertThat(inventory.bootstrapStart(t,c,normal)).isEqualTo(normal);
 }
 @Test void unlistedStoreCannotReleaseOrShipExistingReservations(){
  var jdbc=mock(JdbcTemplate.class);var inventory=new ShopifyOrderInventory(jdbc,true,true);
  UUID t=UUID.randomUUID(),c=UUID.randomUUID(),line=UUID.randomUUID();
  ReflectionTestUtils.setField(inventory,"inventoryScopes",t+"/"+UUID.randomUUID());
  inventory.release(t,c,line);inventory.bootstrapRemaining(t,c,UUID.randomUUID());
  assertThatThrownBy(()->inventory.ship(t,c,UUID.randomUUID(),line,null,null)).isInstanceOf(IllegalStateException.class);
  verifyNoInteractions(jdbc);
 }
}
