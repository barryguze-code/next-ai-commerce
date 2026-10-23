package com.nextaicommerce.platform.marketplace;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local, read-only evidence graph. No acquisition, writes, or inventory consumers in A1. */
@Repository
public class AmazonVariationRelationshipRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public AmazonVariationRelationshipRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public record Observation(String sku,String asin,String listingAsin,String role,String classification,
        boolean complete,boolean reciprocal,boolean members,long revision,Long verifiedRevision,
        String verifiedRole,Instant observedAt,Instant verifiedAt,Instant expiresAt,UUID source,
        int parserVersion,String sourceType,JsonNode payload,String reasonCode) {
        public Observation(String sku,String asin,String listingAsin,String role,String classification,
            boolean complete,boolean reciprocal,boolean members,long revision,Long verifiedRevision,
            String verifiedRole,Instant observedAt,Instant verifiedAt,Instant expiresAt,UUID source,
            int parserVersion,String sourceType,JsonNode payload){this(sku,asin,listingAsin,role,classification,complete,
                reciprocal,members,revision,verifiedRevision,verifiedRole,observedAt,verifiedAt,expiresAt,source,
                parserVersion,sourceType,payload,null);}
    }
    public record Claim(String subject,String related,String direction,long revision,UUID source,Instant observedAt) {}
    public record Graph(Map<String,Observation> observations,List<Claim> claims,boolean validScope,boolean limited) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Graph load(AmazonVariationRelationshipProvider.Scope scope,Collection<String> requested){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,scope.tenant().toString());
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM marketplace_connections WHERE tenant_id=? AND id=?
                AND marketplace_identifier=? AND channel='AMAZON' AND status='ACTIVE')
            """,Boolean.class,scope.tenant(),scope.connection(),scope.marketplace()));
        if(!valid)return new Graph(Map.of(),List.of(),false,false);
        Map<String,Observation> observations=new LinkedHashMap<>();Set<String> visited=new HashSet<>();
        Map<String,Claim> claims=new LinkedHashMap<>();Set<String> frontier=new LinkedHashSet<>(requested);
        for(int depth=0;!frontier.isEmpty();depth++){
            if(depth>=16||visited.size()+frontier.size()>1000)return new Graph(observations,List.copyOf(claims.values()),true,true);
            List<Object> args=new ArrayList<>(List.of(scope.tenant(),scope.connection(),scope.marketplace()));args.addAll(frontier);
            String marks=String.join(",",Collections.nCopies(frontier.size(),"?"));
            jdbc.query("""
                SELECT c.*,l.asin listing_asin,d.source_type,d.payload
                FROM amazon_variation_classifications c
                LEFT JOIN amazon_listings l ON l.tenant_id=c.tenant_id
                    AND l.marketplace_connection_id=c.marketplace_connection_id
                    AND l.marketplace_id=c.marketplace_id AND l.seller_sku COLLATE "C"=c.seller_sku
                LEFT JOIN amazon_source_documents d ON d.tenant_id=c.tenant_id
                    AND d.marketplace_connection_id=c.marketplace_connection_id AND d.id=c.source_document_id
                WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.marketplace_id=? AND c.seller_sku IN (
                """+marks+")",rs->{
                String sku=rs.getString("seller_sku");String payload=rs.getString("payload");
                observations.put(sku,new Observation(sku,rs.getString("observed_asin"),rs.getString("listing_asin"),
                    rs.getString("observed_role"),rs.getString("classification"),rs.getBoolean("own_complete"),
                    rs.getBoolean("reciprocal_complete"),rs.getBoolean("members_complete"),rs.getLong("evidence_revision"),
                    (Long)rs.getObject("verified_revision"),rs.getString("verified_role"),instant(rs,"observed_at"),
                    instant(rs,"verified_at"),instant(rs,"valid_until"),rs.getObject("source_document_id",UUID.class),
                    rs.getInt("parser_version"),rs.getString("source_type"),payload==null?null:json.readTree(payload),rs.getString("reason_code")));
            },args.toArray());
            args.addAll(frontier);
            var found=jdbc.query("""
                SELECT * FROM amazon_variation_claims WHERE tenant_id=? AND marketplace_connection_id=?
                    AND marketplace_id=? AND (subject_sku IN (
                """+marks+") OR related_sku IN ("+marks+")) LIMIT 10001",(rs,n)->new Claim(
                    rs.getString("subject_sku"),rs.getString("related_sku"),rs.getString("direction"),
                    rs.getLong("subject_revision"),rs.getObject("source_document_id",UUID.class),instant(rs,"observed_at")),args.toArray());
            if(found.size()>10000)return new Graph(observations,List.copyOf(claims.values()),true,true);
            visited.addAll(frontier);frontier=new LinkedHashSet<>();
            for(Claim claim:found){claims.put(claim.subject()+"\u0000"+claim.related()+"\u0000"+claim.direction(),claim);
                if(!visited.contains(claim.subject()))frontier.add(claim.subject());
                if(!visited.contains(claim.related()))frontier.add(claim.related());}
            if(claims.size()>10000)return new Graph(observations,List.copyOf(claims.values()),true,true);
        }
        return new Graph(Map.copyOf(observations),List.copyOf(claims.values()),true,false);
    }
    private static Instant instant(java.sql.ResultSet rs,String name)throws java.sql.SQLException{
        var value=rs.getTimestamp(name);return value==null?null:value.toInstant();
    }
}
