package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit existing canonical DB only. Synthetic fixtures always roll back; no services or DDL. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="fedexLocalFixtures",matches="true")
class FedExLocalDatabaseTest {
    static SingleConnectionDataSource dataSource;static JdbcTemplate jdbc;static TransactionTemplate tx;
    final ObjectMapper json=new ObjectMapper();final UUID tenant=UUID.randomUUID(),other=UUID.randomUUID(),store=UUID.randomUUID(),carrier=UUID.randomUUID();
    @BeforeAll static void database()throws Exception{
        var p=new Properties();try(var in=java.nio.file.Files.newInputStream(java.nio.file.Path.of(System.getProperty("fedexLocalRuntime")))){p.load(in);}
        dataSource=new SingleConnectionDataSource(java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_fee_readiness_20261024","nextcommerce_fee_preview",p.getProperty("spring.datasource.password")),true);
        jdbc=new JdbcTemplate(dataSource);tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThat(jdbc.queryForObject("SELECT current_database()||'|'||inet_server_port()",String.class)).isEqualTo("next_ai_commerce_fee_readiness_20261024|55432");
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE success",Integer.class)).isEqualTo(122);
    }
    @AfterAll static void close(){if(dataSource!=null)dataSource.destroy();}
    void scope(UUID id){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());}
    void fixture(){
        for(UUID id:List.of(tenant,other)){scope(id);jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'LOCAL TEST - rollback')",id,id.toString());}
        scope(tenant);jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,status,display_name,credential_secret_ref,reporting_timezone,inventory_activated_at) VALUES (?,?,'SHOPIFY',?,?,'ACTIVE','LOCAL TEST','fixture://none','UTC',now())",store,tenant,"fixture-"+store+".myshopify.com","fixture-"+store+".myshopify.com");
        jdbc.update("INSERT INTO carrier_connections(id,tenant_id,display_name,account_number,environment,credential_source) VALUES (?,?,'LOCAL TEST','001234567','SANDBOX','PLATFORM')",carrier,tenant);
    }
    void rollback(java.util.function.Consumer<CarrierConnectionRepository> test){tx.executeWithoutResult(status->{try{fixture();test.accept(new CarrierConnectionRepository(jdbc));}finally{status.setRollbackOnly();}});}
    void rejected(String sql,Object... args){try{var connection=dataSource.getConnection();var point=connection.setSavepoint();try{assertThatThrownBy(()->jdbc.update(sql,args)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{connection.rollback(point);connection.releaseSavepoint(point);}}catch(java.sql.SQLException error){throw new AssertionError(error);}}
    @Test void customerCasRlsRevisionAndEditInvalidation(){rollback(repo->{
        var encrypted=new ShippingLabelCrypto.Encrypted(new byte[20],new byte[12]);
        assertThat(repo.publishRegisteredCustomer(other,carrier,1,"001234567",encrypted)).isFalse();
        assertThat(repo.publishRegisteredCustomer(tenant,carrier,1,"009999999",encrypted)).isFalse();
        assertThat(repo.publishRegisteredCustomer(tenant,carrier,2,"001234567",encrypted)).isFalse();
        assertThat(repo.publishRegisteredCustomer(tenant,carrier,1,"001234567",encrypted)).isTrue();
        assertThat(repo.publishRegisteredCustomer(tenant,carrier,1,"001234567",encrypted)).isFalse();
        assertThat(repo.registeredCustomer(tenant,carrier,2,"001234567","SANDBOX").payload()).isEqualTo(encrypted.payload());
        assertThat(repo.registeredCustomer(other,carrier,2,"001234567","SANDBOX")).isNull();
        assertThat(repo.registeredCustomer(tenant,carrier,2,"001234567","PRODUCTION")).isNull();
        scope(other);assertThat(jdbc.queryForObject("SELECT count(*) FROM carrier_connections WHERE id=?",Integer.class,carrier)).isZero();
        repo.save(tenant,carrier,"Edited","001234567","SANDBOX","PLATFORM",null);
        var current=repo.find(tenant,carrier);assertThat(current.revision()).isEqualTo(3);assertThat(repo.registeredCustomer(tenant,carrier,3,"001234567","SANDBOX").payload()).isNull();
        repo.health(tenant,carrier,2,"ACTIVE","Stale check");assertThat(repo.find(tenant,carrier).healthStatus()).isEqualTo("PENDING");
        assertThat(repo.publishRegisteredCustomer(tenant,carrier,3,"001234567",encrypted)).isTrue();repo.delete(tenant,carrier);scope(tenant);
        assertThat(jdbc.queryForObject("SELECT registered_customer_payload IS NULL AND registered_customer_nonce IS NULL FROM carrier_connections WHERE id=?",Boolean.class,carrier)).isTrue();
    });}
    @Test void pairConstraintPreservesOriginalPlatformEnvelopeAndRejectsSimulationSecrets(){rollback(repo->{
        rejected("UPDATE carrier_connections SET registered_customer_payload=? WHERE id=?",new byte[20],carrier);
        rejected("UPDATE carrier_connections SET registered_customer_nonce=? WHERE id=?",new byte[12],carrier);
        rejected("UPDATE carrier_connections SET registered_customer_payload=?,registered_customer_nonce=? WHERE id=?",new byte[20],new byte[11],carrier);
        rejected("UPDATE carrier_connections SET credential_payload=?,credential_nonce=? WHERE id=?",new byte[20],new byte[12],carrier);
        jdbc.update("UPDATE carrier_connections SET is_simulated=true WHERE id=?",carrier);
        assertThat(repo.publishRegisteredCustomer(tenant,carrier,1,"001234567",new ShippingLabelCrypto.Encrypted(new byte[20],new byte[12]))).isFalse();
        rejected("UPDATE carrier_connections SET registered_customer_payload=?,registered_customer_nonce=? WHERE id=?",new byte[20],new byte[12],carrier);
        rejected("UPDATE carrier_connections SET environment='PRODUCTION' WHERE id=?",carrier);
    });}
    @Test void nativeFixturesNeverCreateProviderOrdersAndRequireExactStoreTenant(){rollback(repo->{
        UUID id=UUID.randomUUID();String definition=json.writeValueAsString(new LocalShippingFixtures.Definition(List.of(new CarrierLabelComposer.Line("LOCAL TEST","LOCAL-EAx3","Synthetic product",3)),null));
        jdbc.update("INSERT INTO local_shipping_fixtures(id,tenant_id,marketplace_connection_id,name,definition) VALUES (?,?,?,'LOCAL TEST - manual',?::jsonb)",id,tenant,store,definition);
        var fixtures=new LocalShippingFixtures(jdbc,json,true);String order=LocalShippingFixtures.PREFIX+id;
        assertThat(fixtures.context(tenant,store,order).items().getFirst().remaining()).isEqualTo(3);
        var recipient=new FedExPayloads.Address("Synthetic Updated","9015550100","20 Test Street","Collierville","TN","38017","US",false);
        fixtures.saveRecipient(tenant,store,order,recipient);
        assertThat(new LocalShippingFixtures(jdbc,json,true).get(tenant,store,order).definition().recipient()).isEqualTo(recipient);
        assertThat(fixtures.get(tenant,store,order).definition().packing()).hasSize(1);
        assertThatThrownBy(()->fixtures.saveRecipient(other,store,order,recipient)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->fixtures.saveRecipient(tenant,UUID.randomUUID(),order,recipient)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new LocalShippingFixtures(jdbc,json,false).saveRecipient(tenant,store,order,recipient)).isInstanceOf(IllegalStateException.class);
        assertThat(fixtures.get(tenant,store,order).definition().recipient()).isEqualTo(recipient);
        assertThatThrownBy(()->fixtures.get(other,store,order)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->fixtures.get(tenant,UUID.randomUUID(),order)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->fixtures.get(tenant,store,"real-order")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new LocalShippingFixtures(jdbc,json,false).get(tenant,store,order)).isInstanceOf(IllegalStateException.class);
        scope(other);assertThat(jdbc.queryForObject("SELECT count(*) FROM local_shipping_fixtures WHERE id=?",Integer.class,id)).isZero();
        scope(tenant);assertThat(jdbc.queryForObject("SELECT count(*) FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=?",Integer.class,tenant,store)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM marketplace_sync_jobs WHERE tenant_id=? AND marketplace_connection_id=?",Integer.class,tenant,store)).isZero();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid='local_shipping_fixtures'::regclass",Boolean.class)).isTrue();
    });}
    @Test void sharedQuoteClaimAndReprintCannotCrossModeOrRepeat(){rollback(repo->{
        jdbc.update("UPDATE carrier_connections SET is_simulated=true WHERE id=?",carrier);var c=repo.find(tenant,carrier);var shipments=new FedExShipmentRepository(jdbc,json);
        var request=new ShippingLabelCrypto.Encrypted(new byte[20],new byte[12]);var rates=List.of(new FedExPayloads.Rate("FEDEX_GROUND","Simulated Ground",java.math.BigDecimal.TEN,"USD"));
        String order="local-fixture:"+UUID.randomUUID();UUID quote=shipments.quote(tenant,store,order,c,request,rates,List.of(),"LOCAL SIMULATION");
        assertThat(shipments.claim(other,store,order,quote,"FEDEX_GROUND",1)).isNull();
        assertThat(shipments.claim(tenant,store,order,quote,"UNKNOWN_SERVICE",1)).isNull();
        jdbc.update("UPDATE carrier_connections SET is_simulated=false WHERE id=?",carrier);
        assertThat(shipments.claim(tenant,store,order,quote,"FEDEX_GROUND",1)).isNull();
        jdbc.update("UPDATE carrier_connections SET is_simulated=true WHERE id=?",carrier);
        assertThat(shipments.claim(tenant,store,order,quote,"FEDEX_GROUND",1)).isNotNull();
        assertThat(shipments.claim(tenant,store,order,quote,"FEDEX_GROUND",1)).isNull();
        shipments.purchased(tenant,quote,"SIMULATED-TEST",request,null);
        assertThat(shipments.list(tenant,store,order).getFirst().simulated()).isTrue();assertThat(shipments.label(tenant,store,quote)).isNotNull();
        assertThat(shipments.claim(tenant,store,order,quote,"FEDEX_GROUND",1)).isNull();
    });}
}
