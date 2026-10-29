package com.nextaicommerce.platform.shopify;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.nextaicommerce.platform.shopify.ShopifyOrderLifecycle.*;

class ShopifyOrderLifecycleTest {
    private final Identity id=new Identity(UUID.randomUUID(),UUID.randomUUID(),"gid://shopify/Order/1","gid://shopify/LineItem/1");
    private final Instant cutoff=Instant.parse("2026-10-27T10:00:00Z"),created=cutoff.plusSeconds(1),updated=created.plusSeconds(10);
    private Fulfillment ship(int qty,String status){return new Fulfillment("gid://shopify/Fulfillment/1","gid://shopify/FulfillmentLineItem/1",qty,status,Ownership.MERCHANT,updated);}
    private Observation observation(Instant time,String payment,boolean cancel,Ownership ownership,int open,Fulfillment... ships){
        return new Observation(id,created,time,true,false,cancel,payment,true,ownership,3,open,List.of(ships),"hash-"+time+payment+cancel+open+List.of(ships));
    }
    private History history(Observation o,Plan p){return new History(id,o.updated(),o.hash(),p.state(),p.nextSuccess());}
    @Test void pendingPaymentReservesButDoesNotShip(){
        var p=plan(observation(updated,"PENDING",false,Ownership.MERCHANT,3),null,cutoff);
        assertThat(p.state()).isEqualTo(State.LIVE);assertThat(p.reserveUnits()).isEqualTo(3);assertThat(p.shipments()).isEmpty();
    }
    @Test void paidIsNotPhysicalShipment(){assertThat(plan(observation(updated,"PAID",false,Ownership.MERCHANT,3),null,cutoff).shipments()).isEmpty();}
    @Test void successfulPartialFulfillmentConsumesOnlyDeltaAndIsReplaySafe(){
        var first=observation(updated,"PAID",false,Ownership.MERCHANT,3);var h=history(first,plan(first,null,cutoff));
        var second=observation(updated.plusSeconds(1),"PAID",false,Ownership.MERCHANT,2,ship(1,"SUCCESS"));
        var p=plan(second,h,cutoff);assertThat(p.shipments()).containsExactly(new Shipment(ship(1,"SUCCESS").key(),1));
        assertThat(p.reserveUnits()).isEqualTo(2);
        assertThat(plan(second,history(second,p),cutoff).unchanged()).isTrue();
        var third=observation(updated.plusSeconds(2),"PAID",false,Ownership.MERCHANT,1,ship(2,"SUCCESS"));
        assertThat(plan(third,history(second,p),cutoff).shipments()).containsExactly(new Shipment(ship(2,"SUCCESS").key(),1));
    }
    @Test void failedOrPendingFulfillmentDoesNotShip(){
        for(String status:List.of("PENDING","OPEN","ERROR","FAILURE","CANCELLED"))
            assertThat(plan(observation(updated,"PAID",false,Ownership.MERCHANT,3,ship(1,status)),null,cutoff).shipments()).isEmpty();
    }
    @Test void noCutoverAndPreCutoverOrdersAreReportingOnly(){
        var first=observation(updated,"PAID",false,Ownership.MERCHANT,3);
        assertThat(plan(first,null,null).state()).isEqualTo(State.REPORTING_ONLY);
        assertThat(plan(first,null,created.plusSeconds(1)).state()).isEqualTo(State.REPORTING_ONLY);
    }
    @Test void firstSeenFulfilledDoesNotAllocateOrDeductTodayStock(){
        var p=plan(observation(updated,"PAID",false,Ownership.MERCHANT,0,ship(3,"SUCCESS")),null,cutoff);
        assertThat(p.state()).isEqualTo(State.REPORTING_ONLY);assertThat(p.reserveUnits()).isZero();assertThat(p.shipments()).isEmpty();
        assertThat(p.nextSuccess()).containsEntry(ship(3,"SUCCESS").key(),3);
    }
    @Test void firstSeenPartialEstablishesBaselineAndOnlyReservesRemaining(){
        var o=observation(updated,"PAID",false,Ownership.MERCHANT,2,ship(1,"SUCCESS"));var p=plan(o,null,cutoff);
        assertThat(p.reserveUnits()).isEqualTo(2);assertThat(p.shipments()).isEmpty();
        var next=observation(updated.plusSeconds(1),"PAID",false,Ownership.MERCHANT,1,ship(2,"SUCCESS"));
        assertThat(plan(next,history(o,p),cutoff).shipments()).containsExactly(new Shipment(ship(2,"SUCCESS").key(),1));
    }
    @Test void cancellationReleasesRemainingAndKeepsRealShipmentDelta(){
        var first=observation(updated,"PAID",false,Ownership.MERCHANT,3);var h=history(first,plan(first,null,cutoff));
        var p=plan(observation(updated.plusSeconds(1),"PAID",true,Ownership.MERCHANT,0,ship(1,"SUCCESS")),h,cutoff);
        assertThat(p.state()).isEqualTo(State.CANCELLED);assertThat(p.releaseReservations()).isTrue();
        assertThat(p.reserveUnits()).isZero();assertThat(p.shipments()).hasSize(1);
        assertThat(plan(observation(updated,"VOIDED",false,Ownership.MERCHANT,3),null,cutoff).releaseReservations()).isTrue();
    }
    @Test void refundAndFulfillmentReversalNeverCreatePhysicalReturn(){
        var o=observation(updated,"PAID",false,Ownership.MERCHANT,2,ship(1,"SUCCESS"));var p=plan(o,null,cutoff);
        var refund=plan(observation(updated.plusSeconds(1),"REFUNDED",false,Ownership.MERCHANT,2,ship(1,"SUCCESS")),history(o,p),cutoff);
        assertThat(refund.shipments()).isEmpty();
        var reversed=plan(observation(updated.plusSeconds(1),"PAID",false,Ownership.MERCHANT,3,ship(1,"CANCELLED")),history(o,p),cutoff);
        assertThat(reversed.reserveUnits()).isNull();assertThat(reversed.shipments()).isEmpty();assertThat(reversed.review()).contains("review");
    }
    @Test void missingOrExternalOwnershipHoldsExistingReservationsWithoutGuessing(){
        for(var ownership:List.of(Ownership.UNKNOWN,Ownership.EXTERNAL)){
            var p=plan(observation(updated,"PENDING",false,ownership,3),null,cutoff);
            assertThat(p.state()).isEqualTo(State.NEEDS_REVIEW);assertThat(p.reserveUnits()).isNull();
            assertThat(p.releaseReservations()).isFalse();assertThat(p.shipments()).isEmpty();
        }
    }
    @Test void incompleteStaleAndConflictingEvidenceNeverChangeStockPlan(){
        var o=observation(updated,"PAID",false,Ownership.MERCHANT,3);var h=history(o,plan(o,null,cutoff));
        var incomplete=new Observation(id,created,updated.plusSeconds(1),false,false,false,"PAID",true,Ownership.MERCHANT,3,0,List.of(ship(3,"SUCCESS")),"incomplete");
        for(var next:List.of(incomplete,observation(updated.minusSeconds(1),"PAID",false,Ownership.MERCHANT,2),observation(updated,"PAID",false,Ownership.MERCHANT,2))){
            var p=plan(next,h,cutoff);assertThat(p.reserveUnits()).isNull();assertThat(p.shipments()).isEmpty();assertThat(p.review()).isNotBlank();
        }
    }
    @Test void tenantConnectionAndLineIdentityAreFenced(){
        var o=observation(updated,"PAID",false,Ownership.MERCHANT,3);
        for(var foreign:List.of(new Identity(UUID.randomUUID(),id.connection(),id.order(),id.line()),new Identity(id.tenant(),UUID.randomUUID(),id.order(),id.line()),new Identity(id.tenant(),id.connection(),id.order(),"gid://shopify/LineItem/2")))
            assertThatThrownBy(()->plan(o,new History(foreign,updated,"x",State.LIVE,Map.of()),cutoff)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void testOrderDoesNotReserve(){
        var o=new Observation(id,created,updated,true,true,false,"PAID",true,Ownership.MERCHANT,3,3,List.of(),"test");
        var p=plan(o,null,cutoff);assertThat(p.state()).isEqualTo(State.TEST);assertThat(p.reserveUnits()).isZero();
    }
}
