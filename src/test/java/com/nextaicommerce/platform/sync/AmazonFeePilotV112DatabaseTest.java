package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AmazonFeePilotV112DatabaseTest {
    @Test void archiveOnlyUsesV112WithoutFeeTablesOrHistoricalMutation()throws Exception{
        try(var postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start()){
            String schema="fee_archive_"+UUID.randomUUID().toString().replace("-","");
            var ds=new DriverManagerDataSource(postgres.getJdbcUrl("postgres","postgres")+"&currentSchema="+schema,"postgres",null);
            org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).target("112").locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var json=new ObjectMapper();
            UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),run=UUID.randomUUID(),job=UUID.randomUUID(),old=UUID.randomUUID();String market="ATVPDKIKX0DER",sku="00001";
            jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Archive fixture')",tenant,tenant.toString());
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','SYNTHETIC',?,'ACTIVE','Archive fixture','fixture','America/Los_Angeles',now())",connection,tenant,market);
            jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,status,window_start,window_end) VALUES (?,?,?,'RECONCILIATION','QUEUED',now(),now())",run,tenant,connection);
            jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,price,currency,fulfillment_channel,listing_status) VALUES (?,?,?,?,30,'USD','AFN','Active')",tenant,connection,market,sku);
            jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,source_key,sha256,payload) VALUES (?,?,?,'FINANCES_30_DAY','historical',?,'{\"preserved\":true}')",old,tenant,connection,old.toString());
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,currency,fulfillment_channel) VALUES (?,?,?,'HISTORICAL','2020-01-01','USD','AFN')",tenant,connection,market);
            jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillable_quantity,total_quantity,snapshot_at) VALUES (?,?,?,?,17,17,'2020-01-01')",tenant,connection,market,sku);
            var beforeSource=jdbc.queryForMap("SELECT * FROM amazon_source_documents WHERE id=?",old);var beforeOrder=jdbc.queryForMap("SELECT * FROM amazon_orders WHERE tenant_id=?",tenant);var beforeStock=jdbc.queryForMap("SELECT * FROM amazon_inventory_snapshots WHERE tenant_id=?",tenant);
            var store=new AmazonSyncStore(jdbc,tx);var jobs=new AmazonProductFeeJobs(jdbc,tx,json,store,0.5);
            jobs.configureFeeEvidence(new com.nextaicommerce.platform.fees.AmazonFeeEvidence(jdbc,json,false));
            var contract=json.createObjectNode().put("format","FEE_RUN_V1").put("approval",UUID.randomUUID().toString()).put("tenant",tenant.toString()).put("connection",connection.toString()).put("marketplace",market).put("seller","SYNTHETIC")
                .put("maxTargets",20).put("maxHttp",3).put("maxOauth",2).put("maxSeconds",300).put("approvalExpiresAt",Instant.now().plusSeconds(600).toString()).put("publicationMode","ARCHIVE_ONLY");
            contract.putArray("skus").add(sku);var digest=json.createArrayNode();digest.addArray().add(sku).add("30").add("USD").add("FBA").add("0");contract.put("basisSha256",com.nextaicommerce.platform.fees.AmazonFeeEvidence.digest(digest.toString()));
            var cursor=json.createObjectNode().put("shippingBasisConfirmed",true);cursor.set("feeRun",contract);
            jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,status,required_for_ready,cursor) VALUES (?,?,?,?,'FEES_SNAPSHOT',1,'QUEUED',false,CAST(? AS jsonb))",job,tenant,run,connection,cursor.toString());
            String hash=jobs.pilotContractHash(contract);var claimed=jobs.claimPilot(tenant,connection,market,job,hash,"v112");assertThat(claimed).isNotNull();assertThat(jobs.matchesPilot(claimed,hash)).isTrue();
            var targets=jobs.batchNext(claimed);assertThat(targets).hasSize(1);var basis=jobs.basis(claimed,targets.getFirst());
            var response=json.createObjectNode().put("Status","Success");var id=response.putObject("FeesEstimateIdentifier").put("IdType","SellerSKU").put("IdValue",sku).put("SellerId","SYNTHETIC").put("MarketplaceId",market).put("IsAmazonFulfilled",true).put("SellerInputIdentifier","v112");id.set("PriceToEstimateFees",basis.path("PriceToEstimateFees"));
            var estimate=response.putObject("FeesEstimate");estimate.putObject("TotalFeesEstimate").put("CurrencyCode","USD").put("Amount",8);var fees=estimate.putArray("FeeDetailList");
            for(var entry:Map.of("ReferralFee",3,"FBAFees",5).entrySet()){var fee=fees.addObject().put("FeeType",entry.getKey());fee.putObject("FeeAmount").put("CurrencyCode","USD").put("Amount",entry.getValue());fee.putObject("FinalFee").put("CurrencyCode","USD").put("Amount",entry.getValue());}
            var envelope=json.createArrayNode().add(response);var verified=AmazonProductFeeBatch.verify(List.of(new AmazonProductFeeBatch.Request(targets.getFirst(),basis,"v112")),envelope);assertThat(verified.accepted()).hasSize(1);
            assertThat(jobs.reserve(claimed,sku,"FEE")).isTrue();jobs.received(claimed);jobs.publishBatch(claimed,targets,verified,new AmazonSpApiClient.ApiResponse(200,"synthetic",envelope,envelope.toString()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_estimates",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('amazon_fee_observations','amazon_order_fee_charge_observations','amazon_fee_comparisons','amazon_fee_approvals')",Integer.class,schema)).isZero();
            assertThat(jdbc.queryForObject("SELECT text_payload FROM amazon_source_documents WHERE sync_job_id=?",String.class,job)).isEqualTo(envelope.toString());
            assertThat(jdbc.queryForMap("SELECT * FROM amazon_source_documents WHERE id=?",old)).isEqualTo(beforeSource);assertThat(jdbc.queryForMap("SELECT * FROM amazon_orders WHERE tenant_id=?",tenant)).isEqualTo(beforeOrder);assertThat(jdbc.queryForMap("SELECT * FROM amazon_inventory_snapshots WHERE tenant_id=?",tenant)).isEqualTo(beforeStock);
            var finished=jobs.claimPilot(tenant,connection,market,job,hash,"v112-next");assertThat(finished).isNotNull();assertThat(jobs.batchNext(finished)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT user_message FROM marketplace_sync_runs WHERE id=?",String.class,run)).isEqualTo("Archived fee quotes: 1 passed validation; 0 unsupported; 0 unresolved results. Comparable per-unit FBA fees: 0 of 1. Quotes reused: 0. Estimates unchanged.");
        }
    }
}
