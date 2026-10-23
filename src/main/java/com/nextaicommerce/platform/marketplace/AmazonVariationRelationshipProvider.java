package com.nextaicommerce.platform.marketplace;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipRepository.*;

/** A1 local classification only. Eligibility is advisory; no inventory path consumes it. */
@Component
public class AmazonVariationRelationshipProvider {
    public enum State { PARENT,CHILD,STANDALONE,UNKNOWN,CONFLICT }
    public enum Quality { VERIFIED,MISSING,INCOMPLETE,EXPIRED,CONFLICT }
    public enum Eligibility { EXCLUDED_PARENT,OFFER_ELIGIBLE,UNRESOLVED }
    public record Scope(UUID tenant,UUID connection,String marketplace){
        public Scope{Objects.requireNonNull(tenant);Objects.requireNonNull(connection);
            if(marketplace==null||marketplace.isBlank()||marketplace.length()>30)throw new IllegalArgumentException("Invalid marketplace scope");}
    }
    public record Classification(State state,Quality quality,Eligibility eligibility,String reason,
        String lastVerifiedRole,Instant observedAt,Instant verifiedAt,Instant validUntil,
        long evidenceRevision,Long verifiedRevision,UUID sourceDocumentId) {}
    private final AmazonVariationRelationshipRepository repository;
    private final Clock clock;
    @Autowired public AmazonVariationRelationshipProvider(AmazonVariationRelationshipRepository repository){this(repository,Clock.systemUTC());}
    public AmazonVariationRelationshipProvider(AmazonVariationRelationshipRepository repository,Clock clock){this.repository=repository;this.clock=clock;}

