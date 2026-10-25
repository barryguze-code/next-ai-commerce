package com.nextaicommerce.platform.fees;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
class AmazonFeeEvidenceDatabaseTest {
 static String schema;static JdbcTemplate jdbc;static TransactionTemplate tx;static io.zonky.test.db.postgres.embedded.EmbeddedPostgres embedded;
 final ObjectMapper json=new ObjectMapper(); UUID tenant,store,source; AmazonFeeEvidence evidence;
 final String market="ATVPDKIKX0DER",sku="00001 / Exact",seller="SYNTHETIC";
 final Instant time=Instant.parse("2026-10-20T10:00:00Z");
 @BeforeAll static void db()throws Exception {
  schema="fee_evidence_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
  if(Boolean.getBoolean("localTestDatabase")){url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";password=Files.readString(Path.of(".local/database-password")).trim();}
  else{embedded=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=embedded.getJdbcUrl("postgres","postgres");}
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
  org.flywaydb.core.Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
  jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
 }
 @AfterAll static void close()throws Exception {if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");if(embedded!=null)embedded.close();}
 @BeforeEach void setup(){
  tenant=UUID.randomUUID();store=UUID.randomUUID();source=UUID.randomUUID();
  jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Synthetic fees')",tenant,tenant.toString());
  jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,?,'ACTIVE','Synthetic','fixture','America/Los_Angeles',now())",store,tenant,seller,market);
  jdbc.update("INSERT INTO amazon_source_documents(id,tenant_id,marketplace_connection_id,source_type,source_key,content_type,sha256,payload) VALUES (?,?,?,'FINANCES_30_DAY','synthetic','application/json',?,'{}')",source,tenant,store,source.toString());
  evidence=new AmazonFeeEvidence(jdbc,json,true);
 }
 tools.jackson.databind.JsonNode basis(){return json.readTree("""
  {"MarketplaceId":"ATVPDKIKX0DER","IsAmazonFulfilled":true,"_platformSellerId":"SYNTHETIC","_platformShippingApproved":true,
  "PriceToEstimateFees":{"ListingPrice":{"CurrencyCode":"USD","Amount":30},"Shipping":{"CurrencyCode":"USD","Amount":0}}}
  """);}
 void quote(Instant at,String fee){tx.executeWithoutResult(s->evidence.quote(tenant,store,market,sku,source,at.toString(),basis(),new BigDecimal("3"),new BigDecimal(fee),at));}
 void order(Instant purchase){
  order(purchase,new BigDecimal("60"),BigDecimal.ZERO);
 }
 void order(Instant purchase,BigDecimal amount,BigDecimal shipping){
  jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,currency,fulfillment_channel) VALUES (?,?,?,'ORDER',?,'USD','AFN')",tenant,store,market,Timestamp.from(purchase));
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,quantity_shipped,item_price,shipping_price,item_tax,shipping_tax,promotion_discount,shipping_discount,currency) VALUES (?,?,'ORDER',?, ?,2,2,?,?,0,0,0,0,'USD')",tenant,store,UUID.randomUUID().toString(),sku,amount,shipping);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
 }
 tools.jackson.databind.JsonNode payload(String amount,String type,String status){return json.readTree("""
  {"payload":{"transactions":[{"transactionId":"TX","transactionType":"%s","transactionStatus":"%s","postedDate":"2026-10-24T12:00:00Z",
   "sellingPartnerMetadata":{"sellingPartnerId":"SYNTHETIC","marketplaceId":"ATVPDKIKX0DER"},"relatedIdentifiers":[{"relatedIdentifierName":"ORDER_ID","relatedIdentifierValue":"ORDER"}],
   "items":[{"contexts":[{"contextType":"ProductContext","sku":"00001 / Exact","quantityShipped":2,"fulfillmentNetwork":"AFN"}],
   "breakdowns":[{"breakdownType":"FBAPerUnitFulfillmentFee","breakdownAmount":{"currencyCode":"USD","currencyAmount":%s}},{"breakdownType":"ProductCharges","breakdownAmount":{"currencyCode":"USD","currencyAmount":60},"breakdowns":[{"breakdownType":"OurPricePrincipal","breakdownAmount":{"currencyCode":"USD","currencyAmount":60}}]}]}]}]}}
  """.formatted(type,status,amount));}
 void actual(String amount){tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,payload(amount,"Shipment","RELEASED")));}
 String state(){return jdbc.queryForObject("SELECT state FROM amazon_fee_comparisons WHERE tenant_id=? AND fee_type='FBA' ORDER BY revision_seq DESC LIMIT 1",String.class,tenant);}
 long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant);}
 @Test void emptyStorageAndOffDoNotQueryOrWrite(){new AmazonFeeEvidence(jdbc,json,false).bindOrder(UUID.randomUUID(),UUID.randomUUID(),"OTHER","NONE");assertThat(count("amazon_fee_observations")).isZero();}
 @Test void readOnlyUiWithEmptyStorageCannotCollectApproveOrReprice(){
  var off=new AmazonFeeEvidence(jdbc,json,false);var repo=new AmazonFeeVarianceRepository(jdbc,off,true,false);
  tx.executeWithoutResult(t->{off.quote(tenant,store,market,sku,source,"disabled",basis(),BigDecimal.ONE,BigDecimal.ONE,time);off.bindOrder(tenant,store,market,"ORDER");off.finances(tenant,store,market,source,payload("-12","Shipment","RELEASED"));});
  assertThat(repo.visible()).isTrue();assertThat(repo.approvalsEnabled()).isFalse();assertThat(repo.prospectiveEnabled()).isFalse();
  assertThat(tx.execute(t->repo.historyPage(tenant,store,"ORDER",sku,0,null)).rows()).isEmpty();
  Long affected=tx.execute(t->repo.count(tenant,store,false));assertThat(affected).isZero();
  assertThatThrownBy(()->tx.execute(t->repo.approveAll(tenant,store,List.of(new AmazonFeeVarianceRepository.Choice(UUID.randomUUID(),null)),UUID.randomUUID(),"owner"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not enabled");
  for(String table:List.of("amazon_fee_observations","amazon_order_fee_charge_observations","amazon_fee_comparisons","amazon_fee_approvals"))assertThat(count(table)).isZero();
 }
 @Test void orderWithoutEvidenceRemainsUnresolved(){order(time);assertThat(state()).isEqualTo("NOT_COMPARABLE");actual("-12");assertThat(state()).isEqualTo("NOT_COMPARABLE");}
 @Test void frozenBeforeActualAndHistoricalVariancePreserved(){quote(time,"5");order(time.plusSeconds(1));assertThat(state()).isEqualTo("AWAITING");quote(time.plusSeconds(10),"6");actual("-12");assertThat(state()).isEqualTo("HIGHER");assertThat(jdbc.queryForObject("SELECT expected_amount FROM amazon_fee_comparisons WHERE tenant_id=? AND fee_type='FBA' AND revision_key='BASIS'",BigDecimal.class,tenant)).isEqualByComparingTo("10");}
 @Test void unchangedEconomicEvidenceHasSameRevisionAcrossJvmTimezones(){
  quote(time,"5");order(time.plusSeconds(1));actual("-12");long revisions=count("amazon_fee_comparisons");var previous=TimeZone.getDefault();
  try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));tx.executeWithoutResult(t->evidence.compareOrder(tenant,store,market,"ORDER"));assertThat(count("amazon_fee_comparisons")).isEqualTo(revisions);}
  finally{TimeZone.setDefault(previous);}
 }
 @Test void repeatReceiptDoesNotDuplicateExpense(){quote(time,"5");order(time.plusSeconds(1));actual("-12");long charges=count("amazon_order_fee_charge_observations"),comparisons=count("amazon_fee_comparisons");actual("-12");assertThat(count("amazon_order_fee_charge_observations")).isEqualTo(charges);assertThat(count("amazon_fee_comparisons")).isEqualTo(comparisons);}
 @Test void conflictingReleasedContentNeverUsesReceiptOrderAsCorrectionAuthority(){
  quote(time,"5");order(time.plusSeconds(1));actual("-12");assertThat(state()).isEqualTo("HIGHER");
  actual("-14");assertThat(state()).isEqualTo("NOT_COMPARABLE");
  actual("-12");assertThat(state()).isEqualTo("NOT_COMPARABLE");
  assertThat(count("amazon_order_fee_charge_observations")).isEqualTo(2);
  long revisions=count("amazon_fee_comparisons");actual("-12");assertThat(count("amazon_fee_comparisons")).isEqualTo(revisions);
 }
 @Test void staleDeferredReceiptDoesNotRegressAnIdenticalReleasedEconomicState(){
  quote(time,"5");order(time.plusSeconds(1));actual("-12");
  var stale=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","DEFERRED");
  ((tools.jackson.databind.node.ObjectNode)stale.path("payload").path("transactions").get(0)).put("description","Repeated older deferral observation");
  tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,stale));
  assertThat(state()).isEqualTo("HIGHER");
 }
 @Test void chargeObservationRetainsArchiveReceiptTimeRatherThanReprocessingTime(){
  actual("-12");
  assertThat(jdbc.queryForObject("SELECT observed_at FROM amazon_order_fee_charge_observations WHERE tenant_id=? LIMIT 1",Timestamp.class,tenant))
      .isEqualTo(jdbc.queryForObject("SELECT received_at FROM amazon_source_documents WHERE tenant_id=? AND id=?",Timestamp.class,tenant,source));
 }
 @Test void incompleteOrDifferentFinancialPrincipalCannotUseTheOrderPriceAsAssumedBase(){
  quote(time,"5");order(time.plusSeconds(1));
  var root=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");
  var nodes=(tools.jackson.databind.node.ArrayNode)root.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns");
  nodes.remove(1);tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));
  assertThat(state()).isEqualTo("NOT_COMPARABLE");
 }
 @Test void financialPrincipalMustMatchTheCompleteOrderItemAmount(){
  quote(time,"5");order(time.plusSeconds(1));
  var root=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");
  var principal=(tools.jackson.databind.node.ObjectNode)root.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns").get(1);
  ((tools.jackson.databind.node.ObjectNode)principal.path("breakdownAmount")).put("currencyAmount",59);
  ((tools.jackson.databind.node.ObjectNode)principal.path("breakdowns").get(0).path("breakdownAmount")).put("currencyAmount",59);
  tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));assertThat(state()).isEqualTo("NOT_COMPARABLE");
 }
 @Test void duplicateShippingCannotUseLastZeroToHideACharge(){
  quote(time,"5");order(time.plusSeconds(1));var root=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");
  var nodes=(tools.jackson.databind.node.ArrayNode)root.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns");
  for(int amount:List.of(4,0)){var shipping=nodes.addObject().put("breakdownType","Shipping");shipping.putObject("breakdownAmount").put("currencyCode","USD").put("currencyAmount",amount);shipping.putArray("breakdowns").addObject().put("breakdownType","ShippingPrincipal").putObject("breakdownAmount").put("currencyCode","USD").put("currencyAmount",amount);}
  tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));assertThat(state()).isEqualTo("NOT_COMPARABLE");
 }
 @Test void missingShippingDiscountIsNotAnExplicitZero(){
  quote(time,"5");order(time.plusSeconds(1));jdbc.update("UPDATE amazon_order_items SET shipping_discount=NULL WHERE tenant_id=?",tenant);actual("-12");assertThat(state()).isEqualTo("NOT_COMPARABLE");
 }
 @Test void duplicatePrincipalCannotBeChosenAsAnExactBase(){
  quote(time,"5");order(time.plusSeconds(1));var root=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");
  var children=(tools.jackson.databind.node.ArrayNode)root.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns").get(1).path("breakdowns");children.add(children.get(0).deepCopy());
  tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));assertThat(state()).isEqualTo("NOT_COMPARABLE");
 }
 @Test void unchangedQuoteDoesNotResetBoundary(){quote(time,"5");quote(time.plusSeconds(10),"5");assertThat(jdbc.queryForList("SELECT DISTINCT boundary_at FROM amazon_fee_observations WHERE tenant_id=?",Timestamp.class,tenant)).containsExactly(Timestamp.from(time));}
 @Test void sourceAndEvidenceAreAppendOnly(){quote(time,"5");assertThatThrownBy(()->jdbc.update("UPDATE amazon_fee_observations SET amount=99 WHERE tenant_id=?",tenant)).hasMessageContaining("append-only");assertThat(count("amazon_fee_observations")).isEqualTo(2);}
 @Test void tenantStoreMarketAndSellerCannotContaminate(){assertThatThrownBy(()->tx.executeWithoutResult(s->evidence.bindOrder(UUID.randomUUID(),store,market,"ORDER"))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->tx.executeWithoutResult(s->evidence.bindOrder(tenant,UUID.randomUUID(),market,"ORDER"))).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,"OTHER","ORDER"))).isInstanceOf(RuntimeException.class);
  var b=(tools.jackson.databind.node.ObjectNode)basis();b.put("_platformSellerId","OTHER");assertThatThrownBy(()->tx.executeWithoutResult(s->evidence.quote(tenant,store,market,sku,source,"bad",b,BigDecimal.ONE,BigDecimal.ONE,time))).isInstanceOf(IllegalArgumentException.class);}
 @Test void noPreBoundaryComparison(){quote(time,"5");order(time);actual("-12");assertThat(state()).isEqualTo("NOT_COMPARABLE");}
 @Test void partialShipmentAndRefundNeverBecomeFeeApproval(){quote(time,"5");order(time.plusSeconds(1));tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,payload("6","Refund","RELEASED")));assertThat(state()).isEqualTo("NOT_COMPARABLE");assertThat(count("amazon_fee_approvals")).isZero();}
 @Test void noInventoryMappingOrSourceMutation(){quote(time,"5");order(time.plusSeconds(1));var before=jdbc.queryForMap("SELECT * FROM amazon_source_documents WHERE id=?",source);actual("-12");assertThat(jdbc.queryForMap("SELECT * FROM amazon_source_documents WHERE id=?",source)).isEqualTo(before);assertThat(count("amazon_inventory_snapshots")).isZero();assertThat(count("marketplace_sku_mappings")).isZero();}
 @Test void correctedOrderContextInvalidatesComparison(){quote(time,"5");order(time.plusSeconds(1));actual("-12");assertThat(state()).isEqualTo("HIGHER");jdbc.update("UPDATE amazon_orders SET currency='CAD' WHERE tenant_id=?",tenant);tx.executeWithoutResult(s->evidence.compareOrder(tenant,store,market,"ORDER"));assertThat(state()).isEqualTo("NOT_COMPARABLE");}
 @Test void completedOrderSnapshotCanRecoverOnlyItsPrePurchaseBasis(){
  quote(time,"5");order(time.plusSeconds(1),null,null);actual("-12");assertThat(state()).isEqualTo("NOT_COMPARABLE");
  quote(time.plusSeconds(10),"6");
  jdbc.update("UPDATE amazon_order_items SET item_price=60,shipping_price=0 WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  assertThat(state()).isEqualTo("HIGHER");
  assertThat(jdbc.queryForObject("SELECT expected_amount FROM amazon_fee_comparisons WHERE tenant_id=? AND fee_type='FBA' ORDER BY revision_seq DESC LIMIT 1",BigDecimal.class,tenant)).isEqualByComparingTo("10");
  assertThat(jdbc.queryForObject("SELECT state FROM amazon_fee_comparisons WHERE tenant_id=? AND fee_type='FBA' AND revision_key='BASIS'",String.class,tenant)).isEqualTo("NOT_COMPARABLE");
  long recovered=count("amazon_fee_comparisons");
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));assertThat(count("amazon_fee_comparisons")).isEqualTo(recovered);
 }
 @Test void completingOrderWithoutPrePurchaseEvidenceNeverBackdatesLaterQuote(){
  order(time.plusSeconds(1),null,null);actual("-12");quote(time.plusSeconds(10),"5");
  jdbc.update("UPDATE amazon_order_items SET item_price=60,shipping_price=0 WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  assertThat(state()).isEqualTo("NOT_COMPARABLE");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons WHERE tenant_id=? AND expected_amount IS NOT NULL",Long.class,tenant)).isZero();
 }
 @Test void staleAndIncompatibleHistoricalQuotesCannotRecoverAnIncompleteBasis(){
  quote(time.minusSeconds(86401),"5");order(time.plusSeconds(1),null,null);
  jdbc.update("UPDATE amazon_order_items SET item_price=60,shipping_price=0 WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons WHERE tenant_id=? AND expected_amount IS NOT NULL",Long.class,tenant)).isZero();
  quote(time,"5");jdbc.update("UPDATE amazon_order_items SET item_price=62 WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons WHERE tenant_id=? AND expected_amount IS NOT NULL",Long.class,tenant)).isZero();
 }
 @Test void concurrentOrderCompletionAppendsOneRecoveryForEachFeeAndPreservesProvenance()throws Exception{
  quote(time,"5");order(time.plusSeconds(1),null,null);actual("-12");
  jdbc.update("UPDATE amazon_order_items SET item_price=60,shipping_price=0 WHERE tenant_id=?",tenant);
  var start=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var one=pool.submit(()->{start.await();tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));return true;});
   var two=pool.submit(()->{start.await();tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));return true;});
   start.countDown();assertThat(one.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(two.get(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  }
  assertThat(state()).isEqualTo("HIGHER");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons WHERE tenant_id=? AND revision_key LIKE 'BASIS:RECOVERED:%'",Long.class,tenant)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons recovered JOIN amazon_fee_comparisons original ON original.id=recovered.previous_id AND original.tenant_id=recovered.tenant_id WHERE recovered.tenant_id=? AND recovered.revision_key LIKE 'BASIS:RECOVERED:%' AND original.revision_key='BASIS' AND original.state='NOT_COMPARABLE' AND recovered.observation_id IS NOT NULL",Long.class,tenant)).isEqualTo(2);
 }
 @Test void restoredOrderContextCreatesCurrentDerivationWithoutReplayingOldState(){
  quote(time,"5");order(time.plusSeconds(1));actual("-12");assertThat(state()).isEqualTo("HIGHER");
  jdbc.update("UPDATE amazon_orders SET currency='CAD' WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.compareOrder(tenant,store,market,"ORDER"));assertThat(state()).isEqualTo("NOT_COMPARABLE");
  long before=count("amazon_fee_comparisons");
  jdbc.update("UPDATE amazon_orders SET currency='USD' WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.compareOrder(tenant,store,market,"ORDER"));assertThat(state()).isEqualTo("HIGHER");
  assertThat(count("amazon_fee_comparisons")).isGreaterThan(before);
  long restored=count("amazon_fee_comparisons");
  tx.executeWithoutResult(s->evidence.compareOrder(tenant,store,market,"ORDER"));assertThat(count("amazon_fee_comparisons")).isEqualTo(restored);
 }
 @Test void nonEconomicOrderItemChangesDoNotCreateComparisonRevisions(){
  quote(time,"5");order(time.plusSeconds(1));actual("-12");long before=count("amazon_fee_comparisons");
  jdbc.update("UPDATE amazon_order_items SET title='Updated display title',updated_at=updated_at+interval '1 second' WHERE tenant_id=?",tenant);
  tx.executeWithoutResult(s->evidence.compareOrder(tenant,store,market,"ORDER"));
  assertThat(state()).isEqualTo("HIGHER");assertThat(count("amazon_fee_comparisons")).isEqualTo(before);
 }
 @Test void itemlessAdjustmentPreventsOldChargeApproval(){quote(time,"5");order(time.plusSeconds(1));actual("-12");var p=(tools.jackson.databind.node.ObjectNode)payload("6","Refund","RELEASED");var t=(tools.jackson.databind.node.ObjectNode)p.path("payload").path("transactions").get(0);t.put("transactionId","ADJUSTMENT");t.remove("items");tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,p));assertThat(state()).isEqualTo("NOT_COMPARABLE");}
 @Test void supportedWithUnknownSiblingIsNotComplete(){quote(time,"5");order(time.plusSeconds(1));var p=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");var a=(tools.jackson.databind.node.ArrayNode)p.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns");a.addObject().put("breakdownType","UnclassifiedFee");tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,p));assertThat(state()).isEqualTo("NOT_COMPARABLE");}
 @Test void malformedSupportedInsertIsRejected(){assertThatThrownBy(()->jdbc.update("INSERT INTO amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,transaction_key,revision,component_path,order_id,seller_sku,fee_type,signed_amount,posted_at,state,reason,source_document_id,raw_evidence) VALUES (?,?,?,'bad','bad','bad','ORDER',?,'FBA',-3,now(),'SUPPORTED','bad',?,'{}')",tenant,store,market,sku,source)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);}

 AmazonFeeVarianceRepository approvals(boolean allowed){var repo=new AmazonFeeVarianceRepository(jdbc,evidence,true,allowed);repo.configureRelationships(new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider(new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipRepository(jdbc,json)));return repo;}
 UUID approvalFixture(){quote(time,"5");order(time.plusSeconds(1));actual("-12");jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,listing_status,fulfillment_channel,price,currency,updated_at) VALUES (?,?,?,?,'ACTIVE','AMAZON_NA',30,'USD',?)",tenant,store,market,sku,Timestamp.from(time));quote(Instant.now().minusSeconds(1),"5");return jdbc.queryForObject("SELECT id FROM amazon_fee_comparisons WHERE tenant_id=? AND fee_type='FBA' ORDER BY revision_seq DESC LIMIT 1",UUID.class,tenant);}
 @Test void approvalIsProspectiveIdempotentAndDoesNotRemoveVariance(){UUID comparison=approvalFixture(),request=UUID.randomUUID();var repo=approvals(true);UUID accepted=tx.execute(s->repo.approve(tenant,store,comparison,request,null,"admin"));assertThat(tx.<UUID>execute(s->repo.approve(tenant,store,comparison,request,null,"admin"))).isEqualTo(accepted);assertThat(state()).isEqualTo("HIGHER");assertThat(count("amazon_fee_approvals")).isOne();assertThat(tx.<BigDecimal>execute(s->repo.applicableSku(tenant,store,market,"FBA",sku,"USD","FBA",new BigDecimal("30"),BigDecimal.ZERO))).isEqualByComparingTo("6");assertThat(tx.<BigDecimal>execute(s->repo.applicableSku(tenant,store,"OTHER","FBA",sku,"USD","FBA",new BigDecimal("30"),BigDecimal.ZERO))).isNull();}
 @Test void staleApprovalVersionAndReusedKeyAreRejected(){UUID comparison=approvalFixture(),request=UUID.randomUUID();var repo=approvals(true);tx.execute(s->repo.approve(tenant,store,comparison,request,null,"admin"));assertThatThrownBy(()->tx.execute(s->repo.approve(tenant,store,comparison,UUID.randomUUID(),null,"other-admin"))).hasMessageContaining("basis changed");assertThatThrownBy(()->tx.execute(s->repo.approve(tenant,store,comparison,request,null,"other-admin"))).hasMessageContaining("different evidence");}
 @Test void approvalsDisabledNeverWrite(){UUID comparison=approvalFixture();var repo=approvals(false);assertThatThrownBy(()->tx.execute(s->repo.approve(tenant,store,comparison,UUID.randomUUID(),null,"admin"))).hasMessageContaining("not enabled");assertThat(count("amazon_fee_approvals")).isZero();}
 @Test void restrictedRoleGroupedApprovalReplaysAndRejectsGenuineStaleEvidence(){
  quote(time,"5");order(time.plusSeconds(1));
  jdbc.update("INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,listing_status,fulfillment_channel,price,currency,updated_at) VALUES (?,?,?,?,'ACTIVE','AMAZON_NA',30,'USD',?)",tenant,store,market,sku,Timestamp.from(time));
  quote(Instant.now().minusSeconds(1),"5");var payload=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");
  ((tools.jackson.databind.node.ArrayNode)payload.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns")).addObject().put("breakdownType","Commission").putObject("breakdownAmount").put("currencyCode","USD").put("currencyAmount",-5);
  tx.executeWithoutResult(t->evidence.finances(tenant,store,market,source,payload));var repo=approvals(true);UUID request=UUID.randomUUID();
  String role="fee_group_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
  try{jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON ALL TABLES IN SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT INSERT ON amazon_fee_comparisons,amazon_fee_approvals TO "+role);jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO "+role);
   var choices=tx.execute(t->repo.history(tenant,store,"ORDER",sku)).stream().filter(r->Boolean.TRUE.equals(r.get("current_revision"))&&Set.of("HIGHER","LOWER").contains(r.get("state"))).map(r->new AmazonFeeVarianceRepository.Choice((UUID)r.get("id"),null)).toList();assertThat(choices).hasSize(2);
   var accepted=tx.execute(t->{jdbc.execute("SET LOCAL ROLE "+role);return repo.approveAll(tenant,store,choices,request,"owner");});assertThat(accepted).hasSize(2);
   assertThat(tx.<List<UUID>>execute(t->{jdbc.execute("SET LOCAL ROLE "+role);return repo.approveAll(tenant,store,choices,request,"owner");})).isEqualTo(accepted);assertThat(count("amazon_fee_approvals")).isEqualTo(2);
   actual("-14");assertThatThrownBy(()->tx.execute(t->{jdbc.execute("SET LOCAL ROLE "+role);return repo.approveAll(tenant,store,choices,UUID.randomUUID(),"owner");})).hasMessageContaining("evidence changed");
   assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons WHERE tenant_id=? AND state IN ('HIGHER','LOWER')",Long.class,tenant)).isGreaterThan(0);assertThat(count("amazon_fee_approvals")).isEqualTo(2);
  }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
 }
 @Test void forceRlsActuallyHidesOtherTenantAndRejectsWrites(){quote(time,"5");String role="fee_role_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");try{jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT,INSERT ON amazon_fee_observations,amazon_order_fee_charge_observations TO "+role);jdbc.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA "+schema+" TO "+role);tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_observations",Long.class)).isZero();});
  assertThatThrownBy(()->tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());jdbc.update("INSERT INTO amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,transaction_key,revision,component_path,order_id,seller_sku,fee_type,signed_amount,posted_at,state,reason,source_document_id,raw_evidence) VALUES (?,?,?,'bad','bad','bad','ORDER',?,'FBA',-3,now(),'SUPPORTED','bad',?,'{}')",tenant,store,market,sku,source);})).hasMessageContaining("check constraint");
  assertThatThrownBy(()->tx.executeWithoutResult(s->{jdbc.execute("SET LOCAL ROLE "+role);jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,UUID.randomUUID().toString());jdbc.update("INSERT INTO amazon_fee_observations(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,fulfillment,fee_type,unit_price,shipping,amount,observed_at,boundary_at,source_document_id,evidence_key,basis) VALUES (?,?,?,?,'USD','FBA','FBA',30,0,5,now(),now(),?,'forbidden','{}')",tenant,store,market,sku,source);})).rootCause().hasMessageContaining("row-level security");
  }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}}

 @Test void prospectiveSkuApprovalNeverRecostsHistoricalOrder(){UUID comparison=approvalFixture();var fees=approvals(true);tx.execute(s->fees.approve(tenant,store,comparison,UUID.randomUUID(),null,"admin"));jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES (?,?,?,?,'USD',3,5,now(),'{}',?)",tenant,store,market,sku,source);var costs=new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc);var profit=new com.nextaicommerce.platform.profit.ProfitRepository(jdbc,costs);org.springframework.test.util.ReflectionTestUtils.setField(profit,"acceptedFees",fees);assertThat(tx.execute(s->profit.skus(tenant,store,List.of(sku))).get(sku).fbaFee()).isEqualByComparingTo("6");assertThat(tx.execute(s->profit.orders(tenant,store,List.of("ORDER"))).get("ORDER").fbaFee()).isEqualByComparingTo("10");}

 @Test void incompleteSiblingCannotCountAsCompleteExpectedCoverage(){
  quote(time,"5");order(time.plusSeconds(1));
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,quantity_shipped,item_price,shipping_price,item_tax,shipping_tax,shipping_discount,currency) VALUES (?,?,'ORDER','SIBLING',?,1,1,30,0,0,0,0,'USD')",tenant,store,sku);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  assertThat(tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(tenant,store,List.of("ORDER"))).getOrDefault("ORDER",Map.of())).doesNotContainKey("FBA");
 }
 com.nextaicommerce.platform.profit.ProfitRepository profitWithFrozenBasis(){
  var profit=new com.nextaicommerce.platform.profit.ProfitRepository(jdbc,new com.nextaicommerce.platform.profit.ProfitCostRepository(jdbc));
  org.springframework.test.util.ReflectionTestUtils.setField(profit,"acceptedFees",approvals(false));return profit;
 }
 @Test void frozenExpectedTotalsCoverEachItemAndFeeIndependently(){
  quote(time,"5");order(time.plusSeconds(1));
  var other=(tools.jackson.databind.node.ObjectNode)basis();((tools.jackson.databind.node.ObjectNode)other.path("PriceToEstimateFees").path("ListingPrice")).put("Amount",31);
  tx.executeWithoutResult(s->evidence.quote(tenant,store,market,sku,source,"price31",other,null,new BigDecimal("6"),time));
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,quantity_shipped,item_price,shipping_price,item_tax,shipping_tax,promotion_discount,shipping_discount,currency) VALUES (?,?,'ORDER','SECOND-EXACT',?,1,1,31,0,0,0,0,0,'USD')",tenant,store,sku);
  tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  var frozen=tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(tenant,store,List.of("ORDER"))).get("ORDER");assertThat(frozen.get("FBA")).isEqualByComparingTo("16");assertThat(frozen).doesNotContainKey("REFERRAL");
  var view=tx.execute(s->profitWithFrozenBasis().orders(tenant,store,List.of("ORDER"))).get("ORDER");assertThat(view.fbaFee()).isEqualByComparingTo("16");assertThat(view.fbaReferralFee()).isNull();
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES (?,?,?,?,'USD',5,99,now(),'{}',?)",tenant,store,market,sku,source);
  view=tx.execute(s->profitWithFrozenBasis().orders(tenant,store,List.of("ORDER"))).get("ORDER");assertThat(view.fbaFee()).isEqualByComparingTo("16");assertThat(view.fbaReferralFee()).isEqualByComparingTo("15");
  assertThat(view.feeSource()).contains("Expected FBA fees fixed at purchase","historical referral basis unavailable").doesNotContain("historical FBA basis unavailable");
 }
 @Test void laterQuotesAndActualApprovalsCannotReplaceAnOlderFrozenOrder(){
  UUID comparison=approvalFixture();var approved=approvals(true);tx.execute(s->approved.approve(tenant,store,comparison,UUID.randomUUID(),null,"owner"));quote(Instant.now().minusSeconds(1),"9");
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES (?,?,?,?,'USD',3,9,now(),jsonb_build_object('_platformFeeContract','FINAL_FEE_V1','FeesEstimateIdentifier','{}'::jsonb),?)",tenant,store,market,sku,source);
  jdbc.update("INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,referral_fee,fulfillment_fee,effective_at,raw_payload,source_document_id) VALUES (?,?,?,?,'USD',99,99,now()-interval '1 day','{}',?)",tenant,store,market,sku,source);
  long before=count("amazon_fee_comparisons");var view=tx.execute(s->profitWithFrozenBasis().orders(tenant,store,List.of("ORDER"))).get("ORDER");
  assertThat(view.fbaFee()).isEqualByComparingTo("10");assertThat(view.fbaReferralFee()).isEqualByComparingTo("6");assertThat(view.feeSource()).contains("fixed at purchase").doesNotContain("historical FBA basis unavailable","historical referral basis unavailable","Current report estimate");assertThat(count("amazon_fee_comparisons")).isEqualTo(before);
 }
 @Test void changedShippingOrCurrencyCannotReuseFrozenHistoricalAmounts(){
  quote(time,"5");order(time.plusSeconds(1));assertThat(tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(tenant,store,List.of("ORDER"))).get("ORDER").get("FBA")).isEqualByComparingTo("10");
  jdbc.update("UPDATE amazon_order_items SET shipping_price=2 WHERE tenant_id=?",tenant);assertThat(tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(tenant,store,List.of("ORDER")))).isEmpty();
  jdbc.update("UPDATE amazon_order_items SET shipping_price=0,currency='CAD' WHERE tenant_id=?",tenant);jdbc.update("UPDATE amazon_orders SET currency='CAD' WHERE tenant_id=?",tenant);assertThat(tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(tenant,store,List.of("ORDER")))).isEmpty();
  assertThatThrownBy(()->tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(UUID.randomUUID(),store,List.of("ORDER")))).hasMessageContaining("Choose an Amazon store");
 }
 @Test void postPurchaseQuotesNeverInventAFrozenHistoricalBasis(){
  order(time);quote(time.plusSeconds(1),"5");tx.executeWithoutResult(s->evidence.bindOrder(tenant,store,market,"ORDER"));
  assertThat(tx.<Map<String,Map<String,BigDecimal>>>execute(s->approvals(false).orderBasis(tenant,store,List.of("ORDER")))).isEmpty();assertThat(tx.execute(s->profitWithFrozenBasis().orders(tenant,store,List.of("ORDER"))).get("ORDER").fbaFee()).isNull();
 }
 @Test void taxedOrDiscountedBasisCannotCreateFalseVariance(){quote(time,"5");order(time.plusSeconds(1));jdbc.update("UPDATE amazon_order_items SET promotion_discount=2 WHERE tenant_id=?",tenant);actual("-12");assertThat(state()).isEqualTo("NOT_COMPARABLE");}
 @Test void unknownOnlyChargeIsVisibleAsUnresolved(){quote(time,"5");order(time.plusSeconds(1));var root=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");((tools.jackson.databind.node.ObjectNode)root.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns").get(0)).put("breakdownType","UnsupportedFee");tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));assertThat(state()).isEqualTo("NOT_COMPARABLE");}

 @Test void conflictingReleasedFeeSetDoesNotLeaveOldChargeEligible(){quote(time,"5");order(time.plusSeconds(1));actual("-12");var root=(tools.jackson.databind.node.ObjectNode)payload("-6","Shipment","RELEASED");((tools.jackson.databind.node.ObjectNode)root.path("payload").path("transactions").get(0).path("items").get(0).path("breakdowns").get(0)).put("breakdownType","Commission");tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));assertThat(state()).isEqualTo("NOT_COMPARABLE");assertThat(jdbc.queryForObject("SELECT count(*) FROM amazon_fee_comparisons WHERE tenant_id=? AND state='HIGHER'",Integer.class,tenant)).isEqualTo(1);}
 @Test void correctedTransactionOrderIdentityInvalidatesOriginalOrder(){quote(time,"5");order(time.plusSeconds(1));actual("-12");var root=(tools.jackson.databind.node.ObjectNode)payload("-12","Shipment","RELEASED");((tools.jackson.databind.node.ObjectNode)root.path("payload").path("transactions").get(0).path("relatedIdentifiers").get(0)).put("relatedIdentifierValue","OTHER-ORDER");tx.executeWithoutResult(s->evidence.finances(tenant,store,market,source,root));assertThat(state()).isEqualTo("NOT_COMPARABLE");}

 @Test void changedSkuLookupUsesScopedIndexesOnRepresentativeFixture(){
  jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,purchase_date,currency,fulfillment_channel) SELECT ?,?,?,'BENCH-'||n,?,'USD','AFN' FROM generate_series(1,5000) n",tenant,store,market,Timestamp.from(time.plusSeconds(60)));
  jdbc.update("INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,seller_sku,quantity_ordered,item_price,shipping_price,currency) SELECT ?,?,'BENCH-'||n,'BENCH-'||n,'BENCH-'||(n%100),1,30,0,'USD' FROM generate_series(1,5000) n",tenant,store);
  jdbc.execute("ANALYZE amazon_orders");jdbc.execute("ANALYZE amazon_order_items");
  String plan=jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT i.id FROM amazon_order_items i JOIN amazon_orders o ON o.tenant_id=i.tenant_id AND o.marketplace_connection_id=i.marketplace_connection_id AND o.amazon_order_id=i.amazon_order_id WHERE i.tenant_id=? AND i.marketplace_connection_id=? AND i.seller_sku='BENCH-7' AND o.purchase_date>?",String.class,tenant,store,Timestamp.from(time));
  assertThat(json.readTree(plan).get(0).path("Plan").path("Actual Rows").asInt()).isEqualTo(50);assertThat(plan).contains("amazon_order_items_sku");System.out.println("FEE_CHANGED_SKU_SYNTHETIC_PLAN "+plan);
 }

 @Test void historyPaginationIsExplicitAndSnapshotBounded(){quote(time,"5");order(time.plusSeconds(1));jdbc.update("INSERT INTO amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,order_item_id,order_id,seller_sku,fee_type,currency,quantity,unit_price,shipping,purchase_at,boundary_at,observation_id,expected_amount,state,reason,revision_key) SELECT f.tenant_id,f.marketplace_connection_id,f.marketplace_id,f.order_item_id,f.order_id,f.seller_sku,f.fee_type,f.currency,f.quantity,f.unit_price,f.shipping,f.purchase_at,f.boundary_at,f.observation_id,f.expected_amount,'AWAITING','synthetic history','PAGE-'||n FROM amazon_fee_comparisons f CROSS JOIN generate_series(1,205) n WHERE f.tenant_id=? AND f.fee_type='FBA' AND f.revision_key='BASIS'",tenant);var repo=approvals(true);var first=tx.execute(s->repo.historyPage(tenant,store,"ORDER",sku,0,null));var second=tx.execute(s->repo.historyPage(tenant,store,"ORDER",sku,1,first.snapshot()));var third=tx.execute(s->repo.historyPage(tenant,store,"ORDER",sku,2,first.snapshot()));assertThat(first.rows()).hasSize(100);assertThat(first.hasMore()).isTrue();assertThat(second.rows()).hasSize(100);assertThat(third.rows()).hasSize(7);assertThat(third.hasMore()).isFalse();var ids=new HashSet<Object>();for(var page:List.of(first,second,third))for(var row:page.rows())assertThat(ids.add(row.get("id"))).isTrue();}
 @Test void historyDistinguishesEconomicChangeFromProjectionRevisions(){
  quote(time,"5");order(time.plusSeconds(1));actual("-12");
  jdbc.update("""
   INSERT INTO amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,order_item_id,order_id,seller_sku,fee_type,currency,quantity,unit_price,shipping,purchase_at,boundary_at,observation_id,approval_id,expected_amount,actual_amount,difference,state,reason,charge_id,previous_id,revision_key)
   SELECT tenant_id,marketplace_connection_id,marketplace_id,order_item_id,order_id,seller_sku,fee_type,currency,quantity,unit_price,shipping,purchase_at,boundary_at,observation_id,approval_id,expected_amount,actual_amount,difference,state,reason,charge_id,id,'DERIVATION-TEST' FROM amazon_fee_comparisons WHERE tenant_id=? AND fee_type='FBA' AND state='HIGHER'
   """,tenant);
  var repo=approvals(true);var same=tx.execute(t->repo.history(tenant,store,"ORDER",sku)).stream().filter(r->"HIGHER".equals(r.get("state"))&&!Boolean.TRUE.equals(r.get("current_revision"))).toList();
  assertThat(same).hasSize(1);assertThat(same.getFirst().get("same_economic_as_current")).isEqualTo(true);assertThat(same.getFirst().get("charge_id")).isNotNull();
  actual("-14");var changed=tx.execute(t->repo.history(tenant,store,"ORDER",sku)).stream().filter(r->"HIGHER".equals(r.get("state"))&&!Boolean.TRUE.equals(r.get("current_revision"))).toList();
  assertThat(changed).hasSize(2);assertThat(changed).allMatch(r->Boolean.FALSE.equals(r.get("same_economic_as_current")));
 }
 @Test void malformedApprovalChoicesHaveControlledFailure(){var repo=approvals(true);assertThatThrownBy(()->tx.execute(s->repo.approveAll(tenant,store,Arrays.asList((AmazonFeeVarianceRepository.Choice)null),UUID.randomUUID(),"admin"))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->tx.execute(s->repo.approveAll(tenant,store,List.of(new AmazonFeeVarianceRepository.Choice(UUID.randomUUID(),null)),UUID.randomUUID(),"admin"))).isInstanceOf(IllegalArgumentException.class);}

}
