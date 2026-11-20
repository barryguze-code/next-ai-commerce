package com.nextaicommerce.platform.shopify;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Local warehouse effects only. Uses the existing ledger, tenant stock lock and shared reservation pool. */
@Repository
public class ShopifyOrderInventory {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    @Value("${app.shopify.orders.inventory-scopes:}") private String inventoryScopes="";
    @Value("${app.shopify.orders.bootstrap-scopes:}") private String bootstrapScopes="";
    public ShopifyOrderInventory(JdbcTemplate jdbc,@Value("${app.shopify.orders.inventory-enabled:false}") boolean enabled,
                                 @Value("${app.shopify.orders.enabled:false}") boolean ordersEnabled){this.jdbc=jdbc;this.enabled=enabled&&ordersEnabled;}
    public boolean enabled(){return enabled;}
    public boolean enabled(UUID tenant,UUID connection){return enabled&&inScope(inventoryScopes,tenant,connection);}
    private static boolean inScope(String scopes,UUID tenant,UUID connection){
        return Arrays.stream(scopes.split(",")).map(String::trim).anyMatch((tenant+"/"+connection)::equals);
    }
    /** Re-observe mapped outstanding history in the same fenced scan that will authorize its cutover. */
    Instant bootstrapStart(UUID tenant,UUID connection,Instant normalStart){
        if(!enabled(tenant,connection)||!inScope(bootstrapScopes,tenant,connection))return normalStart;
        Timestamp earliest=jdbc.queryForObject("""
            SELECT min(o.source_updated_at) FROM shopify_orders o
            JOIN shopify_order_lines l ON l.tenant_id=o.tenant_id AND l.marketplace_connection_id=o.marketplace_connection_id AND l.order_id=o.id
            JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
            JOIN marketplace_sku_mappings m ON m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id AND m.shopify_variant_id=v.id
              AND m.status='ACTIVE' AND m.shopify_mapping_hash=v.mapping_hash
            WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND o.evidence_complete AND NOT o.is_test
              AND o.cancelled_at IS NULL AND o.payment_status<>'VOIDED' AND o.review_reason IS NULL
              AND l.stock_scope='REPORTING_ONLY' AND l.inventory_state='REPORTING_ONLY' AND l.review_reason IS NULL
              AND l.requires_shipping AND l.ownership='MERCHANT' AND l.unfulfilled_quantity>0
              AND l.local_handoff_units=0 AND l.local_pending_units=0
              AND EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c WHERE c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id)
            """,Timestamp.class,tenant,connection);
        return earliest!=null&&earliest.toInstant().minusSeconds(300).isBefore(normalStart)?earliest.toInstant().minusSeconds(300):normalStart;
    }
    /** Read-only batch projection using the same shelf-life/location eligibility and shared reservations. */
    @Transactional(readOnly=true)
    public Map<UUID,BigDecimal> availability(UUID tenant,Collection<UUID> items){
        if(items.isEmpty())return Map.of();
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        var values=new ArrayList<Object>();values.add(tenant);values.addAll(items);values.add(tenant);values.add(tenant);
        String ids=String.join(",",Collections.nCopies(items.size(),"?"));var result=new HashMap<UUID,BigDecimal>();
        jdbc.query("""
            WITH positive AS (
              SELECT ledger.*,coalesce(sum(quantity) OVER(PARTITION BY account_catalog_item_id,location_id,expiration_date
                ORDER BY occurred_at,id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior_received
              FROM inventory_ledger_entries ledger WHERE tenant_id=? AND account_catalog_item_id IN (
            """+ids+"""
              ) AND quantity>0
            ), depleted AS (
              SELECT account_catalog_item_id,location_id,expiration_date,-coalesce(sum(quantity) FILTER(WHERE quantity<0),0) quantity
              FROM inventory_ledger_entries WHERE tenant_id=? GROUP BY account_catalog_item_id,location_id,expiration_date
            ) SELECT p.account_catalog_item_id,sum(greatest(p.quantity-least(p.quantity,greatest(coalesce(d.quantity,0)-p.prior_received,0))-coalesce(r.quantity,0),0)) available
            FROM positive p JOIN warehouse_locations location ON location.tenant_id=p.tenant_id AND location.id=p.location_id AND location.status='ACTIVE'
            JOIN account_catalog_items item ON item.tenant_id=p.tenant_id AND item.id=p.account_catalog_item_id AND item.status='ACTIVE'
            LEFT JOIN depleted d ON d.account_catalog_item_id=p.account_catalog_item_id AND d.location_id=p.location_id AND d.expiration_date IS NOT DISTINCT FROM p.expiration_date
            LEFT JOIN LATERAL(SELECT sum(quantity) quantity FROM order_inventory_reservations WHERE tenant_id=p.tenant_id AND cost_layer_id=p.id AND status='ACTIVE') r ON true
            WHERE p.expiration_date IS NULL OR p.expiration_date>current_date+coalesce((SELECT minimum_sellable_days FROM inventory_shelf_life_policies WHERE tenant_id=?),10)
            GROUP BY p.account_catalog_item_id
            """,rs->{result.put(rs.getObject(1,UUID.class),rs.getBigDecimal(2));},values.toArray());
        return Map.copyOf(result);
    }
    void lock(UUID tenant){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",rs->{},tenant);
    }
    Instant cutoff(UUID tenant,UUID connection){
        if(!enabled(tenant,connection))return null;
        var rows=jdbc.queryForList("SELECT activated_at FROM shopify_order_controls WHERE tenant_id=? AND marketplace_connection_id=? AND inventory_enabled AND baseline_completed_at IS NOT NULL",tenant,connection);
        return rows.isEmpty()?null:((Timestamp)rows.getFirst().get("activated_at")).toInstant();
    }
    /** Called within the fenced source publication transaction, before changing reservation targets. */
    boolean ship(UUID tenant,UUID connection,UUID order,UUID line,ShopifyOrderLifecycle.Shipment shipment,
                 ShopifyOrderLifecycle.Fulfillment source){
        if(!enabled(tenant,connection))throw new IllegalStateException("Shopify warehouse operations are disabled.");
        // A local packing handoff already consumed these physical units. The source
        // checkpoint and this credit are committed together, so replay cannot spend twice.
        int pending=jdbc.queryForObject("SELECT local_pending_units FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND id=? FOR UPDATE",Integer.class,tenant,connection,line);
        int covered=Math.min(pending,shipment.marketplaceUnits());
        if(covered>0)jdbc.update("UPDATE shopify_order_lines SET local_pending_units=local_pending_units-? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",covered,tenant,connection,line);
        if(covered==shipment.marketplaceUnits())return false;
        shipment=new ShopifyOrderLifecycle.Shipment(shipment.fulfillmentKey(),shipment.marketplaceUnits()-covered);
        boolean review=false;
        var components=jdbc.queryForList("SELECT account_catalog_item_id,quantity FROM shopify_order_components WHERE tenant_id=? AND marketplace_connection_id=? AND order_line_id=? ORDER BY account_catalog_item_id",tenant,connection,line);
        if(components.isEmpty())return true;
        for(var component:components){
            UUID item=(UUID)component.get("account_catalog_item_id");
            BigDecimal remaining=((BigDecimal)component.get("quantity")).multiply(BigDecimal.valueOf(shipment.marketplaceUnits()));
            var rows=jdbc.queryForList("""
                SELECT r.*,layer.occurred_at layer_time FROM order_inventory_reservations r
                JOIN inventory_ledger_entries layer ON layer.tenant_id=r.tenant_id AND layer.id=r.cost_layer_id
                WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.shopify_order_line_id=?
                  AND r.account_catalog_item_id=? AND r.status='ACTIVE'
                ORDER BY r.expiration_date NULLS LAST,layer.occurred_at,layer.id FOR UPDATE OF r
                """,tenant,connection,line,item);
            for(var r:rows){
                if(remaining.signum()==0)break;
                UUID reservation=(UUID)r.get("id"),location=(UUID)r.get("location_id");
                BigDecimal quantity=(BigDecimal)r.get("quantity"),take=remaining.min(quantity);
                boolean counted=Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=?
                      AND location_id=? AND expiration_date IS NOT DISTINCT FROM ?::date
                      AND source_type='PHYSICAL_COUNT' AND occurred_at>=?)
                    """,Boolean.class,tenant,item,location,r.get("expiration_date"),Timestamp.from(source.occurred())));
                String key="shopify-shipment:"+ShopifyCatalogRepository.hash(shipment.fulfillmentKey())+":"+source.quantity()+":"+reservation;
                jdbc.update("""
                    INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,marketplace_connection_id,
                      entry_type,quantity,expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,cost_status)
                    VALUES(?,?,?,?,?,?,?,?,?,'SHOPIFY_ORDER',?,?,?,?,?) ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
                    """,tenant,item,location,connection,counted?"SHIPMENT_UNRECORDED":"SHIPMENT",counted?BigDecimal.ZERO:take.negate(),
                    r.get("expiration_date"),r.get("unit_cost"),r.get("cost_currency"),order,Timestamp.from(source.occurred()),key,
                    counted?"A physical count overlaps Shopify fulfillment evidence. Departure timing requires review; no additional stock deducted.":"Shopify confirmed successful fulfillment. Deducted the order's existing reserved warehouse lot.","FINAL");
                if(quantity.compareTo(take)==0)jdbc.update("UPDATE order_inventory_reservations SET status=? WHERE tenant_id=? AND id=?",counted?"RELEASED":"SHIPPED",tenant,reservation);
                else{
                    jdbc.update("UPDATE order_inventory_reservations SET quantity=quantity-? WHERE tenant_id=? AND id=?",take,tenant,reservation);
                    jdbc.update("""
                        INSERT INTO order_inventory_reservations(tenant_id,marketplace_connection_id,shopify_order_line_id,
                          account_catalog_item_id,location_id,cost_layer_id,expiration_date,unit_cost,cost_currency,quantity,status)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?)
                        """,tenant,connection,line,item,location,r.get("cost_layer_id"),r.get("expiration_date"),r.get("unit_cost"),r.get("cost_currency"),take,counted?"RELEASED":"SHIPPED");
                }
                review|=counted;remaining=remaining.subtract(take);
            }
            if(remaining.signum()>0){
                review=true;
                // Zero movement retains the missing allocation as an explicit review; never allocate today's stock to it.
                jdbc.update("""
                    INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,marketplace_connection_id,entry_type,
                      quantity,source_type,source_id,occurred_at,idempotency_key,notes,cost_status)
                    VALUES(?,?,?,'SHIPMENT_UNRECORDED',0,'SHOPIFY_ORDER',?,?,?,?,'FINAL') ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
                    """,tenant,item,connection,order,Timestamp.from(source.occurred()),
                    "shopify-unrecorded:"+ShopifyCatalogRepository.hash(shipment.fulfillmentKey())+":"+source.quantity()+":"+item,
                    "Shopify confirmed fulfillment without sufficient prior warehouse reservations. Unrecorded eaches: "+remaining.toPlainString()+". No stock allocated retrospectively.");
            }
        }
        return review;
    }
    void release(UUID tenant,UUID connection,UUID line){if(!enabled(tenant,connection))return;jdbc.update("UPDATE order_inventory_reservations SET status='RELEASED' WHERE tenant_id=? AND marketplace_connection_id=? AND shopify_order_line_id=? AND status='ACTIVE'",tenant,connection,line);}

    /** Freeze a valid current-account mapping once. Never replace an existing order's components after catalogue edits. */
    private boolean basis(UUID tenant,UUID connection,UUID line){
        jdbc.update("""
            INSERT INTO shopify_order_components(tenant_id,marketplace_connection_id,order_line_id,account_catalog_item_id,quantity)
            SELECT l.tenant_id,l.marketplace_connection_id,l.id,c.account_catalog_item_id,sum(c.quantity)
            FROM shopify_order_lines l JOIN shopify_variants v ON v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
            JOIN marketplace_sku_mappings m ON m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id
              AND m.shopify_variant_id=v.id AND m.status='ACTIVE' AND m.shopify_mapping_hash=v.mapping_hash
            JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
            JOIN account_catalog_items item ON item.tenant_id=c.tenant_id AND item.id=c.account_catalog_item_id AND item.status='ACTIVE'
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.id=? AND l.basis_hash IS NULL
              AND NOT EXISTS(SELECT 1 FROM shopify_order_components old WHERE old.tenant_id=l.tenant_id AND old.marketplace_connection_id=l.marketplace_connection_id AND old.order_line_id=l.id)
              AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mapping_components allc JOIN account_catalog_items alli ON alli.tenant_id=allc.tenant_id AND alli.id=allc.account_catalog_item_id WHERE allc.tenant_id=m.tenant_id AND allc.marketplace_sku_mapping_id=m.id AND alli.status<>'ACTIVE')
            GROUP BY l.tenant_id,l.marketplace_connection_id,l.id,c.account_catalog_item_id
            """,tenant,connection,line);
        jdbc.update("""
            UPDATE shopify_order_lines l SET basis_hash=v.mapping_hash,basis_variant_gid=v.variant_gid FROM shopify_variants v
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.id=? AND l.basis_hash IS NULL
              AND v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
              AND EXISTS(SELECT 1 FROM shopify_order_components c WHERE c.tenant_id=l.tenant_id AND c.marketplace_connection_id=l.marketplace_connection_id AND c.order_line_id=l.id)
            """,tenant,connection,line);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shopify_order_components WHERE tenant_id=? AND marketplace_connection_id=? AND order_line_id=?)",Boolean.class,tenant,connection,line));
    }
    @Transactional
    public int reconcileTenant(UUID tenant){
        if(!enabled)return 0;lock(tenant);
        var lines=jdbc.queryForList("""
            SELECT l.id,l.marketplace_connection_id,greatest(l.reserve_units-l.local_pending_units,0) reserve_units,l.review_reason FROM shopify_order_lines l
            JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id
            JOIN shopify_order_controls c ON c.tenant_id=l.tenant_id AND c.marketplace_connection_id=l.marketplace_connection_id
            JOIN marketplace_connections connection ON connection.tenant_id=l.tenant_id AND connection.id=l.marketplace_connection_id AND connection.channel='SHOPIFY' AND connection.status='ACTIVE'
            WHERE l.tenant_id=? AND l.stock_scope='LIVE' AND l.inventory_revision IS NOT NULL
              AND c.inventory_enabled AND c.baseline_completed_at IS NOT NULL AND o.evidence_complete
              AND l.inventory_revision=l.source_updated_at AND l.review_reason IS NULL
            ORDER BY o.source_created_at,o.id,l.line_gid FOR UPDATE OF l
            """,tenant);
        for(var l:lines)if(enabled(tenant,(UUID)l.get("marketplace_connection_id")))reserve(tenant,(UUID)l.get("marketplace_connection_id"),(UUID)l.get("id"),(Integer)l.get("reserve_units"));
        return jdbc.queryForObject("SELECT count(*) FROM shopify_order_lines WHERE tenant_id=? AND inventory_state='INVENTORY_SHORTAGE'",Integer.class,tenant);
    }
    /** Only called at a fully read, lease-fenced job boundary under the account stock lock. */
    void bootstrapRemaining(UUID tenant,UUID connection,UUID job){
        if(!enabled(tenant,connection)||!inScope(bootstrapScopes,tenant,connection))return;
        lock(tenant);
        var lines=jdbc.queryForList("""
            SELECT l.id,l.inventory_revision,l.inventory_hash,l.unfulfilled_quantity,o.source_document_id
            FROM shopify_order_lines l JOIN shopify_orders o ON o.tenant_id=l.tenant_id AND o.marketplace_connection_id=l.marketplace_connection_id AND o.id=l.order_id
            JOIN shopify_source_documents d ON d.tenant_id=o.tenant_id AND d.marketplace_connection_id=o.marketplace_connection_id AND d.id=o.source_document_id
            JOIN shopify_order_controls control ON control.tenant_id=l.tenant_id AND control.marketplace_connection_id=l.marketplace_connection_id
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND d.sync_job_id=? AND d.query_revision=?
             AND control.inventory_enabled AND control.activated_at IS NOT NULL AND control.baseline_completed_at IS NOT NULL
             AND o.evidence_complete AND NOT o.is_test AND o.cancelled_at IS NULL AND o.payment_status<>'VOIDED' AND o.review_reason IS NULL
             AND l.stock_scope='REPORTING_ONLY' AND l.inventory_state='REPORTING_ONLY' AND l.review_reason IS NULL
             AND l.requires_shipping AND l.ownership='MERCHANT' AND l.unfulfilled_quantity>0
             AND l.local_handoff_units=0 AND l.local_pending_units=0
             AND l.inventory_revision=l.source_updated_at AND l.inventory_hash=l.observation_hash
             AND NOT EXISTS(SELECT 1 FROM shopify_order_cutovers prior WHERE prior.tenant_id=l.tenant_id AND prior.marketplace_connection_id=l.marketplace_connection_id AND prior.order_line_id=l.id)
            ORDER BY o.source_created_at,l.id FOR UPDATE OF l,o
            """,tenant,connection,job,ShopifyOrderClient.REVISION);
        for(var row:lines){
            UUID line=(UUID)row.get("id");if(!basis(tenant,connection,line))continue;
            int recorded=jdbc.update("""
                INSERT INTO shopify_order_cutovers(tenant_id,marketplace_connection_id,order_line_id,source_document_id,sync_job_id,source_revision,source_hash,fulfillment_checkpoint,composition,remaining_units)
                SELECT l.tenant_id,l.marketplace_connection_id,l.id,?,?,l.inventory_revision,l.inventory_hash,l.success_checkpoint,
                  (SELECT jsonb_agg(jsonb_build_object('item',c.account_catalog_item_id,'quantity',c.quantity) ORDER BY c.account_catalog_item_id)
                   FROM shopify_order_components c WHERE c.tenant_id=l.tenant_id AND c.marketplace_connection_id=l.marketplace_connection_id AND c.order_line_id=l.id),l.unfulfilled_quantity
                FROM shopify_order_lines l WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.id=?
                  AND l.stock_scope='REPORTING_ONLY' AND l.inventory_revision=? AND l.inventory_hash=? AND l.unfulfilled_quantity=?
                ON CONFLICT(tenant_id,marketplace_connection_id,order_line_id) DO NOTHING
                """,row.get("source_document_id"),job,tenant,connection,line,row.get("inventory_revision"),row.get("inventory_hash"),row.get("unfulfilled_quantity"));
            if(recorded==0)continue;
            jdbc.update("UPDATE shopify_order_lines SET stock_scope='LIVE',inventory_state='NEEDS_MAPPING',reserve_units=unfulfilled_quantity,updated_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,connection,line);
            reserve(tenant,connection,line,((Number)row.get("unfulfilled_quantity")).intValue());
        }
    }
    /** Called only from an explicit guarded local test cycle, never source synchronization. */
    void prepareLocalUat(UUID tenant,UUID connection,UUID line,int units){
        if(!enabled(tenant,connection))throw new IllegalStateException("Shopify warehouse operations are disabled.");
        release(tenant,connection,line);
        jdbc.update("UPDATE shopify_order_lines SET stock_scope='TEST',reserve_units=?,unfulfilled_quantity=?,local_handoff_units=0,local_pending_units=0,inventory_revision=source_updated_at,updated_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",units,units,tenant,connection,line);
        if(!basis(tenant,connection,line)){state(tenant,line,"NEEDS_MAPPING");return;}
        reserve(tenant,connection,line,units);
    }
    void restoreLocalUatReservations(UUID tenant,UUID connection,UUID order){
        if(!enabled(tenant,connection))return;
        for(var l:jdbc.queryForList("SELECT id,greatest(reserve_units-local_pending_units,0) units FROM shopify_order_lines WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? AND stock_scope='LIVE' AND review_reason IS NULL",tenant,connection,order))if(basis(tenant,connection,(UUID)l.get("id")))reserve(tenant,connection,(UUID)l.get("id"),((Number)l.get("units")).intValue());
    }
    private void reserve(UUID tenant,UUID connection,UUID line,int units){
        if(!enabled(tenant,connection))return;
        if(!basis(tenant,connection,line)){state(tenant,line,"NEEDS_MAPPING");return;}
        var components=jdbc.queryForList("SELECT account_catalog_item_id,quantity FROM shopify_order_components WHERE tenant_id=? AND marketplace_connection_id=? AND order_line_id=? ORDER BY account_catalog_item_id",tenant,connection,line);
        boolean shortage=false;
        for(var component:components){
            UUID item=(UUID)component.get("account_catalog_item_id");BigDecimal required=((BigDecimal)component.get("quantity")).multiply(BigDecimal.valueOf(units));
            BigDecimal active=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations WHERE tenant_id=? AND marketplace_connection_id=? AND shopify_order_line_id=? AND account_catalog_item_id=? AND status='ACTIVE'",BigDecimal.class,tenant,connection,line,item);
            // Quantity reductions release the newest/least-expiring reservations first, retaining existing earlier picks.
            BigDecimal excess=active.subtract(required);
            if(excess.signum()>0)for(var r:jdbc.queryForList("""
                SELECT r.id,r.quantity FROM order_inventory_reservations r JOIN inventory_ledger_entries layer ON layer.tenant_id=r.tenant_id AND layer.id=r.cost_layer_id
                WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.shopify_order_line_id=? AND r.account_catalog_item_id=? AND r.status='ACTIVE'
                ORDER BY r.expiration_date DESC NULLS FIRST,layer.occurred_at DESC,layer.id DESC FOR UPDATE OF r
                """,tenant,connection,line,item)){
                if(excess.signum()<=0)break;BigDecimal held=(BigDecimal)r.get("quantity"),drop=held.min(excess);
                if(drop.compareTo(held)==0)jdbc.update("UPDATE order_inventory_reservations SET status='RELEASED' WHERE tenant_id=? AND id=?",tenant,r.get("id"));
                else jdbc.update("UPDATE order_inventory_reservations SET quantity=quantity-? WHERE tenant_id=? AND id=?",drop,tenant,r.get("id"));
                excess=excess.subtract(drop);active=active.subtract(drop);
            }
            BigDecimal missing=required.subtract(active);
            if(missing.signum()<=0)continue;
            int days=jdbc.queryForObject("SELECT coalesce((SELECT minimum_sellable_days FROM inventory_shelf_life_policies WHERE tenant_id=?),10)",Integer.class,tenant);
            LocalDate boundary=jdbc.queryForObject("SELECT current_date+?",LocalDate.class,days);
            var lots=jdbc.query("""
                WITH positive AS (
                  SELECT ledger.*,coalesce(sum(ledger.quantity) OVER (PARTITION BY ledger.location_id,ledger.expiration_date ORDER BY ledger.occurred_at,ledger.id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior_received
                  FROM inventory_ledger_entries ledger WHERE ledger.tenant_id=? AND ledger.account_catalog_item_id=? AND ledger.quantity>0
                ), depleted AS (
                  SELECT location_id,expiration_date,-coalesce(sum(quantity) FILTER(WHERE quantity<0),0) quantity
                  FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? GROUP BY location_id,expiration_date
                ) SELECT p.id,p.location_id,location.status='ACTIVE' location_active,p.expiration_date,p.occurred_at,
                    p.quantity-least(p.quantity,greatest(coalesce(d.quantity,0)-p.prior_received,0))-coalesce(reserved.quantity,0) available
                FROM positive p JOIN warehouse_locations location ON location.tenant_id=p.tenant_id AND location.id=p.location_id
                JOIN account_catalog_items item ON item.tenant_id=p.tenant_id AND item.id=p.account_catalog_item_id AND item.status='ACTIVE'
                LEFT JOIN depleted d ON d.location_id=p.location_id AND d.expiration_date IS NOT DISTINCT FROM p.expiration_date
                LEFT JOIN LATERAL(SELECT sum(quantity) quantity FROM order_inventory_reservations WHERE tenant_id=p.tenant_id AND cost_layer_id=p.id AND status='ACTIVE') reserved ON true
                """,(rs,n)->new ShopifyStockAllocation.Lot(tenant,item,rs.getObject("id",UUID.class),rs.getObject("location_id",UUID.class),rs.getBoolean("location_active"),rs.getObject("expiration_date",LocalDate.class),rs.getTimestamp("occurred_at").toInstant(),rs.getBigDecimal("available")),tenant,item,tenant,item);
            var plan=ShopifyStockAllocation.reserve(tenant,item,missing,boundary,lots);shortage|=plan.shortage().signum()>0;
            for(var pick:plan.picks())jdbc.update("""
                INSERT INTO order_inventory_reservations(tenant_id,marketplace_connection_id,shopify_order_line_id,
                  account_catalog_item_id,location_id,cost_layer_id,expiration_date,unit_cost,cost_currency,quantity,status)
                SELECT tenant_id,?,?,account_catalog_item_id,location_id,id,expiration_date,unit_cost,currency,?,'ACTIVE'
                FROM inventory_ledger_entries WHERE tenant_id=? AND id=?
                ON CONFLICT(tenant_id,marketplace_connection_id,shopify_order_line_id,account_catalog_item_id,cost_layer_id)
                    WHERE status='ACTIVE' AND shopify_order_line_id IS NOT NULL
                DO UPDATE SET quantity=order_inventory_reservations.quantity+EXCLUDED.quantity
                """,connection,line,pick.quantity(),tenant,pick.layer());
        }
        boolean shipped=units==0&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT local_handoff_units>0 OR (ordered_quantity>0 AND coalesce((SELECT sum(value::numeric) FROM jsonb_each_text(success_checkpoint)),0)>=ordered_quantity) FROM shopify_order_lines WHERE tenant_id=? AND id=?",Boolean.class,tenant,line));
        state(tenant,line,shortage?"INVENTORY_SHORTAGE":units==0?(shipped?"SHIPPED":"CLOSED"):"READY_TO_SHIP");
    }
    private void state(UUID tenant,UUID line,String state){jdbc.update("UPDATE shopify_order_lines SET inventory_state=?,updated_at=now() WHERE tenant_id=? AND id=?",state,tenant,line);}
}
