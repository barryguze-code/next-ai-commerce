package com.nextaicommerce.platform.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only draft planning. Basket/PO previews do not write operational data. */
@org.springframework.stereotype.Component
public class ReplenishmentDataLoader {
    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;
    public ReplenishmentDataLoader(JdbcTemplate jdbc, InventoryRepository inventory) {
        this.jdbc=jdbc; this.inventory=inventory;
    }
    List<Map<String,Object>> load(UUID tenant, int offset) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        var rows=jdbc.queryForList("""
            SELECT offer.unit_cost,item.id,coalesce(item.display_name,product.canonical_name) name,
                   coalesce(offer.vendor_item_code,item.account_sku,'—') code,
                   offer.vendor_id,coalesce(offer.vendor_name,'Choose vendor') vendor,coalesce(offer.vendor_code,'—') vendor_code,coalesce(offer.dc,'') dc,
                   coalesce(item.replenishment_units_per_case,offer.units_per_case,product.units_per_case,1) pack,
                   (SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=product.id
                    AND identifier_type IN ('UPC','EAN','GTIN') ORDER BY is_primary DESC,identifier_type,identifier_value LIMIT 1) upc
            FROM account_catalog_items item
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (
                SELECT CASE WHEN o.currency='USD' THEN round(o.list_cost*(1-o.discount_rate/100),4) END unit_cost,o.vendor_item_code,v.id vendor_id,v.vendor_code,v.name vendor_name,v.distribution_center dc,p.units_per_case
                FROM vendor_catalog_offers o JOIN vendors v ON v.tenant_id=o.tenant_id AND v.id=o.vendor_id
                LEFT JOIN global_product_packaging_versions p ON p.id=o.packaging_version_id
                WHERE o.tenant_id=item.tenant_id AND o.account_catalog_item_id=item.id
                  AND o.effective_from<=current_date AND (o.effective_to IS NULL OR o.effective_to>=current_date)
                ORDER BY o.is_default DESC,o.updated_at DESC,o.id LIMIT 1
            ) offer ON true
            WHERE item.tenant_id=? AND item.status='ACTIVE'
              AND EXISTS(SELECT 1 FROM marketplace_sku_mapping_components c
                JOIN marketplace_sku_mappings m ON m.tenant_id=c.tenant_id AND m.id=c.marketplace_sku_mapping_id
                JOIN amazon_order_items oi ON oi.tenant_id=m.tenant_id AND oi.marketplace_connection_id=m.marketplace_connection_id AND oi.seller_sku=m.marketplace_sku
                JOIN amazon_orders o ON o.tenant_id=oi.tenant_id AND o.marketplace_connection_id=oi.marketplace_connection_id AND o.amazon_order_id=oi.amazon_order_id
                WHERE c.tenant_id=item.tenant_id AND c.account_catalog_item_id=item.id AND m.status='ACTIVE'
                  AND oi.quantity_ordered>0 AND o.purchase_date>=now()-interval '28 days' AND o.purchase_date<=now()
                  AND (upper(o.order_status) IN ('UNSHIPPED','PARTIALLYSHIPPED','SHIPPED','SHIPPING','INVOICEUNCONFIRMED') OR upper(o.order_status) LIKE 'SHIPPED - %')
                  AND upper(coalesce(o.fulfillment_channel,'MFN')) NOT IN ('AFN','AMAZON'))
            ORDER BY coalesce(item.display_name,product.canonical_name),item.id LIMIT 25 OFFSET ?
            """,tenant,offset);
        List<UUID> ids=rows.stream().map(r->(UUID)r.get("id")).toList();
        Map<UUID,List<InventoryRepository.InventoryView>> positions=new HashMap<>();
        if(!ids.isEmpty())for(var p:inventory.inventory(tenant,ids))positions.computeIfAbsent(p.itemId(),ignored->new ArrayList<>()).add(p);
        var policy=inventory.shelfLifePolicy(tenant);
        var today=LocalDate.now(ZoneId.of("America/Los_Angeles"));
        Map<UUID,List<Map<String,Object>>> skus=new HashMap<>();
        if(!ids.isEmpty()) {
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            List<Object> args=new ArrayList<>();args.add(tenant);args.addAll(ids);
            var details=jdbc.queryForList("""
                WITH selected AS MATERIALIZED (
                    SELECT c.account_catalog_item_id item_id,c.quantity,m.tenant_id,m.marketplace_connection_id,m.marketplace_sku
                    FROM marketplace_sku_mapping_components c JOIN marketplace_sku_mappings m
                      ON m.tenant_id=c.tenant_id AND m.id=c.marketplace_sku_mapping_id
                    WHERE c.tenant_id=? AND m.status='ACTIVE' AND c.account_catalog_item_id IN (
                """+placeholders+"""
                    )
                ), sales AS (
                    SELECT oi.marketplace_connection_id,oi.seller_sku,
                      sum(oi.quantity_ordered) FILTER(WHERE o.purchase_date<now()-interval '21 days') w1,
                      sum(oi.quantity_ordered) FILTER(WHERE o.purchase_date>=now()-interval '21 days' AND o.purchase_date<now()-interval '14 days') w2,
                      sum(oi.quantity_ordered) FILTER(WHERE o.purchase_date>=now()-interval '14 days' AND o.purchase_date<now()-interval '7 days') w3,
                      sum(oi.quantity_ordered) FILTER(WHERE o.purchase_date>=now()-interval '7 days') w4,
                      count(DISTINCT o.amazon_order_id) orders
                    FROM amazon_order_items oi JOIN amazon_orders o ON o.tenant_id=oi.tenant_id
                      AND o.marketplace_connection_id=oi.marketplace_connection_id AND o.amazon_order_id=oi.amazon_order_id
                    WHERE EXISTS(SELECT 1 FROM selected s WHERE s.tenant_id=oi.tenant_id
                      AND s.marketplace_connection_id=oi.marketplace_connection_id AND s.marketplace_sku=oi.seller_sku)
                      AND o.purchase_date>=now()-interval '28 days' AND o.purchase_date<=now()
                      AND (upper(o.order_status) IN ('UNSHIPPED','PARTIALLYSHIPPED','SHIPPED','SHIPPING','INVOICEUNCONFIRMED') OR upper(o.order_status) LIKE 'SHIPPED - %')
                      AND upper(coalesce(o.fulfillment_channel,'MFN')) NOT IN ('AFN','AMAZON')
                    GROUP BY oi.marketplace_connection_id,oi.seller_sku
                )
                SELECT s.item_id,s.marketplace_connection_id connection_id,mc.marketplace_identifier marketplace_id,mc.reporting_timezone,s.marketplace_sku sku,s.quantity,coalesce(mc.display_name,'Amazon') store,
                  coalesce(sales.w1,0) w1,coalesce(sales.w2,0) w2,coalesce(sales.w3,0) w3,coalesce(sales.w4,0) w4,
                  coalesce(sales.orders,0) orders,listing.quantity listed_quantity,listing.item_name title,listing.image_url,listing.asin,
                  listing.price price,coalesce(nullif(trim(listing.currency),''),md.currency_code) price_currency,
                  listing.buy_box_price bb,listing.buy_box_currency currency,listing.buy_box_updated_at bb_updated,
                  CASE WHEN sale.status='CONFIRMED'
                    AND (sale.owned_discount#>>'{0,schedule,0,start_at}')::timestamptz<=now()
                    AND (sale.owned_discount#>>'{0,schedule,0,end_at}')::timestamptz>now()
                    THEN (sale.owned_discount#>>'{0,schedule,0,value_with_tax}')::numeric END sale_price
                FROM selected s JOIN marketplace_connections mc ON mc.tenant_id=s.tenant_id AND mc.id=s.marketplace_connection_id
                LEFT JOIN marketplace_definitions md ON md.channel=mc.channel AND md.marketplace_identifier=mc.marketplace_identifier
                LEFT JOIN sales ON sales.marketplace_connection_id=s.marketplace_connection_id AND sales.seller_sku=s.marketplace_sku
                LEFT JOIN amazon_listings listing ON listing.tenant_id=s.tenant_id
                  AND listing.marketplace_connection_id=s.marketplace_connection_id AND listing.seller_sku=s.marketplace_sku
                LEFT JOIN shelf_sale_publications sale ON sale.tenant_id=s.tenant_id AND sale.connection_id=s.marketplace_connection_id
                  AND sale.marketplace_id=listing.marketplace_id AND sale.seller_sku=s.marketplace_sku
                ORDER BY s.item_id,s.marketplace_sku
                """,args.toArray());
            for(var sku:details)skus.computeIfAbsent((UUID)sku.get("item_id"),ignored->new ArrayList<>()).add(sku);
        }
        for(var row:rows) {
            UUID id=(UUID)row.get("id");
            var stock=positions.getOrDefault(id,List.of());
            BigDecimal available=BigDecimal.ZERO,physical=BigDecimal.ZERO,reserved=BigDecimal.ZERO,expired=BigDecimal.ZERO,soon=BigDecimal.ZERO;
            for(var p:stock) {
                physical=physical.add(p.quantity());reserved=reserved.add(p.reservedQuantity());
                if(p.daysRemaining(today)<0)expired=expired.add(p.quantity().max(BigDecimal.ZERO));
                boolean cutoff=policy.autoZeroMarketplaceSellable()&&p.daysRemaining(today)<=policy.minimumSellableDays();
                if(!cutoff&&!p.marketplaceStoppedByPlan()) {
                    var sellable=p.quantity().subtract(p.reservedQuantity()).max(BigDecimal.ZERO);
                    available=available.add(sellable);
                    if(p.daysRemaining(today)>=0&&p.daysRemaining(today)<=policy.warningDays())soon=soon.add(sellable);
                }
            }
            var children=skus.getOrDefault(id,List.of());
            BigDecimal demand=BigDecimal.ZERO;
            for(var sku:children) {
                sku.put("seller_url","https://sellercentral."+com.nextaicommerce.platform.marketplace.MarketplaceLinks.amazonDomain(Objects.toString(sku.get("marketplace_id"),""))+"/myinventory/inventory?searchTerm="+java.net.URLEncoder.encode(Objects.toString(sku.get("asin"),sku.get("sku").toString()),java.nio.charset.StandardCharsets.UTF_8));
                BigDecimal sold=BigDecimal.ZERO;
                for(String key:List.of("w1","w2","w3","w4"))sold=sold.add(new BigDecimal(sku.get(key).toString()));
                var eaches=sold.multiply(new BigDecimal(sku.get("quantity").toString()));sku.put("eaches",eaches);demand=demand.add(eaches);
            }
            row.put("available",available);row.put("physical",physical);row.put("reserved",reserved);
            row.put("expired",expired);row.put("soon",soon);
            row.put("demand",demand);row.put("skus",children);
            BigDecimal pack=new BigDecimal(row.get("pack").toString()).max(BigDecimal.ONE);
            row.put("demand_cases",demand.divideToIntegralValue(pack));
            row.put("demand_remainder",demand.remainder(pack));
            row.put("available_cases",available.divideToIntegralValue(pack));
            row.put("available_remainder",available.remainder(pack));
            List<BigDecimal> weeks=new ArrayList<>();
            for(String key:List.of("w1","w2","w3","w4")) {
                BigDecimal sum=BigDecimal.ZERO;
                for(var sku:children)sum=sum.add(new BigDecimal(sku.get(key).toString()).multiply(new BigDecimal(sku.get("quantity").toString())));
                weeks.add(sum);
            }
            row.put("weeks",weeks.stream().map(BigDecimal::toPlainString).collect(java.util.stream.Collectors.joining(" | ")));
        }
        if(!ids.isEmpty()) {
            var args=new ArrayList<Object>();args.add(tenant);args.addAll(ids);
            var receiptStats=jdbc.queryForList("SELECT l.account_catalog_item_id id,round(avg(l.expiration_date-(l.occurred_at AT TIME ZONE 'America/Los_Angeles')::date)) average_days,count(*) samples FROM inventory_ledger_entries l WHERE l.tenant_id=? AND l.account_catalog_item_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") AND l.entry_type='RECEIPT' AND l.quantity>0 AND l.expiration_date IS NOT NULL AND l.occurred_at>=now()-interval '180 days' AND NOT EXISTS(SELECT 1 FROM receiving_line_receipts r WHERE r.tenant_id=l.tenant_id AND r.id=l.source_id AND r.voided_at IS NOT NULL) GROUP BY l.account_catalog_item_id",args.toArray());
            for(var row:rows) {row.put("receipt_days",null);row.put("receipt_samples",0);for(var stat:receiptStats)if(row.get("id").equals(stat.get("id"))){row.put("receipt_days",stat.get("average_days"));row.put("receipt_samples",stat.get("samples"));}}
        }
        return rows;
    }
}
