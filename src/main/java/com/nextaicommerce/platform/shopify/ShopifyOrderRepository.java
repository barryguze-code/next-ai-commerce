package com.nextaicommerce.platform.shopify;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static com.nextaicommerce.platform.shopify.ShopifyOrderLifecycle.*;

/** Source projection and warehouse checkpoint commit together inside a fenced Orders job transaction. */
@Repository
public class ShopifyOrderRepository {
    private final JdbcTemplate jdbc; private final NamedParameterJdbcTemplate named;
    private final ShopifyOrderInventory inventory; private final boolean enabled;
    private final ObjectMapper json=new ObjectMapper();
    public ShopifyOrderRepository(JdbcTemplate jdbc,ShopifyOrderInventory inventory,@Value("${app.shopify.orders.enabled:false}") boolean enabled){
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.inventory=inventory;this.enabled=enabled;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID archive(UUID tenant,UUID connection,UUID job,String cursor,String body){
        String hash=ShopifyCatalogRepository.hash(body);
        jdbc.update("""
            INSERT INTO shopify_source_documents(tenant_id,marketplace_connection_id,sync_job_id,api_version,query_revision,request_cursor,sha256,response_body)
            VALUES(?,?,?,'2026-10',?,?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id,sync_job_id,sha256) DO NOTHING
            """,tenant,connection,job,ShopifyOrderClient.REVISION,cursor,hash,body);
        return jdbc.queryForObject("SELECT id FROM shopify_source_documents WHERE tenant_id=? AND marketplace_connection_id=? AND sync_job_id=? AND sha256=?",UUID.class,tenant,connection,job,hash);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void publish(UUID tenant,UUID connection,UUID job,String cursor,String body,ShopifyOrderClient.Page page){
        if(!enabled)throw new IllegalStateException("Shopify Orders sync is disabled.");
        inventory.lock(tenant);UUID source=archive(tenant,connection,job,cursor,body);
        Instant cutoff=inventory.cutoff(tenant,connection);
        for(var o:page.orders()){
            String hash=ShopifyCatalogRepository.hash(json.writeValueAsString(o));
            var previous=jdbc.queryForList("SELECT id,source_updated_at,observation_hash FROM shopify_orders WHERE tenant_id=? AND marketplace_connection_id=? AND order_gid=? FOR UPDATE",tenant,connection,o.gid());
            if(!previous.isEmpty()){
                var prior=previous.getFirst();Instant revision=((Timestamp)prior.get("source_updated_at")).toInstant();
                if(o.updated().isBefore(revision))continue;
                if(o.updated().equals(revision)&&!hash.equals(prior.get("observation_hash"))){
                    jdbc.update("UPDATE shopify_orders SET review_reason='Conflicting order evidence at the same source revision',evidence_complete=false WHERE tenant_id=? AND id=?",tenant,prior.get("id"));
                    continue;
                }
            }
            var fields=new LinkedHashMap<String,Object>();fields.put("tenant",tenant);fields.put("connection",connection);
            fields.put("gid",o.gid());fields.put("name",o.name());fields.put("created",Timestamp.from(o.created()));fields.put("updated",Timestamp.from(o.updated()));
            fields.put("cancelled",o.cancelled()==null?null:Timestamp.from(o.cancelled()));fields.put("test",o.test());fields.put("payment",o.payment());fields.put("fulfillment",o.fulfillment());
            fields.put("currency",o.total().currency());fields.put("total",o.total().amount());fields.put("complete",o.complete());fields.put("hash",hash);fields.put("source",source);fields.put("review",o.review());
            UUID order=named.queryForObject("""
                INSERT INTO shopify_orders(tenant_id,marketplace_connection_id,order_gid,order_name,source_created_at,source_updated_at,cancelled_at,
                    is_test,payment_status,fulfillment_status,currency,total,evidence_complete,observation_hash,source_document_id,review_reason)
                VALUES(:tenant,:connection,:gid,:name,:created,:updated,:cancelled,:test,:payment,:fulfillment,:currency,:total,:complete,:hash,:source,:review)
                ON CONFLICT(tenant_id,marketplace_connection_id,order_gid) DO UPDATE SET order_name=EXCLUDED.order_name,
                    source_updated_at=EXCLUDED.source_updated_at,cancelled_at=EXCLUDED.cancelled_at,is_test=EXCLUDED.is_test,
                    payment_status=EXCLUDED.payment_status,fulfillment_status=EXCLUDED.fulfillment_status,total=EXCLUDED.total,currency=EXCLUDED.currency,
                    evidence_complete=EXCLUDED.evidence_complete,observation_hash=EXCLUDED.observation_hash,source_document_id=EXCLUDED.source_document_id,
                    observed_at=clock_timestamp(),review_reason=EXCLUDED.review_reason RETURNING id
                """,fields,UUID.class);
            if(!o.complete())continue; // Retain the prior complete line snapshot and reservations.
            for(var l:o.lines())publishLine(tenant,connection,order,o,l,cutoff);
            // A vanished line is not proof of cancellation or a physical return.
            for(var old:jdbc.queryForList("SELECT id,line_gid FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=?",tenant,connection,order))
                if(o.lines().stream().noneMatch(l->l.gid().equals(old.get("line_gid"))))
                    jdbc.update("UPDATE shopify_order_lines SET review_reason='Line absent from current order evidence; prior allocations retained',inventory_state='NEEDS_REVIEW' WHERE tenant_id=? AND id=?",tenant,old.get("id"));
        }
        inventory.reconcileTenant(tenant);
    }
    @SuppressWarnings("unchecked")
    private void publishLine(UUID tenant,UUID connection,UUID order,ShopifyOrderClient.Order o,ShopifyOrderClient.Line l,Instant cutoff){
        var oldRows=jdbc.queryForList("SELECT * FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND line_gid=? FOR UPDATE",tenant,connection,l.gid());
        var old=oldRows.isEmpty()?null:oldRows.getFirst();
        if(old!=null&&!order.equals(old.get("order_id")))throw new IllegalArgumentException("Shopify line identity belongs to another order.");
        String hash=ShopifyCatalogRepository.hash(json.writeValueAsString(Arrays.asList(o.created(),o.updated(),o.cancelled(),o.test(),o.payment(),o.complete(),l)));
        Identity identity=new Identity(tenant,connection,o.gid(),l.gid());History history=null;
        if(old!=null&&old.get("inventory_revision")!=null){
            var quantities=new LinkedHashMap<String,Integer>();
            ((Map<String,Object>)json.readValue(old.get("success_checkpoint").toString(),Map.class)).forEach((k,v)->quantities.put(k,((Number)v).intValue()));
            history=new History(identity,((Timestamp)old.get("inventory_revision")).toInstant(),old.get("inventory_hash").toString(),State.valueOf(old.get("stock_scope").toString()),quantities);
        }
        var observation=new Observation(identity,o.created(),o.updated(),o.complete(),o.test(),o.cancelled()!=null,o.payment(),l.shipping(),l.ownership(),l.ordered(),l.unfulfilled(),l.fulfillments(),hash);
        Plan plan=ShopifyOrderLifecycle.plan(observation,history,cutoff);
        boolean frozenVariantChanged=old!=null&&old.get("basis_hash")!=null&&!Objects.equals(old.get("basis_variant_gid"),l.variant());
        var variants=l.variant()==null?List.<UUID>of():jdbc.queryForList("SELECT id FROM shopify_variants WHERE tenant_id=? AND marketplace_connection_id=? AND variant_gid=?",UUID.class,tenant,connection,l.variant());
        var fields=new LinkedHashMap<String,Object>();fields.put("tenant",tenant);fields.put("connection",connection);fields.put("order",order);fields.put("line",l.gid());
        fields.put("variant",l.variant());fields.put("variantId",variants.isEmpty()?null:variants.getFirst());fields.put("sku",l.sku());fields.put("title",l.title());fields.put("image",l.image());
        fields.put("ordered",l.ordered());fields.put("current",l.current());fields.put("open",l.unfulfilled());fields.put("shipping",l.shipping());fields.put("owner",l.ownership().name());
        fields.put("updated",Timestamp.from(o.updated()));fields.put("hash",hash);fields.put("total",l.total().amount());fields.put("currency",l.total().currency());
        UUID line=named.queryForObject("""
            INSERT INTO shopify_order_lines(tenant_id,marketplace_connection_id,order_id,line_gid,variant_gid,variant_id,seller_sku,title,image_url,
                ordered_quantity,current_quantity,unfulfilled_quantity,requires_shipping,ownership,source_updated_at,observation_hash,line_total,currency)
            VALUES(:tenant,:connection,:order,:line,:variant,:variantId,:sku,:title,:image,:ordered,:current,:open,:shipping,:owner,:updated,:hash,:total,:currency)
            ON CONFLICT(tenant_id,marketplace_connection_id,line_gid) DO UPDATE SET variant_gid=EXCLUDED.variant_gid,variant_id=EXCLUDED.variant_id,
                seller_sku=EXCLUDED.seller_sku,title=EXCLUDED.title,image_url=EXCLUDED.image_url,ordered_quantity=EXCLUDED.ordered_quantity,
                current_quantity=EXCLUDED.current_quantity,unfulfilled_quantity=EXCLUDED.unfulfilled_quantity,requires_shipping=EXCLUDED.requires_shipping,
                ownership=EXCLUDED.ownership,source_updated_at=EXCLUDED.source_updated_at,observation_hash=EXCLUDED.observation_hash,
                line_total=EXCLUDED.line_total,currency=EXCLUDED.currency,updated_at=now() RETURNING id
            """,fields,UUID.class);
        // An OFF switch freezes existing operational checkpoints; subsequent re-enablement can still process missed evidence.
        if(history!=null&&history.state()==State.LIVE&&cutoff==null)return;
        if(frozenVariantChanged){review(tenant,line,"Variant identity changed after the order's warehouse mapping was frozen.");return;}
        if(plan.reserveUnits()==null){if(plan.review()!=null)review(tenant,line,plan.review());return;}
        if(plan.unchanged())return;
        if(old!=null&&((Number)old.get("local_handoff_units")).intValue()>0
                && (o.cancelled()!=null || "VOIDED".equals(o.payment()) || "REFUNDED".equals(o.payment())
                    || l.ordered()!=((Number)old.get("ordered_quantity")).intValue()
                    || l.current()<((Number)old.get("current_quantity")).intValue())){
            review(tenant,line,"Order changed after a local packing handoff. Review physical stock; no automatic return or new allocation.");return;
        }
        boolean shipmentReview=false;
        for(var shipment:plan.shipments()){
            var source=l.fulfillments().stream().filter(f->f.key().equals(shipment.fulfillmentKey())).findFirst().orElseThrow();
            shipmentReview|=inventory.ship(tenant,connection,order,line,shipment,source);
        }
        if(plan.releaseReservations()&&cutoff!=null)inventory.release(tenant,connection,line);
        String note=shipmentReview?"Successful fulfillment lacks complete prior allocations, or a physical count overlaps its evidence window. Review required; stock was not allocated retrospectively.":plan.review();
        String state=shipmentReview?"NEEDS_REVIEW":switch(plan.state()){
            case REPORTING_ONLY,TEST->"REPORTING_ONLY";case CANCELLED->"CANCELLED";case NEEDS_REVIEW->"NEEDS_REVIEW";case LIVE->"NEEDS_MAPPING";
        };
        jdbc.update("""
            UPDATE shopify_order_lines SET stock_scope=?,inventory_state=?,reserve_units=?,success_checkpoint=?::jsonb,
                inventory_revision=?,inventory_hash=?,review_reason=?,updated_at=now() WHERE tenant_id=? AND id=?
            """,plan.state().name(),state,plan.reserveUnits(),json.writeValueAsString(plan.nextSuccess()),Timestamp.from(o.updated()),hash,note,tenant,line);
    }
    private void review(UUID tenant,UUID line,String reason){jdbc.update("UPDATE shopify_order_lines SET inventory_state='NEEDS_REVIEW',review_reason=?,updated_at=now() WHERE tenant_id=? AND id=?",reason,tenant,line);}
    @Transactional
    public void catalogueReady(UUID tenant,UUID connection){
        if(!enabled)return;inventory.lock(tenant);
        jdbc.update("UPDATE shopify_order_lines l SET variant_id=v.id FROM shopify_variants v WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.variant_id IS NULL AND v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.variant_gid=l.variant_gid",tenant,connection);
        inventory.reconcileTenant(tenant);
    }
}
