package com.nextaicommerce.platform.fba;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class FbaShipmentHierarchyTest {
 @Test void knownParentCountsOnceAndStandaloneNeverBorrowsRelationship(){var parent=new FbaShipmentParents.Parent("plan","Name",UUID.randomUUID(),UUID.randomUUID(),"child");var groups=FbaShipmentHierarchy.group(List.of("A","X","B","Y"),Map.of("A",parent,"B",parent));assertThat(groups.keySet()).containsExactly("plan:plan","shipment:X","shipment:Y");assertThat(groups.get("plan:plan")).containsExactly("A","B");}
 @Test void pagesParentsBeforeLoadingChildrenAndRestoresGroupOrder(){UUID t=UUID.randomUUID(),s=UUID.randomUUID();var repo=mock(FbaReadRepository.class);var parents=mock(FbaShipmentParents.class);var parent=new FbaShipmentParents.Parent("P","Name",UUID.randomUUID(),UUID.randomUUID(),"child");when(repo.matchingShipmentReferences(t,s,"","user","",null,false)).thenReturn(List.of("A","X","B"));when(parents.read(eq(t),eq(s),any())).thenReturn(new FbaShipmentParents.Projection(Map.of("A",parent,"B",parent),Set.of()));var a=new FbaReadRepository.Shipment("A","A","SHIPPED","FC",null,null,1,2,0);var b=new FbaReadRepository.Shipment("B","B","SHIPPED","FC",null,null,1,3,0);var x=new FbaReadRepository.Shipment("X","X","SHIPPED","FC",null,null,1,4,0);when(repo.shipmentReferences(eq(t),eq(s),eq(List.of("A","B","X")),eq("user"),eq(false))).thenReturn(new FbaReadRepository.ShipmentPage(List.of(a,x,b),3,0,25));var result=new FbaShipmentHierarchy(repo,parents).page(t,s,"",0,25,"user","",null,false);assertThat(result.shipments().total()).isEqualTo(2);assertThat(result.shipments().rows()).extracting(FbaReadRepository.Shipment::id).containsExactly("A","B","X");}
 @Test void verifiedParentIdentitySearchIncludesOnlyScopedEligibleChildrenBeforePagination(){UUID t=UUID.randomUUID(),s=UUID.randomUUID();var repo=mock(FbaReadRepository.class);var parents=mock(FbaShipmentParents.class);var p=new FbaShipmentParents.Parent("verified-parent","Shipment plan",null,null,"child");when(repo.matchingShipmentReferences(t,s,"VERIFIED-PARENT","user","SHIPPED",null,false)).thenReturn(List.of());when(repo.matchingShipmentReferences(t,s,"","user","SHIPPED",null,false)).thenReturn(List.of("A","X","B"));when(parents.read(eq(t),eq(s),any())).thenReturn(new FbaShipmentParents.Projection(Map.of("A",p,"B",p),Set.of()));var a=new FbaReadRepository.Shipment("A","A","SHIPPED","FC",null,null,1,2,0);var b=new FbaReadRepository.Shipment("B","B","SHIPPED","FC",null,null,1,3,0);when(repo.shipmentReferences(eq(t),eq(s),eq(List.of("A","B")),eq("user"),eq(false))).thenReturn(new FbaReadRepository.ShipmentPage(List.of(a,b),2,0,25));var result=new FbaShipmentHierarchy(repo,parents).page(t,s,"VERIFIED-PARENT",0,25,"user","SHIPPED",null,false);assertThat(result.shipments().total()).isEqualTo(1);assertThat(result.shipments().rows()).extracting(FbaReadRepository.Shipment::id).containsExactly("A","B");verify(repo).matchingShipmentReferences(t,s,"","user","SHIPPED",null,false);}
 @Test void parentSkuUnionNeverSumsDuplicateSkuAndMissingItemsRemainUnknown(){var a=new FbaReadRepository.Shipment("A","A","SHIPPED","FC",null,null,2,5,1);var b=new FbaReadRepository.Shipment("B","B","SHIPPED","FC",null,null,1,3,2);var summary=FbaShipmentHierarchy.summarize(List.of(a,b),Map.of("A",new FbaReadRepository.CompletedShipmentItems(Set.of("same","other"),5,1),"B",new FbaReadRepository.CompletedShipmentItems(Set.of("same"),3,2)),1);assertThat(summary.skus()).isEqualTo(2);assertThat(summary.shipped()).isEqualTo(8);assertThat(summary.received()).isEqualTo(3);assertThat(summary.matching()).isEqualTo(1);assertThat(summary.dates()).isEqualTo("—");assertThat(summary.costs().getTotal()).isNull();assertThat(FbaShipmentHierarchy.summarize(List.of(a,b),Map.of("A",new FbaReadRepository.CompletedShipmentItems(Set.of("same","other"),5,1)),1).skus()).isNull();}
 @Test void parentTotalsIncludeOtherStatusesAndWarehousesWhileMatchingChildrenStayFiltered(){UUID t=UUID.randomUUID(),store=UUID.randomUUID(),warehouse=UUID.randomUUID();var repo=mock(FbaReadRepository.class);var parents=mock(FbaShipmentParents.class);var parent=new FbaShipmentParents.Parent("P","Plan",null,null,"child");when(repo.matchingShipmentReferences(t,store,"","u","SHIPPED",warehouse,false)).thenReturn(List.of("A"));when(repo.matchingShipmentReferences(t,store,"","u","",null,false)).thenReturn(List.of("A","B"));when(parents.read(eq(t),eq(store),any())).thenReturn(new FbaShipmentParents.Projection(Map.of("A",parent,"B",parent),Set.of()));var a=new FbaReadRepository.Shipment("A","A","SHIPPED","FC",null,null,1,5,0);var b=new FbaReadRepository.Shipment("B","B","RECEIVING","FC",null,null,1,7,2);when(repo.shipmentReferences(t,store,List.of("A"),"u",false)).thenReturn(new FbaReadRepository.ShipmentPage(List.of(a),1,0,25));when(repo.shipmentReferences(t,store,List.of("B"),"u",false)).thenReturn(new FbaReadRepository.ShipmentPage(List.of(b),1,0,25));when(repo.completedShipmentItems(t,store,List.of("A","B"))).thenReturn(Map.of("A",new FbaReadRepository.CompletedShipmentItems(Set.of("SKU"),5,0),"B",new FbaReadRepository.CompletedShipmentItems(Set.of("SKU"),7,2)));var result=new FbaShipmentHierarchy(repo,parents).page(t,store,"",0,25,"u","SHIPPED",warehouse,false);assertThat(result.shipments().rows()).extracting(FbaReadRepository.Shipment::id).containsExactly("A");var sum=result.summaries().get("P");assertThat(sum.shipped()).isEqualTo(12);assertThat(sum.shipments()).isEqualTo(2);assertThat(sum.matching()).isEqualTo(1);assertThat(sum.skus()).isEqualTo(1);assertThat(sum.status()).isEqualTo("Mixed statuses");}
 @Test void parentDepletionSeparatesNoneSomeAllAndUnknownCoverage(){
  var no=new FbaWorkspaceRepository.DepletionState(false,false,false,null);var yes=new FbaWorkspaceRepository.DepletionState(false,true,false,null);
  assertThat(FbaShipmentHierarchy.depletionSummary(List.of("A","B"),Map.of("A",no,"B",no),true)).isEmpty();
  assertThat(FbaShipmentHierarchy.depletionSummary(List.of("A","B"),Map.of("A",yes,"B",no),true)).isEqualTo("Partially decreased");
  assertThat(FbaShipmentHierarchy.depletionSummary(List.of("A","B"),Map.of("A",yes,"B",yes),true)).isEqualTo("Decreased");
  assertThat(FbaShipmentHierarchy.depletionSummary(List.of("A","B"),Map.of("A",yes),true)).isEqualTo("Needs review");
  assertThat(FbaShipmentHierarchy.depletionSummary(List.of("A"),Map.of("A",yes),false)).isEqualTo("Needs review");
 }
 @Test void parentDatePrefersVerifiedPlanCreationEvenWhenChildTimestampIsMissing(){
  var child=new FbaReadRepository.Shipment("A","FBA shipment (11/18/2026 14:20)","SHIPPED","FC",null,null,1,5,0);
  var summary=FbaShipmentHierarchy.summarize(List.of(child),Map.of(),1);
  var parent=new FbaShipmentParents.Parent("P","Plan",null,null,"A",null,null,java.time.Instant.parse("2026-11-19T14:16:41Z"),Set.of("A"),"proof");
  assertThat(child.importedCreation()).isNull();
  assertThat(summary.displayDate(parent)).isEqualTo("11/19/2026");
 }
 @Test void parentWithoutCreationUsesCompleteValidatedChildDisplayDateRange(){
  var a=new FbaReadRepository.Shipment("A","Shipment (11/18/2026 14:20)","SHIPPED","FC",null,null,1,5,0);
  var b=new FbaReadRepository.Shipment("B","Shipment (11/19/2026 14:20)","SHIPPED","FC",null,null,1,5,0);
  var parent=new FbaShipmentParents.Parent("P","Plan",null,null,"A");
  assertThat(FbaShipmentHierarchy.summarize(List.of(a,b),Map.of(),2).displayDate(parent)).isEqualTo("11/18/2026 – 11/19/2026");
  assertThat(FbaShipmentHierarchy.summarize(List.of(a,a),Map.of(),2).displayDate(parent)).isEqualTo("11/18/2026");
 }
 @Test void parentDateDoesNotInventMissingOrInvalidChildDates(){
  var a=new FbaReadRepository.Shipment("A","Shipment (11/18/2026 14:20)","SHIPPED","FC",null,null,1,5,0);
  var invalid=new FbaReadRepository.Shipment("B","Shipment (02/30/2026 14:20)","SHIPPED","FC",null,null,1,5,0);
  var parent=new FbaShipmentParents.Parent("P","Plan",null,null,"A");
  assertThat(FbaShipmentHierarchy.summarize(List.of(a,invalid),Map.of(),2).displayDate(parent)).isEqualTo("—");
  assertThat(FbaShipmentHierarchy.summarize(List.of(),Map.of(),0).displayDate(parent)).isEqualTo("—");
 }
 @org.junit.jupiter.api.Test void remainingWarehouseEligibilityIncludesHiddenMembersAndIgnoresCompletedSiblings(){
  var done=new FbaWorkspaceRepository.DepletionState(false,true,false,null);var pending=new FbaWorkspaceRepository.DepletionState(false,false,false,null);
  var states=java.util.Map.of("A",done,"B",pending,"C",pending);
  org.assertj.core.api.Assertions.assertThat(FbaShipmentHierarchy.remainingMatched(java.util.List.of("A","B","C"),states,java.util.Set.of("B","C"),true)).isTrue();
  org.assertj.core.api.Assertions.assertThat(FbaShipmentHierarchy.remainingMatched(java.util.List.of("A","B","C"),states,java.util.Set.of("B"),true)).isFalse();
  org.assertj.core.api.Assertions.assertThat(FbaShipmentHierarchy.remainingMatched(java.util.List.of("A","B","C"),states,java.util.Set.of("B","C"),false)).isFalse();
  org.assertj.core.api.Assertions.assertThat(FbaShipmentHierarchy.remainingMatched(java.util.List.of("A"),states,java.util.Set.of(),true)).isFalse();
  org.assertj.core.api.Assertions.assertThat(FbaShipmentHierarchy.remainingMatched(java.util.List.of("B","unknown"),states,java.util.Set.of("B","unknown"),true)).isFalse();
 }
}