    public Map<String,Classification> classify(Scope scope,Collection<String> skus){
        Objects.requireNonNull(scope);Objects.requireNonNull(skus);
        Set<String> exact=new LinkedHashSet<>(skus);
        if(exact.size()>100)throw new IllegalArgumentException("At most 100 exact SKUs per read");
        for(String sku:exact)if(sku==null||sku.isEmpty()||sku.codePointCount(0,sku.length())>240)throw new IllegalArgumentException("Invalid seller SKU");
        if(exact.isEmpty())return Map.of();
        return classifyAt(scope,exact,clock.instant());
    }
    Map<String,Classification> classifyAt(Scope scope,Collection<String> skus,Instant now){
        if(skus.size()>100)throw new IllegalArgumentException("At most 100 exact SKUs per read");
        return classifyGraph(scope,skus,repository.load(scope,skus),now);
    }
    Map<String,Classification> classifyGraph(Scope scope,Collection<String> exact,Graph graph,Instant now){
        Map<String,Classification> result=new LinkedHashMap<>();
        for(String sku:exact)result.put(sku,evaluate(scope,sku,graph,now));
        return Collections.unmodifiableMap(result);
    }
    private Classification evaluate(Scope scope,String sku,Graph graph,Instant now){
        Observation own=graph.observations().get(sku);
        if(!graph.validScope())return unresolved(own,Quality.MISSING,"INVALID_SCOPE");
        if(graph.limited())return unresolved(own,Quality.INCOMPLETE,"GRAPH_LIMIT");
        // Evaluate only this connected family: a bad, unrelated requested SKU cannot contaminate it.
        Set<String> family=new LinkedHashSet<>();Deque<String> pending=new ArrayDeque<>();pending.add(sku);
        while(!pending.isEmpty()){
            String member=pending.remove();if(!family.add(member))continue;
            for(Claim c:graph.claims()){
                if(c.subject().equals(member)&&!family.contains(c.related()))pending.add(c.related());
                if(c.related().equals(member)&&!family.contains(c.subject()))pending.add(c.subject());
            }
        }
        Quality failure=null;String reason=null;Instant oldest=now,newest=Instant.MIN,expiry=null;
        Map<String,Shape> shapes=new HashMap<>();
        for(String member:family){
            Observation o=graph.observations().get(member);Check check=check(scope,o,now);shapes.put(member,check.shape());
            if(check.quality()!=Quality.VERIFIED){
                if(failure==null||check.quality()==Quality.CONFLICT){failure=check.quality();reason=check.reason();}
                continue;
            }
            oldest=o.observedAt().isBefore(oldest)?o.observedAt():oldest;
            newest=o.observedAt().isAfter(newest)?o.observedAt():newest;
            Instant memberExpiry=o.expiresAt().isBefore(o.observedAt().plus(Duration.ofHours(24)))?o.expiresAt():o.observedAt().plus(Duration.ofHours(24));
            expiry=expiry==null||memberExpiry.isBefore(expiry)?memberExpiry:expiry;
            Set<String> actual=new HashSet<>();
            for(Claim c:graph.claims())if(c.subject().equals(member)){
                if(c.revision()!=o.revision()||!c.source().equals(o.source())||!c.observedAt().equals(o.observedAt())){
                    if(failure!=Quality.CONFLICT){failure=Quality.INCOMPLETE;reason="CLAIM_REVISION_MISMATCH";}
                }
                actual.add(c.direction()+":"+c.related());
            }
            Set<String> expected=new HashSet<>();
            check.shape().children().forEach(child->expected.add("PARENT_OF:"+child));
            check.shape().parents().forEach(parent->expected.add("CHILD_OF:"+parent));
            if(!actual.equals(expected)){
                if(failure!=Quality.CONFLICT){failure=actual.stream().anyMatch(c->!expected.contains(c))?Quality.CONFLICT:Quality.INCOMPLETE;reason="CLAIM_SET_MISMATCH";}
            }
        }
        // A definite contradiction between available authoritative observations wins
        // over an unrelated missing counterpart; absence alone is never a conflict.
        for(var entry:shapes.entrySet()){
            Shape shape=entry.getValue();if(shape==null)continue;
            for(String child:shape.children())if(shapes.get(child)!=null&&!shapes.get(child).parents().contains(entry.getKey())){
                failure=Quality.CONFLICT;reason="NONRECIPROCAL_CLAIM";
            }
            for(String parent:shape.parents())if(shapes.get(parent)!=null&&!shapes.get(parent).children().contains(entry.getKey())){
                failure=Quality.CONFLICT;reason="NONRECIPROCAL_CLAIM";
            }
        }
        if(failure!=null)return unresolved(own,failure,reason);
        if(own.verifiedAt().isBefore(newest))return unresolved(own,Quality.INCOMPLETE,"VERIFIED_BEFORE_MEMBERS");
        if(Duration.between(oldest,newest).compareTo(Duration.ofMinutes(15))>0)return unresolved(own,Quality.INCOMPLETE,"OBSERVATION_SKEW");
        for(String member:family){
            Shape shape=shapes.get(member);
            for(String child:shape.children())if(!shapes.containsKey(child)||!shapes.get(child).parents().contains(member))return unresolved(own,Quality.CONFLICT,"NONRECIPROCAL_CLAIM");
            for(String parent:shape.parents())if(!shapes.containsKey(parent)||!shapes.get(parent).children().contains(member))return unresolved(own,Quality.CONFLICT,"NONRECIPROCAL_CLAIM");
        }
        State role=shapes.get(sku).role();
        return new Classification(role,Quality.VERIFIED,role==State.PARENT?Eligibility.EXCLUDED_PARENT:Eligibility.OFFER_ELIGIBLE,
            "VERIFIED",own.verifiedRole(),own.observedAt(),own.verifiedAt(),expiry,own.revision(),own.verifiedRevision(),own.source());
    }
    private record Shape(State role,Set<String> parents,Set<String> children) {}
    private record Check(Quality quality,String reason,Shape shape) {}
    private static Check fail(Quality quality,String reason){return new Check(quality,reason,null);}
    private Check check(Scope scope,Observation o,Instant now){
        if(o==null||o.source()==null)return fail(Quality.MISSING,"MISSING_EVIDENCE");
        if("CONFLICT".equals(o.classification()))return fail(Quality.CONFLICT,o.reasonCode()==null?"RECORDED_CONFLICT":o.reasonCode());
        if(o.observedAt()!=null&&!o.observedAt().plus(Duration.ofHours(24)).isAfter(now))return fail(Quality.EXPIRED,"STALE_EVIDENCE");
        if(!o.complete()||!o.reciprocal()||!o.members()||"UNKNOWN".equals(o.classification()))return fail(Quality.INCOMPLETE,o.reasonCode()==null?"INCOMPLETE_EVIDENCE":o.reasonCode());
        if(o.verifiedRevision()==null||o.verifiedRevision()!=o.revision()||!Objects.equals(o.role(),o.verifiedRole())
            ||!Objects.equals(o.classification(),o.verifiedRole()))return fail(Quality.INCOMPLETE,"REVISION_MISMATCH");
        if(o.observedAt()==null||o.verifiedAt()==null||o.expiresAt()==null||o.observedAt().isAfter(now)||o.verifiedAt().isAfter(now)
            ||o.verifiedAt().isBefore(o.observedAt())||!o.expiresAt().isAfter(o.verifiedAt()))return fail(Quality.INCOMPLETE,"INVALID_TIME");
        if(!o.expiresAt().isAfter(now)||!o.observedAt().plus(Duration.ofHours(24)).isAfter(now))return fail(Quality.EXPIRED,"STALE_EVIDENCE");
        if(o.parserVersion()!=1||!"VARIATION_RELATIONSHIPS".equals(o.sourceType())||o.payload()==null)return fail(Quality.INCOMPLETE,"UNSUPPORTED_PROVENANCE");
        JsonNode root=o.payload();
        if(!o.sku().equals(root.path("sku").asText()))return fail(Quality.CONFLICT,"SOURCE_SKU_MISMATCH");
        JsonNode summary=group(root.path("summaries"),scope.marketplace());
        JsonNode relationships=group(root.path("relationships"),scope.marketplace());
        if(summary==null||relationships==null||!root.path("attributes").isObject()
            ||!summary.path("status").isArray()||!relationships.path("relationships").isArray())return fail(Quality.INCOMPLETE,"MISSING_DATASET");
        String asin=summary.path("asin").asText();
        if(asin.isBlank()||!asin.equals(o.asin())||(o.listingAsin()!=null&&!asin.equals(o.listingAsin())))return fail(Quality.CONFLICT,"ASIN_IDENTITY_MISMATCH");
        Set<String> parents=new HashSet<>(),children=new HashSet<>();
        for(JsonNode relation:relationships.path("relationships")){
            if("PACKAGE_HIERARCHY".equals(relation.path("type").asText()))continue;
            if(!"VARIATION".equals(relation.path("type").asText()))return fail(Quality.INCOMPLETE,"UNSUPPORTED_RELATIONSHIP");
            if(!identifiers(relation.path("parentSkus"),parents)||!identifiers(relation.path("childSkus"),children))return fail(Quality.INCOMPLETE,"MALFORMED_MEMBER");
        }
        if(parents.contains(o.sku())||children.contains(o.sku())||parents.size()>1||(!parents.isEmpty()&&!children.isEmpty()))return fail(Quality.CONFLICT,"CONFLICTING_DIRECTIONS");
        for(String field:List.of("parentage_level","child_parent_sku_relationship")){
            JsonNode value=root.path("attributes").path(field);
            if(!value.isMissingNode()&&!value.isArray())return fail(Quality.INCOMPLETE,"MALFORMED_ATTRIBUTES");
        }
        Set<String> levels=new HashSet<>(),attributeParents=new HashSet<>();
        for(JsonNode level:root.path("attributes").path("parentage_level")){
            if(!scope.marketplace().equals(level.path("marketplace_id").asText()))return fail(Quality.INCOMPLETE,"ATTRIBUTE_SCOPE_MISMATCH");
            levels.add(level.path("value").asText());
        }
        for(JsonNode relation:root.path("attributes").path("child_parent_sku_relationship")){
            if(!scope.marketplace().equals(relation.path("marketplace_id").asText()))return fail(Quality.INCOMPLETE,"ATTRIBUTE_SCOPE_MISMATCH");
            if("variation".equals(relation.path("child_relationship_type").asText())&&relation.has("parent_sku"))attributeParents.add(relation.path("parent_sku").asText());
        }
        State role;
        if(!children.isEmpty()){
            if(levels.isEmpty())return fail(Quality.INCOMPLETE,"MISSING_ROLE");
            if(!levels.equals(Set.of("parent"))||!attributeParents.isEmpty())return fail(Quality.CONFLICT,"ROLE_CONTRADICTION");
            for(JsonNode status:summary.path("status"))if("BUYABLE".equals(status.asText()))return fail(Quality.CONFLICT,"BUYABLE_PARENT");
            role=State.PARENT;
        }else if(!parents.isEmpty()){
            if(levels.isEmpty()||attributeParents.isEmpty())return fail(Quality.INCOMPLETE,"MISSING_ROLE");
            if(!levels.equals(Set.of("child"))||!attributeParents.equals(parents))return fail(Quality.CONFLICT,"ROLE_CONTRADICTION");role=State.CHILD;
        }else{
            if(!levels.isEmpty()||!attributeParents.isEmpty())return fail(Quality.INCOMPLETE,"MISSING_MEMBERS");role=State.STANDALONE;
        }
        if(!role.name().equals(o.role()))return fail(Quality.CONFLICT,"ROLE_CONTRADICTION");
        return new Check(Quality.VERIFIED,"VERIFIED",new Shape(role,Set.copyOf(parents),Set.copyOf(children)));
    }
    private static JsonNode group(JsonNode groups,String marketplace){
        if(!groups.isArray())return null;JsonNode result=null;
        for(JsonNode group:groups)if(marketplace.equals(group.path("marketplaceId").asText())){if(result!=null)return null;result=group;}
        return result;
    }
    private static boolean identifiers(JsonNode array,Set<String> output){
        if(array.isMissingNode())return true;if(!array.isArray())return false;
        for(JsonNode value:array){if(!value.isString()||value.asText().isEmpty())return false;output.add(value.asText());}return true;
    }
    private static Classification unresolved(Observation own,Quality quality,String reason){
        return new Classification(quality==Quality.CONFLICT?State.CONFLICT:State.UNKNOWN,quality,Eligibility.UNRESOLVED,reason,
            own==null?null:own.verifiedRole(),own==null?null:own.observedAt(),own==null?null:own.verifiedAt(),own==null?null:own.expiresAt(),own==null?0:own.revision(),own==null?null:own.verifiedRevision(),own==null?null:own.source());
    }
}
