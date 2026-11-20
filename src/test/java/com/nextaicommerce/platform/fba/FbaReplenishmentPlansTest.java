package com.nextaicommerce.platform.fba;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import java.util.*;import java.math.*;import tools.jackson.databind.ObjectMapper;import org.springframework.jdbc.core.JdbcTemplate;
import com.nextaicommerce.platform.receiving.InventoryRepository;import static com.nextaicommerce.platform.fba.FbaReplenishmentPlans.*;
class FbaReplenishmentPlansTest {
 final UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),id=UUID.randomUUID(),user=UUID.randomUUID(),item=UUID.randomUUID(),warehouse=UUID.randomUUID(),layer=UUID.randomUUID(),location=UUID.randomUUID();
 JdbcTemplate jdbc;FbaReplenishmentPlans plans;final String email="operator@example.test";
 Plan plan(String status){return new Plan(id,"FBA-001",status,1,0,Map.of("lines",List.of(Map.of("sku","PACK","units",2))),List.of(),List.of());}
 @BeforeEach void setup(){jdbc=mock(JdbcTemplate.class);plans=spy(new FbaReplenishmentPlans(jdbc,new ObjectMapper(),mock(InventoryRepository.class)));doReturn(user).when(plans).actor(email);doReturn(plan("DRAFT")).when(plans).read(tenant,connection,id,true);doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,false);doReturn(false).when(plans).replay(any(),any(),any(),any(),any());doReturn(List.of(new Component("PACK",item,4))).when(plans).components(eq(tenant),eq(connection),anyList());doReturn(List.of(new Stock(layer,item,location,warehouse,"Warehouse","MAIN","Product",null,BigDecimal.TEN,BigDecimal.ONE,"USD"))).when(plans).stock(eq(tenant),anyList());}
 @Test void allocationReservesCompleteUnitsWithoutLedgerDeduction(){plans.allocate(tenant,connection,id,email,new Allocate(UUID.randomUUID(),1,List.of(new Choice("PACK",layer,8))));verify(jdbc).update(startsWith("INSERT INTO order_inventory_reservations"),any(Object[].class));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));}
 @Test void shortageAndIncompleteBundleFailBeforeAnyStockWrite(){assertThrows(RuntimeException.class,()->plans.allocate(tenant,connection,id,email,new Allocate(UUID.randomUUID(),1,List.of(new Choice("PACK",layer,9)))));verify(jdbc,never()).update(startsWith("INSERT INTO order_inventory_reservations"),any(Object[].class));}
 @Test void sharedLayerCannotBeOverallocatedAcrossSkus(){doReturn(List.of(new Component("PACK",item,4),new Component("OTHER",item,4))).when(plans).components(eq(tenant),eq(connection),anyList());assertThrows(RuntimeException.class,()->plans.allocate(tenant,connection,id,email,new Allocate(UUID.randomUUID(),1,List.of(new Choice("PACK",layer,8),new Choice("OTHER",layer,8)))));verify(jdbc,never()).update(startsWith("INSERT INTO order_inventory_reservations"),any(Object[].class));}
 @Test void revisionConflictPreservesAllStock(){assertThrows(RuntimeException.class,()->plans.allocate(tenant,connection,id,email,new Allocate(UUID.randomUUID(),0,List.of(new Choice("PACK",layer,8)))));verify(jdbc,never()).update(anyString(),any(Object[].class));}
 @Test void retryUsesSameIdentityAndDoesNotReserveAgain(){var command=new Allocate(UUID.randomUUID(),1,List.of(new Choice("PACK",layer,8)));doReturn(true).when(plans).replay(tenant,id,command.requestId(),"ALLOCATE",command);plans.allocate(tenant,connection,id,email,command);verify(jdbc,never()).update(anyString(),any(Object[].class));}
 @Test void cancellationReleasesOnlyThisPlansRemainingReservationsAndNeverRestoresOnHand(){doReturn(plan("PART_DISPATCHED")).when(plans).read(tenant,connection,id,true);plans.cancel(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Remaining units will not be sent"));verify(jdbc).update(eq("UPDATE order_inventory_reservations SET status='RELEASED',updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND fba_plan_id=? AND status='ACTIVE'"),eq(tenant),eq(connection),eq(id));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));}
 @Test void draftValidationRequiresSavedNameAndUniqueCompleteSkuUnits(){assertThrows(RuntimeException.class,()->validate(new Draft(id,0,"FBA-Draft",0,List.of(new Line("SKU",1)),Map.of())));assertThrows(RuntimeException.class,()->validate(new Draft(id,0,"FBA001",0,List.of(new Line("SKU",1),new Line("SKU",2)),Map.of())));assertDoesNotThrow(()->validate(new Draft(id,0,"FBA001",0,List.of(new Line("SKU",1)),Map.of())));}
 @Test void savedPlanReadAlwaysIncludesBothTenantAndStore(){doCallRealMethod().when(plans).read(tenant,connection,id,false);when(jdbc.queryForList(anyString(),eq(tenant),eq(connection),eq(id))).thenReturn(List.of());assertThrows(RuntimeException.class,()->plans.get(tenant,connection,id));verify(jdbc).queryForList(contains("WHERE tenant_id=? AND marketplace_connection_id=? AND id=?"),eq(tenant),eq(connection),eq(id));}
 @Test void partialDispatchDebitsOnlyDispatchedContentsAndKeepsRemainderReserved(){
  doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,true);UUID allocation=UUID.randomUUID(),reservation=UUID.randomUUID();var row=new HashMap<String,Object>();row.put("id",allocation);row.put("warehouse_id",warehouse);row.put("seller_sku","PACK");row.put("item",item);row.put("location_id",location);row.put("expiration_date",null);row.put("each_per_sku",4);row.put("reserved",8);row.put("reservation_status","ACTIVE");row.put("reservation_id",reservation);row.put("amazon_shipment_id","FBA-verified");row.put("unit_cost",BigDecimal.ONE);row.put("cost_currency","USD");
  when(jdbc.queryForList(startsWith("SELECT a.*,r.account_catalog_item_id"),any(Object[].class))).thenReturn(List.of(row));
  when(jdbc.queryForObject(startsWith("SELECT EXISTS"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
  when(jdbc.queryForObject(startsWith("SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries"),eq(BigDecimal.class),any(Object[].class))).thenReturn(BigDecimal.TEN);
  when(jdbc.queryForObject(startsWith("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations"),eq(BigDecimal.class),any(Object[].class))).thenReturn(BigDecimal.valueOf(8));
  plans.dispatch(tenant,connection,id,email,new Dispatch(UUID.randomUUID(),1,List.of(new DispatchLine("PACK",warehouse,1)),"Truck reference"));
  var args=org.mockito.ArgumentCaptor.forClass(Object[].class);verify(jdbc).update(startsWith("INSERT INTO inventory_ledger_entries"),args.capture());assertEquals(-4,args.getValue()[4]);
  verify(jdbc).update(eq("UPDATE order_inventory_reservations SET quantity=quantity-?,updated_at=now() WHERE tenant_id=? AND id=? AND fba_plan_id=?"),eq(4),eq(tenant),eq(reservation),eq(id));
 }
 @Test void unlinkedAmazonShipmentCannotBeDispatched(){doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,true);var row=new HashMap<String,Object>();row.put("warehouse_id",warehouse);row.put("seller_sku","PACK");when(jdbc.queryForList(startsWith("SELECT a.*,r.account_catalog_item_id"),any(Object[].class))).thenReturn(List.of(row));assertThrows(RuntimeException.class,()->plans.dispatch(tenant,connection,id,email,new Dispatch(UUID.randomUUID(),1,List.of(new DispatchLine("PACK",warehouse,1)),"Truck")));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));}


 @Test void packingCountsReceiptLayersAndBundleComponentsOnlyOnce(){
  UUID other=UUID.randomUUID();var allocations=new ArrayList<Map<String,Object>>();
  allocations.add(packedAllocation(item,4,4,"ACTIVE"));allocations.add(packedAllocation(item,4,4,"ACTIVE"));
  allocations.add(packedAllocation(other,2,4,"ACTIVE"));allocations.add(packedAllocation(item,4,80,"RELEASED"));
  var p=new Plan(id,"FBA001","ALLOCATED",1,2,Map.of(),allocations,List.of());assertEquals(Map.of(warehouse+":PACK",2),expectedPacking(p));
 }
 Map<String,Object> packedAllocation(UUID product,int ratio,int each,String status){return Map.of("warehouse_id",warehouse,"seller_sku","PACK","account_catalog_item_id",product,"each_per_sku",ratio,"quantity",each,"dispatched",0,"reservation_status",status);}
 @Test void packingRejectsIncompleteBundleAtOrigin(){var p=new Plan(id,"FBA001","ALLOCATED",1,2,Map.of(),List.of(packedAllocation(item,4,8,"ACTIVE"),packedAllocation(UUID.randomUUID(),2,2,"ACTIVE")),List.of());assertThrows(RuntimeException.class,()->expectedPacking(p));}
 @Test void checkpointBackPreservesProductsAndDoesNotMutateStock(){
  doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,true);
  plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,1,null));
  verify(jdbc).update(startsWith("UPDATE fba_replenishment_plans SET step="),any(Object[].class));
  verify(jdbc,never()).update(startsWith("UPDATE order_inventory_reservations"),any(Object[].class));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));
 }
 @Test void checkpointCannotBypassAmazonGuardOrAllocation(){assertThrows(RuntimeException.class,()->plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,4,null)));assertThrows(RuntimeException.class,()->plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,2,null)));verify(jdbc,never()).update(anyString(),any(Object[].class));}
 @Test void releaseReturnsUndispatchedPlanToSourcesWithoutDeletingHistoryOrLedger(){doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,true);plans.release(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Use a different warehouse"));verify(jdbc).update(startsWith("UPDATE order_inventory_reservations SET status='RELEASED'"),any(Object[].class));verify(jdbc,never()).update(startsWith("DELETE"),any(Object[].class));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));}
 @Test void releaseCannotReassignDispatchedPlan(){doReturn(plan("PART_DISPATCHED")).when(plans).read(tenant,connection,id,true);assertThrows(RuntimeException.class,()->plans.release(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Change origin")));verify(jdbc,never()).update(anyString(),any(Object[].class));}
 @Test void mappingRejectsDecimalAndInactiveComponents(){assertEquals(0,wholeMapping(BigDecimal.ZERO));assertThrows(RuntimeException.class,()->wholeMapping(new BigDecimal("1.5")));}

 @Test void cancellationCannotReleaseLinkedAmazonCommitment(){
  var p=new Plan(id,"FBA001","PART_DISPATCHED",1,2,Map.of(),List.of(Map.of("amazon_shipment_id","FBA-real","dispatched",4)),List.of());doReturn(p).when(plans).read(tenant,connection,id,true);
  assertThrows(RuntimeException.class,()->plans.cancel(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Cancel remaining")));verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void destinationSplitCannotBeGuessedFromWarehouseAndSku(){
  doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,true);var first=Map.<String,Object>of("warehouse_id",warehouse,"seller_sku","PACK","amazon_shipment_id","FBA-child-A");var second=Map.<String,Object>of("warehouse_id",warehouse,"seller_sku","PACK","amazon_shipment_id","FBA-child-B");
  when(jdbc.queryForList(startsWith("SELECT a.*,r.account_catalog_item_id"),any(Object[].class))).thenReturn(List.of(first,second));
  assertThrows(RuntimeException.class,()->plans.dispatch(tenant,connection,id,email,new Dispatch(UUID.randomUUID(),1,List.of(new DispatchLine("PACK",warehouse,1)),"Truck")));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));
 }
 @Test void sameStoreCannotCreateSecondEditableDraft(){
  when(jdbc.queryForObject(startsWith("SELECT EXISTS(SELECT 1 FROM fba_replenishment_plans"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
  assertThrows(RuntimeException.class,()->plans.save(tenant,connection,email,new Draft(id,0,"FBA002",0,List.of(new Line("PACK",1)),Map.of())));verify(jdbc,never()).update(startsWith("INSERT INTO fba_replenishment_plans"),any(Object[].class));
 }
 @Test void sourceReleaseCannotCreateSecondEditableDraft(){
  doReturn(plan("ALLOCATED")).when(plans).read(tenant,connection,id,true);when(jdbc.queryForObject(startsWith("SELECT EXISTS(SELECT 1 FROM fba_replenishment_plans"),eq(Boolean.class),any(Object[].class))).thenReturn(true);
  assertThrows(RuntimeException.class,()->plans.release(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Reassign")));verify(jdbc,never()).update(startsWith("UPDATE order_inventory_reservations"),any(Object[].class));
 }

 @Test void masterCaseRequiresWholeKnownValuesAndOldFixtureConstructorRemainsCompatible(){assertEquals(BigDecimal.ONE,new Component("SKU",item,2).masterCase());assertEquals(new BigDecimal("6.0000"),wholeCase(new BigDecimal("6.0000")));assertNull(wholeCase(new BigDecimal("1.5")));assertNull(wholeCase(BigDecimal.ZERO));assertNull(wholeCase(null));}
 @Test void catalogueNetMeasurementsAreNeverActualPackedMeasurements(){var profile=new SourceProfile(item,BigDecimal.ONE,"lb",BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE,"in","Catalogue",false);assertFalse(profile.actualConfirmed());}

 @Test void requiredExpirationCannotBeInventedFromMissingSourceEvidence(){
  var allocation=packedAllocation(item,4,8,"ACTIVE");var p=new Plan(id,"FBA001","ALLOCATED",1,2,Map.of(),List.of(allocation),List.of());
  doReturn(List.of(new SourceProfile(item,null,null,null,null,null,null,"Catalogue",false,true))).when(plans).sourceProfiles(eq(tenant),anyList());
  assertThrows(RuntimeException.class,()->plans.validateExpirations(tenant,p,List.of()));
 }
 @Test void pendingAmazonCreateBlocksCancellationEvenBeforeRemoteIdentityArrives(){
  var scope=new FbaInboundWorkflow.Scope(tenant,tenant,connection,id);var origin=FbaInboundWorkflow.begin(FbaInboundWorkflow.start(scope,warehouse),UUID.randomUUID(),FbaInboundAdapter.Operation.CREATE,null,null,false,java.time.Instant.now());var p=new Plan(id,"FBA001","ALLOCATED",1,3,Map.of("inboundOrigins",Map.of(warehouse.toString(),new FbaInboundOrchestration.Checkpoint(origin,Map.of(),Map.of()))),List.of(),List.of());doReturn(p).when(plans).read(tenant,connection,id,true);
  assertThrows(RuntimeException.class,()->plans.cancel(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Cancel")));verify(jdbc,never()).update(startsWith("UPDATE order_inventory_reservations"),any(Object[].class));
 }
 @Test void pendingAmazonAttemptBlocksPackingChanges(){
  var scope=new FbaInboundWorkflow.Scope(tenant,tenant,connection,id);var origin=FbaInboundWorkflow.begin(FbaInboundWorkflow.start(scope,warehouse),UUID.randomUUID(),FbaInboundAdapter.Operation.CREATE,null,null,false,java.time.Instant.now());var payload=new HashMap<String,Object>();payload.put("inboundOrigins",Map.of(warehouse.toString(),new FbaInboundOrchestration.Checkpoint(origin,Map.of(),Map.of())));assertThrows(RuntimeException.class,()->plans.invalidateInboundPacking(payload));
 }
 @Test void reconciledRemoteCancellationCanReleaseRemainingStockWithoutRestoringDispatchedStock(){
  var scope=new FbaInboundWorkflow.Scope(tenant,tenant,connection,id);UUID request=UUID.randomUUID();var attempt=new FbaInboundWorkflow.Attempt(request,FbaInboundAdapter.Operation.CANCEL,null,1,null,FbaInboundWorkflow.Status.SUCCEEDED,"verified-cancel-operation");var origin=new FbaInboundWorkflow.Origin(scope,warehouse,1,"verified-plan",Map.of(request,attempt),Map.of(),Map.of(),List.of());var p=new Plan(id,"FBA001","PART_DISPATCHED",1,4,Map.of("inboundOrigins",Map.of(warehouse.toString(),new FbaInboundOrchestration.Checkpoint(origin,Map.of(),Map.of()))),List.of(Map.of("warehouse_id",warehouse,"amazon_shipment_id","verified-child","dispatched",4)),List.of());doReturn(p).when(plans).read(tenant,connection,id,true);
  plans.cancel(tenant,connection,id,email,new Cancel(UUID.randomUUID(),1,"Amazon cancellation reconciled"));verify(jdbc).update(startsWith("UPDATE order_inventory_reservations SET status='RELEASED'"),any(Object[].class));verify(jdbc,never()).update(startsWith("INSERT INTO inventory_ledger_entries"),any(Object[].class));
 }
 @Test void retiredOriginHistoryKeepsPreviouslyIssuedChildren(){
  var scope=new FbaInboundWorkflow.Scope(tenant,tenant,connection,id);var origin=new FbaInboundWorkflow.Origin(scope,warehouse,1,"verified-plan",Map.of(),Map.of("verified-child",new FbaInboundWorkflow.Child("verified-child",Map.of("SKU",1))),Map.of(),List.of());var checkpoint=new FbaInboundOrchestration.Checkpoint(origin,Map.of(),Map.of());var payload=new HashMap<String,Object>();payload.put("inboundOrigins",Map.of(warehouse.toString(),checkpoint));plans.retireInboundOrigins(payload);assertFalse(payload.containsKey("inboundOrigins"));assertTrue(payload.containsKey("inboundOriginHistory"));assertTrue(new ObjectMapper().writeValueAsString(payload).contains("verified-child"));
 }

 @Test void preparationRequiresExplicitConfirmationAndScopedSku(){
  var p=new Plan(id,"FBA001","ALLOCATED",1,2,Map.of(),List.of(packedAllocation(item,4,8,"ACTIVE")),List.of());doReturn(p).when(plans).read(tenant,connection,id,true);
  assertThrows(RuntimeException.class,()->plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,2,null,null,Map.of(warehouse,Map.of("PACK",new Preparation("SELLER","SELLER",false))))));
  assertThrows(RuntimeException.class,()->plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,2,null,null,Map.of(warehouse,Map.of("OTHER",new Preparation("SELLER","SELLER",true))))));verify(jdbc,never()).update(startsWith("UPDATE fba_replenishment_plans"),any(Object[].class));
 }
 @Test void confirmedPreparationIsSavedOnlyAsShipmentInputNotProductDefault(){
  var p=new Plan(id,"FBA001","ALLOCATED",1,2,Map.of(),List.of(packedAllocation(item,4,8,"ACTIVE")),List.of());doReturn(p).when(plans).read(tenant,connection,id,true);
  plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,2,null,null,Map.of(warehouse,Map.of("PACK",new Preparation("SELLER","SELLER",true)))));var args=org.mockito.ArgumentCaptor.forClass(Object[].class);verify(jdbc).update(startsWith("UPDATE fba_replenishment_plans SET step="),args.capture());assertTrue(args.getValue()[1].toString().contains("preparationByOrigin"));verify(jdbc,never()).update(startsWith("UPDATE account_catalog_items"),any(Object[].class));verify(jdbc,never()).update(startsWith("UPDATE order_inventory_reservations"),any(Object[].class));
 }
 @Test void createdOriginCannotChangePreparationOwnerWithoutCancelAndRecreate(){
  var scope=new FbaInboundWorkflow.Scope(tenant,tenant,connection,id);var origin=new FbaInboundWorkflow.Origin(scope,warehouse,1,"verified-amazon-plan",Map.of(),Map.of(),Map.of(),List.of());var current=Map.<String,Object>of("inboundOrigins",Map.of(warehouse.toString(),new FbaInboundOrchestration.Checkpoint(origin,Map.of(),Map.of())),"packing",Map.of("preparationByOrigin",Map.of(warehouse.toString(),Map.of("PACK",new Preparation("SELLER","SELLER",true)))));var changed=new HashMap<>(current);changed.put("packing",Map.of("preparationByOrigin",Map.of(warehouse.toString(),Map.of("PACK",new Preparation("AMAZON","SELLER",true)))));assertThrows(RuntimeException.class,()->plans.requirePreparationMutable(current,changed));
 }
 @Test void partialDispatchStillRejectsCartonOrPreparationMutation(){doReturn(plan("PART_DISPATCHED")).when(plans).read(tenant,connection,id,true);assertThrows(RuntimeException.class,()->plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,2,List.of(),Map.of(warehouse,new TransportInput(java.time.Instant.now().plusSeconds(3600))))));assertThrows(RuntimeException.class,()->plans.checkpoint(tenant,connection,id,email,new Checkpoint(UUID.randomUUID(),1,2,null,Map.of(warehouse,new TransportInput(java.time.Instant.now().plusSeconds(3600))),Map.of())));verify(jdbc,never()).update(startsWith("UPDATE fba_replenishment_plans"),any(Object[].class));}
}
