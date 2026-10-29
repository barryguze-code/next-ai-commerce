package com.nextaicommerce.platform.shopify;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.nextaicommerce.platform.marketplace.ShopifySkuRepository;
import java.time.*;
import static com.nextaicommerce.platform.shopify.ShopifyOrderLifecycle.Ownership;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.env.MockEnvironment;
import com.nextaicommerce.platform.shipping.FedExSandboxRates;
import tools.jackson.databind.ObjectMapper;

/** Existing canonical restricted DB; every synthetic row and ledger movement rolls back. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="shopifyOrderLocalFixtures",matches="true")
class LocalShopifyShippingUatDatabaseTest {
 static JdbcTemplate jdbc;static SingleConnectionDataSource restricted;static TransactionTemplate tx;
 UUID tenant,connection,otherConnection,item,variant,job,run,main,other,earlyLayer;String packingActor;
 java.time.Instant cutoff,created,revision;ShopifyOrderInventory inventory;ShopifyOrderRepository repo;
 @BeforeAll static void database()throws Exception{
  var p=new Properties();try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of(System.getProperty("shopifyLocalRuntime")))){p.load(in);}
  restricted=new SingleConnectionDataSource(java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_fee_readiness_20261024","nextcommerce_fee_preview",p.getProperty("spring.datasource.password")),true);jdbc=new JdbcTemplate(restricted);tx=new TransactionTemplate(new DataSourceTransactionManager(restricted));
  assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(122);assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
 }
 @AfterAll static void cleanup(){if(restricted!=null)restricted.destroy();}
    void scope(UUID t){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,t.toString());}
    void fixture(){
        tenant=UUID.randomUUID();connection=UUID.randomUUID();otherConnection=UUID.randomUUID();item=UUID.randomUUID();run=UUID.randomUUID();job=UUID.randomUUID();
        packingActor="packing-"+tenant+"@example.invalid";jdbc.update("INSERT INTO app_users(email,display_name,status) VALUES (?,'Packing fixture','ACTIVE')",packingActor);
        cutoff=Instant.now().minusSeconds(120);created=cutoff.plusSeconds(1);revision=created.plusSeconds(10);scope(tenant);
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Orders fixture')",tenant,tenant.toString());
        for(UUID c:List.of(connection,otherConnection)){String domain="fixture-"+c+".myshopify.com";
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'SHOPIFY',?,?,'ACTIVE','Fixture','fixture://none','UTC',now())",c,tenant,domain,domain);}
        UUID product=UUID.randomUUID();jdbc.update("INSERT INTO global_catalog_products(id,canonical_name,unit_of_measure) VALUES (?,'Fixture product','EA')",product);
        jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id,account_sku) VALUES (?,?,?,'000001')",item,tenant,product);
        main=jdbc.queryForObject("SELECT location_id FROM account_catalog_item_locations WHERE tenant_id=? AND account_catalog_item_id=? AND is_default",UUID.class,tenant,item);
        other=UUID.randomUUID();jdbc.update("INSERT INTO warehouse_locations(id,tenant_id,code,name) VALUES (?,?,'OTHER','Earlier expiry warehouse')",other,tenant);
        jdbc.update("INSERT INTO marketplace_sync_runs(id,tenant_id,marketplace_connection_id,run_type,sync_profile,status,window_start,window_end) VALUES(?,?,?,'INCREMENTAL','SHOPIFY_ORDERS','RUNNING',now()-interval '30 days',now())",run,tenant,connection);
        jdbc.update("INSERT INTO marketplace_sync_jobs(id,tenant_id,marketplace_connection_id,sync_run_id,job_type,sequence_number) VALUES(?,?,?,?,'SHOPIFY_ORDERS',1)",job,tenant,connection,run);
        var evidence=new ShopifyCatalogRepository(jdbc);
        var p=new ShopifyCatalogClient.Product("gid://shopify/Product/1","Product","Brand","ACTIVE",created,null,null);
        evidence.publish(tenant,connection,job,run,null,"{\"fixture\":\"catalogue\"}",new ShopifyCatalogClient.Page(List.of(new ShopifyCatalogClient.Variant("gid://shopify/ProductVariant/1","gid://shopify/InventoryItem/1","000001-EAx3","Default Title",BigDecimal.TEN,created,"[]","[]",true,null,p)),null,false,1,1000,100),"USD");
        variant=jdbc.queryForObject("SELECT id FROM shopify_variants WHERE tenant_id=? AND marketplace_connection_id=?",UUID.class,tenant,connection);
        String hash=jdbc.queryForObject("SELECT observation_hash FROM shopify_variants WHERE tenant_id=? AND id=?",String.class,tenant,variant);
        new ShopifySkuRepository(jdbc,true).save(tenant,connection,variant,hash,List.of(item.toString()),List.of("3"),()->{});
        jdbc.update("INSERT INTO shopify_order_controls(tenant_id,marketplace_connection_id,inventory_enabled,activated_at,baseline_completed_at) VALUES(?,?,true,?,?)",tenant,connection,java.sql.Timestamp.from(cutoff),java.sql.Timestamp.from(cutoff.minusSeconds(1)));
        earlyLayer=stock(other,5,40,cutoff.minusSeconds(100));stock(main,10,80,cutoff.minusSeconds(1000));
        inventory=new ShopifyOrderInventory(jdbc,true,true);repo=new ShopifyOrderRepository(jdbc,inventory,true);
    }
    UUID stock(UUID location,int units,int expiryDays,Instant received){
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO inventory_ledger_entries(id,tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key) VALUES(?,?,?,?,'RECEIPT',?,current_date+?,2,'USD','MANUAL',?,?,?)",id,tenant,item,location,units,expiryDays,UUID.randomUUID(),java.sql.Timestamp.from(received),id.toString());return id;
    }
    ShopifyOrderClient.Order order(int open,boolean cancel,String payment,List<ShopifyOrderLifecycle.Fulfillment> fs){
        var money=new ShopifyOrderClient.Money(BigDecimal.valueOf(30),"USD");
        return new ShopifyOrderClient.Order("gid://shopify/Order/1","#1001",created,revision,cancel?revision:null,false,payment,open==0?"FULFILLED":"UNFULFILLED",money,true,null,
            List.of(new ShopifyOrderClient.Line("gid://shopify/LineItem/1","gid://shopify/ProductVariant/1","000001-EAx3","Product",null,3,3,open,true,Ownership.MERCHANT,money,fs)));
    }
    void publish(ShopifyOrderClient.Order order){repo.publish(tenant,connection,job,null,"{\"fixture\":\"order-"+revision+"\"}",new ShopifyOrderClient.Page(List.of(order),null,false,100,1000,100));}
    ShopifyOrderLifecycle.Fulfillment shipment(int units){return new ShopifyOrderLifecycle.Fulfillment("gid://shopify/Fulfillment/1","gid://shopify/FulfillmentLineItem/1",units,"SUCCESS",Ownership.MERCHANT,revision);}
    BigDecimal stock(){return jdbc.queryForObject("SELECT sum(quantity) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?",BigDecimal.class,tenant,item);}
    BigDecimal active(){return jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations WHERE tenant_id=? AND status='ACTIVE'",BigDecimal.class,tenant);}
    void rolled(Runnable action){tx.executeWithoutResult(s->{s.setRollbackOnly();fixture();action.run();});}
    UUID localOrder(){return jdbc.queryForObject("SELECT id FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=?",UUID.class,tenant,connection);}
    ShopifyOrderPacking packing(){return new ShopifyOrderPacking(jdbc,inventory);}
 LocalShopifyShippingUat cycles(){var rates=mock(FedExSandboxRates.class);when(rates.account()).thenReturn("740561073");return new LocalShopifyShippingUat(jdbc,inventory,new ObjectMapper(),rates,true,new MockEnvironment());}
 @Test void explicitHistoricalCyclesReserveRealLotsConsumeOncePreserveHistoryAndRestoreProjection(){rolled(()->{
  created=cutoff.minusSeconds(86400);revision=created.plusSeconds(10);publish(order(0,false,"PAID",List.of()));UUID id=localOrder();BigDecimal before=stock();String originalSource=jdbc.queryForObject("SELECT source_document_id::text||':'||source_updated_at::text FROM shopify_orders WHERE tenant_id=? AND id=?",String.class,tenant,id);
  var uat=cycles();var pack=packing();ReflectionTestUtils.setField(pack,"uat",uat);UUID first=uat.beginRound(tenant,connection,id,packingActor);assertThat(uat.beginRound(tenant,connection,id,packingActor)).isEqualTo(first);assertThat(stock()).isEqualByComparingTo(before);assertThat(active()).isEqualByComparingTo("9");
  var context=new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,mock(com.nextaicommerce.platform.shipping.BuyShippingService.class),true,true);var frozen=context.accountPacking(tenant,connection,id.toString());
  assertThat(frozen).allSatisfy(line->{assertThat(line.sku()).isEqualTo("000001");assertThat(line.format()).isEqualTo("ACCOUNT_ITEM_V2");assertThat(line.allocations()).hasSize(1);});
  assertThat(frozen.stream().map(com.nextaicommerce.platform.shipping.CarrierLabelComposer.Line::mappedQuantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("9");
  assertThatThrownBy(()->context.accountPacking(tenant,otherConnection,id.toString())).isInstanceOf(IllegalArgumentException.class);

  assertThatThrownBy(()->pack.mark(tenant,connection,id,true,packingActor,()->{throw new IllegalStateException("Packing changed");})).hasMessageContaining("Packing changed");assertThat(stock()).isEqualByComparingTo(before);
  assertThat(uat.cycle(tenant,otherConnection,id)).isNull();assertThat(pack.eligibility(tenant,connection,id)).isNull();pack.mark(tenant,connection,id,true,packingActor);BigDecimal once=stock();assertThat(once).isEqualByComparingTo(before.subtract(new BigDecimal("9")));long movements=jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant);
  pack.mark(tenant,connection,id,true,packingActor);assertThat(stock()).isEqualByComparingTo(once);assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant)).isEqualTo(movements);
  UUID second=uat.beginRound(tenant,connection,id,packingActor);assertThat(second).isNotEqualTo(first);assertThat(stock()).isEqualByComparingTo(once);assertThat(pack.eligibility(tenant,connection,id)).isNotNull();assertThatThrownBy(()->pack.mark(tenant,connection,id,true,packingActor)).isInstanceOf(IllegalArgumentException.class);assertThat(stock()).isEqualByComparingTo(once);
  stock(main,9,30,java.time.Instant.now());uat.refresh(tenant,connection,id);pack.mark(tenant,connection,id,true,packingActor);BigDecimal twice=stock();assertThat(twice).isEqualByComparingTo(once);pack.mark(tenant,connection,id,true,packingActor);assertThat(stock()).isEqualByComparingTo(twice);
  uat.closeRound(tenant,connection,id,packingActor);assertThat(uat.cycle(tenant,connection,id)).isNull();assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM shopify_orders WHERE tenant_id=? AND id=?",String.class,tenant,id)).isEqualTo("FULFILLED");assertThat(jdbc.queryForObject("SELECT stock_scope FROM shopify_order_lines WHERE tenant_id=?",String.class,tenant)).isEqualTo("REPORTING_ONLY");assertThat(stock()).isEqualByComparingTo(once);assertThat(jdbc.queryForObject("SELECT source_document_id::text||':'||source_updated_at::text FROM shopify_orders WHERE tenant_id=? AND id=?",String.class,tenant,id)).isEqualTo(originalSource);
 });}
 @Test void unresolvedCarrierOrPendingHandoffBlocksResetAndPreservesCycleAndStock(){rolled(()->{created=cutoff.minusSeconds(86400);revision=created.plusSeconds(10);publish(order(0,false,"PAID",List.of()));UUID id=localOrder();var uat=cycles();UUID cycle=uat.beginRound(tenant,connection,id,packingActor);BigDecimal before=stock();UUID carrier=UUID.randomUUID(),shipment=UUID.randomUUID();jdbc.update("INSERT INTO carrier_connections(id,tenant_id,display_name,account_number,environment,credential_source) VALUES(?,?,'TEST','740561073','SANDBOX','PLATFORM')",carrier,tenant);jdbc.update("INSERT INTO carrier_shipments(id,tenant_id,marketplace_connection_id,carrier_connection_id,carrier_revision,amazon_order_id,environment,state,request_payload,request_nonce,rates,packing_lines,actor) VALUES(?,?,?,?,1,?,'SANDBOX','UNKNOWN',?,?,'[]','[]',?)",shipment,tenant,connection,carrier,LocalShopifyShippingUat.shipmentKey(id,cycle),new byte[16],new byte[12],packingActor);assertThatThrownBy(()->uat.beginRound(tenant,connection,id,packingActor)).isInstanceOf(IllegalStateException.class);jdbc.update("UPDATE carrier_shipments SET state='PURCHASED',error_message='LOCAL_HANDOFF_PENDING' WHERE tenant_id=? AND id=?",tenant,shipment);assertThatThrownBy(()->uat.beginRound(tenant,connection,id,packingActor)).isInstanceOf(IllegalStateException.class);assertThat(uat.cycle(tenant,connection,id)).isEqualTo(cycle);assertThat(stock()).isEqualByComparingTo(before);});}
 @Test void quotedPackingSurvivesActualJsonbArchiveAndReservationRefreshBeforeSandboxShip(){rolled(()->{
  created=cutoff.minusSeconds(86400);revision=created.plusSeconds(10);publish(order(0,false,"PAID",List.of()));UUID id=localOrder();var uat=cycles();uat.beginRound(tenant,connection,id,packingActor);var pack=packing();ReflectionTestUtils.setField(pack,"uat",uat);BigDecimal before=stock();
  var json=new ObjectMapper();var provider=mock(com.nextaicommerce.platform.shipping.FedExSandboxRates.class);when(provider.account()).thenReturn("740561073");
  when(provider.quote(any(),any(),any(),any(),any())).thenReturn(new com.nextaicommerce.platform.shipping.FedExSandboxRates.Quote(List.of(new com.nextaicommerce.platform.shipping.FedExPayloads.Rate("FEDEX_GROUND","Ground",BigDecimal.TEN,"USD")),true,"SANDBOX",""));
  when(provider.ship(any(),any(),any())).thenReturn(new com.nextaicommerce.platform.shipping.FedExPayloads.Label("000000000000",new byte[]{1,2,3}));
  var context=new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,mock(com.nextaicommerce.platform.shipping.BuyShippingService.class),true,true);
  var addresses=new com.nextaicommerce.platform.shipping.BuyShippingRepository(jdbc,json);var sender=addresses.saveAddress(tenant,connection,new com.nextaicommerce.platform.shipping.BuyShippingRepository.Address(null,"Fixture sender","Fixture",null,"Test Street",null,null,"Test City","TN","38103","US","9015550100",null,true));
  var shipments=new com.nextaicommerce.platform.shipping.FedExShipmentRepository(jdbc,json);var service=new com.nextaicommerce.platform.shipping.LocalFedExShippingUat(uat,provider,new com.nextaicommerce.platform.shipping.CarrierConnectionRepository(jdbc),shipments,context,pack,new com.nextaicommerce.platform.shipping.ShippingLabelCrypto(Base64.getEncoder().encodeToString(new byte[32])),new com.nextaicommerce.platform.shipping.CarrierLabelComposer(),json);
  var parcel=new com.nextaicommerce.platform.shipping.FedExPayloads.Parcel(BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.ONE);
  var recipient=new com.nextaicommerce.platform.shipping.FedExPayloads.Address("Fixture Recipient","9015550100","Test Street","Test City","TN","38103","US",false);
  var quote=service.quote(tenant,connection,id.toString(),new com.nextaicommerce.platform.shipping.FedExShippingService.RateOnlyInput(sender.id(),parcel,recipient),packingActor);
  service.purchase(tenant,connection,id.toString(),quote.shipmentId(),"FEDEX_GROUND",packingActor);
  assertThat(stock()).isEqualByComparingTo(before.subtract(new BigDecimal("9")));service.purchase(tenant,connection,id.toString(),quote.shipmentId(),"FEDEX_GROUND",packingActor);assertThat(stock()).isEqualByComparingTo(before.subtract(new BigDecimal("9")));verify(provider,times(1)).ship(any(),any(),any());
 });}
 @Test void optionalAccountSkuUsesOnlyUnambiguousCurrentOwnVendorCodeWithoutChangingStock(){rolled(()->{
  created=cutoff.minusSeconds(86400);revision=created.plusSeconds(10);publish(order(0,false,"PAID",List.of()));UUID id=localOrder();cycles().beginRound(tenant,connection,id,packingActor);
  var context=new com.nextaicommerce.platform.shipping.CarrierOrderContext(jdbc,mock(com.nextaicommerce.platform.shipping.BuyShippingService.class),true,true);
  BigDecimal before=stock();long movements=jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant);var locations=context.accountPacking(tenant,connection,id.toString()).stream().map(l->l.allocations().getFirst().location()).toList();
  UUID vendor=UUID.randomUUID();jdbc.update("INSERT INTO vendors(id,tenant_id,name) VALUES (?,?,'Own vendor')",vendor,tenant);
  jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency,is_default) VALUES (?,?,?,'000462150',2,'USD',true)",tenant,vendor,item);
  jdbc.update("UPDATE account_catalog_items SET account_sku=NULL WHERE tenant_id=? AND id=?",tenant,item);
  var rows=context.accountPacking(tenant,connection,id.toString());assertThat(rows).hasSize(locations.size()).allSatisfy(l->assertThat(l.sku()).isEqualTo("000462150"));assertThat(rows.stream().map(l->l.allocations().getFirst().location()).toList()).isEqualTo(locations);assertThat(rows.stream().map(com.nextaicommerce.platform.shipping.CarrierLabelComposer.Line::mappedQuantity).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("9");
  jdbc.update("UPDATE account_catalog_items SET account_sku='EXPLICIT' WHERE tenant_id=? AND id=?",tenant,item);assertThat(context.accountPacking(tenant,connection,id.toString())).allSatisfy(l->assertThat(l.sku()).isEqualTo("EXPLICIT"));jdbc.update("UPDATE account_catalog_items SET account_sku=NULL WHERE tenant_id=? AND id=?",tenant,item);
  UUID second=UUID.randomUUID();jdbc.update("INSERT INTO vendors(id,tenant_id,name) VALUES (?,?,'Other own vendor')",second,tenant);jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency) VALUES (?,?,?,'DIFFERENT',2,'USD')",tenant,second,item);
  assertThat(context.accountPacking(tenant,connection,id.toString())).allSatisfy(l->{assertThat(l.sku()).isEqualTo("N/A");assertThat(l.accountItemId()).isEqualTo(item);assertThat(l.locationId()).isNotNull();});jdbc.update("UPDATE vendors SET status='INACTIVE' WHERE tenant_id=? AND id=?",tenant,second);assertThat(context.accountPacking(tenant,connection,id.toString())).allSatisfy(l->assertThat(l.sku()).isEqualTo("000462150"));
  jdbc.update("UPDATE vendor_catalog_offers SET effective_from=current_date+1 WHERE tenant_id=? AND vendor_id=?",tenant,vendor);assertThat(context.accountPacking(tenant,connection,id.toString())).allSatisfy(l->{assertThat(l.sku()).isEqualTo("N/A");assertThat(l.accountItemId()).isEqualTo(item);assertThat(l.locationId()).isNotNull();});
  UUID foreignTenant=UUID.randomUUID(),foreignItem=UUID.randomUUID(),foreignVendor=UUID.randomUUID();UUID global=jdbc.queryForObject("SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?",UUID.class,tenant,item);scope(foreignTenant);
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Foreign fixture')",foreignTenant,foreignTenant.toString());jdbc.update("INSERT INTO account_catalog_items(id,tenant_id,global_product_id) VALUES (?,?,?)",foreignItem,foreignTenant,global);jdbc.update("INSERT INTO vendors(id,tenant_id,name) VALUES (?,?,'Foreign vendor')",foreignVendor,foreignTenant);jdbc.update("INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,currency) VALUES (?,?,?,'FOREIGN-CODE',2,'USD')",foreignTenant,foreignVendor,foreignItem);scope(tenant);
  assertThat(context.accountPacking(tenant,connection,id.toString())).allSatisfy(l->{assertThat(l.sku()).isEqualTo("N/A");assertThat(l.accountItemId()).isEqualTo(item);assertThat(l.locationId()).isNotNull();});assertThatThrownBy(()->context.accountPacking(tenant,otherConnection,id.toString())).isInstanceOf(IllegalArgumentException.class);
  assertThat(stock()).isEqualByComparingTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE tenant_id=?",Long.class,tenant)).isEqualTo(movements);
 });}

}
