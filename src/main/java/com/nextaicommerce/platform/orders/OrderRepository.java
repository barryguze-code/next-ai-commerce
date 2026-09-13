package com.nextaicommerce.platform.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.nextaicommerce.platform.sync.AmazonMarketplaceTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {
    static final String MARKETPLACE_IDENTIFIER_SQL="SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?";
    private final JdbcTemplate jdbc;
    public OrderRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record OrderView(UUID id,String amazonOrderId,Instant purchaseDate,LocalDate purchaseDay,String amazonStatus,
            String fulfillmentChannel,String scope,String state,int itemCount,int units,String currency,
            BigDecimal total,String firstSku,String unmappedSku,int unmappedItems,BigDecimal reservedUnits,
            String imageUrl,String marketplaceId){
        public String stateLabel(){return switch(state){
            case "REPORTING_ONLY" -> "Historical"; case "AMAZON_FULFILLED" -> "Amazon fulfilled";
            case "NEEDS_MAPPING" -> "Needs mapping"; case "INVENTORY_SHORTAGE" -> "Inventory shortage";
            case "READY_TO_SHIP" -> "Ready to ship"; case "SHIPPED" -> "Shipped";
            case "CANCELLED" -> "Cancelled"; case "ON_HOLD" -> "On hold"; default -> state;
        };}
        public boolean needsMapping(){return "NEEDS_MAPPING".equals(state);}
        public String amazonStatusLabel(){return amazonStatus==null||amazonStatus.isBlank()?"Status pending":amazonStatus.replaceAll("([a-z])([A-Z])","$1 $2");}
        public String sellerCentralUrl(){return "https://"+sellerCentralDomain(marketplaceId)+"/orders-v3/order/"+amazonOrderId;}
    }
    public record OrderItemView(UUID id,String sellerSku,String asin,String title,int ordered,int shipped,
            String mapping,String mappingDetail,BigDecimal reserved,String imageUrl,BigDecimal available,
            BigDecimal itemPrice,BigDecimal shippingPrice,String currency,BigDecimal buyBoxPrice,
            String buyBoxCurrency,Instant buyBoxUpdatedAt){
        public BigDecimal safeItemPrice(){return itemPrice==null?BigDecimal.ZERO:itemPrice;}
        public BigDecimal safeShippingPrice(){return shippingPrice==null?BigDecimal.ZERO:shippingPrice;}
        public String inventoryUrl(String marketplaceId){return asin==null||asin.isBlank()?null:
            "https://"+sellerCentralDomain(marketplaceId)+"/myinventory/inventory?searchTerm="+
                java.net.URLEncoder.encode(asin,java.nio.charset.StandardCharsets.UTF_8);}
        public String amazonUrl(String marketplaceId){return asin==null||asin.isBlank()?null:
            "https://www."+amazonDomain(marketplaceId)+"/dp/"+asin;}
    }
    public record OrderSummary(long todayOrders,BigDecimal todaySales,BigDecimal sales30Days,long live,long historical,
            Instant lastSyncedAt){}
    public record OrderPage(List<OrderView> rows,long total,int page,int size){
        public int totalPages(){return total==0?1:(int)Math.ceil((double)total/size);}
        public boolean hasPrevious(){return page>0;}
        public boolean hasNext(){return page+1<totalPages();}
    }
    public record ReconciliationResult(int reservedOrders,BigDecimal reservedEaches,
            int shortageOrders,int unmappedOrders,int amazonFulfilledOpenOrders) {}

    @Transactional(readOnly=true)
    public LocalDate marketplaceToday(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        String marketplaceId=jdbc.queryForObject(MARKETPLACE_IDENTIFIER_SQL,String.class,tenantId,connectionId);
        return LocalDate.now(AmazonMarketplaceTime.zone(marketplaceId));
    }

    @Transactional
    public ReconciliationResult reconcile(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",rs->{},tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",rs->{},connectionId);
        return reconcileConnectionInventory(tenantId,connectionId);
    }

    private ReconciliationResult reconcileConnectionInventory(UUID tenantId,UUID connectionId){
        jdbc.update("""
            UPDATE amazon_orders orders SET operational_scope=CASE
                    WHEN orders.purchase_date>=connection.inventory_activated_at THEN 'LIVE' ELSE 'HISTORICAL' END,
                fulfillment_state=CASE
                    WHEN orders.purchase_date<connection.inventory_activated_at THEN 'REPORTING_ONLY'
                    WHEN regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g')
                        IN ('CANCELLED','CANCELED') THEN 'CANCELLED'
                    WHEN upper(coalesce(orders.fulfillment_channel,'')) IN ('AFN','AMAZON') THEN 'AMAZON_FULFILLED'
                    WHEN regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g')
                        IN ('PENDING','UNSHIPPED') THEN 'NEEDS_MAPPING'
                    ELSE orders.fulfillment_state END,
                operational_updated_at=now()
            FROM marketplace_connections connection
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=?
              AND connection.tenant_id=orders.tenant_id AND connection.id=orders.marketplace_connection_id
            """,tenantId,connectionId);
        List<Object[]> candidates=jdbc.query("""
            SELECT orders.amazon_order_id,orders.order_status,orders.fulfillment_state,
                   EXISTS (SELECT 1 FROM order_inventory_reservations reservation
                     WHERE reservation.tenant_id=orders.tenant_id
                       AND reservation.marketplace_connection_id=orders.marketplace_connection_id
                       AND reservation.amazon_order_id=orders.amazon_order_id AND reservation.status='ACTIVE')
            FROM amazon_orders orders
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=? AND orders.operational_scope='LIVE'
              AND upper(coalesce(orders.fulfillment_channel,'')) NOT IN ('AFN','AMAZON')
            ORDER BY orders.purchase_date,orders.created_at
            """,(rs,row)->new Object[]{rs.getString(1),rs.getString(2),rs.getString(3),rs.getBoolean(4)},tenantId,connectionId);
        for(Object[] candidate:candidates){
            if(statusLeavesWarehouse((String)candidate[1]))
                removeLateShipmentPostings(tenantId,connectionId,(String)candidate[0]);
            if(statusLeavesWarehouse((String)candidate[1])
                    &&(!"SHIPPED".equals(candidate[2])||(boolean)candidate[3])){
                if((boolean)candidate[3])
                    postActiveReservations(tenantId,connectionId,(String)candidate[0],null);
                allocateOrder(tenantId,connectionId,(String)candidate[0]);
            }
        }
        jdbc.update("DELETE FROM order_inventory_reservations WHERE tenant_id=? AND marketplace_connection_id=? AND status='ACTIVE'",
            tenantId,connectionId);
        for(Object[] candidate:candidates){
            if(statusReservesInventory((String)candidate[1]))
                allocateOrder(tenantId,connectionId,(String)candidate[0]);
        }
        return reconciliationResult(tenantId,connectionId);
    }

    /** Rebuilds reservations across every store after an authoritative physical count. */
    @Transactional
    public int reconcileTenantAfterPhysicalCount(UUID tenantId){
        setTenant(tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",rs->{},tenantId);
        List<UUID> connections=jdbc.query("""
            SELECT id FROM marketplace_connections
            WHERE tenant_id=? AND channel='AMAZON' AND status='ACTIVE' ORDER BY id
            """,(rs,row)->rs.getObject(1,UUID.class),tenantId);
        for(UUID connectionId:connections)reconcileConnectionInventory(tenantId,connectionId);
        Integer shortages=jdbc.queryForObject("SELECT count(*) FROM amazon_orders WHERE tenant_id=? AND operational_scope='LIVE' AND fulfillment_state='INVENTORY_SHORTAGE'",Integer.class,tenantId);
        return shortages==null?0:shortages;
    }

    private void allocateOrder(UUID tenantId,UUID connectionId,String orderId){
        String amazonStatus=jdbc.queryForObject("SELECT order_status FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",
            String.class,tenantId,connectionId,orderId);
        boolean leavesWarehouse=statusLeavesWarehouse(amazonStatus);
        Integer missing=jdbc.queryForObject("""
            SELECT count(*) FROM amazon_order_items item
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.amazon_order_id=?
              AND item.quantity_ordered>0
              AND NOT EXISTS (SELECT 1 FROM marketplace_sku_mappings mapping
                JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
                  AND component.marketplace_sku_mapping_id=mapping.id
                WHERE mapping.tenant_id=item.tenant_id AND mapping.marketplace_connection_id=item.marketplace_connection_id
                  AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE')
            """,Integer.class,tenantId,connectionId,orderId);
        if(missing!=null&&missing>0){setState(tenantId,connectionId,orderId,"NEEDS_MAPPING");return;}
        List<Object[]> needs=jdbc.query("""
            SELECT item.id,component.account_catalog_item_id,
                   greatest((CASE WHEN ? THEN item.quantity_ordered ELSE item.quantity_shipped END)*component.quantity
                     -coalesce(allocated.quantity,0),0) ship_required,
                   greatest((item.quantity_ordered-CASE WHEN ? THEN item.quantity_ordered ELSE item.quantity_shipped END)
                     *component.quantity,0) reserve_required
            FROM amazon_order_items item
            JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
              AND mapping.marketplace_connection_id=item.marketplace_connection_id
              AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
            JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
              AND component.marketplace_sku_mapping_id=mapping.id
            LEFT JOIN LATERAL (SELECT sum(reservation.quantity) quantity FROM order_inventory_reservations reservation
              WHERE reservation.tenant_id=item.tenant_id AND reservation.amazon_order_item_id=item.id
                AND reservation.account_catalog_item_id=component.account_catalog_item_id
                AND reservation.status IN ('ACTIVE','SHIPPED')) allocated ON true
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.amazon_order_id=?
            ORDER BY item.created_at,component.sort_order
            """,(rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBigDecimal(3),rs.getBigDecimal(4)},
            leavesWarehouse,leavesWarehouse,tenantId,connectionId,orderId);
        if(needs.isEmpty()){setState(tenantId,connectionId,orderId,"ON_HOLD");return;}
        boolean shortage=false;
        for(Object[] need:needs){
            UUID orderItemId=(UUID)need[0],catalogItemId=(UUID)need[1];
            // A physical count is an authoritative snapshot of what was actually on the shelf
            // when it was taken.  Amazon can report an older shipment days later; do not let
            // that delayed status consume the same stock a second time after the count.
            if(leavesWarehouse&&physicalCountAlreadyIncludesShipment(tenantId,connectionId,orderId,catalogItemId)){
                removeLateShipmentPosting(tenantId,connectionId,orderId,catalogItemId);
                continue;
            }
            BigDecimal shipRemaining=allocateQuantity(tenantId,connectionId,orderId,orderItemId,catalogItemId,(BigDecimal)need[2]);
            if(leavesWarehouse){
                postActiveReservations(tenantId,connectionId,orderId,orderItemId);
                if(shipRemaining.signum()>0)shortage=true;
            }
            BigDecimal reserveRemaining=allocateQuantity(tenantId,connectionId,orderId,orderItemId,catalogItemId,(BigDecimal)need[3]);
            if(reserveRemaining.signum()>0)shortage=true;
        }
        if(leavesWarehouse&&!shortage)setState(tenantId,connectionId,orderId,"SHIPPED");
        else setState(tenantId,connectionId,orderId,shortage?"INVENTORY_SHORTAGE":"READY_TO_SHIP");
    }

    /**
     * Amazon order reports can arrive after a warehouse count has already established the
     * current quantity.  A count posted after Amazon's last status timestamp includes that
     * historical shipment, so it must win over the delayed report.
     */
    private boolean physicalCountAlreadyIncludesShipment(UUID tenantId,UUID connectionId,String orderId,UUID catalogItemId){
        Boolean countedAfterShipment=jdbc.queryForObject("""
            SELECT EXISTS (
              SELECT 1
              FROM amazon_orders orders
              JOIN inventory_ledger_entries count_entry
                ON count_entry.tenant_id=orders.tenant_id
               AND count_entry.account_catalog_item_id=?
               AND count_entry.source_type='PHYSICAL_COUNT'
              WHERE orders.tenant_id=? AND orders.marketplace_connection_id=? AND orders.amazon_order_id=?
                AND count_entry.occurred_at>=coalesce(orders.last_update_date,orders.purchase_date,orders.created_at)
            )
            """,Boolean.class,catalogItemId,tenantId,connectionId,orderId);
        return Boolean.TRUE.equals(countedAfterShipment);
    }

    /** Removes a shipment posting that was created from a delayed Amazon status report. */
    private void removeLateShipmentPosting(UUID tenantId,UUID connectionId,String orderId,UUID catalogItemId){
        jdbc.update("""
            DELETE FROM inventory_ledger_entries ledger
            USING amazon_orders orders
            WHERE ledger.tenant_id=? AND ledger.marketplace_connection_id=?
              AND ledger.account_catalog_item_id=? AND ledger.entry_type='SHIPMENT'
              AND ledger.source_type='AMAZON_ORDER' AND ledger.source_id=orders.id
              AND orders.tenant_id=ledger.tenant_id
              AND orders.marketplace_connection_id=ledger.marketplace_connection_id
              AND orders.amazon_order_id=?
              AND EXISTS (
                SELECT 1 FROM inventory_ledger_entries count_entry
                WHERE count_entry.tenant_id=ledger.tenant_id
                  AND count_entry.account_catalog_item_id=ledger.account_catalog_item_id
                  AND count_entry.source_type='PHYSICAL_COUNT'
                  AND count_entry.occurred_at>=coalesce(orders.last_update_date,orders.purchase_date,orders.created_at)
              )
            """,tenantId,connectionId,catalogItemId,orderId);
    }

    /** Repairs an existing delayed posting during every reconciliation, including already-shipped orders. */
    private void removeLateShipmentPostings(UUID tenantId,UUID connectionId,String orderId){
        jdbc.update("""
            DELETE FROM inventory_ledger_entries ledger
            USING amazon_orders orders
            WHERE ledger.tenant_id=? AND ledger.marketplace_connection_id=?
              AND ledger.entry_type='SHIPMENT' AND ledger.source_type='AMAZON_ORDER'
              AND ledger.source_id=orders.id
              AND orders.tenant_id=ledger.tenant_id
              AND orders.marketplace_connection_id=ledger.marketplace_connection_id
              AND orders.amazon_order_id=?
              AND EXISTS (
                SELECT 1 FROM inventory_ledger_entries count_entry
                WHERE count_entry.tenant_id=ledger.tenant_id
                  AND count_entry.account_catalog_item_id=ledger.account_catalog_item_id
                  AND count_entry.source_type='PHYSICAL_COUNT'
                  AND count_entry.occurred_at>=coalesce(orders.last_update_date,orders.purchase_date,orders.created_at)
              )
            """,tenantId,connectionId,orderId);
    }

    private BigDecimal allocateQuantity(UUID tenantId,UUID connectionId,String orderId,UUID orderItemId,
            UUID catalogItemId,BigDecimal required){
        BigDecimal remaining=required==null?BigDecimal.ZERO:required;
        if(remaining.signum()<=0)return BigDecimal.ZERO;
        List<Object[]> layers=jdbc.query("""
                WITH positive AS (
                  SELECT ledger.id,ledger.location_id,ledger.expiration_date,ledger.unit_cost,ledger.currency,ledger.quantity,ledger.occurred_at,
                    coalesce(sum(ledger.quantity) OVER (PARTITION BY ledger.location_id,ledger.expiration_date
                      ORDER BY ledger.occurred_at,ledger.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior_received
                  FROM inventory_ledger_entries ledger
                  WHERE ledger.tenant_id=? AND ledger.account_catalog_item_id=? AND ledger.quantity>0
                ), depleted AS (
                  SELECT location_id,expiration_date,-coalesce(sum(quantity) FILTER (WHERE quantity<0),0) quantity
                  FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? GROUP BY location_id,expiration_date
                )
                SELECT positive.id,positive.expiration_date,positive.unit_cost,positive.currency,
                  positive.quantity-least(positive.quantity,greatest(coalesce(depleted.quantity,0)-positive.prior_received,0))
                    -coalesce(reserved.quantity,0) available
                FROM positive LEFT JOIN depleted ON depleted.location_id=positive.location_id
                  AND depleted.expiration_date IS NOT DISTINCT FROM positive.expiration_date
                LEFT JOIN LATERAL (SELECT sum(quantity) quantity FROM order_inventory_reservations reservation
                  WHERE reservation.tenant_id=? AND reservation.cost_layer_id=positive.id AND reservation.status='ACTIVE') reserved ON true
                WHERE positive.quantity-least(positive.quantity,greatest(coalesce(depleted.quantity,0)-positive.prior_received,0))
                    -coalesce(reserved.quantity,0)>0
                  AND (positive.expiration_date IS NULL OR positive.expiration_date>
                    current_date+coalesce((SELECT minimum_sellable_days FROM inventory_shelf_life_policies WHERE tenant_id=?),10))
                ORDER BY positive.expiration_date NULLS LAST,positive.occurred_at,positive.id
                """,(rs,row)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,LocalDate.class),rs.getBigDecimal(3),rs.getString(4),rs.getBigDecimal(5)},tenantId,catalogItemId,tenantId,catalogItemId,tenantId,tenantId);
        for(Object[] layer:layers){
            if(remaining.signum()<=0)break;BigDecimal take=remaining.min((BigDecimal)layer[4]);
            jdbc.update("""
                    INSERT INTO order_inventory_reservations(tenant_id,marketplace_connection_id,amazon_order_id,
                        amazon_order_item_id,account_catalog_item_id,cost_layer_id,expiration_date,unit_cost,cost_currency,quantity,status)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE')
                    """,tenantId,connectionId,orderId,orderItemId,catalogItemId,layer[0],layer[1],layer[2],layer[3],take);
            remaining=remaining.subtract(take);
        }
        return remaining;
    }

    private ReconciliationResult reconciliationResult(UUID tenantId,UUID connectionId){
        return jdbc.queryForObject("""
            SELECT coalesce(active.order_count,0),coalesce(active.quantity,0),
                   states.shortage_orders,states.unmapped_orders,states.amazon_fulfilled_open_orders
            FROM (
              SELECT count(DISTINCT reservation.amazon_order_id) order_count,
                     coalesce(sum(reservation.quantity),0) quantity
              FROM order_inventory_reservations reservation
              WHERE reservation.tenant_id=? AND reservation.marketplace_connection_id=?
                AND reservation.status='ACTIVE'
            ) active
            CROSS JOIN (
              SELECT count(*) FILTER (WHERE orders.fulfillment_state='INVENTORY_SHORTAGE') shortage_orders,
                     count(*) FILTER (WHERE orders.fulfillment_state='NEEDS_MAPPING') unmapped_orders,
                     count(*) FILTER (WHERE orders.fulfillment_state='AMAZON_FULFILLED'
                       AND regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g')
                           IN ('PENDING','UNSHIPPED')) amazon_fulfilled_open_orders
              FROM amazon_orders orders
              WHERE orders.tenant_id=? AND orders.marketplace_connection_id=?
                AND orders.operational_scope='LIVE'
            ) states
            """,(rs,row)->new ReconciliationResult(rs.getInt(1),rs.getBigDecimal(2),rs.getInt(3),
                rs.getInt(4),rs.getInt(5)),tenantId,connectionId,tenantId,connectionId);
    }

    @Transactional(readOnly=true)
    public String streamVersion(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        String version=jdbc.query("""
            SELECT to_char(max(job.completed_at) AT TIME ZONE 'UTC','YYYYMMDDHH24MISS.US')
            FROM marketplace_sync_jobs job
            JOIN marketplace_sync_runs run ON run.tenant_id=job.tenant_id AND run.id=job.sync_run_id
            WHERE job.tenant_id=? AND job.marketplace_connection_id=?
              AND job.job_type='FINAL_RECONCILIATION' AND job.status='COMPLETED'
              AND run.sync_profile IN ('ORDER_CHANGES','STARTUP_ORDERS',
                'RECENT_ORDER_RECONCILIATION','ORDER_LIFECYCLE')
            """,rs->rs.next()?rs.getString(1):null,tenantId,connectionId);
        return version==null?"waiting":version;
    }

    @Transactional(readOnly=true)
    public OrderSummary summary(UUID tenantId,UUID connectionId){
        setTenant(tenantId);return jdbc.queryForObject("""
            SELECT count(*) FILTER (WHERE orders.purchase_marketplace_date=(now() AT TIME ZONE definition.reporting_timezone)::date
                     AND orders.fulfillment_state<>'CANCELLED'
                     AND upper(regexp_replace(trim(coalesce(orders.order_status,'')),'[^A-Z]','','g')) NOT IN ('CANCELLED','CANCELED')),
                   coalesce(sum(seller_sales.amount) FILTER (WHERE orders.purchase_marketplace_date=(now() AT TIME ZONE definition.reporting_timezone)::date
                     AND orders.fulfillment_state<>'CANCELLED'
                     AND upper(regexp_replace(trim(coalesce(orders.order_status,'')),'[^A-Z]','','g')) NOT IN ('CANCELLED','CANCELED')),0),
                   coalesce(sum(seller_sales.amount) FILTER (WHERE orders.purchase_date>=now()-interval '30 days'
                     AND orders.fulfillment_state<>'CANCELLED'
                     AND upper(regexp_replace(trim(coalesce(orders.order_status,'')),'[^A-Z]','','g')) NOT IN ('CANCELLED','CANCELED')),0),
                   count(*) FILTER (WHERE orders.operational_scope<>'HISTORICAL'),
                   count(*) FILTER (WHERE orders.operational_scope='HISTORICAL'),
                   greatest(
                     (SELECT max(document.normalized_at) FROM amazon_source_documents document
                      WHERE document.tenant_id=? AND document.marketplace_connection_id=?
                        AND document.source_type IN ('ORDERS_API_DELTA','ORDERS_30_DAY')),
                     (SELECT max(job.completed_at) FROM marketplace_sync_jobs job
                      WHERE job.tenant_id=? AND job.marketplace_connection_id=?
                        AND job.job_type IN ('ORDERS_API_DELTA','ORDERS_30_DAY') AND job.status='COMPLETED'))
            FROM amazon_orders orders
            JOIN marketplace_connections connection ON connection.tenant_id=orders.tenant_id
              AND connection.id=orders.marketplace_connection_id
            JOIN marketplace_definitions definition ON definition.channel=connection.channel
              AND definition.marketplace_identifier=connection.marketplace_identifier
            LEFT JOIN LATERAL (
              SELECT coalesce(sum(coalesce(item.item_price,0)-coalesce(item.promotion_discount,0)
                +CASE WHEN upper(coalesce(orders.fulfillment_channel,'')) IN ('AFN','AMAZON') THEN 0
                  ELSE greatest(coalesce(item.shipping_price,0)-coalesce(item.shipping_discount,0),0) END),0) amount
              FROM amazon_order_items item
              WHERE item.tenant_id=orders.tenant_id
                AND item.marketplace_connection_id=orders.marketplace_connection_id
                AND item.amazon_order_id=orders.amazon_order_id
            ) seller_sales ON true
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=?
              AND EXISTS (SELECT 1 FROM amazon_order_items visible_item
                WHERE visible_item.tenant_id=orders.tenant_id
                  AND visible_item.marketplace_connection_id=orders.marketplace_connection_id
                  AND visible_item.amazon_order_id=orders.amazon_order_id)
            """,
            (rs,row)->new OrderSummary(rs.getLong(1),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getLong(4),rs.getLong(5),
                rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant()),tenantId,connectionId,tenantId,connectionId,tenantId,connectionId);
    }

    @Transactional(readOnly=true)
    public OrderPage orders(UUID tenantId,UUID connectionId,String filter,String search,int requestedPage,int pageSize){
        setTenant(tenantId);String state=filter==null?"ALL":filter.toUpperCase();String q="%"+(search==null?"":search.trim().toLowerCase())+"%";
        int size=Math.max(10,Math.min(pageSize,100)),page=Math.max(0,requestedPage),offset=page*size;
        List<Object[]> raw=jdbc.query("""
            SELECT orders.id,orders.amazon_order_id,orders.purchase_date,
                   coalesce(orders.purchase_marketplace_date,orders.purchase_date::date),orders.order_status,
                   orders.fulfillment_channel,orders.operational_scope,orders.fulfillment_state,
                   count(item.id),coalesce(sum(item.quantity_ordered),0),orders.currency,orders.order_total,
                   min(item.seller_sku),min(item.seller_sku) FILTER (WHERE mapping.id IS NULL),
                   count(*) FILTER (WHERE item.id IS NOT NULL AND mapping.id IS NULL),coalesce(max(reserved.quantity),0),
                   min(listing.image_url),orders.marketplace_id,count(*) over()
            FROM amazon_orders orders
            JOIN amazon_order_items item ON item.tenant_id=orders.tenant_id
              AND item.marketplace_connection_id=orders.marketplace_connection_id AND item.amazon_order_id=orders.amazon_order_id
            LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
              AND mapping.marketplace_connection_id=item.marketplace_connection_id
              AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
            LEFT JOIN amazon_listings listing ON listing.tenant_id=item.tenant_id
              AND listing.marketplace_connection_id=item.marketplace_connection_id
              AND listing.marketplace_id=orders.marketplace_id AND listing.seller_sku=item.seller_sku
            LEFT JOIN LATERAL (SELECT sum(quantity) quantity FROM order_inventory_reservations reservation
                WHERE reservation.tenant_id=orders.tenant_id AND reservation.marketplace_connection_id=orders.marketplace_connection_id
                  AND reservation.amazon_order_id=orders.amazon_order_id AND reservation.status='ACTIVE') reserved ON true
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=?
              AND (?='ALL' OR orders.fulfillment_state=?)
              AND (?='%%' OR lower(orders.amazon_order_id) LIKE ? OR lower(coalesce(item.seller_sku,'')) LIKE ?
                   OR lower(coalesce(item.title,'')) LIKE ?
                   OR lower(coalesce(item.asin,listing.asin,mapping.asin,'')) LIKE ?
                   OR EXISTS (SELECT 1 FROM marketplace_sku_mapping_components component
                     JOIN account_catalog_items catalog_item ON catalog_item.tenant_id=component.tenant_id
                       AND catalog_item.id=component.account_catalog_item_id
                     JOIN global_catalog_products product ON product.id=catalog_item.global_product_id
                     WHERE component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id
                       AND lower(coalesce(product.brand,'')) LIKE ?))
            GROUP BY orders.id,reserved.quantity ORDER BY orders.purchase_date DESC NULLS LAST
            LIMIT ? OFFSET ?
            """,(rs,row)->new Object[]{new OrderView(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getTimestamp(3)==null?null:rs.getTimestamp(3).toInstant(),rs.getObject(4,LocalDate.class),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9),rs.getInt(10),
                rs.getString(11),rs.getBigDecimal(12),rs.getString(13),rs.getString(14),rs.getInt(15),
                rs.getBigDecimal(16),rs.getString(17),rs.getString(18)),rs.getLong(19)},tenantId,connectionId,state,state,q,q,q,q,q,q,size,offset);
        long total=raw.isEmpty()?0:(long)raw.getFirst()[1];
        if(total>0&&offset>=total)return orders(tenantId,connectionId,state,search,(int)((total-1)/size),size);
        return new OrderPage(raw.stream().map(row->(OrderView)row[0]).toList(),total,page,size);
    }

    @Transactional(readOnly=true)
    public Map<String,String> fourWeekSales(UUID tenantId,UUID connectionId,java.util.Collection<String> skus){
        setTenant(tenantId);
        Map<String,String> result=new LinkedHashMap<>();
        var values=skus.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if(values.isEmpty())return result;
        String placeholders=String.join(",",java.util.Collections.nCopies(values.size(),"?"));
        List<Object> params=new ArrayList<>();params.add(tenantId);params.add(connectionId);params.addAll(values);
        jdbc.query("""
            SELECT item.seller_sku,
              coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date<now()-interval '21 days'),0),
              coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '21 days' AND orders.purchase_date<now()-interval '14 days'),0),
              coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '14 days' AND orders.purchase_date<now()-interval '7 days'),0),
              coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '7 days'),0)
            FROM amazon_order_items item JOIN amazon_orders orders
              ON orders.tenant_id=item.tenant_id AND orders.marketplace_connection_id=item.marketplace_connection_id
              AND orders.amazon_order_id=item.amazon_order_id
            WHERE item.tenant_id=? AND item.marketplace_connection_id=?
              AND orders.purchase_date>=now()-interval '28 days' AND orders.purchase_date<=now()
              AND upper(coalesce(orders.order_status,'')) NOT IN ('CANCELLED','CANCELED')
              AND item.seller_sku IN (
            """+placeholders+") GROUP BY item.seller_sku",rs->{
                result.put(rs.getString(1),rs.getLong(2)+" | "+rs.getLong(3)+" | "+rs.getLong(4)+" | "+rs.getLong(5));
            },params.toArray());
        return result;
    }

    @Transactional(readOnly=true)
    public Map<String,List<OrderItemView>> itemsForOrders(UUID tenantId,UUID connectionId,List<String> orderIds){
        setTenant(tenantId);Map<String,List<OrderItemView>> result=new LinkedHashMap<>();
        for(String id:orderIds)result.put(id,new ArrayList<>());
        if(orderIds.isEmpty())return result;
        String placeholders=String.join(",",java.util.Collections.nCopies(orderIds.size(),"?"));
        List<Object> parameters=new ArrayList<>();parameters.add(tenantId);parameters.add(connectionId);parameters.addAll(orderIds);
        jdbc.query("""
            SELECT item.amazon_order_id,item.id,item.seller_sku,item.asin,item.title,item.quantity_ordered,item.quantity_shipped,
                   CASE WHEN mapping.id IS NULL THEN 'UNMAPPED' ELSE 'MAPPED' END,
                   CASE WHEN mapping.id IS NULL THEN NULL ELSE coalesce(string_agg(
                     coalesce(nullif(mapped_offer.vendor_item_code,''),nullif(catalog.account_sku,''),
                       coalesce(catalog.display_name,product.canonical_name))||' × '
                       ||to_char(component.quantity,'FM999999990.####'),E'\n'
                       ORDER BY component.sort_order,component.created_at),'Mapped') END,
                   coalesce(reserved.quantity,0),listing.image_url,coalesce(available.quantity,0),
                   item.item_price,greatest(coalesce(item.shipping_price,0)-coalesce(item.shipping_discount,0),0),item.currency,
                   listing.buy_box_price,listing.buy_box_currency,listing.buy_box_updated_at
            FROM amazon_order_items item
            LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
              AND mapping.marketplace_connection_id=item.marketplace_connection_id
              AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
            LEFT JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
              AND component.marketplace_sku_mapping_id=mapping.id
            LEFT JOIN account_catalog_items catalog ON catalog.tenant_id=component.tenant_id
              AND catalog.id=component.account_catalog_item_id
            LEFT JOIN global_catalog_products product ON product.id=catalog.global_product_id
            LEFT JOIN LATERAL (SELECT offer.vendor_item_code FROM vendor_catalog_offers offer
              WHERE offer.tenant_id=catalog.tenant_id AND offer.account_catalog_item_id=catalog.id
              ORDER BY (offer.effective_to IS NULL) DESC,offer.is_default DESC,offer.updated_at DESC LIMIT 1
            ) mapped_offer ON true
            LEFT JOIN LATERAL (SELECT candidate.image_url,candidate.buy_box_price,candidate.buy_box_currency,
                candidate.buy_box_updated_at FROM amazon_listings candidate
              WHERE candidate.tenant_id=item.tenant_id
                AND candidate.marketplace_connection_id=item.marketplace_connection_id
                AND (candidate.seller_sku=item.seller_sku OR
                  (item.asin IS NOT NULL AND candidate.asin=item.asin))
              ORDER BY (candidate.seller_sku=item.seller_sku) DESC,candidate.buy_box_updated_at DESC NULLS LAST
              LIMIT 1) listing ON true
            LEFT JOIN LATERAL (SELECT sum(quantity) quantity FROM order_inventory_reservations reservation
              WHERE reservation.tenant_id=item.tenant_id AND reservation.amazon_order_item_id=item.id
                AND reservation.status='ACTIVE') reserved ON true
            LEFT JOIN LATERAL (SELECT min(floor(greatest(coalesce(stock.on_hand,0)-coalesce(committed.reserved,0),0)
                    /nullif(mapped_component.quantity,0))) quantity
              FROM marketplace_sku_mapping_components mapped_component
              LEFT JOIN LATERAL (SELECT sum(ledger.quantity) on_hand FROM inventory_ledger_entries ledger
                WHERE ledger.tenant_id=mapped_component.tenant_id
                  AND ledger.account_catalog_item_id=mapped_component.account_catalog_item_id
                  AND (ledger.expiration_date IS NULL OR ledger.expiration_date>
                    current_date+coalesce((SELECT minimum_sellable_days FROM inventory_shelf_life_policies
                      WHERE tenant_id=mapped_component.tenant_id),10))) stock ON true
              LEFT JOIN LATERAL (SELECT sum(active.quantity) reserved FROM order_inventory_reservations active
                WHERE active.tenant_id=mapped_component.tenant_id
                  AND active.account_catalog_item_id=mapped_component.account_catalog_item_id
                  AND active.status='ACTIVE') committed ON true
              WHERE mapped_component.tenant_id=mapping.tenant_id
                AND mapped_component.marketplace_sku_mapping_id=mapping.id) available ON true
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.amazon_order_id IN (%s)
            GROUP BY item.id,mapping.id,reserved.quantity,listing.image_url,listing.buy_box_price,
                listing.buy_box_currency,listing.buy_box_updated_at,available.quantity
            ORDER BY item.amazon_order_id,item.created_at
            """.formatted(placeholders),rs->{String orderId=rs.getString(1);result.computeIfAbsent(orderId,key->new ArrayList<>()).add(
                new OrderItemView(rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getString(5),
                    rs.getInt(6),rs.getInt(7),rs.getString(8),rs.getString(9),rs.getBigDecimal(10),
                    rs.getString(11),rs.getBigDecimal(12),rs.getBigDecimal(13),rs.getBigDecimal(14),
                    rs.getString(15),rs.getBigDecimal(16),rs.getString(17),
                    rs.getTimestamp(18)==null?null:rs.getTimestamp(18).toInstant()));},parameters.toArray());
        return result;
    }

    private void postActiveReservations(UUID tenantId,UUID connectionId,String orderId,UUID orderItemId){
        jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,marketplace_connection_id,
                entry_type,quantity,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,
                idempotency_key,notes,created_by,cost_status)
            SELECT reservation.tenant_id,reservation.account_catalog_item_id,reservation.location_id,reservation.marketplace_connection_id,
                   'SHIPMENT',-reservation.quantity,reservation.expiration_date,
                   reservation.unit_cost,coalesce(reservation.cost_currency,orders.currency,'USD'),'AMAZON_ORDER',orders.id,now(),
                   'amazon-shipment:'||reservation.id,'Amazon reported that the order left the warehouse.',
                   NULL,'FINAL'
            FROM order_inventory_reservations reservation
            JOIN amazon_orders orders ON orders.tenant_id=reservation.tenant_id
              AND orders.marketplace_connection_id=reservation.marketplace_connection_id
              AND orders.amazon_order_id=reservation.amazon_order_id
            WHERE reservation.tenant_id=? AND reservation.marketplace_connection_id=?
              AND reservation.amazon_order_id=? AND reservation.status='ACTIVE'
              AND (?::uuid IS NULL OR reservation.amazon_order_item_id=?::uuid)
            ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
            """,tenantId,connectionId,orderId,orderItemId,orderItemId);
        jdbc.update("""
            UPDATE order_inventory_reservations SET status='SHIPPED',updated_at=now()
            WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND status='ACTIVE'
              AND (?::uuid IS NULL OR amazon_order_item_id=?::uuid)
            """,tenantId,connectionId,orderId,orderItemId,orderItemId);
    }

    private void setState(UUID tenantId,UUID connectionId,String orderId,String state){jdbc.update("UPDATE amazon_orders SET fulfillment_state=?,operational_updated_at=now() WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?",state,tenantId,connectionId,orderId);}
    static boolean statusLeavesWarehouse(String status){
        String normalized=status==null?"":status.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]","");
        if(normalized.startsWith("PARTIALLYSHIPPED"))return false;
        return normalized.startsWith("SHIPPED") || List.of("WAITINGFORPICKUP","READYFORPICKUP","PICKUPREADY","PICKEDUP",
            "INTRANSIT","OUTFORDELIVERY","DELIVERED","DELIVERYATTEMPTED","COMPLETED","RETURNED").contains(normalized);
    }
    static boolean statusReservesInventory(String status){
        String normalized=status==null?"":status.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z]","");
        return List.of("PENDING","UNSHIPPED").contains(normalized);
    }
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private static String sellerCentralDomain(String marketplace){return switch(marketplace){
        case "A2EUQ1WTGCTBG2"->"sellercentral.amazon.ca";case "A1F83G8C2ARO7P"->"sellercentral.amazon.co.uk";
        default->"sellercentral.amazon.com";};}
    private static String amazonDomain(String marketplace){return switch(marketplace){
        case "A2EUQ1WTGCTBG2" -> "amazon.ca";case "A1AM78C64UM0Y8" -> "amazon.com.mx";
        case "A1F83G8C2ARO7P" -> "amazon.co.uk";case "A1PA6795UKMFR9" -> "amazon.de";
        case "A13V1IB3VIYZZH" -> "amazon.fr";case "APJ6JRA9NG5V4" -> "amazon.it";
        case "A1RKKUPIHCS9HS" -> "amazon.es";case "A1805IZSGTT6HS" -> "amazon.nl";
        case "A2NODRKZP88ZB9" -> "amazon.se";case "A1C3SOZRARQ6R3" -> "amazon.pl";
        case "AMEN7PMS3EDWL" -> "amazon.com.be";case "A1VC38T7YXB528" -> "amazon.co.jp";
        case "A39IBJ37TRP1C6" -> "amazon.com.au";case "A21TJRUUN4KGV" -> "amazon.in";
        default -> "amazon.com";
    };}
}
