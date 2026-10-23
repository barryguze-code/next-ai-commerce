package com.nextaicommerce.platform.marketplace;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import com.nextaicommerce.platform.sync.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipRepository.*;

/** Called only inside the job's fenced transaction. No HTTP or inventory mutation. */
@Component
public class AmazonVariationRelationshipPublisher {
    private final JdbcTemplate jdbc;private final AmazonSyncStore archive;
    private final AmazonVariationRelationshipRepository repository;private final AmazonVariationRelationshipProvider provider;private final ObjectMapper json;
    public AmazonVariationRelationshipPublisher(JdbcTemplate jdbc,AmazonSyncStore archive,AmazonVariationRelationshipRepository repository,AmazonVariationRelationshipProvider provider,ObjectMapper json){
        this.jdbc=jdbc;this.archive=archive;this.repository=repository;this.provider=provider;this.json=json;
    }
    private static Timestamp time(Instant t){return t==null?null:Timestamp.from(t);}
    private Object[] key(Scope s,String sku){return new Object[]{s.tenant(),s.connection(),s.marketplace(),sku};}
    private void lock(Scope s){jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND id=? AND marketplace_identifier=? FOR UPDATE",s.tenant(),s.connection(),s.marketplace());}
    private void stub(Scope s,String sku){jdbc.update("INSERT INTO amazon_variation_classifications(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",key(s,sku));}
    public Set<String> publish(AmazonSyncStore.Job job,String sku,String body,String requestId,Instant observed){
        Scope scope=new Scope(job.tenantId(),job.connectionId(),job.marketplaceId());lock(scope);
        Graph before=repository.load(scope,List.of(sku));Set<String> affected=new LinkedHashSet<>(before.observations().keySet());affected.add(sku);
        JsonNode root=null;try{root=json.readTree(body);}catch(RuntimeException ignored){/* Raw malformed response is archived below. */}
        UUID source=archive.saveVariationEvidence(job,sku,body,root==null?null:root.toString(),requestId);
        String asin=null,role=null;boolean matching=root!=null&&sku.equals(root.path("sku").asText());
        boolean identityConflict=root!=null&&root.path("sku").isString()&&!root.path("sku").asText().isEmpty()&&!matching;
        Set<String> parents=new LinkedHashSet<>(),children=new LinkedHashSet<>();
        if(matching){
            for(JsonNode group:root.path("summaries"))if(scope.marketplace().equals(group.path("marketplaceId").asText()))asin=group.path("asin").asText(null);
            Set<String> levels=new HashSet<>();for(JsonNode level:root.path("attributes").path("parentage_level"))if(scope.marketplace().equals(level.path("marketplace_id").asText()))levels.add(level.path("value").asText());
            for(JsonNode group:root.path("relationships"))if(scope.marketplace().equals(group.path("marketplaceId").asText()))for(JsonNode relation:group.path("relationships"))if("VARIATION".equals(relation.path("type").asText())){
                collect(relation.path("parentSkus"),parents);collect(relation.path("childSkus"),children);
            }
            if(levels.equals(Set.of("parent"))||!children.isEmpty())role="PARENT";
            else if(levels.equals(Set.of("child"))||!parents.isEmpty())role="CHILD";
            else role="STANDALONE"; // Provisional only: shared verifier requires an explicit complete empty set.
        }
        if(asin!=null&&asin.length()>20)asin=null;
        stub(scope,sku);
        jdbc.update("DELETE FROM amazon_variation_claims WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND subject_sku=?",key(scope,sku));
        long revision=jdbc.queryForObject("""
            UPDATE amazon_variation_classifications SET source_document_id=?,observed_asin=?,observed_role=?,
                observed_at=?,evidence_revision=evidence_revision+1,own_complete=?,next_attempt_at=NULL,classification='UNKNOWN',reason_code=?,
                verified_role=NULL,verified_revision=NULL,verified_at=NULL,valid_until=NULL,reciprocal_complete=false,members_complete=false,
                fetch_status='SUCCESS',failure_count=0,last_attempt_at=?,last_http_status=200,last_error_code=NULL,updated_at=?
            WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? RETURNING evidence_revision
            """,Long.class,source,asin,role,time(observed),matching&&hasDatasets(root,scope.marketplace()),matching?"PENDING_VERIFICATION":identityConflict?"SOURCE_SKU_MISMATCH":"MALFORMED_RESPONSE",
            time(observed),time(observed),scope.tenant(),scope.connection(),scope.marketplace(),sku);
        if(matching){
            JsonNode statuses=json.createArrayNode();for(JsonNode group:root.path("summaries"))if(scope.marketplace().equals(group.path("marketplaceId").asText())&&group.path("status").isArray())statuses=group.path("status");
            jdbc.update("UPDATE amazon_variation_classifications SET amazon_status=CAST(? AS jsonb),attributes=CAST(? AS jsonb) WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",statuses.toString(),root.path("attributes").isObject()?root.path("attributes").toString():"{}",scope.tenant(),scope.connection(),scope.marketplace(),sku);
        }
        Set<String> referenced=new LinkedHashSet<>();referenced.addAll(parents);referenced.addAll(children);
        int count=0;for(String related:referenced){if(related.equals(sku)||++count>1000)continue;stub(scope,related);}
        for(String parent:parents)if(!parent.equals(sku)&&hasStub(scope,parent))claim(scope,sku,parent,"CHILD_OF",revision,source,observed);
        for(String child:children)if(!child.equals(sku)&&hasStub(scope,child))claim(scope,sku,child,"PARENT_OF",revision,source,observed);
        affected.addAll(referenced.stream().limit(1000).toList());
        Graph after=repository.load(scope,affected);affected.addAll(after.observations().keySet());
        // Invalidate the old and new connected families atomically, including members removed by reparenting.
        Map<String,Observation> original=new HashMap<>(after.observations());
        // A changed family is not promoted until the authoritative parent is read after its children.
        for(String member:affected)if(!member.equals(sku)&&after.claims().stream().anyMatch(c->c.subject().equals(member)&&c.related().equals(sku)&&c.direction().equals("PARENT_OF")))jdbc.update("UPDATE amazon_variation_classifications SET diagnostics=jsonb_set(diagnostics,'{parent_reread}', 'false') WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? AND observed_role='PARENT'",key(scope,member));
        if("PARENT".equals(role))jdbc.update("UPDATE amazon_variation_classifications SET diagnostics=jsonb_set(diagnostics,'{parent_reread}',CAST(? AS jsonb)) WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",Boolean.toString(!children.isEmpty()&&children.stream().allMatch(c->original.get(c)!=null&&original.get(c).observedAt()!=null)),scope.tenant(),scope.connection(),scope.marketplace(),sku);

        for(String member:affected){
            jdbc.update("""
                UPDATE amazon_variation_classifications SET classification='UNKNOWN',reason_code='DEPENDENCY_CHANGED',
                    reciprocal_complete=false,members_complete=false,updated_at=?
                WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?
                """,time(observed),scope.tenant(),scope.connection(),scope.marketplace(),member);
            Observation o=original.get(member);
            boolean parentReady=o==null||!"PARENT".equals(o.role())||Boolean.TRUE.equals(jdbc.queryForObject("SELECT coalesce((diagnostics->>'parent_reread')::boolean,false) FROM amazon_variation_classifications WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",Boolean.class,key(scope,member)));
            if(parentReady&&o!=null&&o.complete()&&o.role()!=null&&o.observedAt()!=null&&o.observedAt().plus(Duration.ofHours(24)).isAfter(observed)){
                // Provisional markers remain invisible until the shared verifier's result is committed.
                jdbc.update("""
                    UPDATE amazon_variation_classifications SET classification=observed_role,verified_role=observed_role,
                        verified_revision=evidence_revision,verified_at=?,valid_until=?,reciprocal_complete=true,members_complete=true
                    WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?
                    """,time(observed),time(o.observedAt().plus(Duration.ofHours(24))),scope.tenant(),scope.connection(),scope.marketplace(),member);
            }
        }
        Graph staged=repository.load(scope,affected);var results=provider.classifyGraph(scope,affected,staged,observed);
        for(var entry:results.entrySet()){
            var r=entry.getValue();boolean verified=r.quality()==Quality.VERIFIED;Observation old=original.get(entry.getKey());
            String lastRole=old==null?null:old.verifiedRole();Long lastRevision=old==null?null:old.verifiedRevision();
            Instant lastVerified=old==null?null:old.verifiedAt(),lastExpiry=old==null?null:old.expiresAt();
            jdbc.update("""
                UPDATE amazon_variation_classifications SET classification=?,reason_code=?,reciprocal_complete=?,members_complete=?,
                    verified_role=?,verified_revision=?,verified_at=?,valid_until=?
                WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?
                """,(identityConflict&&entry.getKey().equals(sku))?"CONFLICT":r.state().name(),
                !matching&&entry.getKey().equals(sku)?(identityConflict?"SOURCE_SKU_MISMATCH":"MALFORMED_RESPONSE"):r.reason(),verified,verified,verified?r.state().name():lastRole,
                verified?Long.valueOf(r.evidenceRevision()):lastRevision,time(verified?observed:lastVerified),time(verified?r.validUntil():lastExpiry),
                scope.tenant(),scope.connection(),scope.marketplace(),entry.getKey());
        }
        for(var o:before.observations().values())if("PARENT".equals(o.role())&&!o.sku().equals(sku))referenced.add(o.sku());
        return Collections.unmodifiableSet(new LinkedHashSet<>(referenced));
    }
    private boolean hasDatasets(JsonNode root,String market){
        if(!root.path("attributes").isObject()||!root.path("summaries").isArray()||!root.path("relationships").isArray())return false;
        int summaries=0,relationships=0;
        for(JsonNode group:root.path("summaries"))if(market.equals(group.path("marketplaceId").asText())&&group.path("status").isArray())summaries++;
        for(JsonNode group:root.path("relationships"))if(market.equals(group.path("marketplaceId").asText())&&group.path("relationships").isArray())relationships++;
        return summaries==1&&relationships==1;
    }
    private void collect(JsonNode array,Set<String> values){if(array.isArray())for(JsonNode v:array)if(v.isString()&&!v.asText().isEmpty()&&v.asText().codePointCount(0,v.asText().length())<=240)values.add(v.asText());}
    private boolean hasStub(Scope s,String sku){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_variation_classifications WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?)",Boolean.class,key(s,sku)));}
    private void claim(Scope s,String sku,String related,String direction,long revision,UUID source,Instant observed){jdbc.update("INSERT INTO amazon_variation_claims(tenant_id,marketplace_connection_id,marketplace_id,subject_sku,related_sku,direction,subject_revision,source_document_id,observed_at) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",s.tenant(),s.connection(),s.marketplace(),sku,related,direction,revision,source,time(observed));}
    /** Preserve an oversized response without importing out-of-envelope family members. */
    public void rejectFamily(AmazonSyncStore.Job job,String sku,String body,String requestId,Instant observed){
        Scope s=new Scope(job.tenantId(),job.connectionId(),job.marketplaceId());lock(s);
        JsonNode root=null;try{root=json.readTree(body);}catch(RuntimeException ignored){}
        UUID source=archive.saveVariationEvidence(job,sku,body,root==null?null:root.toString(),requestId);
        Graph graph=repository.load(s,List.of(sku));stub(s,sku);
        jdbc.update("UPDATE amazon_variation_classifications SET source_document_id=?,observed_at=?,evidence_revision=evidence_revision+1,observed_role=NULL,observed_asin=NULL,own_complete=false,classification='UNKNOWN',verified_role=NULL,verified_revision=NULL,verified_at=NULL,valid_until=NULL,reciprocal_complete=false,members_complete=false,fetch_status='SUCCESS',last_http_status=200,next_attempt_at=NULL WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",source,time(observed),s.tenant(),s.connection(),s.marketplace(),sku);
        Set<String> affected=new HashSet<>(graph.observations().keySet());affected.add(sku);
        for(String member:affected)jdbc.update("UPDATE amazon_variation_classifications SET classification='UNKNOWN',reason_code='FAMILY_LIMIT',reciprocal_complete=false,members_complete=false,last_attempt_at=?,updated_at=? WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",time(observed),time(observed),s.tenant(),s.connection(),s.marketplace(),member);
    }
    public void archiveFailure(AmazonSyncStore.Job job,String sku,AmazonSpApiClient.VariationReadException failure){
        if(failure.rawBody()==null)return;
        String parsed=null;try{var node=json.readTree(failure.rawBody());parsed=node==null?null:node.toString();}catch(RuntimeException ignored){}
        archive.saveVariationEvidence(job,sku,failure.rawBody(),parsed,failure.requestId());
    }
    public void failure(AmazonSyncStore.Job job,String sku,int status,Instant at){
        Scope s=new Scope(job.tenantId(),job.connectionId(),job.marketplaceId());lock(s);stub(s,sku);
        if(status==404){
            Graph graph=repository.load(s,List.of(sku));for(String member:graph.observations().keySet())jdbc.update("UPDATE amazon_variation_classifications SET classification='UNKNOWN',reason_code='MEMBER_NOT_FOUND',reciprocal_complete=false,members_complete=false WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",key(s,member));
            jdbc.update("UPDATE amazon_variation_classifications SET own_complete=false WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",key(s,sku));
        }
        jdbc.update("UPDATE amazon_variation_classifications SET last_attempt_at=?,last_http_status=?,last_error_code=?,fetch_status=?,failure_count=failure_count+1 WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=?",time(at),status==0?null:status,"HTTP_"+status,status==401||status==403?"PAUSED":"ERROR",s.tenant(),s.connection(),s.marketplace(),sku);
    }
}
