package com.nextaicommerce.platform.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded read-only 2024 discovery protocol. Checkpoint and completed evidence publish together. */
final class FbaParentPlanImport {
    static final List<String> STATUSES=List.of("ACTIVE","SHIPPED","VOIDED");
    static final int MAX_REQUESTS=10_000, MAX_CHILDREN=200;
    static final String BASE="/inbound/fba/2024-03-20/inboundPlans";
    private static final ObjectMapper JSON=new ObjectMapper();
    static boolean complete(ObjectNode state){return state.path("statusIndex").asInt()>=STATUSES.size();}
    static String path(ObjectNode state){
        if(complete(state))throw new IllegalStateException("Parent discovery already complete.");
        if(!state.path("reuseInitialized").asBoolean()&&state.path("requests").asInt()>=MAX_REQUESTS)throw new IllegalStateException("FBA parent discovery request budget exhausted; incomplete coverage retained in checkpoint.");
        var entry=state.path("entry");
        if(entry.isObject()){
            var children=entry.path("plan").path("shipments");
            return BASE+"/"+id(entry.path("plan").path("inboundPlanId"))+
                "/shipments/"+id(children.get(state.path("childIndex").asInt()).path("shipmentId"));
        }
        var listed=state.path("listed");
        if(listed.isArray())return BASE+"/"+id(listed.get(state.path("planIndex").asInt()).path("inboundPlanId"));
        return BASE+"?pageSize=30&status="+STATUSES.get(state.path("statusIndex").asInt())+
            (state.path("incremental").asBoolean()?"&sortBy=LAST_UPDATED_TIME&sortOrder=DESC":"&sortBy=CREATION_TIME&sortOrder=ASC")+(state.path("nextToken").asText("").isBlank()?"":"&paginationToken="+encode(state.path("nextToken").asText()));
    }
    /** Returns only a complete plan document; a partial child set never becomes trusted evidence. */
    static ObjectNode accept(AmazonSyncStore.Job job,ObjectNode state,JsonNode response,String requestId){
        path(state); // Validate bounds before accepting a response, also on resumed checkpoints.
        if(requestId==null||requestId.isBlank())throw new IllegalStateException("Amazon parent read request ID missing.");
        state.put("requests",state.path("requests").asInt()+1);
        var entry=state.path("entry");
        if(entry.isObject()){
            String expected=entry.path("plan").path("shipments").get(state.path("childIndex").asInt()).path("shipmentId").asText();
            if(!expected.equals(id(response.path("shipmentId"))))throw new IllegalStateException("Amazon parent child identity mismatch; existing relationships retained.");
            var child=JSON.createObjectNode();child.put("requestId",requestId);
            child.set("shipment",pick(response,List.of("shipmentId","shipmentConfirmationId","status")));
            ((tools.jackson.databind.node.ArrayNode)entry.path("children")).add(child);
            state.put("childIndex",state.path("childIndex").asInt()+1);
            if(state.path("childIndex").asInt()<entry.path("plan").path("shipments").size())return null;
            return finish(job,state);
        }
        if(state.path("listed").isArray()){
            var listing=state.path("listed").get(state.path("planIndex").asInt());
            if(!id(listing.path("inboundPlanId")).equals(id(response.path("inboundPlanId")))||!market(response,job.marketplaceId()))throw new IllegalStateException("Amazon inbound plan scope/identity mismatch.");
            if(!response.path("shipments").isArray()||response.path("shipments").size()>MAX_CHILDREN)throw new IllegalStateException("Amazon parent child membership unavailable or exceeds safe bound.");
            var children=new HashSet<String>();for(var child:response.path("shipments"))if(!children.add(id(child.path("shipmentId"))))throw new IllegalStateException("Duplicate Amazon parent child identity.");
            var plan=sanitizePlan(response);var current=state.putObject("entry");current.set("plan",plan);current.put("planRequestId",requestId);current.putArray("children");state.put("childIndex",0);
            return children.isEmpty()?finish(job,state):null;
        }
        if(!response.path("inboundPlans").isArray()||response.path("inboundPlans").size()>30)throw new IllegalStateException("Amazon inbound plan listing unavailable or exceeds requested page size.");
        var listed=state.putArray("listed");var ids=new HashSet<String>();
        for(var plan:response.path("inboundPlans")){
            id(plan.path("inboundPlanId"));
            if(!ids.add(plan.path("inboundPlanId").asText()))throw new IllegalStateException("Duplicate inbound plan in listing page.");
            if(!plan.path("marketplaceIds").isArray()||plan.path("marketplaceIds").isEmpty())throw new IllegalStateException("Inbound plan marketplace scope missing; coverage incomplete.");
            var markets=new HashSet<String>();for(var value:plan.path("marketplaceIds"))if(!value.isString()||!value.asText().matches("[A-Z0-9]{1,20}")||!markets.add(value.asText()))throw new IllegalStateException("Invalid inbound plan marketplace scope.");
            if(market(plan,job.marketplaceId())){
                var sanitized=sanitizePlan(plan);var known=state.path("knownVersions").path(plan.path("inboundPlanId").asText());
                boolean reused=known.isObject()&&validTime(plan.path("lastUpdatedAt").asText())&&known.equals(sanitized);
                if(!reused)listed.add(sanitized);else state.put("reusedPlans",state.path("reusedPlans").asInt()+1);
            }
            else {state.put("otherMarketplacePlans",state.path("otherMarketplacePlans").asInt()+1);for(var value:plan.path("marketplaceIds"))if(job.marketplaceId().equals(value.asText()))state.put("scopeAmbiguousPlans",state.path("scopeAmbiguousPlans").asInt()+1);}
        }
        String next=response.path("pagination").path("nextToken").asText("");
        if(state.path("incremental").asBoolean()){
            boolean stable=!response.path("inboundPlans").isEmpty();
            for(var plan:response.path("inboundPlans")){
                String updated=plan.path("lastUpdatedAt").asText();
                if(!validTime(updated)){state.put("uncertainOrder",true);stable=false;continue;}
                Instant current=Instant.parse(updated);String previous=state.path("lastListedUpdate").asText();
                if(validTime(previous)&&current.isAfter(Instant.parse(previous)))state.put("uncertainOrder",true);
                state.put("lastListedUpdate",updated);
                var known=state.path("knownVersions").path(plan.path("inboundPlanId").asText());
                if(!known.equals(sanitizePlan(plan))||!current.isBefore(Instant.parse(state.path("cutoff").asText())))stable=false;
            }
            // Only stop on a whole known unchanged older page, never on an unknown/unverified plan.
            if(stable&&!state.path("uncertainOrder").asBoolean()&&!state.path("unresolvedOpenParents").asBoolean())next="";
        }
        if(next.length()>1024)throw new IllegalStateException("Invalid parent pagination token.");
        if(!next.isBlank()){
            var seen=state.withArray("seenTokens");for(var old:seen)if(next.equals(old.asText()))throw new IllegalStateException("Repeated parent pagination token; coverage incomplete.");
            seen.add(next);
        }
        state.put("listingRequestId",requestId);state.put("nextToken",next);state.put("planIndex",0);
        if(listed.isEmpty())advancePage(state);
        return null;
    }
    private static ObjectNode finish(AmazonSyncStore.Job job,ObjectNode state){
        var doc=JSON.createObjectNode();doc.put("tenant",job.tenantId().toString());doc.put("store",job.connectionId().toString());doc.put("marketplace",job.marketplaceId());
        doc.put("listingRequestId",state.path("listingRequestId").asText());doc.put("completedAt",Instant.now().toString());
        var listing=doc.putObject("listing").putArray("inboundPlans");listing.add(state.path("listed").get(state.path("planIndex").asInt()).deepCopy());
        doc.putArray("plans").add(state.path("entry").deepCopy());state.remove("entry");state.remove("childIndex");
        state.put("completedPlans",state.path("completedPlans").asInt()+1);state.put("planIndex",state.path("planIndex").asInt()+1);
        if(state.path("planIndex").asInt()>=state.path("listed").size())advancePage(state);
        return doc;
    }
    private static void advancePage(ObjectNode state){state.remove("listed");state.remove("planIndex");if(state.path("nextToken").asText("").isBlank()){state.put("statusIndex",state.path("statusIndex").asInt()+1);state.remove("seenTokens");state.remove("lastListedUpdate");}}
    static boolean validTime(String value){try{Instant.parse(value);return true;}catch(java.time.DateTimeException ex){return false;}}
    /** Only exact fully scoped listing + plan + every child evidence may be reused. */
    static ObjectNode verifiedVersion(AmazonSyncStore.Job job,JsonNode doc){
        if(!validTime(doc.path("completedAt").asText())||!job.tenantId().toString().equals(doc.path("tenant").asText())||!job.connectionId().toString().equals(doc.path("store").asText())||!job.marketplaceId().equals(doc.path("marketplace").asText())||doc.path("listingRequestId").asText().isBlank())return null;
        if(doc.path("listing").path("inboundPlans").size()!=1||doc.path("plans").size()!=1)return null;
        var listing=doc.path("listing").path("inboundPlans").get(0);var entry=doc.path("plans").get(0);var plan=entry.path("plan");
        if(!market(listing,job.marketplaceId())||!market(plan,job.marketplaceId())||!listing.path("inboundPlanId").equals(plan.path("inboundPlanId"))||entry.path("planRequestId").asText().isBlank()||!plan.path("shipments").isArray()||!entry.path("children").isArray())return null;
        var declared=new HashSet<String>();for(var child:plan.path("shipments"))if(!declared.add(child.path("shipmentId").asText())||child.path("shipmentId").asText().isBlank())return null;
        var returned=new HashSet<String>();for(var child:entry.path("children"))if(child.path("requestId").asText().isBlank()||!returned.add(child.path("shipment").path("shipmentId").asText()))return null;
        if(!declared.equals(returned)||!listing.path("lastUpdatedAt").equals(plan.path("lastUpdatedAt"))||!listing.path("status").equals(plan.path("status")))return null;
        try{id(listing.path("inboundPlanId"));for(String child:declared)id(JSON.getNodeFactory().stringNode(child));}catch(IllegalStateException invalid){return null;}
        return sanitizePlan(listing);
    }
    static ObjectNode sanitizePlan(JsonNode input){var result=pick(input,List.of("inboundPlanId","name","createdAt","lastUpdatedAt","status","marketplaceIds"));
        if(input.has("shipments")){var children=result.putArray("shipments");for(var child:input.path("shipments"))children.add(pick(child,List.of("shipmentId","status")));}
        if(input.has("sourceAddress"))result.set("sourceAddress",pick(input.path("sourceAddress"),List.of("city","stateOrProvinceCode","postalCode","countryCode")));return result;}
    private static ObjectNode pick(JsonNode input,List<String> names){var result=JSON.createObjectNode();for(String name:names)if(input.has(name))result.set(name,input.path(name).deepCopy());return result;}
    private static boolean market(JsonNode node,String market){return node.path("marketplaceIds").isArray()&&node.path("marketplaceIds").size()==1&&market.equals(node.path("marketplaceIds").get(0).asText());}
    private static String id(JsonNode node){String value=node.asText("");if(!value.matches("[A-Za-z0-9-]{38}"))throw new IllegalStateException("Invalid Amazon parent/internal shipment ID.");return value;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
}
