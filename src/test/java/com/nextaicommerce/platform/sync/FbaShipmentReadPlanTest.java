package com.nextaicommerce.platform.sync;

import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class FbaShipmentReadPlanTest {
 final ObjectMapper json=new ObjectMapper();final Instant end=Instant.parse("2026-11-20T12:00:00Z");
 @Test void historyIsBroadOnlyOnceAndIncrementalUsesUpdatedDates(){
  var c=json.createObjectNode();FbaShipmentReadPlan.initialize(c,false,null,end);
  assertThat(FbaShipmentReadPlan.path("US",c)).contains("QueryType=SHIPMENT","ShipmentStatusList=").doesNotContain("DATE_RANGE");
  FbaShipmentReadPlan.initialize(c,true,end.minus(Duration.ofDays(1)),end);
  assertThat(FbaShipmentReadPlan.path("US",c)).contains("QueryType=DATE_RANGE","LastUpdatedAfter=2026-11-13","LastUpdatedBefore=2026-11-20").doesNotContain("ShipmentStatusList","CreationDate");
 }
 @Test void outageWindowReachesPreviousWatermarkInsteadOfDroppingOlderUpdates(){
  assertThat(FbaShipmentReadPlan.windowStart(end,end.minus(Duration.ofDays(20)))).isEqualTo(end.minus(Duration.ofDays(21)));
 }
 @Test void nextTokenNeverChangesOriginalQueryContract(){var c=json.createObjectNode();FbaShipmentReadPlan.initialize(c,true,end,end);c.put("next","token +/=");assertThat(FbaShipmentReadPlan.path("US",c)).contains("QueryType=NEXT_TOKEN","token%20%2B%2F%3D").doesNotContain("LastUpdated","ShipmentStatusList");}
 @Test void olderOpenIdsAreReadWithoutStatusFilterSoClosedTransitionIsVisible(){var c=json.createObjectNode();FbaShipmentReadPlan.initialize(c,true,end,end);var p=c.withObject("shipmentRead");p.put("stage","OPEN");p.putArray("openBatch").add("FBA-A").add("FBA-B");assertThat(FbaShipmentReadPlan.path("US",c)).contains("ShipmentIdList=FBA-A,FBA-B").doesNotContain("ShipmentStatusList");}
 @Test void recentlyClosedShipmentGetsFinalItemsOnceThenReusesExactEvidence(){
  var row=json.readTree("{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"CLOSED\",\"LastUpdatedDate\":\"2026-11-19T00:00:00Z\"}");var saved=json.createObjectNode();saved.put("itemEvidenceVersion",1);saved.putArray("itemEvidence");saved.put("itemsMetadataRevision","older");assertThat(FbaShipmentReadPlan.needsItems(row,saved,true)).isTrue();saved.put("itemsMetadataRevision",FbaShipmentReadPlan.revision(row));assertThat(FbaShipmentReadPlan.needsItems(row,saved,true)).isFalse();
 }
 @Test void openReceivedQuantitiesAreRefreshedEvenWithoutMetadataTimestampChange(){var row=json.readTree("{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"RECEIVING\"}");var saved=json.createObjectNode();saved.put("itemEvidenceVersion",1).put("itemsMetadataRevision",FbaShipmentReadPlan.revision(row));saved.putArray("itemEvidence");assertThat(FbaShipmentReadPlan.needsItems(row,saved,true)).isTrue();}
 @Test void missingEvidenceCannotBeTreatedAsAnUnchangedClosedShipment(){var row=json.readTree("{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"CLOSED\"}");assertThat(FbaShipmentReadPlan.needsItems(row,null,true)).isTrue();assertThat(FbaShipmentReadPlan.needsItems(row,json.createObjectNode(),true)).isTrue();}
 @Test void historyCompletionWaitsForFinalItemAndOpenPhaseDoesNotAdvanceWatermark(){var c=json.createObjectNode();FbaShipmentReadPlan.initialize(c,false,null,end);c.putArray("pending").add("FBA-A");assertThat(FbaShipmentReadPlan.complete(c)).isFalse();assertThat(c.path("shipmentRead").path("historyComplete").asBoolean()).isFalse();c.putArray("pending");assertThat(FbaShipmentReadPlan.complete(c)).isTrue();assertThat(c.path("shipmentRead").path("coveredThrough").asText()).isEqualTo(end.toString());}
 @Test void recentCompletionRequiresOpenSweepBeforeNewCoverage(){var c=json.createObjectNode();var previous=end.minusSeconds(86400);FbaShipmentReadPlan.initialize(c,true,previous,end);assertThat(FbaShipmentReadPlan.complete(c)).isFalse();assertThat(c.path("shipmentRead").path("stage").asText()).isEqualTo("OPEN");assertThat(c.path("shipmentRead").path("coveredThrough").asText()).isEqualTo(previous.toString());}
 @Test void errorAndUnknownShipmentStatesAreNotTerminal(){assertThat(FbaShipmentReadPlan.TERMINAL).containsExactlyInAnyOrder("CLOSED","CANCELLED","DELETED").doesNotContain("ERROR","RECEIVING");}
 @Test void partialAndSelectedItemCursorsNeverEstablishHistoricalCoverage(){
  assertThat(FbaShipmentReadPlan.trustedHistory(json.readTree("{\"shipmentsComplete\":true,\"pages\":3,\"pending\":[\"A\"]}"))).isFalse();
  assertThat(FbaShipmentReadPlan.trustedHistory(json.readTree("{\"shipmentsComplete\":true,\"pages\":3,\"itemsRefreshOnly\":true}"))).isFalse();
  assertThat(FbaShipmentReadPlan.trustedHistory(json.readTree("{\"shipmentsComplete\":true,\"pages\":0}"))).isFalse();
  assertThat(FbaShipmentReadPlan.trustedHistory(json.readTree("{\"shipmentsComplete\":true,\"pages\":3}"))).isTrue();
 }
 @Test void recentClosedHeaderWithoutProviderRevisionMustRefetchReceivedQuantities(){var row=json.readTree("{\"ShipmentId\":\"FBA-A\",\"ShipmentStatus\":\"CLOSED\"}");var previous=json.createObjectNode().put("itemEvidenceVersion",1).put("itemsMetadataRevision",FbaShipmentReadPlan.revision(row));previous.putArray("itemEvidence");assertThat(FbaShipmentReadPlan.needsItems(row,previous,true)).isTrue();}
}
