package com.nextaicommerce.platform.sync;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class FbaParentPlanImportTest {
 final ObjectMapper json=new ObjectMapper();
 final String plan="wf1234abcd-1234-abcd-5678-1234abcd5678",child="sh1234abcd-1234-abcd-5678-1234abcd5678";
 final AmazonSyncStore.Job job=new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FBA_INBOUND","FBA_MANUAL","ATVPDKIKX0DER",Instant.now().minusSeconds(86400),Instant.now(),null,0,0,false,null);
 ObjectNode state(){return json.createObjectNode();}
 ObjectNode plan(){var p=json.createObjectNode();p.put("inboundPlanId",plan).put("name","Fixture");p.putArray("marketplaceIds").add(job.marketplaceId());p.putObject("sourceAddress").put("city","Tomball").put("postalCode","77377").put("addressLine1","DO NOT ARCHIVE").put("phoneNumber","PRIVATE");return p;}
 ObjectNode list(String next){var r=json.createObjectNode();r.putArray("inboundPlans").add(plan());if(next!=null)r.putObject("pagination").put("nextToken",next);return r;}
 ObjectNode detail(){var p=plan();p.putArray("shipments").addObject().put("shipmentId",child);return p;}
 void listed(ObjectNode s){assertThat(FbaParentPlanImport.accept(job,s,list(null),"listing-request")).isNull();}
 void detailed(ObjectNode s){listed(s);assertThat(FbaParentPlanImport.accept(job,s,detail(),"plan-request")).isNull();}
 @Test void paginatedFiltersStayIdenticalAndTokenIsEscaped(){var s=state();FbaParentPlanImport.accept(job,s,list("a+b&c"),"r1");var empty=plan();empty.putArray("shipments");FbaParentPlanImport.accept(job,s,empty,"r2");assertThat(FbaParentPlanImport.path(s)).isEqualTo(FbaParentPlanImport.BASE+"?pageSize=30&status=ACTIVE&sortBy=CREATION_TIME&sortOrder=ASC&paginationToken=a%2Bb%26c");}
 @Test void fullStatusCoverageDoesNotPretendWindowFiltersExist(){var s=state();for(String status:FbaParentPlanImport.STATUSES){assertThat(FbaParentPlanImport.path(s)).contains("status="+status).doesNotContain("After","Before");var empty=json.createObjectNode();empty.putArray("inboundPlans");FbaParentPlanImport.accept(job,s,empty,"list-"+status);}assertThat(FbaParentPlanImport.complete(s)).isTrue();}
 @Test void completeChildSetProducesScopedSanitizedEvidence(){var s=state();detailed(s);var body=json.createObjectNode().put("shipmentId",child).put("shipmentConfirmationId","FBA123456789").put("contactInformation","PRIVATE");var doc=FbaParentPlanImport.accept(job,s,body,"child-request");assertThat(doc.path("tenant").asText()).isEqualTo(job.tenantId().toString());assertThat(doc.path("store").asText()).isEqualTo(job.connectionId().toString());assertThat(doc.path("marketplace").asText()).isEqualTo(job.marketplaceId());assertThat(doc.toString()).contains("listing-request","plan-request","child-request","FBA123456789").doesNotContain("PRIVATE","DO NOT ARCHIVE","addressLine1","phoneNumber");}
 @Test void partialChildrenNeverPublishAndResumeSameExactGet(){var s=state();listed(s);var d=detail();((tools.jackson.databind.node.ArrayNode)d.path("shipments")).addObject().put("shipmentId","sh2234abcd-1234-abcd-5678-1234abcd5678");FbaParentPlanImport.accept(job,s,d,"plan-request");assertThat(FbaParentPlanImport.accept(job,s,json.createObjectNode().put("shipmentId",child),"child-one")).isNull();var resumed=(ObjectNode)json.readTree(s.toString());assertThat(FbaParentPlanImport.path(resumed)).endsWith("/shipments/sh2234abcd-1234-abcd-5678-1234abcd5678");assertThat(resumed.path("entry").path("children")).hasSize(1);}
 @Test void mismatchedChildFailsClosed(){var s=state();detailed(s);assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,json.createObjectNode().put("shipmentId","sh2234abcd-1234-abcd-5678-1234abcd5678"),"r")).isInstanceOf(IllegalStateException.class);assertThat(s.path("entry").path("children")).isEmpty();}
 @Test void differentMarketplaceCannotBecomePlanEvidence(){var s=state();listed(s);var d=detail();d.putArray("marketplaceIds").add("OTHER");assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,d,"r")).isInstanceOf(IllegalStateException.class);}
 @Test void missingRequestIdCannotProduceEvidence(){var s=state();assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,list(null),null)).isInstanceOf(IllegalStateException.class);assertThat(s).isEmpty();}
 @Test void duplicateChildIdsFailClosed(){var s=state();listed(s);var d=detail();((tools.jackson.databind.node.ArrayNode)d.path("shipments")).addObject().put("shipmentId",child);assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,d,"r")).isInstanceOf(IllegalStateException.class);}
 @Test void repeatedPaginationTokenCannotLoop(){var s=state();var page=json.createObjectNode();page.putArray("inboundPlans");page.putObject("pagination").put("nextToken","repeat");FbaParentPlanImport.accept(job,s,page,"r1");assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,page,"r2")).isInstanceOf(IllegalStateException.class);}
 @Test void parentBudgetIsIndependentOfLegacyPagesAndFinite(){var s=state();s.put("requests",FbaParentPlanImport.MAX_REQUESTS);assertThatThrownBy(()->FbaParentPlanImport.path(s)).isInstanceOf(IllegalStateException.class).hasMessageContaining("budget");}
 @Test void malformedRequiredMarketplaceScopeCannotMasqueradeAsCompleteForeignPage(){for(String invalid:new String[]{"null","\"wrong-type\"","[]","[null]","[\"\"]","[5]","[\"ATVPDKIKX0DER\",\"ATVPDKIKX0DER\"]","[\" ATVPDKIKX0DER\"]"}){var s=state();var r=list(null);((ObjectNode)r.path("inboundPlans").get(0)).set("marketplaceIds",json.readTree(invalid));assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,r,"listing")).isInstanceOf(IllegalStateException.class);assertThat(FbaParentPlanImport.complete(s)).isFalse();}var s=state();var r=list(null);((ObjectNode)r.path("inboundPlans").get(0)).remove("marketplaceIds");assertThatThrownBy(()->FbaParentPlanImport.accept(job,s,r,"listing")).isInstanceOf(IllegalStateException.class);assertThat(FbaParentPlanImport.complete(s)).isFalse();}
}
