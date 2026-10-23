package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class CarrierDatabaseTest {
    @Test void packagingPersistsPerOrderAndExactCompositionWithTenantIsolation(){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,fulfillment_channel) VALUES (?,?,'US','PACKAGE','Unshipped','MFN')",tenant,store);
        var parcel=FedExPackaging.normalize(new FedExPayloads.Parcel(null,null,null,java.math.BigDecimal.TEN,"MEDIUM_M2"));
        shipments.orderPackage(tenant,store,"PACKAGE","composition",parcel);
        assertThat(shipments.orderPackage(tenant,store,"PACKAGE","composition")).isEqualTo(parcel);
        assertThat(shipments.orderPackage(other,store,"PACKAGE","composition")).isNull();
        assertThat(shipments.orderPackage(tenant,store,"PACKAGE","changed")).isNull();
        shipments.dimensions(tenant,store,"composition",parcel);
        assertThat(shipments.dimensions(tenant,store,"composition")).isEqualTo(parcel);
        assertThat(shipments.dimensions(other,store,"composition")).isNull();
        assertThat(jdbc.queryForObject("SELECT relforcerowsecurity FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname='fedex_order_packaging'",Boolean.class,schema)).isTrue();
    });}
    @Test void queueRequiresExplicitSaveAndMatchingQuantity(){tx.executeWithoutResult(s->{
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,fulfillment_channel) VALUES (?,?,'US','ORDER','Unshipped','MFN')",tenant,store);
        jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered) VALUES (?,?,'ORDER','ITEM','SKU',2)",tenant,store);
        var buy=new BuyShippingRepository(jdbc,json);var desk=new ShippingDeskRepository(jdbc,json);
        var n=java.math.BigDecimal.TEN;
        UUID profile=buy.saveProfile(tenant,store,new BuyShippingRepository.PackageProfile(null,"Box",null,n,n,n,"inches",n,"oz",null,"AMBIENT"),List.of("SKU"));
        assertThat(desk.candidates(tenant,store)).isEmpty();
        buy.readyPackage(tenant,store,"ORDER",profile,"ITEM:2");
        assertThat(desk.candidates(tenant,store)).hasSize(1);
        jdbc.update("UPDATE amazon_order_items SET quantity_ordered=3 WHERE tenant_id=? AND marketplace_connection_id=?",tenant,store);
        assertThat(desk.candidates(tenant,store)).isEmpty();
    });}
    @Test void preferencesAreStoreScopedAndQueueQueryRuns(){tx.executeWithoutResult(s->{
        var preferences=new ShippingPreferences(jdbc);
        assertThat(preferences.get(tenant,store).layout()).isEqualTo(CarrierLabelComposer.Layout.ORIGINAL);
        var selected=new ShippingPreferences.Settings("THERMAL_4X6",CarrierLabelComposer.Layout.COMBINED_80_20);
        assertThat(preferences.save(tenant,store,selected)).isEqualTo(selected);
        assertThat(preferences.get(tenant,store)).isEqualTo(selected);
        assertThatThrownBy(()->preferences.get(other,store)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ShippingDeskRepository(jdbc,json).candidates(tenant,store)).isEmpty();
    });}
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    UUID tenant,other,store,carrier;CarrierConnectionRepository connections;FedExShipmentRepository shipments;
    final ObjectMapper json=new ObjectMapper();
    @BeforeAll static void setup()throws Exception {
        schema="carrier_test_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();}
        else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        org.flywaydb.core.Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterAll static void cleanup()throws Exception{if(jdbc!=null&&schema.matches("carrier_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(postgres!=null)postgres.close();}
    @BeforeEach void fixture(){tenant=UUID.randomUUID();other=UUID.randomUUID();store=UUID.randomUUID();connections=new CarrierConnectionRepository(jdbc);shipments=new FedExShipmentRepository(jdbc,json);tx.executeWithoutResult(s->{for(UUID id:List.of(tenant,other))jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Carrier test')",id,id.toString());jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON','test','ATVPDKIKX0DER','test','ACTIVE','Test','America/Los_Angeles',now())",store,tenant);carrier=connections.save(tenant,null,"FedEx test","123456789","SANDBOX","PLATFORM",null);});}
    @Test void nonBypassRoleEnforcesRowsAndStoreOwnership() {
        String role="carrier_reader_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
            jdbc.execute("GRANT SELECT,INSERT ON carrier_connections TO "+role);
            jdbc.execute("GRANT SELECT ON marketplace_connections,shipping_print_preferences TO "+role);
            tx.executeWithoutResult(status->{
                jdbc.execute("SET LOCAL ROLE "+role);
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM carrier_connections",Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
                assertThat(jdbc.queryForObject("SELECT count(*) FROM carrier_connections",Integer.class)).isZero();
                assertThatThrownBy(()->new ShippingPreferences(jdbc).get(other,store)).isInstanceOf(IllegalArgumentException.class);
            });
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                jdbc.execute("SET LOCAL ROLE "+role);
                jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
                jdbc.update("INSERT INTO carrier_connections(id,tenant_id,display_name,account_number,environment,credential_source) VALUES (?,?,'Denied','123456789','SANDBOX','PLATFORM')",UUID.randomUUID(),tenant);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("REVOKE ALL ON carrier_connections,marketplace_connections,shipping_print_preferences FROM "+role);
            jdbc.execute("REVOKE USAGE ON SCHEMA "+schema+" FROM "+role);jdbc.execute("DROP ROLE "+role);
        }
    }
    @Test void accountCannotReadEditOrDeleteAnotherAccountsConnection(){tx.executeWithoutResult(s->{assertThat(connections.list(other)).isEmpty();assertThatThrownBy(()->connections.find(other,carrier)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->connections.delete(other,carrier)).isInstanceOf(IllegalArgumentException.class);assertThat(connections.list(tenant)).hasSize(1);});}
    @Test void rotationInvalidatesOldHealthResultAndDeletionErasesSecrets(){tx.executeWithoutResult(s->{connections.save(tenant,carrier,"FedEx test","123456789","SANDBOX","MERCHANT",new ShippingLabelCrypto.Encrypted(new byte[]{1,2},new byte[12]));connections.health(tenant,carrier,1,"ACTIVE","Old response");assertThat(connections.find(tenant,carrier).healthStatus()).isEqualTo("PENDING");connections.delete(tenant,carrier);assertThat(connections.list(tenant)).isEmpty();assertThat(jdbc.queryForObject("SELECT credential_payload IS NULL FROM carrier_connections WHERE id=?",Boolean.class,carrier)).isTrue();});}
    @Test void quoteCanBeClaimedOnlyOnceAndOnlyByMatchingStoreAndService(){tx.executeWithoutResult(s->{var c=connections.find(tenant,carrier);UUID id=shipments.quote(tenant,store,"ORDER",c,new ShippingLabelCrypto.Encrypted(new byte[]{1},new byte[12]),List.of(new FedExPayloads.Rate("FEDEX_GROUND","Ground",java.math.BigDecimal.TEN,"USD")),List.of(),"tester");assertThat(shipments.claim(other,store,"ORDER",id,"FEDEX_GROUND",1)).isNull();assertThat(shipments.claim(tenant,UUID.randomUUID(),"ORDER",id,"FEDEX_GROUND",1)).isNull();assertThat(shipments.claim(tenant,store,"ORDER",id,"EXPRESS",1)).isNull();assertThat(shipments.claim(tenant,store,"ORDER",id,"FEDEX_GROUND",1)).isNotNull();assertThat(shipments.claim(tenant,store,"ORDER",id,"FEDEX_GROUND",1)).isNull();shipments.failure(tenant,id,true);assertThat(shipments.list(tenant,store,"ORDER").getFirst().state()).isEqualTo("UNKNOWN");assertThat(shipments.claim(tenant,store,"ORDER",id,"FEDEX_GROUND",1)).isNull();});}
    @Test void expiredRatesAndChangedConnectionsCannotBePurchased(){tx.executeWithoutResult(s->{var c=connections.find(tenant,carrier);UUID id=shipments.quote(tenant,store,"ORDER",c,new ShippingLabelCrypto.Encrypted(new byte[]{1},new byte[12]),List.of(new FedExPayloads.Rate("FEDEX_GROUND","Ground",java.math.BigDecimal.TEN,"USD")),List.of(),"tester");jdbc.update("UPDATE carrier_shipments SET expires_at=now()-interval '1 minute' WHERE id=?",id);assertThat(shipments.claim(tenant,store,"ORDER",id,"FEDEX_GROUND",1)).isNull();});}
    @Test void packingUsesCatalogueQueryAndAllTablesHaveForcedTenantPolicies(){tx.executeWithoutResult(s->{assertThat(shipments.packing(tenant,store,"MISSING")).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname IN ('carrier_connections','carrier_shipments','carrier_package_defaults') AND c.relrowsecurity AND c.relforcerowsecurity",Integer.class,schema)).isEqualTo(3);});}
}
