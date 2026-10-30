package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.orders.PackingSlipRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal packing only. No provider transport or retrospective stock allocation. */
@Repository
public class ShopifyOrderPacking {
    private final JdbcTemplate jdbc;private final ShopifyOrderInventory inventory;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private LocalShopifyShippingUat uat;
    public ShopifyOrderPacking(JdbcTemplate jdbc,ShopifyOrderInventory inventory){this.jdbc=jdbc;this.inventory=inventory;}
    private Map<String,Object> order(UUID tenant,UUID connection,UUID id,boolean lock){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        var rows=jdbc.queryForList("SELECT o.* FROM shopify_orders o JOIN marketplace_connections c ON c.tenant_id=o.tenant_id AND c.id=o.marketplace_connection_id AND c.channel='SHOPIFY' AND c.status='ACTIVE' WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND o.id=?"+(lock?" FOR UPDATE OF o":""),tenant,connection,id);
        if(rows.isEmpty())throw new IllegalArgumentException("This order is not available in the selected store.");return rows.getFirst();
    }
    @Transactional(readOnly=true)
    public String eligibility(UUID tenant,UUID connection,UUID id){return eligibility(tenant,connection,order(tenant,connection,id,false));}
    private String eligibility(UUID tenant,UUID connection,Map<String,Object> o){
        boolean localUat=uat!=null&&uat.active(tenant,connection,(UUID)o.get("id"));
        var cutoff=inventory.cutoff(tenant,connection);if(cutoff==null)return "Local warehouse operations are disabled.";
        if(!localUat&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shopify_order_controls c WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.last_error IS NULL AND EXISTS(SELECT 1 FROM marketplace_sync_runs run WHERE run.tenant_id=c.tenant_id AND run.marketplace_connection_id=c.marketplace_connection_id AND run.sync_profile='SHOPIFY_ORDERS' AND run.status='COMPLETED' AND run.window_end>=clock_timestamp()-interval '10 minutes'))",Boolean.class,tenant,connection)))return "Refresh Shopify Orders before packing: a recent complete read window is required.";
        if(!Boolean.TRUE.equals(o.get("evidence_complete"))||o.get("review_reason")!=null)return "Complete, current order evidence is required.";
        if(Boolean.TRUE.equals(o.get("is_test"))||o.get("cancelled_at")!=null)return "Cancelled or test orders cannot be packed.";
        boolean historical=!localUat&&((Timestamp)o.get("source_created_at")).toInstant().isBefore(cutoff);
        if(Boolean.TRUE.equals(o.get("platform_waiting_for_pickup"))){
            if(historical&&!verifiedHistoricalHandoff(tenant,connection,(UUID)o.get("id")))
                return "Imported order needs a verified warehouse handoff before reprinting.";
            return null;
        }
        if(Set.of("PENDING","AUTHORIZED","PARTIALLY_PAID","VOIDED","REFUNDED").contains(o.get("payment_status")))return "Payment must be complete before a local shipping handoff.";
        var lines=jdbc.queryForList("SELECT l.*,coalesce((SELECT sum(value::int) FROM jsonb_each_text(l.success_checkpoint)),0) acknowledged FROM shopify_order_lines l WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? ORDER BY id",tenant,connection,o.get("id"));
        boolean open=false;
        for(var l:lines){
            int units=((Number)l.get("reserve_units")).intValue();
            if(historical&&Boolean.TRUE.equals(l.get("requires_shipping"))&&((Number)l.get("unfulfilled_quantity")).intValue()>0
                &&!verifiedCutover(tenant,connection,(UUID)l.get("id")))return "Imported open units need a verified warehouse cutover before packing.";
            if(units==0)continue;open=true;
            if(!("LIVE".equals(l.get("stock_scope"))||(localUat&&"TEST".equals(l.get("stock_scope"))))||!"READY_TO_SHIP".equals(l.get("inventory_state"))||l.get("review_reason")!=null
                ||!"MERCHANT".equals(l.get("ownership"))||!Objects.equals(l.get("inventory_revision"),l.get("source_updated_at"))
                ||((Number)l.get("local_handoff_units")).intValue()!=0)return "Each open line needs verified mapping and complete reserved warehouse stock.";
            var parts=jdbc.queryForList("SELECT account_catalog_item_id,quantity FROM shopify_order_components WHERE tenant_id=? AND marketplace_connection_id=? AND order_line_id=?",tenant,connection,l.get("id"));
            if(parts.isEmpty())return "Each open line needs a verified catalogue mapping.";
            for(var p:parts){
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT status='ACTIVE' FROM account_catalog_items WHERE tenant_id=? AND id=?",Boolean.class,tenant,p.get("account_catalog_item_id"))))return "A mapped catalogue item is inactive.";
                Boolean invalid=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM order_inventory_reservations r JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id JOIN inventory_ledger_entries layer ON layer.tenant_id=r.tenant_id AND layer.id=r.cost_layer_id WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.shopify_order_line_id=? AND r.status='ACTIVE' AND (loc.status<>'ACTIVE' OR layer.quantity<=0 OR layer.account_catalog_item_id<>r.account_catalog_item_id OR (r.expiration_date IS NOT NULL AND r.expiration_date<=current_date+coalesce((SELECT minimum_sellable_days FROM inventory_shelf_life_policies WHERE tenant_id=r.tenant_id),10))))",Boolean.class,tenant,connection,l.get("id"));
                if(Boolean.TRUE.equals(invalid))return "A reserved warehouse lot is no longer eligible. Review its location and expiry.";
                BigDecimal active=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM order_inventory_reservations WHERE tenant_id=? AND marketplace_connection_id=? AND shopify_order_line_id=? AND account_catalog_item_id=? AND status='ACTIVE'",BigDecimal.class,tenant,connection,l.get("id"),p.get("account_catalog_item_id"));
                if(active.compareTo(((BigDecimal)p.get("quantity")).multiply(BigDecimal.valueOf(units)))!=0)return "Reserved stock changed. Refresh this order before packing.";
            }
        }
        return open?null:"This order has no eligible open warehouse units.";
    }
    private boolean verifiedHistoricalHandoff(UUID tenant,UUID connection,UUID order){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM shopify_orders o WHERE o.tenant_id=? AND o.marketplace_connection_id=? AND o.id=?
              AND o.local_handoff_id IS NOT NULL AND o.local_handoff_at IS NOT NULL
              AND EXISTS(SELECT 1 FROM shopify_order_lines l WHERE l.tenant_id=o.tenant_id AND l.marketplace_connection_id=o.marketplace_connection_id AND l.order_id=o.id AND l.local_handoff_units>0)
              AND NOT EXISTS(SELECT 1 FROM shopify_order_lines l WHERE l.tenant_id=o.tenant_id AND l.marketplace_connection_id=o.marketplace_connection_id AND l.order_id=o.id
                AND l.requires_shipping AND (l.unfulfilled_quantity>0 OR l.local_handoff_units>0)
                AND (l.stock_scope<>'LIVE' OR l.ownership<>'MERCHANT' OR l.review_reason IS NOT NULL
                  OR l.local_handoff_units<=0 OR NOT EXISTS(SELECT 1 FROM shopify_order_cutovers c
                    WHERE c.tenant_id=l.tenant_id AND c.marketplace_connection_id=l.marketplace_connection_id AND c.order_line_id=l.id
                      AND c.remaining_units>=l.local_handoff_units)
                  OR NOT EXISTS(SELECT 1 FROM order_inventory_reservations r JOIN inventory_ledger_entries movement
                    ON movement.tenant_id=r.tenant_id AND movement.marketplace_connection_id=r.marketplace_connection_id
                      AND movement.idempotency_key='shopify-local:'||o.local_handoff_id::text||':'||r.id::text
                    WHERE r.tenant_id=l.tenant_id AND r.marketplace_connection_id=l.marketplace_connection_id AND r.shopify_order_line_id=l.id
                      AND r.status='SHIPPED' AND movement.quantity=-r.quantity))))
            )
            """,Boolean.class,tenant,connection,order));
    }
    private boolean verifiedCutover(UUID tenant,UUID connection,UUID line){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM shopify_order_cutovers c
            JOIN shopify_order_lines l ON l.tenant_id=c.tenant_id AND l.marketplace_connection_id=c.marketplace_connection_id AND l.id=c.order_line_id
            WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.order_line_id=?
              AND l.stock_scope='LIVE' AND l.inventory_state='READY_TO_SHIP' AND l.ownership='MERCHANT'
              AND l.reserve_units=l.unfulfilled_quantity AND l.reserve_units>0 AND l.reserve_units<=c.remaining_units
              AND l.inventory_revision=l.source_updated_at AND l.inventory_hash=l.observation_hash
              AND l.inventory_revision>=c.source_revision AND l.review_reason IS NULL
              AND c.composition=(SELECT jsonb_agg(jsonb_build_object('item',p.account_catalog_item_id,'quantity',p.quantity) ORDER BY p.account_catalog_item_id)
                FROM shopify_order_components p WHERE p.tenant_id=l.tenant_id AND p.marketplace_connection_id=l.marketplace_connection_id AND p.order_line_id=l.id))
            """,Boolean.class,tenant,connection,line));
    }
    @Transactional
    public void mark(UUID tenant,UUID connection,UUID id,boolean waiting,String actor){mark(tenant,connection,id,waiting,actor,()->{});}
    /** Verify an archived packing snapshot under the same stock/order locks as handoff. */
    @Transactional
    public void mark(UUID tenant,UUID connection,UUID id,boolean waiting,String actor,Runnable verifyPacking){
        var actors=jdbc.queryForList("SELECT id FROM app_users WHERE lower(email)=lower(?) AND status='ACTIVE'",UUID.class,actor);
        if(actors.size()!=1)throw new IllegalArgumentException("An active authenticated user is required for warehouse audit.");UUID actorId=actors.getFirst();
        inventory.lock(tenant);if(inventory.cutoff(tenant,connection)==null)throw new IllegalArgumentException("Local warehouse operations are disabled.");var o=order(tenant,connection,id,true);
        boolean marked=Boolean.TRUE.equals(o.get("platform_waiting_for_pickup"));
        if(waiting==marked)return; // repeated click is a no-op, including an undo replay
        if(!waiting){undo(tenant,connection,id,o,actor,actorId);return;}
        verifyPacking.run();
        jdbc.query("SELECT r.id FROM order_inventory_reservations r JOIN shopify_order_lines l ON l.tenant_id=r.tenant_id AND l.marketplace_connection_id=r.marketplace_connection_id AND l.id=r.shopify_order_line_id JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id JOIN account_catalog_items item ON item.tenant_id=r.tenant_id AND item.id=r.account_catalog_item_id JOIN inventory_ledger_entries layer ON layer.tenant_id=r.tenant_id AND layer.id=r.cost_layer_id WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND l.order_id=? AND r.status='ACTIVE' ORDER BY r.id FOR UPDATE OF r,l FOR SHARE OF loc,item,layer",rs->{},tenant,connection,id);
        String reason=eligibility(tenant,connection,o);if(reason!=null)throw new IllegalArgumentException(reason);
        UUID uatCycle=uat==null?null:uat.cycle(tenant,connection,id);UUID event=uatCycle==null?UUID.randomUUID():uatCycle;
        // Every component has been checked before the first movement. Allocation is never performed here.
        jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,marketplace_connection_id,entry_type,quantity,
              expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,cost_status,created_by)
            SELECT r.tenant_id,r.account_catalog_item_id,r.location_id,r.marketplace_connection_id,'SHIPMENT',-r.quantity,
              r.expiration_date,r.unit_cost,r.cost_currency,'SHOPIFY_ORDER',l.order_id,clock_timestamp(),?||r.id::text,
              ?,'FINAL',?
            FROM order_inventory_reservations r JOIN shopify_order_lines l ON l.tenant_id=r.tenant_id AND l.marketplace_connection_id=r.marketplace_connection_id AND l.id=r.shopify_order_line_id
            WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND l.order_id=? AND r.status='ACTIVE'
            ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
            ""","shopify-local:"+event+":",uatCycle==null?"Local packing handoff — awaiting pickup. Shopify fulfillment is unchanged.":"Local UAT shipping cycle "+uatCycle+" — sandbox label; Shopify fulfillment unchanged.",actorId,tenant,connection,id);
        jdbc.update("UPDATE order_inventory_reservations r SET status='SHIPPED' FROM shopify_order_lines l WHERE l.tenant_id=r.tenant_id AND l.marketplace_connection_id=r.marketplace_connection_id AND l.id=r.shopify_order_line_id AND r.tenant_id=? AND r.marketplace_connection_id=? AND l.order_id=? AND r.status='ACTIVE'",tenant,connection,id);
        jdbc.update("UPDATE shopify_order_lines SET local_handoff_units=reserve_units,local_pending_units=reserve_units,inventory_state='SHIPPED',updated_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? AND reserve_units>0",tenant,connection,id);
        jdbc.update("UPDATE shopify_orders SET platform_waiting_for_pickup=true,local_handoff_id=?,local_handoff_at=clock_timestamp(),local_handoff_revision=source_updated_at,local_handoff_actor=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",event,actor,tenant,connection,id);
        if(uat!=null)uat.handedOff(tenant,connection,id,event);
    }
    private void undo(UUID tenant,UUID connection,UUID id,Map<String,Object> o,String actor,UUID actorId){
        boolean changed=!Objects.equals(o.get("source_updated_at"),o.get("local_handoff_revision"));
        Boolean unsafe=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM shopify_order_lines l WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.order_id=?
              AND (l.local_pending_units<>l.local_handoff_units OR l.review_reason IS NOT NULL)) OR EXISTS(
              SELECT 1 FROM inventory_ledger_entries movement JOIN inventory_ledger_entries counted ON counted.tenant_id=movement.tenant_id
                AND counted.account_catalog_item_id=movement.account_catalog_item_id AND counted.location_id=movement.location_id
                AND counted.expiration_date IS NOT DISTINCT FROM movement.expiration_date AND counted.source_type='PHYSICAL_COUNT'
                AND counted.occurred_at>=movement.occurred_at WHERE movement.tenant_id=? AND movement.marketplace_connection_id=? AND movement.source_id=? AND movement.idempotency_key LIKE ?)
            """,Boolean.class,tenant,connection,id,tenant,connection,id,"shopify-local:"+o.get("local_handoff_id")+":%");
        if(changed||Boolean.TRUE.equals(unsafe))throw new IllegalArgumentException("Source evidence or a physical count changed after packing. Review the recorded movement; automatic undo is unsafe.");
        jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,marketplace_connection_id,entry_type,quantity,
              expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,cost_status,created_by)
            SELECT tenant_id,account_catalog_item_id,location_id,marketplace_connection_id,'PACKING_REVERSAL',-quantity,
              expiration_date,unit_cost,currency,'SHOPIFY_ORDER',source_id,clock_timestamp(),'shopify-undo:'||id::text,
              'Explicit undo of the exact local packing handoff. Shopify is unchanged.','FINAL',?
            FROM inventory_ledger_entries WHERE tenant_id=? AND marketplace_connection_id=? AND source_id=? AND idempotency_key LIKE ?
            ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
            """,actorId,tenant,connection,id,"shopify-local:"+o.get("local_handoff_id")+":%");
        jdbc.update("UPDATE shopify_order_lines SET local_handoff_units=0,local_pending_units=0,updated_at=clock_timestamp() WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=?",tenant,connection,id);
        jdbc.update("UPDATE shopify_orders SET platform_waiting_for_pickup=false,local_handoff_actor=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",actor,tenant,connection,id);
        inventory.reconcileTenant(tenant);
    }
    @Transactional(readOnly=true)
    public PackingSlipRepository.Slip slip(UUID tenant,UUID connection,UUID id){
        var o=order(tenant,connection,id,false);String domain=jdbc.queryForObject("SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,connection);
        domain=com.nextaicommerce.platform.web.ShopifyConnectionService.shopDomain(domain);
        var lines=jdbc.query("""
            SELECT coalesce(nullif(item.account_sku,''),l.seller_sku,'Unmapped SKU'),coalesce(item.display_name,g.canonical_name,l.title),
              coalesce(r.quantity,(CASE WHEN l.unfulfilled_quantity>0 THEN l.unfulfilled_quantity ELSE l.ordered_quantity END)*part.quantity,l.ordered_quantity),r.expiration_date,coalesce(loc.code,'—'),g.brand,item.id
            FROM shopify_order_lines l LEFT JOIN LATERAL(
              SELECT f.account_catalog_item_id,f.quantity FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id
              UNION ALL SELECT c.account_catalog_item_id,c.quantity FROM shopify_variants v
              JOIN marketplace_sku_mappings m ON m.tenant_id=v.tenant_id AND m.marketplace_connection_id=v.marketplace_connection_id AND m.shopify_variant_id=v.id AND m.status='ACTIVE' AND m.shopify_mapping_hash=v.mapping_hash
              JOIN marketplace_sku_mapping_components c ON c.tenant_id=m.tenant_id AND c.marketplace_sku_mapping_id=m.id
              WHERE v.tenant_id=l.tenant_id AND v.marketplace_connection_id=l.marketplace_connection_id AND v.id=l.variant_id
                AND NOT EXISTS(SELECT 1 FROM shopify_order_components f WHERE f.tenant_id=l.tenant_id AND f.marketplace_connection_id=l.marketplace_connection_id AND f.order_line_id=l.id)
            ) part ON true
            LEFT JOIN account_catalog_items item ON item.tenant_id=l.tenant_id AND item.id=part.account_catalog_item_id
            LEFT JOIN global_catalog_products g ON g.id=item.global_product_id
            LEFT JOIN order_inventory_reservations r ON r.tenant_id=l.tenant_id AND r.marketplace_connection_id=l.marketplace_connection_id AND r.shopify_order_line_id=l.id AND r.account_catalog_item_id=item.id AND (r.status='ACTIVE' OR (r.status='SHIPPED' AND EXISTS(SELECT 1 FROM shopify_orders h JOIN inventory_ledger_entries movement ON movement.tenant_id=h.tenant_id AND movement.marketplace_connection_id=h.marketplace_connection_id AND movement.source_id=h.id WHERE h.tenant_id=l.tenant_id AND h.marketplace_connection_id=l.marketplace_connection_id AND h.id=l.order_id AND h.platform_waiting_for_pickup AND movement.idempotency_key='shopify-local:'||h.local_handoff_id::text||':'||r.id::text)))
            LEFT JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.order_id=? ORDER BY l.line_gid,r.expiration_date NULLS LAST,r.id
            """,(rs,n)->new PackingSlipRepository.Line(rs.getString(1),rs.getString(2),rs.getBigDecimal(3).intValueExact(),rs.getObject(4,LocalDate.class),rs.getString(5),rs.getString(6),rs.getObject(7,UUID.class),null,!"—".equals(rs.getString(5))),tenant,connection,id);
        String gid=(String)o.get("order_gid"),name=(String)o.get("package_name");
        return new PackingSlipRepository.Slip(id.toString(),"https://"+domain+"/admin/orders/"+gid.substring(gid.lastIndexOf('/')+1),(String)o.get("order_name"),name==null?"Package to confirm":name,name!=null,lines);
    }
    @Transactional(readOnly=true)
    public List<String> packageNames(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        return jdbc.queryForList("SELECT DISTINCT ON (lower(name)) name FROM (SELECT btrim(regexp_replace(raw,'[[:space:]]+',' ','g')) name FROM (SELECT packaging raw FROM temporary_order_packaging_lookup WHERE tenant_id=? UNION ALL SELECT package_name FROM shopify_orders WHERE tenant_id=?) source) p WHERE nullif(name,'') IS NOT NULL ORDER BY lower(name),name",String.class,tenant,tenant);}
    @Transactional(readOnly=true)
    public boolean marked(UUID tenant,UUID connection,UUID id){return Boolean.TRUE.equals(order(tenant,connection,id,false).get("platform_waiting_for_pickup"));}
    @Transactional
    public String savePackage(UUID tenant,UUID connection,UUID id,String name){
        order(tenant,connection,id,true);String clean=name==null?"":name.trim().replaceAll("\\s+"," ");
        if(clean.isBlank()||clean.length()>120)throw new IllegalArgumentException("Choose a package name of up to120 characters.");
        jdbc.update("UPDATE shopify_orders SET package_name=? WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",clean,tenant,connection,id);return clean;
    }
}
