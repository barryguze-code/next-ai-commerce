package com.nextaicommerce.platform.sync;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class FbaParentReuseTest {
 final ObjectMapper json=new ObjectMapper();final String id="wf1234abcd-1234-abcd-5678-1234abcd5678",child="sh1234abcd-1234-abcd-5678-1234abcd5678";
 final AmazonSyncStore.Job job=new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FBA_INBOUND","FBA_MANUAL","ATVPDKIKX0DER",Instant.parse("2026-11-01T00:00:00Z"),Instant.parse("2026-11-20T00:00:00Z"),null,0,0,false,null);
 ObjectNode listing(String date){return (ObjectNode)json.readTree("{\"inboundPlanId\":\""+id+"\",\"marketplaceIds\":[\"ATVPDKIKX0DER\"],\"lastUpdatedAt\":\""+date+"\",\"status\":\"SHIPPED\"}");}
 ObjectNode response(ObjectNode listing){var response=json.createObjectNode();response.putArray("inboundPlans").add(listing);response.putObject("pagination").put("nextToken","more");return response;}
 ObjectNode state(){var s=json.createObjectNode().put("incremental",true).put("cutoff","2026-11-13T00:00:00Z");s.putObject("knownVersions").set(id,listing("2026-10-01T00:00:00Z"));return s;}
 @Test void unchangedVerifiedOlderPageReusesExactRelationshipsWithoutDetailOrChildRequests(){var s=state();FbaParentPlanImport.accept(job,s,response(listing("2026-10-01T00:00:00Z")),"list");assertThat(s.path("reusedPlans").asInt()).isEqualTo(1);assertThat(s.path("statusIndex").asInt()).isEqualTo(1);assertThat(s.has("listed")).isFalse();assertThat(FbaParentPlanImport.path(s)).contains("LAST_UPDATED_TIME","sortOrder=DESC","status=SHIPPED");}
 @Test void changedOrUnknownPlanCannotBeSkippedEvenBelowCutoff(){var s=state();FbaParentPlanImport.accept(job,s,response(listing("2026-10-02T00:00:00Z")),"list");assertThat(s.path("listed").size()).isEqualTo(1);assertThat(FbaParentPlanImport.path(s)).endsWith("/"+id);assertThat(s.path("nextToken").asText()).isEqualTo("more");}
 @Test void unresolvedOlderOpenLinksDisableEarlyCutoffButStillReuseVerifiedDetails(){var s=state();s.put("unresolvedOpenParents",true);FbaParentPlanImport.accept(job,s,response(listing("2026-10-01T00:00:00Z")),"list");assertThat(s.path("nextToken").asText()).isEqualTo("more");assertThat(s.path("statusIndex").asInt()).isZero();}
 @Test void missingTimestampOrInconsistentOrderingCannotClaimStableCutoff(){var s=state();FbaParentPlanImport.accept(job,s,response(listing("missing")),"list");assertThat(s.path("uncertainOrder").asBoolean()).isTrue();assertThat(s.path("nextToken").asText()).isEqualTo("more");var s2=state();s2.put("lastListedUpdate","2026-09-01T00:00:00Z");FbaParentPlanImport.accept(job,s2,response(listing("2026-10-01T00:00:00Z")),"list");assertThat(s2.path("uncertainOrder").asBoolean()).isTrue();}
 @Test void onlyFullTenantScopedEvidenceIsReusable(){
  var doc=json.createObjectNode().put("tenant",job.tenantId().toString()).put("store",job.connectionId().toString()).put("marketplace",job.marketplaceId()).put("listingRequestId","request").put("completedAt","2026-11-19T00:00:00Z");doc.putObject("listing").putArray("inboundPlans").add(listing("2026-10-01T00:00:00Z"));var entry=doc.putArray("plans").addObject().put("planRequestId","plan");var plan=listing("2026-10-01T00:00:00Z");plan.putArray("shipments").addObject().put("shipmentId",child);entry.set("plan",plan);entry.putArray("children");assertThat(FbaParentPlanImport.verifiedVersion(job,doc)).isNull();entry.withArray("children").addObject().put("requestId","child").putObject("shipment").put("shipmentId",child);assertThat(FbaParentPlanImport.verifiedVersion(job,doc)).isEqualTo(listing("2026-10-01T00:00:00Z"));doc.remove("completedAt");assertThat(FbaParentPlanImport.verifiedVersion(job,doc)).isNull();doc.put("completedAt","malformed");assertThat(FbaParentPlanImport.verifiedVersion(job,doc)).isNull();doc.put("completedAt","2026-11-19T00:00:00Z");doc.put("tenant",UUID.randomUUID().toString());assertThat(FbaParentPlanImport.verifiedVersion(job,doc)).isNull();
 }
}
