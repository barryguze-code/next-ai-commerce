package com.nextaicommerce.platform.orders;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Disposable embedded database only; production benchmarks are read-only and separate. */
class OrderTabTotalsDatabaseTest {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static String schema;
    UUID tenant, connection;

    @BeforeAll static void setup() throws Exception {
        schema="order_tabs_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")) {
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("testDatabasePasswordFile",".local/database-password"))).trim();
        } else {
            schema="public"; // This branch owns a disposable cluster; match production function lookup.
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");
        }
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        for(String f:List.of("ensure_item_default_location","fill_inventory_ledger_location","fill_receiving_receipt_location","fill_order_reservation_location"))
            jdbc.execute("ALTER FUNCTION "+schema+"."+f+"() SET search_path TO "+schema);
    }
    @AfterAll static void stop() throws Exception {
        if(jdbc!=null&&schema.matches("order_tabs_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }

    void fixture() {
        tenant=UUID.randomUUID();connection=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Tabs test')",tenant,tenant.toString());
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','US','test','ACTIVE','Test','America/Los_Angeles',now())",connection,tenant);
    }
    void listing(String sku,String channel,int quantity) {
        jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillment_channel,price,buy_box_price,currency,buy_box_currency,quantity) VALUES (?,?,'US',?,?,20,19,'USD','USD',?)",tenant,connection,sku,channel,quantity);
    }
    UUID order(String id,String status,String state,boolean pickup,String sku,int quantity) {
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,fulfillment_state,platform_waiting_for_pickup,purchase_date) VALUES (?,?,'US',?,?,?,?,now())",tenant,connection,id,status,state,pickup);
        return item(id,sku,quantity);
    }
    UUID item(String order,String sku,int quantity) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO amazon_order_items(id,tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price) VALUES (?,?,?,?,?,?,?,?)",id,tenant,connection,order,id.toString(),sku,quantity,quantity*20);
        return id;
    }
    List<Long> totals(String sql,UUID t,UUID c) {
        return jdbc.queryForObject(sql,(rs,n)->{var values=new ArrayList<Long>();for(int i=1;i<=14;i++)values.add(rs.getLong(i));return values;},t,c);
    }
    void equivalent() {
        assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection))
            .isEqualTo(totals(OrderTabsSqlExportTest.legacySql(),tenant,connection));
        var actual=new OrderRepository(jdbc).tabs(tenant,connection).stream()
            .flatMap(t->java.util.stream.Stream.of(t.orders(),t.units())).toList();
        assertThat(actual).isEqualTo(totals(OrderTabTotalsQuery.SQL,tenant,connection));
    }
    @Test void preservesStatusCountsMultiItemOrdersAndIsolation() {
        tx.executeWithoutResult(s->{fixture();
            listing("LOST","MFN",10);listing("MATCH","MFN",10);listing("EMPTY","MFN",0);
            jdbc.update("UPDATE amazon_listings SET buy_box_price=price WHERE tenant_id=? AND seller_sku='MATCH'",tenant);
            String[] statuses={"Pending","PendingAvailability","Unshipped","PartiallyShipped","Shipped","Cancelled","Waiting for pickup","Ready-For-Pickup","Delivered",null,"Unknown"};
            for(int i=0;i<statuses.length;i++) {
                order("status-"+i,statuses[i],i==2?"INVENTORY_SHORTAGE":i==9?"NEEDS_MAPPING":"READY_TO_SHIP",i==10,"LOST",2);
                item("status-"+i,"MATCH",3);item("status-"+i,"LOST",1);
            }
            order("zero","Unshipped","READY_TO_SHIP",false,"EMPTY",1);
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,purchase_date) VALUES (?,?,'US','no-items','Unshipped',now())",tenant,connection);
            equivalent();
            assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isEqualTo(11);
            assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(13)).isEqualTo(66);
            assertThat(totals(OrderTabTotalsQuery.SQL,UUID.randomUUID(),connection)).containsOnly(0L);
            assertThat(totals(OrderTabTotalsQuery.SQL,tenant,UUID.randomUUID())).containsOnly(0L);
        });
    }
    @Test void excludesUnknownAndNonpositivePricesFromLostTotals() {
        tx.executeWithoutResult(s->{fixture();listing("LOST","MFN",10);
            UUID missing=order("missing","Unshipped","NEEDS_MAPPING",false,"LOST",1);
            UUID zero=order("zero-price","Unshipped","NEEDS_MAPPING",false,"LOST",1);
            order("positive","Unshipped","NEEDS_MAPPING",false,"LOST",1);
            jdbc.update("UPDATE amazon_order_items SET item_price=NULL WHERE id=?",missing);
            jdbc.update("UPDATE amazon_order_items SET item_price=0 WHERE id=?",zero);
            equivalent();
            assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isEqualTo(1);
        });
    }
    @Test void honorsLatestFbaSnapshotCurrencyAndMarketplace() {
        tx.executeWithoutResult(s->{fixture();listing("FBA","AFN",999);order("fba","Shipped","SHIPPED",false,"FBA",2);
            snapshot("FBA",20,"now()-interval '1 day'");snapshot("FBA",0,"now()");
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isZero();
            jdbc.update("UPDATE amazon_inventory_snapshots SET fulfillable_quantity=5 WHERE tenant_id=? AND snapshot_at=now()",tenant);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isEqualTo(1);
            jdbc.update("UPDATE amazon_listings SET buy_box_currency='EUR' WHERE tenant_id=?",tenant);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isZero();
            jdbc.update("UPDATE amazon_listings SET buy_box_currency='USD',marketplace_id='OTHER' WHERE tenant_id=?",tenant);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isZero();
        });
    }
    void snapshot(String sku,int qty,String time) {
        jdbc.update("INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,fulfillable_quantity,snapshot_at) VALUES (?,?,'US',?,?,"+time+")",tenant,connection,sku,qty);
    }
    UUID product() {
        UUID global=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("INSERT INTO global_catalog_products(id,canonical_name) VALUES (?,'Test')",global);
        jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,?)",item,tenant,global,item.toString());return item;
    }
    void stock(UUID item,int qty,int days) {
        jdbc.update("INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,entry_type,quantity,expiration_date,source_type,occurred_at,idempotency_key) VALUES (?,?,'ADJUSTMENT',?,current_date+?,'MANUAL',now(),?)",tenant,item,qty,days,UUID.randomUUID().toString());
    }
    @Test void bundleUsesFreshSellableStockReservationsAndReviewHolds() {
        tx.executeWithoutResult(s->{fixture();listing("BUNDLE","MFN",999);
            UUID a=product(),b=product(),mapping=UUID.randomUUID();stock(a,10,60);stock(b,1,60);stock(b,100,-1);
            jdbc.update("INSERT INTO marketplace_sku_mappings(id,tenant_id,marketplace_connection_id,marketplace_sku,account_catalog_item_id,quantity_per_marketplace_unit) VALUES (?,?,?,'BUNDLE',?,2)",mapping,tenant,connection,a);
            for(UUID p:List.of(a,b))jdbc.update("INSERT INTO marketplace_sku_mapping_components(tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity) VALUES (?,?,?,2)",tenant,mapping,p);
            UUID orderItem=order("bundle","Unshipped","READY_TO_SHIP",false,"BUNDLE",1);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isZero();
            stock(b,3,60);equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isEqualTo(1);
            jdbc.update("INSERT INTO order_inventory_reservations(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,account_catalog_item_id,quantity) VALUES (?,?,'bundle',?,?,4)",tenant,connection,orderItem,b);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isZero();
            jdbc.update("UPDATE order_inventory_reservations SET status='RELEASED' WHERE tenant_id=?",tenant);
            jdbc.update("INSERT INTO order_unrecorded_shipments(tenant_id,amazon_order_item_id,account_catalog_item_id,quantity) VALUES (?,?,?,1)",tenant,orderItem,b);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isZero();
            jdbc.update("UPDATE order_unrecorded_shipments SET reviewed_at=now() WHERE tenant_id=?",tenant);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isEqualTo(1);
            // Legacy mappings without component rows retain the same fallback behavior.
            jdbc.update("DELETE FROM marketplace_sku_mapping_components WHERE tenant_id=?",tenant);
            equivalent();assertThat(totals(OrderTabTotalsQuery.SQL,tenant,connection).get(12)).isEqualTo(1);
        });
    }
    @Test void repeatedSkuTotalsStayWithinOneSecondDatabaseBudget() {
        tx.executeWithoutResult(s->{fixture();listing("REPEAT","MFN",10);
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,purchase_date) SELECT ?,?,'US','load-'||n,'Unshipped',now() FROM generate_series(1,4500) n",tenant,connection);
            jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price) SELECT ?,?,'load-'||n,'load-'||n||'-'||p,'REPEAT',p,p*20 FROM generate_series(1,4500) n CROSS JOIN generate_series(1,2) p",tenant,connection);
            // Production has collected statistics; freshly bulk-loaded fixtures do not.
            for(String table:List.of("amazon_orders","amazon_order_items","amazon_listings","marketplace_sku_mappings"))jdbc.execute("ANALYZE "+table);
            long baselineStart=System.nanoTime();var baseline=totals(OrderTabsSqlExportTest.legacySql(),tenant,connection);
            System.out.println("Legacy order tab totals: "+((System.nanoTime()-baselineStart)/1_000_000)+" ms");
            long start=System.nanoTime();var tabs=new OrderRepository(jdbc).tabs(tenant,connection);
            long ms=(System.nanoTime()-start)/1_000_000;
            System.out.println("Order tab totals, 4500 orders / 9000 items: "+ms+" ms");
            assertThat(tabs.getFirst().orders()).isEqualTo(4500);assertThat(tabs.getFirst().units()).isEqualTo(13500);
            assertThat(tabs.getLast().orders()).isEqualTo(4500);assertThat(tabs.getLast().units()).isEqualTo(13500);
            assertThat(tabs.stream().flatMap(t->java.util.stream.Stream.of(t.orders(),t.units())).toList()).isEqualTo(baseline);
            assertThat(ms).as("database query budget, not a browser latency guarantee").isLessThan(1000);
        });
    }
}
