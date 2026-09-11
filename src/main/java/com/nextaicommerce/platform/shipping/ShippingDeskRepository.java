package com.nextaicommerce.platform.shipping;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ShippingDeskRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ShippingDeskRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    public record PolicySettings(int handlingDays,int targetTransitDays,LocalTime cutoffTime,List<String> blockedServiceTerms,
            BigDecimal upsGroundPremiumLimit,boolean weekendHold,boolean paidOrExpeditedFridayHandoff){}
    public record Candidate(String orderId,Instant purchaseDate,Instant latestShip,Instant latestDelivery,String serviceLevel,
            BigDecimal customerShipping,String currency,String primarySku,String primaryTitle,String itemGroupKey,int units,
            BuyShippingRepository.PackageProfile profile,boolean expedited){}
    public record BatchSummary(UUID id,String name,String state,int total,int prepared,int purchased,int attention,
            BigDecimal quotedTotal,String currency,Instant createdAt){}
    public record BatchOrderView(UUID id,String orderId,String state,int printSequence,int priority,String primarySku,String primaryTitle,
            BigDecimal customerShipping,String serviceLevel,LocalDate handoffDate,boolean extraIce,BigDecimal selectedCost,
            String currency,String carrier,String shippingService,Instant delivery,String decisionReason,String error,UUID shipmentId,UUID selectedOfferId){}
    public record LabelView(UUID shipmentId,String orderId,String state,String carrier,String service,String tracking,BigDecimal cost,String currency,
            Instant purchasedAt,String packageName,String packageTag,int accessCount,Instant lastAccessedAt,UUID batchId){}
    public record BatchCommand(UUID itemId,UUID batchId,UUID tenantId,UUID connectionId,String marketplaceId,String orderId,
            BigDecimal customerShipping,String serviceLevel,BuyShippingRepository.PackageProfile profile,String actor){}
    public record BatchArtifact(UUID shipmentId,String orderId,byte[] encryptedPayload,byte[] nonce,String mimeType){}

    @Transactional(readOnly=true)
    public PolicySettings policy(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT handling_days,target_transit_days,cutoff_time,blocked_service_terms,ups_ground_premium_limit,
                   cold_chain_weekend_hold,paid_or_expedited_friday_handoff
            FROM buy_shipping_policies WHERE tenant_id=? AND marketplace_connection_id=?
            """,rs->rs.next()?new PolicySettings(rs.getInt(1),rs.getInt(2),rs.getTime(3).toLocalTime(),strings(rs.getString(4)),
                rs.getBigDecimal(5),rs.getBoolean(6),rs.getBoolean(7)):defaults(),tenantId,connectionId);
    }

    @Transactional(readOnly=true)
    public PolicySettings batchPolicy(UUID tenantId,UUID batchId){
        setTenant(tenantId);
        String snapshot=jdbc.query("SELECT policy_snapshot::text FROM buy_shipping_batches WHERE tenant_id=? AND id=?",
            rs->rs.next()?rs.getString(1):null,tenantId,batchId);
        if(snapshot==null)throw new IllegalArgumentException("Shipping batch was not found.");
        try{return json.readValue(snapshot,PolicySettings.class);}
        catch(Exception ex){throw new IllegalStateException("This batch's saved shipping policy could not be read.",ex);}
    }

    @Transactional
    public PolicySettings savePolicy(UUID tenantId,UUID connectionId,PolicySettings value){
        setTenant(tenantId);if(value.handlingDays()<0||value.handlingDays()>10)throw new IllegalArgumentException("Handling days must be between 0 and 10.");
        if(value.targetTransitDays()<1||value.targetTransitDays()>7)throw new IllegalArgumentException("Transit days must be between 1 and 7.");
        if(value.cutoffTime()==null)throw new IllegalArgumentException("Choose a carrier cutoff time.");
        if(value.upsGroundPremiumLimit()==null||value.upsGroundPremiumLimit().signum()<0)throw new IllegalArgumentException("UPS Ground premium cannot be negative.");
        List<String> blocked=value.blockedServiceTerms()==null?List.of():value.blockedServiceTerms().stream().map(String::trim).filter(term->!term.isBlank()).limit(20).toList();
        jdbc.update("""
            INSERT INTO buy_shipping_policies(tenant_id,marketplace_connection_id,handling_days,target_transit_days,cutoff_time,
                blocked_service_terms,ups_ground_premium_limit,cold_chain_weekend_hold,paid_or_expedited_friday_handoff)
            VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id) DO UPDATE SET
                handling_days=EXCLUDED.handling_days,target_transit_days=EXCLUDED.target_transit_days,cutoff_time=EXCLUDED.cutoff_time,
                blocked_service_terms=EXCLUDED.blocked_service_terms,ups_ground_premium_limit=EXCLUDED.ups_ground_premium_limit,
                cold_chain_weekend_hold=EXCLUDED.cold_chain_weekend_hold,
                paid_or_expedited_friday_handoff=EXCLUDED.paid_or_expedited_friday_handoff
            """,tenantId,connectionId,value.handlingDays(),value.targetTransitDays(),Time.valueOf(value.cutoffTime()),
            json.valueToTree(blocked).toString(),value.upsGroundPremiumLimit(),value.weekendHold(),value.paidOrExpeditedFridayHandoff());
        return policy(tenantId,connectionId);
    }

    @Transactional(readOnly=true)
    public List<Candidate> candidates(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        return jdbc.query("""
            WITH committed AS (
              SELECT shipped.amazon_order_item_id,sum(shipped.quantity) quantity
              FROM buy_shipping_shipment_items shipped JOIN buy_shipping_shipments shipment
                ON shipment.tenant_id=shipped.tenant_id AND shipment.id=shipped.shipment_id
              WHERE shipped.tenant_id=? AND shipped.marketplace_connection_id=?
                AND shipment.state IN ('PURCHASE_QUEUED','PURCHASE_IN_PROGRESS','PURCHASED','PURCHASE_UNKNOWN')
              GROUP BY shipped.amazon_order_item_id
            ), open_items AS (
              SELECT item.*,greatest(item.quantity_ordered-item.quantity_shipped-coalesce(committed.quantity,0),0) remaining
              FROM amazon_order_items item LEFT JOIN committed ON committed.amazon_order_item_id=item.amazon_order_item_id
              WHERE item.tenant_id=? AND item.marketplace_connection_id=?
            )
            SELECT orders.amazon_order_id,orders.purchase_date,orders.latest_ship_date,orders.latest_delivery_date,
                   orders.ship_service_level,coalesce(sum(greatest(coalesce(item.shipping_price,0)-coalesce(item.shipping_discount,0),0)),0),
                   coalesce(orders.currency,min(item.currency),'USD'),min(item.seller_sku),min(item.title),
                   string_agg(coalesce(item.seller_sku,'')||':'||item.remaining::text,'|' ORDER BY item.seller_sku,item.amazon_order_item_id),
                   sum(item.remaining)::integer,
                   profile.id,profile.name,profile.container_code,profile.length,profile.width,profile.height,profile.dimension_unit,
                   profile.weight,profile.weight_unit,profile.preferred_carrier,profile.temperature_class
            FROM amazon_orders orders JOIN open_items item ON item.amazon_order_id=orders.amazon_order_id AND item.remaining>0
            JOIN marketplace_sku_package_defaults defaults ON defaults.tenant_id=item.tenant_id
              AND defaults.marketplace_connection_id=item.marketplace_connection_id AND defaults.seller_sku=item.seller_sku
            JOIN shipping_package_profiles profile ON profile.tenant_id=defaults.tenant_id AND profile.id=defaults.package_profile_id AND profile.status='ACTIVE'
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=?
              AND regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g') IN ('PENDING','UNSHIPPED')
              AND upper(coalesce(orders.fulfillment_channel,'')) NOT IN ('AFN','AMAZON')
              AND NOT EXISTS(SELECT 1 FROM buy_shipping_batch_orders existing
                WHERE existing.tenant_id=orders.tenant_id AND existing.marketplace_connection_id=orders.marketplace_connection_id
                  AND existing.amazon_order_id=orders.amazon_order_id AND existing.state IN ('PENDING','RATING','RATED','PURCHASE_QUEUED'))
            GROUP BY orders.amazon_order_id,orders.purchase_date,orders.latest_ship_date,orders.latest_delivery_date,
                orders.ship_service_level,orders.currency,profile.id
            HAVING count(DISTINCT profile.id)=1 AND count(DISTINCT item.id)=(SELECT count(*) FROM open_items all_items
                WHERE all_items.amazon_order_id=orders.amazon_order_id AND all_items.remaining>0)
            ORDER BY orders.latest_ship_date NULLS LAST,orders.purchase_date
            LIMIT 200
            """,(rs,row)->{
                var profile=new BuyShippingRepository.PackageProfile(rs.getObject(12,UUID.class),rs.getString(13),rs.getString(14),
                    rs.getBigDecimal(15),rs.getBigDecimal(16),rs.getBigDecimal(17),rs.getString(18),rs.getBigDecimal(19),rs.getString(20),rs.getString(21),rs.getString(22));
                String level=rs.getString(5);return new Candidate(rs.getString(1),instant(rs.getTimestamp(2)),instant(rs.getTimestamp(3)),
                    instant(rs.getTimestamp(4)),level,rs.getBigDecimal(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getInt(11),profile,
                    ColdChainShippingPolicy.isExpedited(level));
            },tenantId,connectionId,tenantId,connectionId,tenantId,connectionId);
    }

    @Transactional
    public UUID createBatch(UUID tenantId,UUID connectionId,String name,List<Candidate> selected,PolicySettings policy,String actor){
        setTenant(tenantId);UUID batchId=UUID.randomUUID();
        jdbc.update("INSERT INTO buy_shipping_batches(id,tenant_id,marketplace_connection_id,name,policy_snapshot,created_by_email) VALUES(?,?,?,?,CAST(? AS jsonb),?)",
            batchId,tenantId,connectionId,required(name,"Batch name"),json.valueToTree(policy).toString(),actor);
        List<Candidate> ordered=selected.stream().sorted(java.util.Comparator.comparing((Candidate item)->item.expedited()?0:1)
            .thenComparing(item->safe(item.itemGroupKey())).thenComparing(Candidate::purchaseDate,java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
        int sequence=0;for(Candidate candidate:ordered)jdbc.update("""
            INSERT INTO buy_shipping_batch_orders(tenant_id,batch_id,marketplace_connection_id,amazon_order_id,package_profile_id,
                print_sequence,priority,group_key,primary_sku,primary_title,customer_shipping,service_level,currency)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,tenantId,batchId,connectionId,candidate.orderId(),candidate.profile().id(),++sequence,candidate.expedited()?0:1,
            limit(candidate.itemGroupKey()),candidate.primarySku(),candidate.primaryTitle(),candidate.customerShipping(),candidate.serviceLevel(),candidate.currency());
        return batchId;
    }

    @Transactional
    public BatchCommand claimPending(UUID tenantId){
        setTenant(tenantId);
        return jdbc.query("""
            UPDATE buy_shipping_batch_orders item SET state='RATING',last_error=NULL
            FROM buy_shipping_batches batch,marketplace_connections connection,shipping_package_profiles profile
            WHERE item.id=(SELECT queued.id FROM buy_shipping_batch_orders queued JOIN buy_shipping_batches owner
                ON owner.tenant_id=queued.tenant_id AND owner.id=queued.batch_id
                WHERE queued.tenant_id=? AND queued.state='PENDING' AND owner.state='RATING'
                ORDER BY owner.created_at,queued.print_sequence FOR UPDATE OF queued SKIP LOCKED LIMIT 1)
              AND batch.tenant_id=item.tenant_id AND batch.id=item.batch_id
              AND connection.tenant_id=item.tenant_id AND connection.id=item.marketplace_connection_id
              AND profile.tenant_id=item.tenant_id AND profile.id=item.package_profile_id
            RETURNING item.id,item.batch_id,item.tenant_id,item.marketplace_connection_id,connection.marketplace_identifier,
                item.amazon_order_id,item.customer_shipping,item.service_level,profile.id,profile.name,profile.container_code,
                profile.length,profile.width,profile.height,profile.dimension_unit,profile.weight,profile.weight_unit,
                profile.preferred_carrier,profile.temperature_class,batch.created_by_email
            """,rs->rs.next()?new BatchCommand(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getBigDecimal(7),rs.getString(8),
                new BuyShippingRepository.PackageProfile(rs.getObject(9,UUID.class),rs.getString(10),rs.getString(11),rs.getBigDecimal(12),
                    rs.getBigDecimal(13),rs.getBigDecimal(14),rs.getString(15),rs.getBigDecimal(16),rs.getString(17),rs.getString(18),rs.getString(19)),rs.getString(20)):null,tenantId);
    }

    @Transactional
    public void recoverAbandonedRatings(UUID tenantId){setTenant(tenantId);
        jdbc.update("""
            UPDATE buy_shipping_batch_orders item SET state='PENDING',last_error='Rate preparation resumed after an application interruption.'
            FROM buy_shipping_batches batch
            WHERE item.tenant_id=? AND item.state='RATING' AND item.updated_at<now()-interval '2 minutes'
              AND batch.tenant_id=item.tenant_id AND batch.id=item.batch_id AND batch.state='RATING'
            """,tenantId);
    }

    @Transactional
    public void rated(UUID tenantId,UUID itemId,UUID shipmentId,UUID offerId,BigDecimal cost,String currency,LocalDate handoff,
            boolean extraIce,String reason,JsonNode decision){
        setTenant(tenantId);
        jdbc.update("""
            UPDATE buy_shipping_batch_orders SET state='RATED',shipment_id=?,selected_offer_id=?,selected_cost=?,currency=?,
                planned_handoff_date=?,extra_ice=?,decision_reason=?,last_error=NULL WHERE tenant_id=? AND id=?
            """,shipmentId,offerId,cost,currency,handoff,extraIce,limit(reason),tenantId,itemId);
        jdbc.update("UPDATE buy_shipping_shipments SET batch_id=(SELECT batch_id FROM buy_shipping_batch_orders WHERE tenant_id=? AND id=?),planned_handoff_date=?,extra_ice=?,service_decision=CAST(? AS jsonb) WHERE tenant_id=? AND id=?",
            tenantId,itemId,handoff,extraIce,decision.toString(),tenantId,shipmentId);
        refreshBatchForItem(tenantId,itemId);
    }

    @Transactional
    public void attention(UUID tenantId,UUID itemId,String message){setTenant(tenantId);
        jdbc.update("UPDATE buy_shipping_batch_orders SET state='NEEDS_ATTENTION',last_error=? WHERE tenant_id=? AND id=?",limit(message),tenantId,itemId);refreshBatchForItem(tenantId,itemId);}

    @Transactional
    public void queued(UUID tenantId,UUID itemId){setTenant(tenantId);
        jdbc.update("UPDATE buy_shipping_batch_orders SET state='PURCHASE_QUEUED' WHERE tenant_id=? AND id=?",tenantId,itemId);refreshBatchForItem(tenantId,itemId);}

    @Transactional
    public void confirmBatch(UUID tenantId,UUID connectionId,UUID batchId,String actor){setTenant(tenantId);
        int changed=jdbc.update("UPDATE buy_shipping_batches SET state='PURCHASE_QUEUED',confirmed_by_email=?,confirmed_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state='AWAITING_REVIEW'",
            actor,tenantId,connectionId,batchId);if(changed!=1)throw new IllegalStateException("This batch is not ready for purchase review.");}

    @Transactional(readOnly=true)
    public List<BatchSummary> batches(UUID tenantId,UUID connectionId){setTenant(tenantId);
        return jdbc.query("""
            SELECT batch.id,batch.name,batch.state,count(item.id),count(item.id) FILTER (WHERE item.state NOT IN ('PENDING','RATING')),
                   count(item.id) FILTER (WHERE item.state='PURCHASED'),count(item.id) FILTER (WHERE item.state IN ('NEEDS_ATTENTION','FAILED','PURCHASE_UNKNOWN')),
                   coalesce(sum(item.selected_cost),0),coalesce(batch.currency,min(item.currency)),batch.created_at
            FROM buy_shipping_batches batch LEFT JOIN buy_shipping_batch_orders item ON item.tenant_id=batch.tenant_id AND item.batch_id=batch.id
            WHERE batch.tenant_id=? AND batch.marketplace_connection_id=? GROUP BY batch.id ORDER BY batch.created_at DESC LIMIT 20
            """,(rs,row)->new BatchSummary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),
                rs.getInt(6),rs.getInt(7),rs.getBigDecimal(8),rs.getString(9),instant(rs.getTimestamp(10))),tenantId,connectionId);}

    @Transactional(readOnly=true)
    public BatchSummary batch(UUID tenantId,UUID connectionId,UUID batchId){setTenant(tenantId);
        return jdbc.query("""
            SELECT batch.id,batch.name,batch.state,count(item.id),count(item.id) FILTER (WHERE item.state NOT IN ('PENDING','RATING')),
                   count(item.id) FILTER (WHERE item.state='PURCHASED'),count(item.id) FILTER (WHERE item.state IN ('NEEDS_ATTENTION','FAILED','PURCHASE_UNKNOWN')),
                   coalesce(sum(item.selected_cost),0),coalesce(batch.currency,min(item.currency)),batch.created_at
            FROM buy_shipping_batches batch LEFT JOIN buy_shipping_batch_orders item ON item.tenant_id=batch.tenant_id AND item.batch_id=batch.id
            WHERE batch.tenant_id=? AND batch.marketplace_connection_id=? AND batch.id=? GROUP BY batch.id
            """,rs->rs.next()?new BatchSummary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),
                rs.getInt(6),rs.getInt(7),rs.getBigDecimal(8),rs.getString(9),instant(rs.getTimestamp(10))):null,tenantId,connectionId,batchId);}

    @Transactional(readOnly=true)
    public List<BatchOrderView> batchOrders(UUID tenantId,UUID connectionId,UUID batchId){setTenant(tenantId);
        return jdbc.query("""
            SELECT item.id,item.amazon_order_id,item.state,item.print_sequence,item.priority,item.primary_sku,item.primary_title,
                   item.customer_shipping,item.service_level,item.planned_handoff_date,item.extra_ice,item.selected_cost,item.currency,
                   offer.carrier_name,offer.service_name,offer.latest_delivery,item.decision_reason,item.last_error,item.shipment_id,item.selected_offer_id
            FROM buy_shipping_batch_orders item JOIN buy_shipping_batches batch ON batch.tenant_id=item.tenant_id AND batch.id=item.batch_id
            LEFT JOIN buy_shipping_rate_offers offer ON offer.tenant_id=item.tenant_id AND offer.id=item.selected_offer_id
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.batch_id=? ORDER BY item.print_sequence
            """,(rs,row)->new BatchOrderView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),
                rs.getString(6),rs.getString(7),rs.getBigDecimal(8),rs.getString(9),rs.getObject(10,LocalDate.class),rs.getBoolean(11),
                rs.getBigDecimal(12),rs.getString(13),rs.getString(14),rs.getString(15),instant(rs.getTimestamp(16)),rs.getString(17),
                rs.getString(18),rs.getObject(19,UUID.class),rs.getObject(20,UUID.class)),tenantId,connectionId,batchId);}

    @Transactional(readOnly=true)
    public List<LabelView> labels(UUID tenantId,UUID connectionId){setTenant(tenantId);
        return jdbc.query("""
            SELECT shipment.id,shipment.amazon_order_id,shipment.state,shipment.carrier_name,shipment.shipping_service_name,shipment.tracking_id,
                   shipment.adjusted_rate_amount,shipment.currency,shipment.purchased_at,shipment.package_snapshot->>'name',
                   shipment.package_snapshot->>'containerCode',artifact.access_count,artifact.last_accessed_at,shipment.batch_id
            FROM buy_shipping_shipments shipment JOIN shipping_label_artifacts artifact ON artifact.tenant_id=shipment.tenant_id
              AND artifact.shipment_id=shipment.id AND artifact.artifact_type='COMPOSED_PRINT_FILE'
            WHERE shipment.tenant_id=? AND shipment.marketplace_connection_id=? ORDER BY shipment.purchased_at DESC NULLS LAST LIMIT 200
            """,(rs,row)->new LabelView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getString(6),rs.getBigDecimal(7),rs.getString(8),instant(rs.getTimestamp(9)),rs.getString(10),rs.getString(11),rs.getInt(12),
                instant(rs.getTimestamp(13)),rs.getObject(14,UUID.class)),tenantId,connectionId);}

    @Transactional
    public void recordAccess(UUID tenantId,UUID shipmentId){setTenant(tenantId);
        jdbc.update("UPDATE shipping_label_artifacts SET access_count=access_count+1,last_accessed_at=now() WHERE tenant_id=? AND shipment_id=? AND artifact_type='COMPOSED_PRINT_FILE'",tenantId,shipmentId);}

    @Transactional(readOnly=true)
    public List<BatchArtifact> batchArtifacts(UUID tenantId,UUID connectionId,UUID batchId){setTenant(tenantId);
        return jdbc.query("""
            SELECT shipment.id,item.amazon_order_id,artifact.encrypted_payload,artifact.encryption_nonce,artifact.mime_type
            FROM buy_shipping_batch_orders item JOIN buy_shipping_shipments shipment ON shipment.tenant_id=item.tenant_id AND shipment.id=item.shipment_id
            JOIN shipping_label_artifacts artifact ON artifact.tenant_id=shipment.tenant_id AND artifact.shipment_id=shipment.id
              AND artifact.artifact_type='COMPOSED_PRINT_FILE'
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.batch_id=? AND item.state='PURCHASED'
              AND shipment.state='PURCHASED'
            ORDER BY item.print_sequence
            """,(rs,row)->new BatchArtifact(rs.getObject(1,UUID.class),rs.getString(2),rs.getBytes(3),rs.getBytes(4),rs.getString(5)),tenantId,connectionId,batchId);}

    @Transactional
    public void discard(UUID tenantId,UUID connectionId,UUID batchId){setTenant(tenantId);
        int changed=jdbc.update("UPDATE buy_shipping_batches SET state='FAILED',completed_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state IN ('RATING','AWAITING_REVIEW')",tenantId,connectionId,batchId);
        if(changed!=1)throw new IllegalStateException("A batch cannot be discarded after purchases are queued.");
        jdbc.update("UPDATE buy_shipping_batch_orders SET state='FAILED',last_error='Batch discarded before purchase.' WHERE tenant_id=? AND batch_id=? AND state IN ('PENDING','RATING','RATED','NEEDS_ATTENTION')",tenantId,batchId);}

    private void refreshBatchForItem(UUID tenantId,UUID itemId){
        UUID batchId=jdbc.queryForObject("SELECT batch_id FROM buy_shipping_batch_orders WHERE tenant_id=? AND id=?",UUID.class,tenantId,itemId);
        jdbc.update("""
            UPDATE buy_shipping_batches batch SET quoted_total=totals.cost,currency=totals.currency,state=CASE
              WHEN batch.state IN ('PURCHASE_QUEUED','PROCESSING') AND totals.active_purchase>0 THEN 'PROCESSING'
              WHEN totals.pending>0 THEN 'RATING'
              WHEN totals.rated>0 THEN 'AWAITING_REVIEW'
              WHEN totals.purchased=totals.total AND totals.total>0 THEN 'READY'
              WHEN totals.purchased>0 AND totals.attention>0 THEN 'PARTIAL'
              WHEN totals.attention=totals.total THEN 'FAILED'
              ELSE batch.state END,
              completed_at=CASE WHEN totals.purchased=totals.total OR totals.attention=totals.total OR (totals.purchased>0 AND totals.attention>0) THEN now() ELSE batch.completed_at END
            FROM (SELECT count(*) total,count(*) FILTER (WHERE state IN ('PENDING','RATING')) pending,
                count(*) FILTER (WHERE state='RATED') rated,count(*) FILTER (WHERE state='PURCHASED') purchased,
                count(*) FILTER (WHERE state IN ('NEEDS_ATTENTION','FAILED','PURCHASE_UNKNOWN')) attention,
                count(*) FILTER (WHERE state='PURCHASE_QUEUED') active_purchase,
                coalesce(sum(selected_cost),0) cost,min(currency) currency FROM buy_shipping_batch_orders WHERE tenant_id=? AND batch_id=?) totals
            WHERE batch.tenant_id=? AND batch.id=?
            """,tenantId,batchId,tenantId,batchId);
    }

    static PolicySettings defaults(){return new PolicySettings(0,2,LocalTime.of(11,0),List.of("SUREPOST"),new BigDecimal("1.00"),true,true);}
    private List<String> strings(String value){try{JsonNode node=json.readTree(value);List<String> result=new ArrayList<>();if(node.isArray())for(JsonNode item:node)result.add(item.asText());return List.copyOf(result);}catch(Exception ex){return List.of("SUREPOST");}}
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static String required(String value,String label){if(value==null||value.isBlank())throw new IllegalArgumentException(label+" is required.");return value.trim();}
    private static String safe(String value){return value==null?"":value;}
    private static String limit(String value){String safe=value==null?"Unexpected error.":value;return safe.length()<=500?safe:safe.substring(0,500);}
}
