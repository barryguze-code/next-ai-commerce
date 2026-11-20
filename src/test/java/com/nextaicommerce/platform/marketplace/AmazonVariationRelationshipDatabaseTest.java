package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProviderTest.*;
import java.util.*;
import java.time.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.flywaydb.core.Flyway;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipRepository.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.*;

/** Only the existing local test DB; never starts a service or connects to local UAT. */
@EnabledIfSystemProperty(named="localTestDatabase",matches="true")
class AmazonVariationRelationshipDatabaseTest {
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema,baselineInventory,baselineSource;
    UUID tenant,other,store,second;AmazonVariationRelationshipProvider provider;
    @BeforeAll static void migrate()throws Exception{
        schema="variation_a1_"+UUID.randomUUID().toString().replace("-","");
        String url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test?currentSchema="+schema;
        var data=new DriverManagerDataSource(url,"postgres",Files.readString(Path.of(".local/database-password")).trim());
        Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("109").load().migrate();
        jdbc=new JdbcTemplate(data);tx=new TransactionTemplate(new DataSourceTransactionManager(data));
        int initialJobs=jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs",Integer.class);
        UUID t=UUID.randomUUID(),c=UUID.randomUUID(),source=UUID.randomUUID();tenant(t);connection(t,c,"US");
        jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,sha256,text_payload) VALUES (?,?,?,'INVENTORY_SNAPSHOT',?,'untouched original report')",source,t,c,"a".repeat(64));
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,inbound_shipped_quantity,snapshot_at,source_document_id) VALUES (?,?,'US','HISTORY','ASIN1','FNSKU1',23,160,?,?)",t,c,java.sql.Timestamp.from(NOW),source);
        baselineInventory=inventory();baselineSource=source(source);
        Flyway.configure().dataSource(data).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("110").load().migrate();
        assertThat(inventory()).isEqualTo(baselineInventory);assertThat(source(source)).isEqualTo(baselineSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs",Integer.class)).isEqualTo(initialJobs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_classifications",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_claims",Integer.class)).isZero();
    }
    @AfterAll static void cleanup(){if(jdbc!=null&&schema.matches("variation_a1_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    @BeforeEach void fixture(){tenant=UUID.randomUUID();other=UUID.randomUUID();store=UUID.randomUUID();second=UUID.randomUUID();tenant(tenant);tenant(other);connection(tenant,store,"US");connection(tenant,second,"US");
        provider=new AmazonVariationRelationshipProvider(new AmazonVariationRelationshipRepository(jdbc,JSON),Clock.fixed(NOW,ZoneOffset.UTC));}
    static void tenant(UUID t){jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Variation A1 test')",t,t.toString());}
    static void connection(UUID t,UUID c,String market){jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,?,'test','ACTIVE','A1 test','UTC',now())",c,t,c.toString(),market);}
    static String inventory(){return jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY id),'[]')::text FROM amazon_inventory_snapshots s",String.class);}
    static String source(UUID id){return jdbc.queryForObject("SELECT to_jsonb(d)::text FROM amazon_source_documents d WHERE id=?",String.class,id);}
    static byte[] digest(String value){try{return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    void save(UUID t,UUID c,Observation o){
        String digest=java.util.HexFormat.of().formatHex(digest(o.payload().toString()));
        jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,sha256,payload) VALUES (?,?,?,'VARIATION_RELATIONSHIPS',?,CAST(? AS jsonb))",o.source(),t,c,digest,o.payload().toString());
        jdbc.update("""
            INSERT INTO amazon_variation_classifications(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,
                observed_asin,observed_role,own_complete,evidence_revision,verified_revision,verified_role,observed_at,
                verified_at,valid_until,source_document_id,classification,reciprocal_complete,members_complete)
            VALUES (?,?,'US',?,?,?,true,?,?,?,?,?,?,?, ?,true,true)
            """,t,c,o.sku(),o.asin(),o.role(),o.revision(),o.verifiedRevision(),o.verifiedRole(),java.sql.Timestamp.from(o.observedAt()),
                java.sql.Timestamp.from(o.verifiedAt()),java.sql.Timestamp.from(o.expiresAt()),o.source(),o.classification());
    }
    void edge(UUID t,UUID c,Claim claim){jdbc.update("INSERT INTO amazon_variation_claims(tenant_id,marketplace_connection_id,marketplace_id,subject_sku,related_sku,direction,subject_revision,source_document_id,observed_at) VALUES (?,?,'US',?,?,?,?,?,?)",t,c,claim.subject(),claim.related(),claim.direction(),claim.revision(),claim.source(),java.sql.Timestamp.from(claim.observedAt()));}
    Map<String,Classification> read(UUID t,UUID c,String market,String... skus){return tx.execute(s->provider.classify(new Scope(t,c,market),List.of(skus)));}
    @Test void emptyAndExactIdentityReads(){
        assertThat(read(tenant,store,"US","0001").get("0001").state()).isEqualTo(State.UNKNOWN);
        save(tenant,store,observation("0001","STANDALONE",List.of(),List.of()));
        assertThat(read(tenant,store,"US","0001").get("0001").state()).isEqualTo(State.STANDALONE);
        for(String sku:List.of("1","0001 ","SKU","sku"))assertThat(read(tenant,store,"US",sku).get(sku).state()).isEqualTo(State.UNKNOWN);
        save(tenant,store,observation("Sku","STANDALONE",List.of(),List.of()));
        assertThat(read(tenant,store,"US","sku").get("sku").state()).isEqualTo(State.UNKNOWN);
    }
    @Test void reciprocalClaimsAndHistoricalInventoryArePreserved(){
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","CHILD",List.of("P"),List.of());save(tenant,store,p);save(tenant,store,c);
        edge(tenant,store,claim(p,"C","PARENT_OF"));edge(tenant,store,claim(c,"P","CHILD_OF"));
        var before=inventory();var report=source(p.source());var result=read(tenant,store,"US","P","C");
        assertThat(result.get("P").state()).isEqualTo(State.PARENT);assertThat(result.get("C").state()).isEqualTo(State.CHILD);
        assertThat(inventory()).isEqualTo(before).isEqualTo(baselineInventory);assertThat(source(p.source())).isEqualTo(report);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_claims WHERE marketplace_connection_id=?",Integer.class,store)).isEqualTo(2);
    }
    @Test void unknownCounterpartRevisionAndExpiryNeverVerify(){
        var p=observation("P","PARENT",List.of(),List.of("C"));save(tenant,store,p);
        jdbc.update("INSERT INTO amazon_variation_classifications(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,'US','C')",tenant,store);
        edge(tenant,store,claim(p,"C","PARENT_OF"));assertThat(read(tenant,store,"US","P").get("P").state()).isEqualTo(State.UNKNOWN);
        var s=observation("S","STANDALONE",List.of(),List.of());save(tenant,store,s);
        jdbc.update("UPDATE amazon_variation_classifications SET evidence_revision=2 WHERE marketplace_connection_id=? AND seller_sku='S'",store);
        assertThat(read(tenant,store,"US","S").get("S").reason()).isEqualTo("REVISION_MISMATCH");
        jdbc.update("UPDATE amazon_variation_classifications SET evidence_revision=1,valid_until=? WHERE marketplace_connection_id=? AND seller_sku='S'",java.sql.Timestamp.from(NOW),store);
        assertThat(read(tenant,store,"US","S").get("S").quality()).isEqualTo(Quality.EXPIRED);
    }
    @Test void connectionMarketplaceAndTenantScopesCannotBorrowEvidence(){
        save(tenant,store,observation("S","STANDALONE",List.of(),List.of()));
        assertThat(read(other,store,"US","S").get("S").reason()).isEqualTo("INVALID_SCOPE");
        assertThat(read(tenant,second,"US","S").get("S").state()).isEqualTo(State.UNKNOWN);
        assertThat(read(tenant,store,"CA","S").get("S").reason()).isEqualTo("INVALID_SCOPE");
        assertThatThrownBy(()->jdbc.update("INSERT INTO amazon_variation_classifications(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,'US','X')",other,store)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO amazon_variation_classifications(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,'CA','X')",tenant,store)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void claimsCannotCrossConnectionsOrUseForeignSources(){
        var a=observation("A","STANDALONE",List.of(),List.of());var b=observation("B","STANDALONE",List.of(),List.of());save(tenant,store,a);save(tenant,second,b);
        assertThatThrownBy(()->edge(tenant,store,claim(a,"B","PARENT_OF"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE amazon_variation_classifications SET source_document_id=? WHERE marketplace_connection_id=?",b.source(),store)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void rlsDeniesUnscopedAndForeignTenantReadsAndWrites(){
        save(tenant,store,observation("S","STANDALONE",List.of(),List.of()));
        var p=observation("P","PARENT",List.of(),List.of("C"));var c=observation("C","CHILD",List.of("P"),List.of());
        save(tenant,store,p);save(tenant,store,c);edge(tenant,store,claim(p,"C","PARENT_OF"));edge(tenant,store,claim(c,"P","CHILD_OF"));
        String role="variation_reader_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try{
            jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
            jdbc.execute("GRANT SELECT,INSERT ON amazon_variation_classifications,amazon_variation_claims TO "+role);
            tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id','',true)",String.class);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_classifications",Integer.class)).isZero();
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_classifications",Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_claims",Integer.class)).isEqualTo(2);
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_classifications",Integer.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_variation_claims",Integer.class)).isZero();
            });
            assertThatThrownBy(()->tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
                jdbc.update("INSERT INTO amazon_variation_classifications(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) VALUES (?,?,'US','DENIED')",tenant,store);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(()->tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
                edge(tenant,store,claim(p,"C","PARENT_OF"));
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }finally{
            jdbc.execute("REVOKE ALL ON amazon_variation_classifications,amazon_variation_claims FROM "+role);jdbc.execute("REVOKE USAGE ON SCHEMA "+schema+" FROM "+role);jdbc.execute("DROP ROLE "+role);
        }
    }
    @Test void sharedAsinAndFnskuDoNotMergeOrReclassifyStock(){
        save(tenant,store,observation("A","STANDALONE",List.of(),List.of()));save(tenant,store,observation("B","STANDALONE",List.of(),List.of()));
        tx.executeWithoutResult(status->{
            for(String sku:List.of("A","B"))jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,fnsku,fulfillable_quantity,snapshot_at) VALUES (?,?,'US',?,'ASIN1','SAME-FNSKU',7,?)",tenant,store,sku,java.sql.Timestamp.from(NOW));
            String before=inventory();var result=provider.classify(new Scope(tenant,store,"US"),List.of("A","B"));
            assertThat(result.values()).allSatisfy(v->assertThat(v.state()).isEqualTo(State.STANDALONE));
            assertThat(jdbc.queryForObject("SELECT sum(fulfillable_quantity) FROM amazon_inventory_snapshots WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(14);
            assertThat(inventory()).isEqualTo(before);status.setRollbackOnly();
        });
    }

    @Test void verificationMetadataAndSelfClaimsAreConstrained(){
        var a=observation("A","STANDALONE",List.of(),List.of());save(tenant,store,a);
        assertThatThrownBy(()->jdbc.update("UPDATE amazon_variation_classifications SET verified_revision=NULL WHERE marketplace_connection_id=?",store)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->edge(tenant,store,claim(a,"A","PARENT_OF"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE amazon_variation_classifications SET classification='INFERRED_PARENT' WHERE marketplace_connection_id=?",store)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

}
