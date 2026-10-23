package com.nextaicommerce.platform.shipping;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OrderRecipientDatabaseTest extends CarrierDatabaseTest {
    @Test void reportWindowQueuesOnlyEligibleOrders(){tx.executeWithoutResult(s->{
        order("114-0000000-0000009","Unshipped","MFN");order("114-0000000-0000010","Pending","MFN");
        new OrderRecipientRepository(jdbc).enqueueWindow(tenant,store,java.time.Instant.now().minusSeconds(60),java.time.Instant.now().plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_shipping_recipients WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    });}
    @Test void forcedRlsHidesRowsWithoutMatchingTenant(){tx.executeWithoutResult(s->{
        String id="114-0000000-0000008";order(id,"Unshipped","MFN");var repository=new OrderRecipientRepository(jdbc);repository.enqueue(tenant,store,List.of(id));
        String role="recipient_reader_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE ROLE "+role+" NOLOGIN");jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);jdbc.execute("GRANT SELECT ON order_shipping_recipients TO "+role);
        try {
            jdbc.execute("SET LOCAL ROLE "+role);
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,other.toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_shipping_recipients",Integer.class)).isZero();
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_shipping_recipients",Integer.class)).isEqualTo(1);
        }finally{jdbc.execute("RESET ROLE");jdbc.execute("REVOKE SELECT ON order_shipping_recipients FROM "+role);jdbc.execute("REVOKE USAGE ON SCHEMA "+schema+" FROM "+role);jdbc.execute("DROP ROLE "+role);}
    });}
    void order(String id,String status,String channel){jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,order_status,fulfillment_channel,purchase_date,last_update_date) VALUES (?,?,'ATVPDKIKX0DER',?,?,?,now(),now())",tenant,store,id,status,channel);}
    @Test void eligibilityEncryptionExpiryAndTenantIsolation(){tx.executeWithoutResult(s->{
        order("114-0000000-0000001","Unshipped","MFN");order("114-0000000-0000002","Pending","MFN");order("114-0000000-0000003","Unshipped","AFN");order("114-0000000-0000004","PartiallyShipped","MFN");order("114-0000000-0000005","Shipped","MFN");
        var repository=new OrderRecipientRepository(jdbc);var ids=List.of("114-0000000-0000001","114-0000000-0000002","114-0000000-0000003","114-0000000-0000004","114-0000000-0000005");repository.enqueue(tenant,store,ids);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_shipping_recipients WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(3);
        var command=repository.claim(tenant);assertThat(command).isNotNull();assertThat(repository.claim(tenant)).isNull();
        var encrypted=new ShippingLabelCrypto.Encrypted(new byte[]{7,8,9},new byte[12]);repository.complete(command,"AVAILABLE",encrypted);
        assertThat(repository.read(tenant,store,command.order()).payload()).containsExactly(7,8,9);
        assertThat(repository.read(other,store,command.order())).isNull();assertThat(repository.read(tenant,UUID.randomUUID(),command.order())).isNull();
        jdbc.update("UPDATE order_shipping_recipients SET expires_at=now()-interval '1 second' WHERE tenant_id=? AND amazon_order_id=?",tenant,command.order());
        assertThat(repository.read(tenant,store,command.order()).payload()).isNull();repository.purge(tenant);
        assertThat(jdbc.queryForObject("SELECT encrypted_payload IS NULL FROM order_shipping_recipients WHERE tenant_id=? AND amazon_order_id=?",Boolean.class,tenant,command.order())).isTrue();
    });}
    @Test void permissionFailureBacksOffStoreAndStaleLeaseCannotOverwrite(){tx.executeWithoutResult(s->{
        String id="114-0000000-0000006";order(id,"Unshipped","MFN");var repository=new OrderRecipientRepository(jdbc);repository.enqueue(tenant,store,List.of(id));var command=repository.claim(tenant);repository.failed(command,true);
        assertThat(repository.read(tenant,store,id).status()).isEqualTo("DENIED");assertThat(repository.claim(tenant)).isNull();repository.complete(command,"AVAILABLE",new ShippingLabelCrypto.Encrypted(new byte[]{1},new byte[12]));assertThat(repository.read(tenant,store,id).payload()).isNull();
        jdbc.update("UPDATE order_shipping_recipients SET expires_at=now()-interval '1 day' WHERE tenant_id=? AND amazon_order_id=?",tenant,id);
        assertThat(repository.read(tenant,store,id).status()).isEqualTo("DENIED");
    });}
    @Test void changedOrderRejectsOldResponseAndRequeues(){tx.executeWithoutResult(s->{
        String id="114-0000000-0000007";order(id,"Unshipped","MFN");var repository=new OrderRecipientRepository(jdbc);repository.enqueue(tenant,store,List.of(id));var command=repository.claim(tenant);
        jdbc.update("UPDATE amazon_orders SET last_update_date=now()+interval '1 second' WHERE tenant_id=? AND amazon_order_id=?",tenant,id);
        repository.complete(command,"AVAILABLE",new ShippingLabelCrypto.Encrypted(new byte[]{1},new byte[12]));assertThat(repository.read(tenant,store,id).status()).isEqualTo("QUEUED");assertThat(repository.read(tenant,store,id).payload()).isNull();
    });}
    @Test void lookupUsesCompositePrimaryKey(){tx.executeWithoutResult(s->{
        jdbc.execute("SET LOCAL enable_seqscan=off");
        String plan=String.join("\n",jdbc.query("EXPLAIN SELECT status FROM order_shipping_recipients WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",(rs,n)->rs.getString(1),tenant,store,"114-0000000-0000001"));
        assertThat(plan).contains("order_shipping_recipients_pkey");
    });}
}
