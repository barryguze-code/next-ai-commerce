package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipRepository.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;

class AmazonVariationRelationshipProviderTest {
    static final Instant NOW=Instant.parse("2026-10-18T12:00:00Z");
    static final ObjectMapper JSON=new ObjectMapper();
    static Observation observation(String sku,String role,List<String> parents,List<String> children){
        var attributes=new LinkedHashMap<String,Object>();
        if(!role.equals("STANDALONE"))attributes.put("parentage_level",List.of(Map.of("marketplace_id","US","value",role.toLowerCase(Locale.ROOT))));
        if(role.equals("CHILD"))attributes.put("child_parent_sku_relationship",List.of(Map.of("marketplace_id","US","child_relationship_type","variation","parent_sku",parents.getFirst())));
        var relations=new ArrayList<Map<String,Object>>();
        if(!parents.isEmpty()||!children.isEmpty())relations.add(Map.of("type","VARIATION","parentSkus",parents,"childSkus",children));
        var payload=JSON.valueToTree(Map.of("sku",sku,"summaries",List.of(Map.of("marketplaceId","US","asin","ASIN1","status",List.of(role.equals("PARENT")?"DISCOVERABLE":"BUYABLE"))),
            "attributes",attributes,"relationships",List.of(Map.of("marketplaceId","US","relationships",relations))));
        return new Observation(sku,"ASIN1","ASIN1",role,role,true,true,true,1,1L,role,NOW.minusSeconds(60),NOW.minusSeconds(30),NOW.plusSeconds(3600),UUID.randomUUID(),1,"VARIATION_RELATIONSHIPS",payload);
    }
    static Claim claim(Observation from,String related,String direction){return new Claim(from.sku(),related,direction,from.revision(),from.source(),from.observedAt());}
    static Observation metadata(Observation o,String classification,boolean complete,long revision,Long verifiedRevision,Instant observed,Instant expires){
        return new Observation(o.sku(),o.asin(),o.listingAsin(),o.role(),classification,complete,o.reciprocal(),o.members(),revision,verifiedRevision,o.verifiedRole(),observed,o.verifiedAt(),expires,o.source(),o.parserVersion(),o.sourceType(),o.payload());
    }
    private Classification result(String sku,List<Observation> observations,List<Claim> claims){
        var repo=mock(AmazonVariationRelationshipRepository.class);var scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),"US");
        Map<String,Observation> map=new HashMap<>();observations.forEach(o->map.put(o.sku(),o));
        // Provider deduplicates the requested collection into an exact ordered set.
        when(repo.load(scope,new LinkedHashSet<>(List.of(sku)))).thenReturn(new Graph(map,claims,true,false));
        return new AmazonVariationRelationshipProvider(repo,Clock.fixed(NOW,ZoneOffset.UTC)).classify(scope,List.of(sku)).get(sku);
    }
    @Test void emptyStorageIsUnknown(){assertThat(result("NONE",List.of(),List.of()).state()).isEqualTo(State.UNKNOWN);}
    @Test void reciprocalParentAndChildAreVerified(){
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","CHILD",List.of("P"),List.of());
        var claims=List.of(claim(p,"C","PARENT_OF"),claim(c,"P","CHILD_OF"));
        assertThat(result("P",List.of(p,c),claims).eligibility()).isEqualTo(Eligibility.EXCLUDED_PARENT);
        assertThat(result("C",List.of(p,c),claims).state()).isEqualTo(State.CHILD);
        assertThat(result("P",List.of(p,c),claims).evidenceRevision()).isEqualTo(1);
        assertThat(result("P",List.of(p,c),claims).sourceDocumentId()).isEqualTo(p.source());
    }
    @Test void explicitEmptyEvidenceIsStandalone(){assertThat(result("S",List.of(observation("S","STANDALONE",List.of(),List.of())),List.of()).state()).isEqualTo(State.STANDALONE);}
    @Test void missingRelationshipsDoNotEstablishStandalone(){
        var s=observation("S","STANDALONE",List.of(),List.of());var payload=s.payload().deepCopy();((tools.jackson.databind.node.ObjectNode)payload).remove("relationships");
        var missing=new Observation(s.sku(),s.asin(),s.listingAsin(),s.role(),s.classification(),true,true,true,1,1L,s.role(),s.observedAt(),s.verifiedAt(),s.expiresAt(),s.source(),1,s.sourceType(),payload);
        assertThat(result("S",List.of(missing),List.of()).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void missingChildAndIncompleteEvidenceNeverVerify(){
        var p=observation("P","PARENT",List.of(),List.of("C"));
        assertThat(result("P",List.of(p),List.of(claim(p,"C","PARENT_OF"))).state()).isEqualTo(State.UNKNOWN);
        assertThat(result("P",List.of(metadata(p,"UNKNOWN",false,1,1L,p.observedAt(),p.expiresAt())),List.of()).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void expiredAndChangedRevisionAreUnknown(){
        var s=observation("S","STANDALONE",List.of(),List.of());
        assertThat(result("S",List.of(metadata(s,s.classification(),true,1,1L,s.observedAt(),NOW)),List.of()).quality()).isEqualTo(Quality.EXPIRED);
        assertThat(result("S",List.of(metadata(s,s.classification(),true,2,1L,s.observedAt(),s.expiresAt())),List.of()).reason()).isEqualTo("REVISION_MISMATCH");
        assertThat(result("S",List.of(metadata(s,s.classification(),true,1,1L,NOW.minusSeconds(86401),NOW.plusSeconds(3600))),List.of()).quality()).isEqualTo(Quality.EXPIRED);
    }
    @Test void recordedConflictCannotBecomeParent(){
        var p=observation("P","PARENT",List.of(),List.of("C"));
        assertThat(result("P",List.of(metadata(p,"CONFLICT",true,1,1L,p.observedAt(),p.expiresAt())),List.of()).state()).isEqualTo(State.CONFLICT);
    }
    @Test void extraOrNonreciprocalClaimsAreConflicts(){
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","STANDALONE",List.of(),List.of());
        assertThat(result("P",List.of(p,c),List.of(claim(p,"C","PARENT_OF"))).state()).isEqualTo(State.CONFLICT);
    }
    @Test void staleClaimRevisionAndMissingClaimAreUnresolved(){
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","CHILD",List.of("P"),List.of());
        assertThat(result("P",List.of(p,c),List.of(new Claim("P","C","PARENT_OF",2,p.source(),p.observedAt()),claim(c,"P","CHILD_OF"))).reason()).isEqualTo("CLAIM_REVISION_MISMATCH");
        assertThat(result("P",List.of(p,c),List.of()).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void buyableParentIsConflicting(){
        var p=observation("P","PARENT",List.of(),List.of("C"));
        ((tools.jackson.databind.node.ObjectNode)p.payload().path("summaries").get(0)).set("status",JSON.valueToTree(List.of("BUYABLE")));
        assertThat(result("P",List.of(p),List.of()).reason()).isEqualTo("BUYABLE_PARENT");
    }
    @Test void noInferenceFromMatchingAsin(){
        var a=observation("0001","STANDALONE",List.of(),List.of());var b=observation("1","STANDALONE",List.of(),List.of());
        assertThat(result("0001",List.of(a,b),List.of()).state()).isEqualTo(State.STANDALONE);
        assertThat(result("0001 ",List.of(a,b),List.of()).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void noMarketplaceTransportOrUnboundedReadContract(){
        var repo=mock(AmazonVariationRelationshipRepository.class);var provider=new AmazonVariationRelationshipProvider(repo);
        var scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),"US");assertThat(provider.classify(scope,List.of())).isEmpty();verifyNoInteractions(repo);
        assertThatThrownBy(()->provider.classify(scope,java.util.stream.IntStream.range(0,101).mapToObj(String::valueOf).toList())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sourceIdentityMismatchCannotBorrowClassification(){
        var s=observation("S","STANDALONE",List.of(),List.of());
        ((tools.jackson.databind.node.ObjectNode)s.payload()).put("sku","OTHER");
        assertThat(result("S",List.of(s),List.of()).reason()).isEqualTo("SOURCE_SKU_MISMATCH");
        var a=observation("A","STANDALONE",List.of(),List.of());
        ((tools.jackson.databind.node.ObjectNode)a.payload().path("summaries").get(0)).put("asin","OTHER-ASIN");
        assertThat(result("A",List.of(a),List.of()).reason()).isEqualTo("ASIN_IDENTITY_MISMATCH");
    }
    @Test void malformedRoleAttributeIsUnknownRatherThanStandalone(){
        var s=observation("S","STANDALONE",List.of(),List.of());
        ((tools.jackson.databind.node.ObjectNode)s.payload().path("attributes")).set("parentage_level",JSON.createObjectNode());
        assertThat(result("S",List.of(s),List.of()).state()).isEqualTo(State.UNKNOWN);
    }
    @Test void multipleParentsAreConflicting(){
        var c=observation("C","CHILD",List.of("P"),List.of());
        ((tools.jackson.databind.node.ObjectNode)c.payload().path("relationships").get(0).path("relationships").get(0)).set("parentSkus",JSON.valueToTree(List.of("P","OTHER")));
        assertThat(result("C",List.of(c),List.of()).reason()).isEqualTo("CONFLICTING_DIRECTIONS");
    }
    @Test void oldCounterpartAndFutureEvidenceCannotVerify(){
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","CHILD",List.of("P"),List.of());
        var expired=metadata(c,c.classification(),true,1,1L,c.observedAt(),NOW);
        assertThat(result("P",List.of(p,expired),List.of(claim(p,"C","PARENT_OF"),claim(expired,"P","CHILD_OF"))).quality()).isEqualTo(Quality.EXPIRED);
        var future=metadata(p,p.classification(),true,1,1L,NOW.plusSeconds(1),p.expiresAt());
        assertThat(result("P",List.of(future),List.of()).reason()).isEqualTo("INVALID_TIME");
    }
    @Test void familyObservationSkewAndForeignClaimProvenanceAreUnresolved(){
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","CHILD",List.of("P"),List.of());
        var old=metadata(p,p.classification(),true,1,1L,NOW.minusSeconds(1200),p.expiresAt());
        assertThat(result("P",List.of(old,c),List.of(claim(old,"C","PARENT_OF"),claim(c,"P","CHILD_OF"))).reason()).isEqualTo("OBSERVATION_SKEW");
        assertThat(result("P",List.of(p,c),List.of(new Claim("P","C","PARENT_OF",1,UUID.randomUUID(),p.observedAt()),claim(c,"P","CHILD_OF"))).reason()).isEqualTo("CLAIM_REVISION_MISMATCH");
    }
    @Test void unrelatedMissingFamilyDoesNotContaminateStandalone(){
        var s=observation("S","STANDALONE",List.of(),List.of());var p=observation("P","PARENT",List.of(),List.of("MISSING"));
        assertThat(result("S",List.of(s,p),List.of(claim(p,"MISSING","PARENT_OF"))).state()).isEqualTo(State.STANDALONE);
    }

}
