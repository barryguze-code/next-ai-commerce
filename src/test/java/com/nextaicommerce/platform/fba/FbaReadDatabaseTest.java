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
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
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
                {"payload":{"ShipmentData":[{"ShipmentId":"FBA1","CreationDate":"2026-10-01T12:00:00Z","ShipmentStatus":"SHIPPED","ShipFromAddress":{"City":"Dallas","Name":"PRIVATE","AddressLine1":"PRIVATE"}}]}}
                """),""),
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"ItemData":[{"ShipmentId":"FBA1","SellerSKU":"SKU1","QuantityShipped":10,"QuantityReceived":2}],"NextToken":"more"}}
                """),""),
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"ItemData":[{"ShipmentId":"FBA1","SellerSKU":"SKU2","QuantityShipped":5,"QuantityReceived":1}]}}
                """),""),
            new AmazonSpApiClient.ApiResponse(200,"test",json.readTree("""
                {"payload":{"transactions":[
                  {"transactionId":"transport","transactionType":"ServiceFee","transactionStatus":"RELEASED","description":"FBAPostInboundTransportation","relatedIdentifiers":[{"relatedIdentifierName":"ORDER_ID","relatedIdentifierValue":"FBA1"}],"totalAmount":{"currencyCode":"USD","currencyAmount":-30}},
                  {"transactionId":"placement","transactionType":"ServiceFee","transactionStatus":"RELEASED","description":"FBAInboundConvenience","relatedIdentifiers":[{"relatedIdentifierName":"ORDER_ID","relatedIdentifierValue":"FBA1"}],"totalAmount":{"currencyCode":"USD","currencyAmount":-15}}
                ]}}
                """),"{}"));
        var normalizer=new AmazonReportNormalizer(jdbc,tx,json,null);
        var importer=new FbaInboundSync(amazon,new AmazonSyncStore(jdbc,tx),jdbc,tx,json,normalizer);
        importer.processPage(job);
        assertThat(repository.shipments(tenant,store,"",0,25).rows().getFirst().createdAt()).isEqualTo(java.time.Instant.parse("2026-10-01T12:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("WAITING");
        importer.processPage(job);importer.processPage(job);importer.processPage(job);
        verify(amazon).get(tenant,store,"/finances/2024-06-19/transactions?relatedIdentifierName=ORDER_ID&relatedIdentifierValue=FBA1");
        verify(amazon).get(tenant,store,"/fba/inbound/v0/shipmentItems?MarketplaceId=US&QueryType=NEXT_TOKEN&NextToken=more");
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT sum(quantity_shipped) FROM amazon_fba_shipment_items WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(15);
        assertThat(repository.items(tenant,store,"FBA1","",0).costs().getPerUnit()).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT raw_payload::text FROM amazon_fba_shipments WHERE tenant_id=?",String.class,tenant)).contains("Dallas").doesNotContain("PRIVATE");
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
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,sync_run_id,marketplace_connection_id,job_type,sequence_number,cursor) VALUES (?,?,?,?,'FBA_INBOUND',1,'{\"feeShipment\":\"FBA1\",\"pending\":[],\"records\":2}')",id,tenant,run,store);
        var job=new AmazonSyncStore.Job(id,tenant,run,store,"FBA_INBOUND","FBA_INBOUND","US",now,now,null,0,0,false,null);
        when(amazon.get(eq(tenant),eq(store),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(403,null,"Denied"));
        new FbaInboundSync(amazon,new AmazonSyncStore(jdbc,tx),jdbc,tx,json,new AmazonReportNormalizer(jdbc,tx,json,null)).processPage(job);
        assertThat(jdbc.queryForObject("SELECT status FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT cursor->>'feesUnavailable' FROM marketplace_sync_jobs WHERE id=?",String.class,id)).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_financial_transactions WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }
}
