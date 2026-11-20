package com.nextaicommerce.platform.sync;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FbaSelectedItemsTest {
    static EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    UUID tenant, store;
    FbaManualWorker worker;
    FbaManualSync sync;

    @BeforeAll static void database() throws Exception {
        postgres = EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
        var source = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterAll static void close() throws Exception { if (postgres != null) postgres.close(); }
    @BeforeEach void store() {
        tenant = UUID.randomUUID();
        store = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Item read')",tenant,tenant.toString());
        jdbc.update("""
            INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,
              credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at)
            VALUES (?,?,'AMAZON','test','US','test','ACTIVE','Test','America/Los_Angeles',now())
            """,store,tenant);
        jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",
            tenant,store,new byte[]{1},new byte[]{2});
        for(String id : List.of("A","B")) jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id) VALUES (?,?,?)",tenant,store,id);
        worker = mock(FbaManualWorker.class);
        sync = new FbaManualSync(jdbc,tx,worker,true);
    }
    @Test void headerRefreshPreservesCompletedItemEvidenceWithoutFees() {
        var json=new tools.jackson.databind.ObjectMapper();
        var inbound=new FbaInboundSync(mock(AmazonSpApiClient.class),new AmazonSyncStore(jdbc,tx),jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null));
        var job=new AmazonSyncStore.Job(UUID.randomUUID(),tenant,UUID.randomUUID(),store,"FBA_INBOUND","FBA_MANUAL","US",java.time.Instant.now(),java.time.Instant.now(),null,0,0,false,null);
        jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=?::jsonb WHERE tenant_id=? AND shipment_id='A'",
            "{\"itemEvidenceVersion\":1,\"itemEvidence\":[{\"SellerSKU\":\"0001\",\"QuantityShipped\":3,\"QuantityReceived\":0}],\"itemsCheckedAt\":\"2026-11-12T00:00:00Z\",\"itemEvidenceJob\":\"original\"}",tenant);
        var rows=json.createArrayNode();rows.addObject().put("ShipmentId","A").put("ShipmentStatus","CLOSED");
        inbound.saveShipments(job,rows);
        String payload=jdbc.queryForObject("SELECT raw_payload::text FROM amazon_fba_shipments WHERE tenant_id=? AND shipment_id='A'",String.class,tenant);
        assertThat(payload).contains("itemEvidenceVersion","0001","itemsCheckedAt","original","CLOSED").doesNotContain("feesCheckedAt");
        jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=raw_payload-'itemEvidence'-'itemEvidenceVersion' WHERE tenant_id=? AND shipment_id='A'",tenant);
        inbound.saveShipments(job,rows);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(raw_payload,'itemEvidenceVersion') FROM amazon_fba_shipments WHERE tenant_id=? AND shipment_id='A'",Boolean.class,tenant)).isFalse();
    }
    @Test void selectionCreatesOnlyOneSortedItemJobAndDoesNotJoinOrReplaceIt() {
        UUID job=sync.requestItems(tenant,store,List.of("B","A","B"));
        assertThat(jdbc.queryForObject("""
            SELECT j.cursor @> '{"pending":["A","B"],"itemsRefreshOnly":true,"feesComplete":true,"next":"","pages":0}'::jsonb
              AND j.cursor->'pending'='["A","B"]'::jsonb AND j.job_type='FBA_INBOUND' AND r.sync_profile='FBA_MANUAL'
            FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.id=j.sync_run_id WHERE j.id=?
            """,Boolean.class,job)).isTrue();
        assertThat(sync.state(tenant,store).message()).isEqualTo("Refreshing selected shipment items…");
        assertThatThrownBy(()->sync.requestItems(tenant,store,List.of("A"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sync.request(tenant,store)).isInstanceOf(IllegalStateException.class);
        verify(worker).start(tenant,store,job);
        verify(worker).running(job);
        verifyNoMoreInteractions(worker);
    }
    @Test void targetedRefreshPublishesMembershipOnlyAfterLastPageAndSkipsListingAndFinanceReads() {
        UUID id=sync.requestItems(tenant,store,List.of("B"));
        var api=mock(AmazonSpApiClient.class);
        var json=new tools.jackson.databind.ObjectMapper();
        String first="/fba/inbound/v0/shipments/B/items";
        String next="/fba/inbound/v0/shipmentItems?MarketplaceId=US&QueryType=NEXT_TOKEN&NextToken=next";
        for (String path : List.of(first,next)) {
            var payload=json.createObjectNode();
            var body=payload.putObject("payload");
            var item=body.putArray("ItemData").addObject();
            item.put("ShipmentId","B").put("SellerSKU",path.equals(first)?"0001":"0002")
                .put("QuantityShipped",2).put("QuantityReceived",1);
            if(path.equals(first)) body.put("NextToken","next");
            when(api.get(tenant,store,path)).thenReturn(new AmazonSpApiClient.ApiResponse(200,"request",payload,payload.toString(),1.0));
        }
        var queue=new AmazonSyncStore(jdbc,tx);
        var inbound=new FbaInboundSync(api,queue,jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null));
        var claims=new FbaManualWorker(jdbc,tx,inbound,queue);
        inbound.processPage(claims.claim(tenant,store,id));
        assertThat(jdbc.queryForObject("SELECT raw_payload->>'itemEvidenceVersion' FROM amazon_fba_shipments WHERE tenant_id=? AND shipment_id='B'",String.class,tenant)).isNull();
        jdbc.update("UPDATE marketplace_sync_jobs SET available_at=now() WHERE id=?",id);
        inbound.processPage(claims.claim(tenant,store,id));
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(raw_payload->'itemEvidence') FROM amazon_fba_shipments WHERE tenant_id=? AND shipment_id='B'",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        verify(api).get(tenant,store,first);
        verify(api).get(tenant,store,next);
        verifyNoMoreInteractions(api);
    }
    @Test void rejectsUnknownOrCrossAccountSelectionWithoutQueueingAnyPart() {
        assertThatThrownBy(()->sync.requestItems(tenant,store,List.of("A","b"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->sync.requestItems(UUID.randomUUID(),store,List.of("A"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_runs WHERE tenant_id=?",Integer.class,tenant)).isZero();
        verifyNoInteractions(worker);
    }
    @Test void disabledAndOversizedSelectionsNeverReachWorker() {
        assertThatThrownBy(()->new FbaManualSync(jdbc,tx,worker,false).requestItems(tenant,store,List.of("A"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sync.requestItems(tenant,store,java.util.Collections.nCopies(26,"A"))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(worker);
    }
}
