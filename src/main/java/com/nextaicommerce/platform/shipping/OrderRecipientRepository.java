package com.nextaicommerce.platform.shipping;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class OrderRecipientRepository {
    private final JdbcTemplate jdbc;
    public OrderRecipientRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Command(UUID tenant,UUID store,String order,String marketplace,UUID lease) {}
    public record Stored(String status,byte[] payload,byte[] nonce) { @Override public String toString(){return "Stored recipient [REDACTED]";} }
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    // Explicit allow-list: Pending and unknown fulfillment channels are never eligible.
    private static final String ELIGIBLE="upper(o.fulfillment_channel) IN ('MFN','MERCHANT','FBM') AND regexp_replace(upper(o.order_status),'[^A-Z]','','g') IN ('UNSHIPPED','PARTIALLYSHIPPED','SHIPPED') AND (upper(o.order_status)<>'SHIPPED' OR coalesce(o.latest_delivery_date,o.latest_ship_date,o.purchase_date)>now()-interval '30 days')";
    public void enqueue(UUID tenant,UUID store,Collection<String> ids){
        if(ids.isEmpty())return;
        String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
        var parameters=new ArrayList<Object>();parameters.add(tenant);parameters.add(store);parameters.addAll(ids);
        enqueueWhere(tenant,store,"o.amazon_order_id IN ("+placeholders+")",parameters.toArray());
    }
    public void enqueueWindow(UUID tenant,UUID store,java.time.Instant start,java.time.Instant end){
        enqueueWhere(tenant,store,"o.purchase_date>=? AND o.purchase_date<=?",new Object[]{tenant,store,java.sql.Timestamp.from(start),java.sql.Timestamp.from(end)});
    }
    private void enqueueWhere(UUID tenant,UUID store,String condition,Object[] parameters){
        scope(tenant);
        jdbc.update("""
            INSERT INTO order_shipping_recipients(tenant_id,marketplace_connection_id,amazon_order_id,source_updated_at)
            SELECT o.tenant_id,o.marketplace_connection_id,o.amazon_order_id,o.last_update_date FROM amazon_orders o
            WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND
            """+condition+" AND "+ELIGIBLE+"""
            ON CONFLICT(tenant_id,marketplace_connection_id,amazon_order_id) DO UPDATE SET
              status='QUEUED',next_attempt_at=now(),lease_id=NULL,encrypted_payload=NULL,encryption_nonce=NULL,
              source_updated_at=excluded.source_updated_at
            WHERE order_shipping_recipients.status<>'FETCHING' AND
              (order_shipping_recipients.status='INELIGIBLE' OR order_shipping_recipients.source_updated_at IS DISTINCT FROM excluded.source_updated_at
               OR order_shipping_recipients.expires_at<now() AND order_shipping_recipients.next_attempt_at<now())
            """,parameters);
        jdbc.update("INSERT INTO order_recipient_access(tenant_id,marketplace_connection_id) SELECT tenant_id,id FROM marketplace_connections WHERE tenant_id=? AND id=? ON CONFLICT DO NOTHING",tenant,store);
    }
    public Command claim(UUID tenant){scope(tenant);
        // Cross-process per-store throttle; a crashed lease can be reclaimed after five minutes.
        var stores=jdbc.query("SELECT marketplace_connection_id FROM order_recipient_access WHERE tenant_id=? AND next_allowed_at<=now() ORDER BY next_allowed_at FOR UPDATE SKIP LOCKED LIMIT 1",(rs,n)->rs.getObject(1,UUID.class),tenant);
        if(stores.isEmpty())return null;UUID store=stores.getFirst(),lease=UUID.randomUUID();
        jdbc.update("UPDATE order_recipient_access SET next_allowed_at=now()+interval '2100 milliseconds' WHERE tenant_id=? AND marketplace_connection_id=?",tenant,store);
        return jdbc.query("""
            UPDATE order_shipping_recipients r SET status='FETCHING',lease_id=?,attempts=attempts+1,next_attempt_at=now()+interval '5 minutes'
            WHERE (r.tenant_id,r.marketplace_connection_id,r.amazon_order_id) IN
            (SELECT q.tenant_id,q.marketplace_connection_id,q.amazon_order_id FROM order_shipping_recipients q
             JOIN amazon_orders o ON o.tenant_id=q.tenant_id AND o.marketplace_connection_id=q.marketplace_connection_id AND o.amazon_order_id=q.amazon_order_id
             WHERE q.tenant_id=? AND q.marketplace_connection_id=? AND q.status IN ('QUEUED','RETRY','DENIED','FETCHING') AND q.next_attempt_at<=now() AND
            """+ELIGIBLE+"""
             ORDER BY q.next_attempt_at FOR UPDATE OF q SKIP LOCKED LIMIT 1)
            RETURNING r.amazon_order_id,(SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=r.tenant_id AND id=r.marketplace_connection_id)
            """,rs->rs.next()?new Command(tenant,store,rs.getString(1),rs.getString(2),lease):null,lease,tenant,store);
    }
    public void complete(Command command,String status,ShippingLabelCrypto.Encrypted encrypted){scope(command.tenant());
        int changed=jdbc.update("""
            UPDATE order_shipping_recipients r SET status=?,encrypted_payload=?,encryption_nonce=?,fetched_at=now(),expires_at=now()+interval '24 hours',next_attempt_at=now()+interval '24 hours',lease_id=NULL
            WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND lease_id=?
              AND EXISTS(SELECT 1 FROM amazon_orders o WHERE o.tenant_id=r.tenant_id AND o.marketplace_connection_id=r.marketplace_connection_id AND o.amazon_order_id=r.amazon_order_id AND o.last_update_date IS NOT DISTINCT FROM r.source_updated_at AND
            """+ELIGIBLE+")",status,encrypted==null?null:encrypted.payload(),encrypted==null?null:encrypted.nonce(),command.tenant(),command.store(),command.order(),command.lease());
        if(changed==0)jdbc.update("""
            UPDATE order_shipping_recipients r SET status='QUEUED',next_attempt_at=now(),lease_id=NULL,encrypted_payload=NULL,encryption_nonce=NULL,source_updated_at=o.last_update_date
            FROM amazon_orders o WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.amazon_order_id=? AND r.lease_id=?
              AND o.tenant_id=r.tenant_id AND o.marketplace_connection_id=r.marketplace_connection_id AND o.amazon_order_id=r.amazon_order_id
            """,command.tenant(),command.store(),command.order(),command.lease());
    }
    public void failed(Command c,boolean denied){scope(c.tenant());int seconds=denied?21600:300;
        jdbc.update("UPDATE order_shipping_recipients SET status=?,encrypted_payload=NULL,encryption_nonce=NULL,lease_id=NULL,next_attempt_at=now()+(?*interval '1 second') WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND lease_id=?",denied?"DENIED":"RETRY",seconds,c.tenant(),c.store(),c.order(),c.lease());
        jdbc.update("UPDATE order_recipient_access SET next_allowed_at=greatest(next_allowed_at,now()+(?*interval '1 second')) WHERE tenant_id=? AND marketplace_connection_id=?",seconds,c.tenant(),c.store());
    }
    public Stored read(UUID tenant,UUID store,String order){scope(tenant);
        return jdbc.query("""
            SELECT CASE WHEN r.expires_at<=now() AND r.status IN ('AVAILABLE','INCOMPLETE') THEN 'EXPIRED' ELSE r.status END,
              CASE WHEN r.expires_at>now() THEN r.encrypted_payload END,CASE WHEN r.expires_at>now() THEN r.encryption_nonce END
            FROM order_shipping_recipients r JOIN amazon_orders o ON o.tenant_id=r.tenant_id AND o.marketplace_connection_id=r.marketplace_connection_id AND o.amazon_order_id=r.amazon_order_id
            WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.amazon_order_id=? AND
            """+ELIGIBLE,rs->rs.next()?new Stored(rs.getString(1),rs.getBytes(2),rs.getBytes(3)):null,tenant,store,order);
    }
    public void purge(UUID tenant){scope(tenant);
        jdbc.update("UPDATE order_shipping_recipients SET encrypted_payload=NULL,encryption_nonce=NULL,status='EXPIRED',lease_id=NULL WHERE tenant_id=? AND encrypted_payload IS NOT NULL AND expires_at<=now()",tenant);
        jdbc.update("""
            UPDATE order_shipping_recipients r SET encrypted_payload=NULL,encryption_nonce=NULL,status='INELIGIBLE',lease_id=NULL
            WHERE r.tenant_id=? AND r.status<>'INELIGIBLE' AND NOT EXISTS(SELECT 1 FROM amazon_orders o WHERE o.tenant_id=r.tenant_id AND o.marketplace_connection_id=r.marketplace_connection_id AND o.amazon_order_id=r.amazon_order_id AND
            """+ELIGIBLE+")",tenant);
    }
}
