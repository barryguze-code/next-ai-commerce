package com.nextaicommerce.platform.fba;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.sync.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FbaReadDatabaseTest {
    private record DepletionFixture(FbaDepletionRepository operations,UUID item,UUID warehouse,UUID location,UUID layer,String email){}
    private DepletionFixture depletionFixture(){
        UUID user=UUID.randomUUID(),item=UUID.randomUUID(),product=UUID.randomUUID(),mapping=UUID.randomUUID(),layer=UUID.randomUUID();String email=user+"@fixture.test";
        jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Operator')",user,email);
        jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Fixture product')",product);
        jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,display_name) VALUES (?,?,?,'Fixture product')",item,tenant,product);
        UUID warehouse=jdbc.queryForObject("SELECT id FROM warehouses WHERE tenant_id=? AND is_default",UUID.class,tenant);
        jdbc.update("UPDATE warehouses SET postal_code='00123',country_code='US' WHERE id=?",warehouse);
        UUID location=jdbc.queryForObject("SELECT id FROM warehouse_locations WHERE tenant_id=? AND warehouse_id=? AND code='MAIN'",UUID.class,tenant,warehouse);
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_status,raw_payload) VALUES (?,?,'FBA-TEST','SHIPPED',?::jsonb)",tenant,store,"{\"ShipFromAddress\":{\"PostalCode\":\"00123\",\"CountryCode\":\"US\"},\"itemEvidenceVersion\":1,\"itemEvidence\":[{\"SellerSKU\":\"000-SKU\",\"QuantityShipped\":2,\"QuantityReceived\":1}]}");
        jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received) VALUES (?,?,'FBA-TEST','000-SKU',2,1)",tenant,store);
        jdbc.update("INSERT INTO marketplace_sku_mappings(id,tenant_id,marketplace_connection_id,marketplace_sku,account_catalog_item_id) VALUES (?,?,?,'000-SKU',?)",mapping,tenant,store,item);
        jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) VALUES (?,?,?,3)",tenant,mapping,item);
        jdbc.update("INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,entry_type,quantity,location_id,expiration_date,unit_cost,currency,source_type,occurred_at,idempotency_key) VALUES (?,?,?,'RECEIVED',10,?,current_date+30,2,'USD','FIXTURE',now()-interval '1 day',?)",layer,tenant,item,location,layer.toString());
        return new DepletionFixture(new FbaDepletionRepository(jdbc,new tools.jackson.databind.ObjectMapper()),item,warehouse,location,layer,email);
    }
    @Test void depletionIsAtomicIdempotentAndPreservesAmazonAndHistoricalReceipts(){tx.executeWithoutResult(s->{
        var f=depletionFixture();var p=f.operations.preview(tenant,store,"FBA-TEST");assertThat(p.blockers()).isEmpty();assertThat(p.allocations()).hasSize(1);assertThat(p.allocations().getFirst().layer()).isEqualTo(f.layer);assertThat(p.allocations().getFirst().quantity()).isEqualByComparingTo("6");
        UUID request=UUID.randomUUID();UUID operation=f.operations.deplete(tenant,store,"FBA-TEST",f.email,request,p.revision(),p.allocations());
        assertThat(f.operations.deplete(tenant,store,"FBA-TEST",f.email,request,p.revision(),p.allocations())).isEqualTo(operation);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",java.math.BigDecimal.class,tenant,f.item)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_ledger_entries WHERE id=?",java.math.BigDecimal.class,f.layer)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT quantity_shipped FROM amazon_fba_shipment_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").depleted()).isTrue();
        assertThatThrownBy(()->f.operations.deplete(tenant,store,"FBA-TEST",f.email,request,p.revision(),java.util.List.of(new FbaDepletionRepository.Allocation(f.layer,new java.math.BigDecimal("1"))))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different allocations");
        assertThatThrownBy(()->f.operations.preview(other,store,"FBA-TEST")).isInstanceOf(IllegalArgumentException.class);
    });}
    @Test void depletionBlocksStaleMembershipAndInactiveBundleComponents(){tx.executeWithoutResult(s->{
        var f=depletionFixture();jdbc.update("UPDATE account_catalog_items SET status='INACTIVE' WHERE id=?",f.item);
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").blockers()).anyMatch(v->v.contains("Complete the mapping"));
        jdbc.update("UPDATE account_catalog_items SET status='ACTIVE' WHERE id=?",f.item);
        jdbc.update("UPDATE amazon_fba_shipment_items SET quantity_shipped=3 WHERE tenant_id=?",tenant);
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").blockers()).anyMatch(v->v.contains("incomplete"));
    });}
    private FbaBatchDeduction batch(DepletionFixture f){var access=mock(com.nextaicommerce.platform.web.MemberAccessService.class);when(access.current(tenant,f.email)).thenReturn(new com.nextaicommerce.platform.web.MemberAccessService.Access("OWNER",false,Set.of()));return new FbaBatchDeduction(f.operations,jdbc,access);}
    private void secondShipment(){jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_status,raw_payload) SELECT tenant_id,marketplace_connection_id,'FBA-SECOND',shipment_status,raw_payload FROM amazon_fba_shipments WHERE tenant_id=? AND shipment_id='FBA-TEST'",tenant);jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received) SELECT tenant_id,marketplace_connection_id,'FBA-SECOND',seller_sku,quantity_shipped,quantity_received FROM amazon_fba_shipment_items WHERE tenant_id=? AND shipment_id='FBA-TEST'",tenant);}
    @Test void batchPlansSharedStockOnceAndCommitsAllShipmentsIdempotently(){
        var f=tx.execute(st->depletionFixture());secondShipment();jdbc.update("UPDATE inventory_ledger_entries SET quantity=20 WHERE id=?",f.layer);var service=batch(f);var ids=List.of("FBA-TEST","FBA-SECOND");var review=tx.execute(st->service.preview(tenant,store,ids));assertThat(review.blockers()).isEmpty();var choices=review.plans().stream().map(p->new FbaBatchDeduction.Choice(p.shipment().shipment(),p.allocations())).toList();UUID request=UUID.randomUUID();
        var result=tx.execute(st->service.confirm(tenant,store,f.email,request,review.revision(),choices));assertThat(result).hasSize(2);var replay=tx.execute(st->service.confirm(tenant,store,f.email,request,review.revision(),choices));assertThat(replay).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("8");
        assertThatThrownBy(()->tx.execute(st->service.confirm(tenant,store,f.email,request,review.revision(),choices.subList(0,1)))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different selection");
        UUID secondStore=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','other-seller','US','fixture','ACTIVE','Second authorized store','America/Los_Angeles',now())",secondStore,tenant);
        assertThatThrownBy(()->tx.execute(st->service.confirm(tenant,secondStore,f.email,request,review.revision(),List.of(new FbaBatchDeduction.Choice("OTHER-SHIPMENT",choices.getFirst().allocations()))))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different selection");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT sum(quantity_shipped) FROM amazon_fba_shipment_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(4);
    }
    @Test void batchShortfallAndFailureOnSecondShipmentNeverPartiallyDeduct(){
        var f=tx.execute(st->depletionFixture());secondShipment();var service=batch(f);var ids=List.of("FBA-TEST","FBA-SECOND");var shortfall=tx.execute(st->service.preview(tenant,store,ids));assertThat(shortfall.blockers()).anyMatch(v->v.contains("complete selection"));
        jdbc.update("UPDATE inventory_ledger_entries SET quantity=20 WHERE id=?",f.layer);var p=tx.execute(st->service.preview(tenant,store,ids));var choices=new ArrayList<>(p.plans().stream().map(x->new FbaBatchDeduction.Choice(x.shipment().shipment(),x.allocations())).toList());choices.set(1,new FbaBatchDeduction.Choice(choices.get(1).shipment(),List.of(new FbaDepletionRepository.Allocation(f.layer,java.math.BigDecimal.ONE))));
        assertThatThrownBy(()->tx.execute(st->service.confirm(tenant,store,f.email,UUID.randomUUID(),p.revision(),choices))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isZero();assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("20");
        jdbc.update("UPDATE inventory_ledger_entries SET unit_cost=3 WHERE id=?",f.layer);
        assertThatThrownBy(()->tx.execute(st->service.confirm(tenant,store,f.email,UUID.randomUUID(),p.revision(),p.plans().stream().map(x->new FbaBatchDeduction.Choice(x.shipment().shipment(),x.allocations())).toList()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
    }
    @Test void legacyCompletedShipmentStillShowsItsImportedLinesForManualReview(){tx.executeWithoutResult(st->{
        var f=depletionFixture();jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=(raw_payload-'itemEvidence'-'itemEvidenceVersion')||jsonb_build_object('itemsCheckedAt',now()::text) WHERE tenant_id=?",tenant);
        assertThat(repository.items(tenant,store,"FBA-TEST","",0).rows()).hasSize(1);
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").lines()).hasSize(1);
    });}
    @Test void depletionCorrectionsAreSeparateAndStaleApprovalIsRejected(){tx.executeWithoutResult(s->{
        var f=depletionFixture();var before=f.operations.preview(tenant,store,"FBA-TEST");f.operations.correct(tenant,store,"FBA-TEST",f.email,"000-SKU",1,"Actual packed units",before.revision());
        var after=f.operations.preview(tenant,store,"FBA-TEST");assertThat(after.lines().getFirst().unitsToDeduct()).isEqualTo(1);assertThat(after.allocations().getFirst().quantity()).isEqualByComparingTo("3");
        assertThat(after.lines().getFirst().reason()).isEqualTo("Actual packed units");assertThat(after.revision()).isNotEqualTo(before.revision());
        assertThatThrownBy(()->f.operations.deplete(tenant,store,"FBA-TEST",f.email,UUID.randomUUID(),before.revision(),before.allocations())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
        assertThat(jdbc.queryForObject("SELECT quantity_shipped FROM amazon_fba_shipment_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
    });}
    @Test void depletionNeverUsesReservedPositionAndRejectsDuplicateWarehouseZip(){tx.executeWithoutResult(s->{
        var f=depletionFixture();UUID order=UUID.randomUUID();
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status) VALUES (?,?,'US','RESERVED',now(),'Unshipped')",tenant,store);
        jdbc.update("INSERT INTO amazon_order_items(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,?,'RESERVED','LINE','000-SKU',1)",order,tenant,store);
        jdbc.update("INSERT INTO order_inventory_reservations(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,account_catalog_item_id,expiration_date,quantity,location_id,cost_layer_id) VALUES (?,?,'RESERVED',?,?,current_date+30,1,?,?)",tenant,store,order,f.item,f.location,f.layer);
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").stock()).isEmpty();
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").blockers()).anyMatch(v->v.contains("Insufficient"));
        jdbc.update("INSERT INTO warehouses(tenant_id,name,postal_code,country_code) VALUES (?,'Duplicate ZIP','00123','US')",tenant);
        assertThat(f.operations.preview(tenant,store,"FBA-TEST").warehouse()).isNull();
        assertThat(jdbc.queryForObject("SELECT quantity FROM order_inventory_reservations WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("1");
    });}
    @Test void removalIsPersonalAndStockIsUnchanged(){tx.executeWithoutResult(s->{
        var f=depletionFixture();f.operations.removed(tenant,store,"FBA-TEST",f.email,true);
        assertThat(repository.shipments(tenant,store,"",0,25,f.email,"",null,false).total()).isZero();
        assertThat(repository.shipments(tenant,store,"",0,25,"someone@fixture.test","",null,false).total()).isEqualTo(1);
        assertThat(repository.shipments(tenant,store,"",0,25,f.email,"",null,true).total()).isEqualTo(1);
        f.operations.removed(tenant,store,"FBA-TEST",f.email,false);
        assertThat(repository.shipments(tenant,store,"",0,25,f.email,"",null,false).total()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_ledger_entries WHERE id=?",java.math.BigDecimal.class,f.layer)).isEqualByComparingTo("10");
    });}
    @Test void concurrentSourceRefreshRejectsAnOldQuantityCorrection() throws Exception {
        var f=tx.execute(st->depletionFixture());var before=tx.execute(st->f.operations.preview(tenant,store,"FBA-TEST"));
        var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var writer=threads.submit(()->tx.executeWithoutResult(st->{
                jdbc.queryForList("SELECT id FROM amazon_fba_shipments WHERE tenant_id=? AND shipment_id='FBA-TEST' FOR UPDATE",tenant);locked.countDown();
                try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Fixture timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
                jdbc.update("UPDATE amazon_fba_shipment_items SET quantity_shipped=3 WHERE tenant_id=?",tenant);
                jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=jsonb_set(raw_payload,'{itemEvidence,0,QuantityShipped}','3') WHERE tenant_id=?",tenant);
            }));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var correction=threads.submit(()->tx.executeWithoutResult(st->f.operations.correct(tenant,store,"FBA-TEST",f.email,"000-SKU",1,"Packed correction",before.revision())));
            release.countDown();writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(()->correction.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletion_overrides WHERE tenant_id=?",Integer.class,tenant)).isZero();
            assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_ledger_entries WHERE id=?",java.math.BigDecimal.class,f.layer)).isEqualByComparingTo("10");
        }finally{release.countDown();}
    }
    private record AutomaticFixture(DepletionFixture fixture,FbaAutomaticDepletion automatic,UUID importJob){}
    private AutomaticFixture automaticFixture(){
        var f=tx.execute(st->depletionFixture());var members=mock(com.nextaicommerce.platform.web.MemberAccessService.class);
        when(members.current(eq(tenant),eq(f.email))).thenReturn(new com.nextaicommerce.platform.web.MemberAccessService.Access("OWNER",false,Set.of()));
        var engine=new FbaAutomaticDepletion(jdbc,tx,f.operations,members,true);engine.configure(tenant,store,f.warehouse,f.email,true,0);
        UUID run=UUID.randomUUID(),job=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES (?,?,?,'RECONCILIATION','FBA_MANUAL','RUNNING',now(),now())",run,tenant,store);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,status) VALUES (?,?,?,?,'FBA_INBOUND',1,'COMPLETED')",job,tenant,store,run);
        jdbc.update("UPDATE fba_warehouse_depletion_settings SET enabled_after=now()-interval '1 hour' WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE amazon_fba_shipments SET created_at=now()-interval '5 minutes',raw_payload=raw_payload||jsonb_build_object('CreationDate',?::text,'itemEvidenceJob',?::text) WHERE tenant_id=?",java.time.Instant.now().minusSeconds(600).toString(),job.toString(),tenant);
        return new AutomaticFixture(f,engine,job);
    }
    @Test void automaticDepletionUsesFrozenPlanAndExistingLeasedQueue(){
        var a=automaticFixture();UUID job=a.automatic.enqueue(tenant,store,a.importJob);assertThat(job).isNotNull();assertThat(a.automatic.enqueue(tenant,store,a.importJob)).isEqualTo(job);
        assertThat(a.automatic.enqueue(other,store,a.importJob)).isNull();String owner=a.automatic.claim(tenant,store,job);assertThat(owner).isNotNull();assertThat(a.automatic.claim(tenant,store,job)).isNull();
        assertThat(a.automatic.processOne(tenant,store,job,owner)).isTrue();assertThat(a.automatic.processOne(tenant,store,job,owner)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM fba_shipment_depletion_attempts WHERE tenant_id=?",String.class,tenant)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_ledger_entries WHERE id=?",java.math.BigDecimal.class,a.fixture.layer)).isEqualByComparingTo("10");
    }
    @Test void automaticChangedStockRequiresReviewRatherThanReplanning(){
        var a=automaticFixture();UUID job=a.automatic.enqueue(tenant,store,a.importJob);String owner=a.automatic.claim(tenant,store,job);
        jdbc.update("UPDATE inventory_ledger_entries SET unit_cost=3 WHERE id=?",a.fixture.layer);
        a.automatic.processOne(tenant,store,job,owner);
        assertThat(jdbc.queryForObject("SELECT status FROM fba_shipment_depletion_attempts WHERE tenant_id=?",String.class,tenant)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }
    @Test void automaticCancellationAndExhaustedLeaseCannotWriteStock(){
        var a=automaticFixture();UUID job=a.automatic.enqueue(tenant,store,a.importJob);String owner=a.automatic.claim(tenant,store,job);
        jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",job);
        assertThatThrownBy(()->a.automatic.processOne(tenant,store,job,owner)).isInstanceOf(IllegalStateException.class);
        assertThat(a.automatic.claim(tenant,store,job)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM fba_shipment_depletion_attempts WHERE tenant_id=?",String.class,tenant)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_runs WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",String.class,job)).isEqualTo("CANCELLED");
    }
    @Test void automaticThirdExpiredLeaseTerminalizesInsteadOfBlockingTheStore(){
        var a=automaticFixture();UUID job=a.automatic.enqueue(tenant,store,a.importJob);a.automatic.claim(tenant,store,job);
        jdbc.update("UPDATE marketplace_sync_jobs SET attempt_count=3,lease_expires_at=now()-interval '1 second' WHERE id=?",job);
        assertThat(a.automatic.claim(tenant,store,job)).isNull();assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,job)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("SELECT status FROM fba_shipment_depletion_attempts WHERE tenant_id=?",String.class,tenant)).isEqualTo("NEEDS_REVIEW");
    }
    @Test void automaticHistoricalCandidatesDoNotPermanentlyStarveThe101stShipment(){
        var a=automaticFixture();
        for(int i=0;i<100;i++)jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_status,created_at,raw_payload) VALUES (?, ?,?,'SHIPPED',now()-interval '30 minutes',jsonb_build_object('ShipFromAddress',jsonb_build_object('PostalCode','00123','CountryCode','US'),'itemEvidenceVersion',1,'CreationDate','2020-01-01T00:00:00Z','itemEvidenceJob',?::text))",tenant,store,"HISTORY-"+i,a.importJob.toString());
        new FbaDepletionWorker(a.automatic).afterCompletedImport(tenant,store,a.importJob);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletion_attempts WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(101);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=? AND job_type='FBA_WAREHOUSE_DEPLETION'",Integer.class,tenant)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT cursor->>'depletionDispatch' FROM marketplace_sync_jobs WHERE id=?",String.class,a.importJob)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }
    @Test void overlappingCompletedImportsRemainDurableUntilTheCurrentWorkerDrainsThem(){
        var a=automaticFixture();UUID first=a.automatic.enqueue(tenant,store,a.importJob);String owner=a.automatic.claim(tenant,store,first);
        UUID secondImport=UUID.randomUUID(),secondRun=UUID.randomUUID();jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES (?,?,?,'RECONCILIATION','FBA_MANUAL','RUNNING',now(),now())",secondRun,tenant,store);jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,status) VALUES (?,?,?,?,'FBA_INBOUND',1,'COMPLETED')",secondImport,tenant,store,secondRun);
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_status,created_at,raw_payload) VALUES (?,?,'OVERLAP','SHIPPED',now()-interval '5 minutes',jsonb_build_object('ShipFromAddress',jsonb_build_object('PostalCode','00123','CountryCode','US'),'itemEvidenceVersion',1,'CreationDate','2020-01-01T00:00:00Z','itemEvidenceJob',?::text))",tenant,store,secondImport.toString());
        new FbaDepletionWorker(a.automatic).afterCompletedImport(tenant,store,secondImport);
        assertThat(jdbc.queryForObject("SELECT cursor->>'depletionDispatch' FROM marketplace_sync_jobs WHERE id=?",String.class,secondImport)).isEqualTo("PENDING");
        assertThat(a.automatic.processOne(tenant,store,first,owner)).isTrue();assertThat(a.automatic.processOne(tenant,store,first,owner)).isFalse();
        jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",a.importJob);
        new FbaDepletionWorker(a.automatic).resumePending(tenant,store);
        assertThat(jdbc.queryForObject("SELECT status FROM fba_shipment_depletion_attempts WHERE tenant_id=? AND shipment_id='OVERLAP'",String.class,tenant)).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT cursor->>'depletionDispatch' FROM marketplace_sync_jobs WHERE id=?",String.class,secondImport)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }
    @Test void cancelledParentLockedByAnotherTransactionCannotBeResurrectedByClaim() throws Exception {
        var a=automaticFixture();UUID job=a.automatic.enqueue(tenant,store,a.importJob);var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var cancel=threads.submit(()->tx.executeWithoutResult(st->{jdbc.queryForList("SELECT id FROM marketplace_sync_runs WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?) FOR UPDATE",job);locked.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}jdbc.update("UPDATE marketplace_sync_runs SET status='CANCELLED' WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",job);}));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(a.automatic.claim(tenant,store,job)).isNull();release.countDown();cancel.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(a.automatic.claim(tenant,store,job)).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_runs WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",String.class,job)).isEqualTo("CANCELLED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isZero();
        }finally{release.countDown();}
    }

    @Test void settingsRejectOffEnvironmentAndStaleRevisionAndUnauthorizedActors(){
        var a=automaticFixture();var denied=mock(com.nextaicommerce.platform.web.MemberAccessService.class);
        var off=new FbaAutomaticDepletion(jdbc,tx,a.fixture.operations,denied,false);
        assertThatThrownBy(()->off.configure(tenant,store,a.fixture.warehouse,a.fixture.email,true,1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("off");
        var blocked=new FbaAutomaticDepletion(jdbc,tx,a.fixture.operations,denied,true);
        assertThatThrownBy(()->blocked.configure(tenant,store,a.fixture.warehouse,a.fixture.email,false,1)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->a.automatic.configure(tenant,store,a.fixture.warehouse,a.fixture.email,false,0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("changed");
    }
    @Test void automaticStaleOwnerCannotPublishAfterLeaseReplacement(){
        var a=automaticFixture();UUID job=a.automatic.enqueue(tenant,store,a.importJob);String old=a.automatic.claim(tenant,store,job);
        jdbc.update("UPDATE marketplace_sync_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?",job);String replacement=a.automatic.claim(tenant,store,job);assertThat(replacement).isNotNull().isNotEqualTo(old);
        assertThatThrownBy(()->a.automatic.processOne(tenant,store,job,old)).isInstanceOf(IllegalStateException.class);
        assertThat(a.automatic.processOne(tenant,store,job,replacement)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletions WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }
    @Test void forcedRowSecurityHidesAllFbaOperationalMetadataFromAnotherTenant(){
        var a=automaticFixture();a.automatic.enqueue(tenant,store,a.importJob);String role="fba_rls_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        var tables=List.of("fba_shipment_user_preferences","fba_warehouse_depletion_settings","fba_shipment_depletion_overrides","fba_shipment_depletions","fba_shipment_depletion_allocations","fba_shipment_depletion_attempts");
        jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON "+String.join(",",tables)+" TO "+role);
        tx.executeWithoutResult(st->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());for(String table:tables)assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM fba_shipment_depletion_attempts",Integer.class)).isEqualTo(1);});
    }
    @Test void automaticEnvironmentDefaultOffDoesNotEvenQueryOrEnqueue(){
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);var engine=new FbaAutomaticDepletion(db,tx,mock(FbaDepletionRepository.class),mock(com.nextaicommerce.platform.web.MemberAccessService.class),false);
        assertThat(engine.enqueue(tenant,store,UUID.randomUUID())).isNull();assertThat(engine.claim(tenant,store,UUID.randomUUID())).isNull();assertThat(engine.processOne(tenant,store,UUID.randomUUID(),"stale")).isFalse();verifyNoInteractions(db);
    }
    @Test void outOfStockRequiresHistoricalConfirmedSale(){tx.executeWithoutResult(s->{
        for(String sku:List.of("OLD-SALE","NEW","CANCELLED","PENDING")){
            jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,listing_status,quantity) VALUES (?,?,'US',?,'Active',0)",tenant,store,sku);
            if(sku.equals("NEW"))continue;
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,order_status) VALUES (?,?,'US',?,now()-interval '180 days',?)",tenant,store,sku,sku.equals("OLD-SALE")?"Shipped":sku);
            jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,?,?,?,1)",tenant,store,sku,sku,sku);
        }
        var skus=new com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository(jdbc);
        var summary=skus.summary(tenant,store);
        assertThat(summary.outOfStock()).isEqualTo(1);assertThat(summary.neverSold()).isEqualTo(3);
        var oos=skus.list(tenant,store,"","OOS","status","asc",0,25);
        assertThat(oos.rows()).extracting(r->r.sellerSku()).containsExactly("OLD-SALE");
        var never=skus.list(tenant,store,"","NEVER_SOLD","status","asc",0,25);
        assertThat(never.rows()).hasSize(3).allSatisfy(r->{assertThat(r.statusLabel()).isEqualTo("Never sold");});
        assertThat(skus.summary(other,store).total()).isZero();
    });}
    @Test void barcodeMappingUsesPackQuantityAndPreservesManualDecisions(){tx.executeWithoutResult(s->{
        UUID product=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Coffee')",product);
        jdbc.update("INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value) VALUES (?,'UPC','012345678905')",product);
        jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,display_name) VALUES (?,?,?,'Coffee')",item,tenant,product);
        for(String sku:List.of("BARCODE-ONLY","AMBIGUOUS"))jdbc.update("""
          INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,item_name,catalog_barcodes,catalog_barcodes_asin)
          VALUES (?,?,'US',?,'B000TEST',?,'["00012345678905"]','B000TEST')
          """,tenant,store,sku,sku.equals("AMBIGUOUS")?"Coffee variety pack of 3":"Coffee (pack of 3)");
        var mapper=new com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper(jdbc,tx);
        // Backfill must work even when a listing already has its image.
        jdbc.update("UPDATE amazon_listings SET catalog_barcodes='[]',catalog_barcodes_asin=NULL,image_url='https://example.com/image.png' WHERE tenant_id=?",tenant);
        jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",tenant,store,new byte[]{1},new byte[]{2});
        var amazon=mock(AmazonSpApiClient.class);String body="""
          {"asin":"B000TEST","identifiers":[{"marketplaceId":"US","identifiers":[{"identifierType":"UPC","identifier":"012345678905"}]}]}
          """;
        when(amazon.get(eq(tenant),eq(store),eq("/catalog/2022-04-01/items/B000TEST?marketplaceIds=US&includedData=identifiers")))
            .thenReturn(new AmazonSpApiClient.ApiResponse(200,"test",new tools.jackson.databind.ObjectMapper().readTree(body),body));
        var enrichment=new AmazonBarcodeEnrichment(jdbc,tx,amazon,mapper,true);
        enrichment.enrich();enrichment.enrich();
        verify(amazon,times(1)).get(eq(tenant),eq(store),anyString());
        assertThat(mapper.mapConnection(tenant,store)).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM marketplace_sku_mapping_components WHERE tenant_id=?",java.math.BigDecimal.class,tenant)).isEqualByComparingTo("3");
        jdbc.update("UPDATE marketplace_sku_mappings SET status='INACTIVE',auto_map_blocked=true WHERE tenant_id=?",tenant);
        assertThat(mapper.mapConnection(tenant,store)).isZero();
        assertThat(mapper.mapConnection(other,store)).isZero();
    });}
    @Test void productFeeQueueUsesSkuAndPersistsActualResponse(){
        var json=new tools.jackson.databind.ObjectMapper();var amazon=mock(AmazonSpApiClient.class);
        UUID run=UUID.randomUUID(),id=UUID.randomUUID();var now=java.time.Instant.now();
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end) VALUES (?,?,?,'RECONCILIATION',now(),now())",run,tenant,store);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number) VALUES (?,?,?,?,'FEES_SNAPSHOT',1)",id,tenant,run,store);
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel) VALUES (?,?,'US','SKU/1',30,'USD','AFN')",tenant,store);
        jdbc.update("INSERT INTO marketplace_definitions(channel,marketplace_identifier,country_code,currency_code,display_name,reporting_timezone) VALUES ('AMAZON','US','US','USD','Fixture US','America/Los_Angeles') ON CONFLICT DO NOTHING");
        var queue=new AmazonSyncStore(jdbc,tx);var job=queue.claim("fee-fixture");
        when(amazon.quoteProductFee(eq(tenant),eq(store),eq("SKU/1"),anyString(),any())).thenAnswer(call->{
            var permit=(AmazonSpApiClient.FeePermit)call.getArgument(4);
            assertThat(permit.getAsBoolean()).isTrue();permit.completed();
            var request=json.readTree((String)call.getArgument(3)).path("FeesEstimateRequest");
            assertThat(request.path("PriceToEstimateFees").path("ListingPrice").path("Amount").decimalValue()).isEqualByComparingTo("30");
            assertThat(request.has("_platformSellerId")).isFalse();
            String body="{\"payload\":{\"FeesEstimateResult\":{\"Status\":\"Success\",\"FeesEstimateIdentifier\":{\"MarketplaceId\":\"US\",\"IdType\":\"SellerSKU\",\"IdValue\":\"SKU/1\",\"SellerInputIdentifier\":\""+request.path("Identifier").asText()+"\"},\"FeesEstimate\":{\"TotalFeesEstimate\":{\"Amount\":8,\"CurrencyCode\":\"USD\"},\"FeeDetailList\":[{\"FeeType\":\"ReferralFee\",\"FeeAmount\":{\"Amount\":3,\"CurrencyCode\":\"USD\"}},{\"FeeType\":\"FBAFees\",\"FeeAmount\":{\"Amount\":5,\"CurrencyCode\":\"USD\"}}]}}}}";
            return new AmazonSpApiClient.ApiResponse(200,"test",json.readTree(body),body);
        });
        var worker=new AmazonProductFeeSync(amazon,new com.nextaicommerce.platform.sync.AmazonProductFeeJobs(jdbc,tx,json,queue,0.5),true);
        worker.processPage(job);worker.processPage(queue.claim("fee-fixture"));
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT fulfillment_fee FROM amazon_fee_estimates WHERE tenant_id=? AND marketplace_connection_id=?",java.math.BigDecimal.class,tenant,store)).isEqualByComparingTo("5");
        verify(amazon,times(1)).quoteProductFee(eq(tenant),eq(store),eq("SKU/1"),anyString(),any());
    }
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    UUID tenant,other,store;FbaReadRepository repository;
    @BeforeAll static void setup()throws Exception {
        schema="fba_test_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();}
        else{schema="public"; // This branch owns a disposable cluster; match production function lookup.
        postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("ALTER FUNCTION "+schema+".ensure_item_default_location() SET search_path TO "+schema);
    }
    @AfterAll static void cleanup()throws Exception{if(jdbc!=null&&schema.matches("fba_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(postgres!=null)postgres.close();}
    @BeforeEach void fixture(){tenant=UUID.randomUUID();other=UUID.randomUUID();store=UUID.randomUUID();repository=new FbaReadRepository(jdbc);
        tx.executeWithoutResult(s->{for(UUID id:List.of(tenant,other))jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'FBA test')",id,id.toString());
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','US','test','ACTIVE','Test','America/Los_Angeles',now())",store,tenant);});
    }
    @Test void snapshotsKeepUnknownSeparateFromZeroAndAreTenantScoped(){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_channel) VALUES (?,?,'US','SKU','AMAZON_NA')",tenant,store);
        assertThat(repository.stocks(tenant,store,List.of("SKU")).get("SKU").available()).isNull();
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillable_quantity,inbound_shipped_quantity,inbound_receiving_quantity,reserved_quantity,snapshot_at) VALUES (?,?,'US','SKU',0,100,2,3,now())",tenant,store);
        var stock=repository.stocks(tenant,store,List.of("SKU","SKU")).get("SKU");
        assertThat(stock.available()).isZero();assertThat(stock.shipped()).isEqualTo(100);assertThat(stock.receiving()).isEqualTo(2);assertThat(stock.reserved()).isEqualTo(3);
        assertThat(stock.warehouseAvailable()).isNull();assertThat(stock.fulfillmentFee()).isNull();
        assertThat(stock.fcTransfer()).isNull();assertThat(stock.totalInbound()).isEqualTo(102L);
        jdbc.update("UPDATE amazon_inventory_snapshots SET raw_payload='{\"afn-fc-transfer-quantity\":\"3\"}'::jsonb WHERE tenant_id=?",tenant);
        assertThat(repository.stocks(tenant,store,List.of("SKU")).get("SKU").fcTransfer()).isEqualTo(3);
        assertThat(repository.stocks(other,store,List.of("SKU"))).isEmpty();
        assertThat(repository.stocks(tenant,UUID.randomUUID(),List.of("SKU"))).isEmpty();
    });}
    @Test void skuSearchReturnsWholeShipmentTotalsAndNeverInfersOrigin(){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_name,shipment_status) VALUES (?,?,'FBA123','Test shipment','SHIPPED')",tenant,store);
        jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received) VALUES (?,?,'FBA123','KARACA_%',10,4),(?,?,'FBA123','OTHER',20,2)",tenant,store,tenant,store);
        var page=repository.shipments(tenant,store,"KARACA_%",999,25);
        assertThat(page.total()).isEqualTo(1);assertThat(page.page()).isZero();
        var row=page.rows().getFirst();assertThat(row.shipped()).isEqualTo(30);assertThat(row.received()).isEqualTo(6);assertThat(row.skuCount()).isEqualTo(2);assertThat(row.shipFrom()).isNull();
        assertThat(row.shipFromState()).isNull();
        jdbc.update("UPDATE amazon_fba_shipments SET raw_payload='{\"ShipFromAddress\":{\"City\":\"Houston\",\"StateOrProvinceCode\":\"TX\",\"CountryCode\":\"US\"}}' WHERE tenant_id=? AND marketplace_connection_id=?",tenant,store);
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().shipFromState()).isEqualTo("TX");
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().shipFrom()).isEqualTo("Houston, TX, US");
        jdbc.update("UPDATE amazon_fba_shipments SET raw_payload=jsonb_set(raw_payload,'{ShipFromAddress,PostalCode}','\"01234-5678\"') WHERE tenant_id=?",tenant);
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().shipFrom()).isEqualTo("Houston, TX 01234-5678, US");
        jdbc.update("UPDATE amazon_fba_shipments SET raw_payload='{\"ShipFromAddress\":{\"City\":\"London\",\"PostalCode\":\"SW1A 1AA\",\"CountryCode\":\"GB\"}}' WHERE tenant_id=?",tenant);
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().shipFrom()).isEqualTo("London, SW1A 1AA, GB");
        assertThat(repository.shipments(other,store,"",0,25).rows()).isEmpty();
        assertThat(repository.shipments(tenant,UUID.randomUUID(),"",0,25).rows()).isEmpty();
        assertThat(repository.shipments(tenant,store,"' OR 1=1 --",0,25).rows()).isEmpty();
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,item_name) VALUES (?,?,'US','KARACA_%','Porcelain dinnerware')",tenant,store);
        assertThat(repository.shipments(tenant,store,"dinnerware",0,25).total()).isEqualTo(1);
        var contents=repository.items(tenant,store,"FBA123","dinnerware",0);assertThat(contents.total()).isEqualTo(1);assertThat(contents.rows().getFirst().shipped()).isEqualTo(10);
        assertThatThrownBy(()->repository.items(other,store,"FBA123","",0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant)).isZero();
    });}

    @Test void shipmentChargesUseExactStoreAndNetPostedTotals(){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,shipment_status) VALUES (?,?,'FBA-COST','CLOSED')",tenant,store);
        jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received) VALUES (?,?,'FBA-COST','SKU',10,8)",tenant,store);
        assertThat(repository.items(tenant,store,"FBA-COST","",0).costs().getPerUnit()).isNull();
        for(var entry:List.of(new Object[]{"transport","FBAPostInboundTransportation",-30},new Object[]{"placement","FBAInboundConvenience",-10},new Object[]{"credit","FBAPostInboundTransportation",5}))
            jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,description,amount,currency,raw_payload) VALUES (?,?,?,'ServiceFee','FBA-COST',?,?,'USD','{\"transactionStatus\":\"RELEASED\"}')",tenant,store,entry[0],entry[1],entry[2]);
        var costs=repository.items(tenant,store,"FBA-COST","",0).costs();
        assertThat(costs.transportation()).isEqualByComparingTo("25");assertThat(costs.placement()).isEqualByComparingTo("10");
        assertThat(costs.getPerUnit()).isEqualByComparingTo("3.5");
        jdbc.update("UPDATE amazon_financial_transactions SET raw_payload='{\"transactionStatus\":\"DEFERRED\"}' WHERE tenant_id=? AND transaction_key='placement'",tenant);
        assertThat(repository.items(tenant,store,"FBA-COST","",0).costs().getPerUnit()).isNull();
    });}

    @Test void inboundImportCheckpointsPagesAndExcludesPrivateAddress() {
        var json=new tools.jackson.databind.ObjectMapper();var amazon=mock(AmazonSpApiClient.class);
        UUID run=UUID.randomUUID(),id=UUID.randomUUID();var now=java.time.Instant.now();
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end) VALUES (?,?,?,'RECONCILIATION',now(),now())",run,tenant,store);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number) VALUES (?,?,?,?,'FBA_INBOUND',1)",id,tenant,run,store);
        var job=new AmazonSyncStore.Job(id,tenant,run,store,"FBA_INBOUND","FBA_INBOUND","US",now,now,null,0,0,false,null);
        when(amazon.get(eq(tenant),eq(store),anyString())).thenReturn(
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"ShipmentData":[{"ShipmentId":"FBA1","CreationDate":"2026-10-01T12:00:00Z","ShipmentStatus":"SHIPPED","ShipFromAddress":{"City":"Dallas","PostalCode":"00123","Name":"PRIVATE","AddressLine1":"PRIVATE"}}]}}
                """),""),
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"ItemData":[{"ShipmentId":"FBA1","SellerSKU":"SKU1","QuantityShipped":10,"QuantityReceived":2}],"NextToken":"more"}}
                """),""),
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"ItemData":[{"ShipmentId":"FBA1","SellerSKU":"SKU2","QuantityShipped":5,"QuantityReceived":1}]}}
                """),""),
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"transactions":[
                  {"sellingPartnerMetadata":{"marketplaceId":"US"},"transactionId":"transport","transactionType":"ServiceFee","transactionStatus":"RELEASED","description":"FBAPostInboundTransportation","relatedIdentifiers":[{"relatedIdentifierName":"ORDER_ID","relatedIdentifierValue":"FBA1"}],"totalAmount":{"currencyCode":"USD","currencyAmount":-30}},
                  {"sellingPartnerMetadata":{"marketplaceId":"US"},"transactionId":"placement","transactionType":"ServiceFee","transactionStatus":"RELEASED","description":"FBAInboundConvenience","relatedIdentifiers":[{"relatedIdentifierName":"ORDER_ID","relatedIdentifierValue":"FBA1"}],"totalAmount":{"currencyCode":"USD","currencyAmount":-15}}
                ]}}
                """),"{}"));
        var normalizer=new AmazonReportNormalizer(jdbc,tx,json,null);
        var importer=new FbaInboundSync(amazon,new AmazonSyncStore(jdbc,tx),jdbc,tx,json,normalizer);
        importer.processPage(job);
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().createdAt()).isEqualTo(java.time.Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("WAITING");
        importer.processPage(job);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(raw_payload,'itemEvidence') FROM amazon_fba_shipments WHERE tenant_id=?",Boolean.class,tenant)).isFalse();
        importer.processPage(job);importer.processPage(job);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(raw_payload->'itemEvidence') FROM amazon_fba_shipments WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(2);
        verify(amazon).get(eq(tenant),eq(store),argThat(path->path.startsWith("/finances/2024-06-19/transactions?postedAfter=")&&!path.contains("relatedIdentifier")));
        verify(amazon).get(tenant,store,"/fba/inbound/v0/shipmentItems?MarketplaceId=US&QueryType=NEXT_TOKEN&NextToken=more");
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT sum(quantity_shipped) FROM amazon_fba_shipment_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT cursor->>'consecutiveFailures' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("0");
        assertThat(repository.items(tenant,store,"FBA1","",0).costs().getPerUnit()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT raw_payload::text FROM amazon_fba_shipments WHERE tenant_id=?",String.class,tenant)).contains("Dallas").contains("00123").doesNotContain("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant)).isZero();
    }

    @Test void shipmentDatesSortNewestFirstAndUnknownLast(){tx.executeWithoutResult(s->{
        for(String id:List.of("OLD","NEW","UNKNOWN"))jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id,created_date,updated_date) VALUES (?,?,?,CAST(? AS timestamptz),now())",tenant,store,id,id.equals("UNKNOWN")?null:id.equals("OLD")?"2024-01-01T00:00:00Z":"2026-01-01T00:00:00Z");
        assertThat(repository.shipments(tenant,store,"",0,25).rows()).extracting(FbaReadRepository.Shipment::id).containsExactly("NEW","OLD","UNKNOWN");
        jdbc.update("UPDATE amazon_fba_shipments SET updated_date='2026-10-01T12:00:00Z',raw_payload='{\"LastUpdatedDate\":\"2026-10-01T12:00:00Z\"}' WHERE tenant_id=? AND shipment_id='UNKNOWN'",tenant);
        var fallback=repository.shipments(tenant,store,"",0,25).rows().getFirst();
        assertThat(fallback.id()).isEqualTo("UNKNOWN");assertThat(fallback.createdDisplay()).isEqualTo("10/01/2026 12:00 PM UTC");
    });}

    @Test void deniedFinanceRoleDoesNotFailShipmentImport(){
        var json=new tools.jackson.databind.ObjectMapper();var amazon=mock(AmazonSpApiClient.class);
        UUID run=UUID.randomUUID(),id=UUID.randomUUID();var now=java.time.Instant.now();
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end) VALUES (?,?,?,'RECONCILIATION',now(),now())",run,tenant,store);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,cursor) VALUES (?,?,?,?,'FBA_INBOUND',1,'{\"shipmentsComplete\":true,\"pending\":[],\"records\":2}')",id,tenant,run,store);
        var job=new AmazonSyncStore.Job(id,tenant,run,store,"FBA_INBOUND","FBA_INBOUND","US",now,now,null,0,0,false,null);
        when(amazon.get(eq(tenant),eq(store),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(403,null,"Denied"));
        new FbaInboundSync(amazon,new AmazonSyncStore(jdbc,tx),jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null)).processPage(job);
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT cursor->>'feesUnavailable' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_financial_transactions WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }

    @Test void shipmentListUsesSameNetChargesAsDetailsAndFullShippedDenominator(){tx.executeWithoutResult(st->{
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id) VALUES(?,?,'COST-LIST')",tenant,store);
        for(String sku:List.of("VISIBLE","OTHER"))jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received) VALUES(?,?,'COST-LIST',?,10,8)",tenant,store,sku);
        for(var e:List.of(new Object[]{"transport","FBAPostInboundTransportation",-30},new Object[]{"placement","FBAInboundConvenience",-10},new Object[]{"credit","FBAPostInboundTransportation",5}))jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,description,amount,currency,raw_payload) VALUES(?,?,?,'ServiceFee','COST-LIST',?,?,'USD','{\"transactionStatus\":\"RELEASED\"}')",tenant,store,e[0],e[1],e[2]);
        var list=repository.shipments(tenant,store,"VISIBLE",0,25).rows().getFirst();var details=repository.items(tenant,store,"COST-LIST","VISIBLE",0).costs();
        assertThat(list.costs()).isEqualTo(details);assertThat(list.costs().getTotal()).isEqualByComparingTo("35");assertThat(list.costs().getPerUnit()).isEqualByComparingTo("1.75");assertThat(list.shipped()).isEqualTo(20);assertThat(list.skuCount()).isEqualTo(2);
        assertThat(repository.shipments(other,store,"",0,25).rows()).isEmpty();
    });}
    @Test void shipmentListKeepsPartialZeroAndMixedCurrencyCostsExplicit(){tx.executeWithoutResult(st->{
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id) VALUES(?,?,'PARTIAL')",tenant,store);
        jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received) VALUES(?,?,'PARTIAL','SKU',0,0)",tenant,store);
        jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,description,amount,currency,raw_payload) VALUES(?,?,'partial','ServiceFee','PARTIAL','FBAPostInboundTransportation',0,'USD','{\"transactionStatus\":\"RELEASED\"}')",tenant,store);
        var costs=repository.shipments(tenant,store,"",0,25).rows().getFirst().costs();assertThat(costs.transportation()).isEqualByComparingTo("0");assertThat(costs.placement()).isNull();assertThat(costs.getTotal()).isNull();assertThat(costs.getPerUnit()).isNull();
        jdbc.update("INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,transaction_type,amazon_order_id,description,amount,currency,raw_payload) VALUES(?,?,'foreign','ServiceFee','PARTIAL','FBAInboundConvenience',-10,'CAD','{\"transactionStatus\":\"RELEASED\"}')",tenant,store);
        costs=repository.shipments(tenant,store,"",0,25).rows().getFirst().costs();assertThat(costs.currency()).isNull();assertThat(costs.getTotal()).isNull();
    });}
    @Test void shipmentCasesUseEveryAuthoritativePackSizeOrStayUnknown(){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id) VALUES (?,?,'CASES')",tenant,store);
        jdbc.update("INSERT INTO amazon_fba_shipment_items(tenant_id,marketplace_connection_id,shipment_id,seller_sku,quantity_shipped,quantity_received,quantity_in_case) VALUES (?,?,'CASES','SIX',14,12,6),(?,?,'CASES','TWELVE',24,20,12)",tenant,store,tenant,store);
        var row=repository.shipments(tenant,store,"",0,25).rows().getFirst();assertThat(row.shipped()).isEqualTo(38);assertThat(row.shippedCases()).isEqualTo(4);assertThat(row.looseUnits()).isEqualTo(2);assertThat(row.received()).isEqualTo(32);
        jdbc.update("UPDATE amazon_fba_shipment_items SET quantity_in_case=NULL WHERE tenant_id=? AND seller_sku='TWELVE'",tenant);
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().shippedCases()).isNull();
    });}
    @Test void manualFbaNeverTakesOverSharedRunOrOlderCheckpoint() throws Exception {
        jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",tenant,store,new byte[]{1},new byte[]{2});
        UUID run=UUID.randomUUID(),fba=UUID.randomUUID(),orders=UUID.randomUUID();
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end) VALUES (?,?,?,'RECONCILIATION','INITIAL',now()-interval '1 day',now()-interval '3 minutes')",run,tenant,store);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number) VALUES (?,?,?,?,'FBA_INBOUND',1),(?,?,?,?,'ORDERS',2)",fba,tenant,store,run,orders,tenant,store,run);
        var inbound=mock(FbaInboundSync.class);var manualWorker=new FbaManualWorker(jdbc,tx,inbound,new AmazonSyncStore(jdbc,tx));
        manualWorker.start(tenant,store,fba);verifyNoInteractions(inbound);
        var manual=new FbaManualSync(jdbc,tx,mock(FbaManualWorker.class),true);
        assertThatThrownBy(()->manual.request(tenant,store)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_runs WHERE id=?",String.class,run)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,orders)).isEqualTo("QUEUED");
        jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED',completed_at=now()-interval '10 minutes' WHERE sync_run_id=?",run);
        jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED' WHERE id=?",run);
        UUID old=manual.request(tenant,store);
        jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',completed_at=now(),error_message='HTTP 429' WHERE id=?",old);
        jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS' WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",old);
        jdbc.update("UPDATE marketplace_sync_jobs SET created_at=now()-interval '1 hour' WHERE id=?",old);
        UUID newer=manual.request(tenant,store);assertThat(newer).isNotEqualTo(old);
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,old)).isEqualTo("FAILED");
    }
    @Test void fbaFinanceEvidenceCannotCrossMarketplaceOrGuessMissingAttribution(){
        var json=new tools.jackson.databind.ObjectMapper();var normalizer=new AmazonReportNormalizer(jdbc,tx,json,null);
        var job=new AmazonSyncStore.Job(UUID.randomUUID(),tenant,UUID.randomUUID(),store,"FBA_INBOUND","FBA_MANUAL","US",java.time.Instant.now(),java.time.Instant.now(),null,0,0,false,null);
        var rows=json.createObjectNode();var array=rows.putObject("payload").putArray("transactions");
        for(String market:List.of("US","CA")){var r=array.addObject();r.put("transactionType","ServiceFee");r.put("description","FBAInboundConvenience");r.putObject("sellingPartnerMetadata").put("marketplaceId",market);}
        var scoped=normalizer.scopedShipmentFinances(job,rows);assertThat(scoped.path("payload").path("transactions").size()).isEqualTo(1);
        assertThat(scoped.toString()).doesNotContain("CA");
        array.addObject().put("transactionType","ServiceFee").put("description","FBAInboundConvenience");
        assertThatThrownBy(()->normalizer.scopedShipmentFinances(job,rows)).isInstanceOf(IllegalStateException.class);
    }

    @Test void manualFbaQueueIsExplicitScopedAndDeduplicated(){
        jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",tenant,store,new byte[]{1},new byte[]{2});
        var worker=mock(FbaManualWorker.class);var manual=new FbaManualSync(jdbc,tx,worker,true);
        UUID job=manual.request(tenant,store);assertThat(manual.request(tenant,store)).isEqualTo(job);
        assertThat(manual.state(tenant,store).running()).isTrue();assertThat(manual.state(other,store).status()).isEqualTo("NOT_STARTED");
        assertThatThrownBy(()->manual.request(other,store)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new FbaManualSync(jdbc,tx,worker,false).request(tenant,store)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=? AND job_type<>'FBA_INBOUND'",Long.class,tenant)).isZero();
        var amazon=mock(AmazonSpApiClient.class);var json=new tools.jackson.databind.ObjectMapper();var queue=new AmazonSyncStore(jdbc,tx);var inbound=new FbaInboundSync(amazon,queue,jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null));
        assertThat(queue.claim("fba-lease-fixture")).isNull();var claimed=new AmazonSyncStore.Job(job,tenant,jdbc.queryForObject("SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?",UUID.class,job),store,"FBA_INBOUND","FBA_MANUAL","US",java.time.Instant.now().minusSeconds(60),java.time.Instant.now(),null,0,0,false,null,"fba-lease-fixture");jdbc.update("UPDATE marketplace_sync_jobs SET lease_owner='replacement' WHERE id=?",job);
        assertThatThrownBy(()->inbound.processPage(claimed)).isInstanceOf(AmazonSyncStore.LostOrderLease.class);verifyNoInteractions(amazon);
    }

    @Test void fbaThrottleDoesNotExhaustLifetimeRetryCountAndKeepsCheckpoint() throws Exception {
        jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",tenant,store,new byte[]{1},new byte[]{2});
        UUID id=new FbaManualSync(jdbc,tx,mock(FbaManualWorker.class),true).request(tenant,store);
        jdbc.update("UPDATE marketplace_sync_jobs SET failure_count=3,cursor='{\"shipmentsComplete\":true,\"feeNext\":\"saved-page\"}' WHERE id=?",id);
        var inbound=mock(FbaInboundSync.class);doThrow(new AmazonSpApiClient.AmazonApiException(429,java.time.Duration.ofSeconds(95),"Amazon rate limit")).when(inbound).processPage(any());
        var worker=new FbaManualWorker(jdbc,tx,inbound,new AmazonSyncStore(jdbc,tx));
        Thread thread=new Thread(()->worker.start(tenant,store,id));thread.start();
        try {
            long end=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();String state;
            do{Thread.sleep(20);state=jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id);}while(List.of("QUEUED","RUNNING").contains(state)&&System.nanoTime()<end);
            assertThat(state).isEqualTo("WAITING");
            assertThat(jdbc.queryForObject("SELECT available_at>now()+interval '90 seconds' FROM marketplace_sync_jobs WHERE id=?",Boolean.class,id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT cursor->>'feeNext' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("saved-page");
        }finally{thread.interrupt();thread.join(3000);}
        verify(inbound,times(1)).processPage(any());
    }

    @Test void explicitFbaThrottleRetryReusesJobAndNeverRestartsShipmentImport(){
        jdbc.update("INSERT INTO marketplace_connection_credentials(tenant_id,marketplace_connection_id,encrypted_payload,encryption_nonce) VALUES (?,?,?,?)",tenant,store,new byte[]{1},new byte[]{2});
        var worker=mock(FbaManualWorker.class);var manual=new FbaManualSync(jdbc,tx,worker,true);UUID id=manual.request(tenant,store);
        jdbc.update("UPDATE marketplace_sync_jobs SET status='FAILED',completed_at=now(),error_message='Amazon FBA read could not complete (HTTP 429)',cursor='{\"shipmentsComplete\":true,\"feeNext\":\"same-page\"}' WHERE id=?",id);
        jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED_WITH_WARNINGS',completed_at=now() WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",id);
        assertThat(manual.request(tenant,store)).isEqualTo(id);
        assertThat(manual.request(tenant,store)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cursor->>'feeNext' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("same-page");
        assertThat(jdbc.queryForObject("SELECT cursor->>'shipmentsComplete' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("true");
        jdbc.update("UPDATE marketplace_sync_jobs SET status='COMPLETED',completed_at=now()-interval '6 minutes',cursor=cursor||'{\"feesComplete\":true,\"feesUnavailable\":true}'::jsonb WHERE id=?",id);
        jdbc.update("UPDATE marketplace_sync_runs SET status='COMPLETED' WHERE id=(SELECT sync_run_id FROM marketplace_sync_jobs WHERE id=?)",id);
        assertThat(manual.request(tenant,store)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT cursor->>'feeRestartCount' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT cursor->>'feeNext' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT cursor->>'feesUnavailable' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1);
        assertThatThrownBy(()->manual.request(other,store)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant)).isZero();
    }
    @Test void fbaRetryStillStopsForAuthorizationOrEightConsecutiveFailures() throws Exception {
        for(int status:List.of(401,429)){
            UUID run=UUID.randomUUID(),id=UUID.randomUUID();
            jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,window_start,window_end) VALUES (?,?,?,'RECONCILIATION','FBA_MANUAL',now()-interval '180 days',now()-interval '3 minutes')",run,tenant,store);
            jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,cursor) VALUES (?,?,?,?,'FBA_INBOUND',1,CAST(? AS jsonb))",id,tenant,store,run,status==429?"{\"consecutiveFailures\":7}":"{}");
            var inbound=mock(FbaInboundSync.class);doThrow(new AmazonSpApiClient.AmazonApiException(status,null,"Amazon read rejected")).when(inbound).processPage(any());
            var worker=new FbaManualWorker(jdbc,tx,inbound,new AmazonSyncStore(jdbc,tx));Thread thread=new Thread(()->worker.start(tenant,store,id));thread.start();
            try{long end=System.nanoTime()+java.time.Duration.ofSeconds(5).toNanos();String state;do{Thread.sleep(20);state=jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id);}while(List.of("QUEUED","RUNNING").contains(state)&&System.nanoTime()<end);assertThat(state).isEqualTo("FAILED");}finally{thread.interrupt();thread.join(3000);}
            verify(inbound,times(1)).processPage(any());
        }
    }

    @Test void rejectedFeePageRestartsOnlyFinanceOnceRatherThanPretendingComplete(){
        var amazon=mock(AmazonSpApiClient.class);var json=new tools.jackson.databind.ObjectMapper();UUID run=UUID.randomUUID(),id=UUID.randomUUID();var now=java.time.Instant.now();
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,window_start,window_end) VALUES (?,?,?,'RECONCILIATION',now()-interval '180 days',now()-interval '3 minutes')",run,tenant,store);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number,cursor) VALUES (?,?,?,?,'FBA_INBOUND',1,'{\"shipmentsComplete\":true,\"feeQueryRevision\":2,\"feeNext\":\"rejected-token\"}')",id,tenant,store,run);
        when(amazon.get(any(),any(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(400,null,"Amazon rejected page"));
        var inbound=new FbaInboundSync(amazon,new AmazonSyncStore(jdbc,tx),jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null));
        var job=new AmazonSyncStore.Job(id,tenant,run,store,"FBA_INBOUND","FBA_INBOUND","US",now.minus(java.time.Duration.ofDays(179)),now.minusSeconds(180),null,0,0,false,null,null);
        inbound.processPage(job);
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT cursor->>'feeNext' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT cursor->>'feesComplete' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT cursor->>'shipmentsComplete' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("true");
        inbound.processPage(job);
        assertThat(jdbc.queryForObject("SELECT cursor->>'feesUnavailable' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("true");
    }

}
