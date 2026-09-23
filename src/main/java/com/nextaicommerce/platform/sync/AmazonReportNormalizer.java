package com.nextaicommerce.platform.sync;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneId;
import java.sql.Timestamp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper;

@Component
public class AmazonReportNormalizer {
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions; private final ObjectMapper json;
    private final MarketplaceSkuAutoMapper skuMapper;
    public AmazonReportNormalizer(JdbcTemplate jdbc,TransactionTemplate transactions,ObjectMapper json,
            MarketplaceSkuAutoMapper skuMapper){this.jdbc=jdbc;this.transactions=transactions;this.json=json;this.skuMapper=skuMapper;}

    public long normalize(AmazonSyncStore.Job job,String text,UUID sourceDocument){
        return transactions.execute(status->{setTenant(job.tenantId());return switch(job.type()){
            case "LISTINGS_SNAPSHOT" -> listings(job,text);
            case "ORDERS_30_DAY" -> orders(job,text);
            case "INVENTORY_SNAPSHOT" -> inventorySnapshot(job,text,sourceDocument);
            case "INVENTORY_LEDGER_30_DAY" -> inventoryLedger(job,text,sourceDocument);
            case "FBA_CUSTOMER_SHIPMENTS_30_DAY" -> customerShipments(job,text,sourceDocument);
            case "RETURNS_30_DAY" -> returns(job,text,sourceDocument);
            case "REIMBURSEMENTS_30_DAY" -> reimbursements(job,text,sourceDocument);
            case "FEES_SNAPSHOT" -> feeEstimates(job,text,sourceDocument);
            default -> countRows(text);
        };});
    }

    public long normalizeJson(AmazonSyncStore.Job job,JsonNode root,UUID sourceDocument){
        return transactions.execute(status->{setTenant(job.tenantId());
            if(!"FINANCES_30_DAY".equals(job.type()))return 0L;
            return finances(job,root,sourceDocument);
        });
    }

    public long normalizeOrderPage(AmazonSyncStore.Job job,JsonNode root,UUID sourceDocument){
        return transactions.execute(status->{setTenant(job.tenantId());long count=0;
            for(JsonNode order:root.path("payload").path("Orders")){upsertApiOrder(job,order);count++;}
            return count;
        });
    }

    public long normalizeOrderItems(AmazonSyncStore.Job job,String orderId,JsonNode root,UUID sourceDocument){
        return transactions.execute(status->{setTenant(job.tenantId());long count=0;
            jdbc.query("SELECT id FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? FOR UPDATE",rs->{},job.tenantId(),job.connectionId(),orderId);
            for(JsonNode item:root.path("payload").path("OrderItems")){upsertApiOrderItem(job,orderId,item);count++;}
            refreshOrderTotal(job,orderId);return count;
        });
    }

    private void upsertApiOrder(AmazonSyncStore.Job job,JsonNode order){
        String orderId=order.path("AmazonOrderId").asText();if(blank(orderId))return;
        var purchase=time(order.path("PurchaseDate").asText(),job.marketplaceId());
        var updated=time(order.path("LastUpdateDate").asText(),job.marketplaceId());
        JsonNode total=order.path("OrderTotal");
        jdbc.update("""
            INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,
                purchase_date,last_update_date,purchase_marketplace_date,last_update_marketplace_date,
                order_status,fulfillment_channel,sales_channel,ship_service_level,order_total,currency)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_id,marketplace_connection_id,amazon_order_id) DO UPDATE SET
                purchase_date=EXCLUDED.purchase_date,last_update_date=EXCLUDED.last_update_date,
                purchase_marketplace_date=EXCLUDED.purchase_marketplace_date,
                last_update_marketplace_date=EXCLUDED.last_update_marketplace_date,
                order_status=EXCLUDED.order_status,fulfillment_channel=EXCLUDED.fulfillment_channel,
                sales_channel=EXCLUDED.sales_channel,ship_service_level=EXCLUDED.ship_service_level,
                order_total=coalesce(EXCLUDED.order_total,amazon_orders.order_total),
                currency=coalesce(EXCLUDED.currency,amazon_orders.currency),updated_at=now()
            WHERE amazon_orders.last_update_date IS NULL OR EXCLUDED.last_update_date>amazon_orders.last_update_date
              OR (EXCLUDED.last_update_date=amazon_orders.last_update_date AND
                (regexp_replace(upper(amazon_orders.order_status),'[^A-Z]','','g') NOT IN ('CANCELED','CANCELLED')
                 OR regexp_replace(upper(EXCLUDED.order_status),'[^A-Z]','','g') IN ('CANCELED','CANCELLED')))
            """,job.tenantId(),job.connectionId(),job.marketplaceId(),orderId,purchase.instant(),updated.instant(),
            purchase.marketplaceDate(),updated.marketplaceDate(),order.path("OrderStatus").asText(null),
            order.path("FulfillmentChannel").asText(null),order.path("SalesChannel").asText(null),
            order.path("ShipServiceLevel").asText(null),decimal(total.path("Amount").asText(null)),
            nullIfBlank(total.path("CurrencyCode").asText(null)));
        updateOperationalState(job,orderId);
    }

    private void upsertApiOrderItem(AmazonSyncStore.Job job,String orderId,JsonNode item){
        String itemId=item.path("OrderItemId").asText();
        String apiItemId=itemId;
        if(!blank(itemId)){
            var matches=jdbc.queryForList("""
                SELECT amazon_order_item_id FROM amazon_order_items
                WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
                  AND (amazon_order_item_id=? OR raw_payload->>'apiOrderItemId'=?
                    OR (amazon_order_item_id=? AND raw_payload->>'apiOrderItemId' IS NULL))
                ORDER BY CASE WHEN amazon_order_item_id=? THEN 0
                    WHEN raw_payload->>'apiOrderItemId'=? THEN 1 ELSE 2 END LIMIT 1
                """,String.class,job.tenantId(),job.connectionId(),orderId,itemId,itemId,
                stableItemId(orderId,item.path("SellerSKU").asText(),item.path("ASIN").asText()),itemId,itemId);
            if(!matches.isEmpty())itemId=matches.getFirst();
        }
        if(blank(itemId))itemId=stableItemId(orderId,item.path("SellerSKU").asText(),item.path("ASIN").asText());
        jdbc.update("""
            INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,
                seller_sku,asin,title,quantity_ordered,quantity_shipped,item_price,item_tax,shipping_price,shipping_tax,
                promotion_discount,shipping_discount,currency)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(tenant_id,marketplace_connection_id,amazon_order_item_id) DO UPDATE SET
                seller_sku=EXCLUDED.seller_sku,asin=EXCLUDED.asin,title=EXCLUDED.title,
                quantity_ordered=EXCLUDED.quantity_ordered,quantity_shipped=EXCLUDED.quantity_shipped,
                item_price=EXCLUDED.item_price,item_tax=EXCLUDED.item_tax,shipping_price=EXCLUDED.shipping_price,
                shipping_tax=EXCLUDED.shipping_tax,promotion_discount=EXCLUDED.promotion_discount,
                shipping_discount=EXCLUDED.shipping_discount,
                currency=EXCLUDED.currency,updated_at=now()
            """,job.tenantId(),job.connectionId(),orderId,itemId,item.path("SellerSKU").asText(null),
            item.path("ASIN").asText(null),item.path("Title").asText(null),item.path("QuantityOrdered").asInt(0),
            item.path("QuantityShipped").asInt(0),money(item,"ItemPrice"),money(item,"ItemTax"),
            money(item,"ShippingPrice"),money(item,"ShippingTax"),money(item,"PromotionDiscount"),money(item,"ShippingDiscount"),
            moneyCurrency(item,"ItemPrice","ShippingPrice"));
        if(!blank(apiItemId))jdbc.update("""
            UPDATE amazon_order_items SET raw_payload=jsonb_set(raw_payload,'{apiOrderItemId}',to_jsonb(?::text))
            WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_item_id=?
            """,apiItemId,job.tenantId(),job.connectionId(),itemId);
    }

    private void refreshOrderTotal(AmazonSyncStore.Job job,String orderId){
        jdbc.update("""
            UPDATE amazon_orders orders SET order_total=coalesce(orders.order_total,totals.total),
                currency=coalesce(orders.currency,totals.currency),updated_at=now()
            FROM (SELECT sum(coalesce(item_price,0)+coalesce(item_tax,0)+coalesce(shipping_price,0)
                      +coalesce(shipping_tax,0)-coalesce(promotion_discount,0)-coalesce(shipping_discount,0)) total,max(currency) currency
                  FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?) totals
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=? AND orders.amazon_order_id=?
            """,job.tenantId(),job.connectionId(),orderId,job.tenantId(),job.connectionId(),orderId);
    }

    private void updateOperationalState(AmazonSyncStore.Job job,String orderId){
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
                    ELSE orders.fulfillment_state END,operational_updated_at=now()
            FROM marketplace_connections connection
            WHERE orders.tenant_id=? AND orders.marketplace_connection_id=? AND orders.amazon_order_id=?
              AND connection.tenant_id=orders.tenant_id AND connection.id=orders.marketplace_connection_id
            """,job.tenantId(),job.connectionId(),orderId);
    }

    private static BigDecimal money(JsonNode item,String field){
        return decimal(item.path(field).path("Amount").asText(null));
    }
    private static String moneyCurrency(JsonNode item,String... fields){
        for(String field:fields){String value=item.path(field).path("CurrencyCode").asText();if(!value.isBlank())return value;}
        return null;
    }

    private long listings(AmazonSyncStore.Job job,String text){
        Parsed report=parse(text);long count=0;
        for(String[] row:report.rows()){
            String sku=value(report,row,"seller-sku","sku");if(blank(sku))continue;
            jdbc.update("""
                INSERT INTO amazon_listings(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,asin,
                    product_id,product_id_type,item_name,listing_id,listing_status,condition_type,fulfillment_channel,
                    merchant_shipping_group,price,currency,quantity,pending_quantity,open_date,image_url,last_seen_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
                ON CONFLICT(tenant_id,marketplace_connection_id,marketplace_id,seller_sku) DO UPDATE SET
                    asin=EXCLUDED.asin,product_id=EXCLUDED.product_id,product_id_type=EXCLUDED.product_id_type,
                    item_name=EXCLUDED.item_name,listing_id=EXCLUDED.listing_id,listing_status=EXCLUDED.listing_status,
                    condition_type=EXCLUDED.condition_type,fulfillment_channel=EXCLUDED.fulfillment_channel,
                    merchant_shipping_group=EXCLUDED.merchant_shipping_group,price=EXCLUDED.price,currency=EXCLUDED.currency,
                    quantity=EXCLUDED.quantity,pending_quantity=EXCLUDED.pending_quantity,open_date=EXCLUDED.open_date,
                    image_url=coalesce(EXCLUDED.image_url,amazon_listings.image_url),
                    image_source_url=coalesce(EXCLUDED.image_url,amazon_listings.image_source_url),
                    image_status=CASE WHEN EXCLUDED.image_url IS NOT NULL THEN 'AVAILABLE'
                        WHEN amazon_listings.platform_status='REMOVED' THEN 'PENDING' ELSE amazon_listings.image_status END,
                    image_next_attempt_at=CASE WHEN amazon_listings.platform_status='REMOVED' THEN NULL ELSE amazon_listings.image_next_attempt_at END,
                    image_failure_code=CASE WHEN amazon_listings.platform_status='REMOVED' THEN NULL ELSE amazon_listings.image_failure_code END,
                    image_failure_message=CASE WHEN amazon_listings.platform_status='REMOVED' THEN NULL ELSE amazon_listings.image_failure_message END,
                    platform_status=CASE WHEN amazon_listings.platform_status='REMOVED' THEN 'VISIBLE' ELSE amazon_listings.platform_status END,
                    amazon_removed_at=CASE WHEN amazon_listings.platform_status='REMOVED' THEN NULL ELSE amazon_listings.amazon_removed_at END,
                    last_seen_at=now(),updated_at=now()
                """,job.tenantId(),job.connectionId(),job.marketplaceId(),sku,
                value(report,row,"asin1","asin"),value(report,row,"product-id"),value(report,row,"product-id-type"),
                value(report,row,"item-name","product-name"),value(report,row,"listing-id"),value(report,row,"status"),
                value(report,row,"item-condition","condition-type"),value(report,row,"fulfillment-channel"),
                value(report,row,"merchant-shipping-group"),decimal(value(report,row,"price")),
                currency(value(report,row,"currency")),integer(value(report,row,"quantity")),
                integer(value(report,row,"pending-quantity")),time(value(report,row,"open-date"),job.marketplaceId()).instant(),
                nullIfBlank(value(report,row,"image-url","main-image-url")));
            count++;
        }
        skuMapper.mapConnection(job.tenantId(),job.connectionId());
        return count;
    }

    private long orders(AmazonSyncStore.Job job,String text){
        Parsed report=parse(text);long count=0;
        for(String[] row:report.rows()){
            String orderId=value(report,row,"amazon-order-id");if(blank(orderId))continue;
            var purchase=time(value(report,row,"purchase-date"),job.marketplaceId());
            var updated=time(value(report,row,"last-updated-date"),job.marketplaceId());
            jdbc.update("""
                INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id,
                    purchase_date,last_update_date,purchase_marketplace_date,last_update_marketplace_date,
                    order_status,fulfillment_channel,sales_channel,ship_service_level)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,marketplace_connection_id,amazon_order_id) DO UPDATE SET
                    purchase_date=EXCLUDED.purchase_date,last_update_date=EXCLUDED.last_update_date,
                    purchase_marketplace_date=EXCLUDED.purchase_marketplace_date,
                    last_update_marketplace_date=EXCLUDED.last_update_marketplace_date,
                    order_status=EXCLUDED.order_status,fulfillment_channel=EXCLUDED.fulfillment_channel,
                    sales_channel=EXCLUDED.sales_channel,ship_service_level=EXCLUDED.ship_service_level,updated_at=now()
                WHERE amazon_orders.last_update_date IS NULL OR EXCLUDED.last_update_date>amazon_orders.last_update_date
                  OR (EXCLUDED.last_update_date=amazon_orders.last_update_date AND
                    (regexp_replace(upper(amazon_orders.order_status),'[^A-Z]','','g') NOT IN ('CANCELED','CANCELLED')
                     OR regexp_replace(upper(EXCLUDED.order_status),'[^A-Z]','','g') IN ('CANCELED','CANCELLED')))
                """,job.tenantId(),job.connectionId(),job.marketplaceId(),orderId,
                purchase.instant(),updated.instant(),purchase.marketplaceDate(),updated.marketplaceDate(),
                value(report,row,"order-status"),value(report,row,"fulfillment-channel"),
                value(report,row,"sales-channel"),value(report,row,"ship-service-level"));
            String sku=value(report,row,"sku","seller-sku");String asin=value(report,row,"asin");
            String itemId=value(report,row,"order-item-id","amazon-order-item-id");
            if(blank(itemId)){
                var existing=jdbc.queryForList("""
                    SELECT amazon_order_item_id FROM amazon_order_items
                    WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
                      AND seller_sku IS NOT DISTINCT FROM ? AND asin IS NOT DISTINCT FROM ?
                    """,String.class,job.tenantId(),job.connectionId(),orderId,sku,asin);
                // Reports can aggregate multiple API lines for the same SKU. Preserve
                // those distinct lines rather than adding a duplicate aggregate item.
                if(existing.size()>1){updateOperationalState(job,orderId);count++;continue;}
                itemId=existing.isEmpty()?stableItemId(orderId,sku,asin):existing.getFirst();
            }
            jdbc.update("""
                INSERT INTO amazon_order_items(tenant_id,marketplace_connection_id,amazon_order_id,amazon_order_item_id,
                    seller_sku,asin,title,quantity_ordered,quantity_shipped,item_price,item_tax,shipping_price,shipping_tax,
                    promotion_discount,shipping_discount,currency)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,marketplace_connection_id,amazon_order_item_id) DO UPDATE SET
                    seller_sku=EXCLUDED.seller_sku,asin=EXCLUDED.asin,title=EXCLUDED.title,
                    quantity_ordered=EXCLUDED.quantity_ordered,quantity_shipped=EXCLUDED.quantity_shipped,
                    item_price=EXCLUDED.item_price,item_tax=EXCLUDED.item_tax,shipping_price=EXCLUDED.shipping_price,
                    shipping_tax=EXCLUDED.shipping_tax,promotion_discount=EXCLUDED.promotion_discount,
                    shipping_discount=EXCLUDED.shipping_discount,
                    currency=EXCLUDED.currency,updated_at=now()
                """,job.tenantId(),job.connectionId(),orderId,itemId,sku,asin,value(report,row,"product-name","title"),
                zero(integer(value(report,row,"quantity","quantity-purchased"))),
                zero(integer(value(report,row,"quantity-shipped"))),decimal(value(report,row,"item-price")),
                decimal(value(report,row,"item-tax")),decimal(value(report,row,"shipping-price")),
                decimal(value(report,row,"shipping-tax")),decimal(value(report,row,"item-promotion-discount")),
                decimal(value(report,row,"ship-promotion-discount","shipping-promotion-discount")),
                currency(value(report,row,"currency")));
            jdbc.update("""
                UPDATE amazon_orders orders SET order_total=totals.total,
                    currency=coalesce(totals.currency,orders.currency),updated_at=now()
                FROM (SELECT sum(coalesce(item_price,0)+coalesce(item_tax,0)+coalesce(shipping_price,0)
                          +coalesce(shipping_tax,0)-coalesce(promotion_discount,0)-coalesce(shipping_discount,0)) total,max(currency) currency
                      FROM amazon_order_items
                      WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?) totals
                WHERE orders.tenant_id=? AND orders.marketplace_connection_id=? AND orders.amazon_order_id=?
                """,job.tenantId(),job.connectionId(),orderId,job.tenantId(),job.connectionId(),orderId);
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
                WHERE orders.tenant_id=? AND orders.marketplace_connection_id=? AND orders.amazon_order_id=?
                  AND connection.tenant_id=orders.tenant_id AND connection.id=orders.marketplace_connection_id
                """,job.tenantId(),job.connectionId(),orderId);
            count++;
        }return count;
    }

    private long inventorySnapshot(AmazonSyncStore.Job job,String text,UUID source){
        Parsed report=parse(text);long count=0;Timestamp snapshot=Timestamp.from(Instant.now());
        for(int i=0;i<report.rows().size();i++){
            String[] row=report.rows().get(i);String sku=value(report,row,"sku","seller-sku");
            if(blank(sku)){reject(job,source,i+2,"Missing seller SKU",row);continue;}
            jdbc.update("""
                INSERT INTO amazon_inventory_snapshots(tenant_id,marketplace_connection_id,marketplace_id,
                    seller_sku,fnsku,asin,condition_type,fulfillable_quantity,inbound_working_quantity,
                    inbound_shipped_quantity,inbound_receiving_quantity,reserved_quantity,unfulfillable_quantity,
                    researching_quantity,total_quantity,snapshot_at,raw_payload,source_document_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                ON CONFLICT(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,snapshot_at) DO UPDATE SET
                    fnsku=EXCLUDED.fnsku,asin=EXCLUDED.asin,condition_type=EXCLUDED.condition_type,
                    fulfillable_quantity=EXCLUDED.fulfillable_quantity,
                    inbound_working_quantity=EXCLUDED.inbound_working_quantity,
                    inbound_shipped_quantity=EXCLUDED.inbound_shipped_quantity,
                    inbound_receiving_quantity=EXCLUDED.inbound_receiving_quantity,
                    reserved_quantity=EXCLUDED.reserved_quantity,unfulfillable_quantity=EXCLUDED.unfulfillable_quantity,
                    researching_quantity=EXCLUDED.researching_quantity,total_quantity=EXCLUDED.total_quantity,
                    raw_payload=EXCLUDED.raw_payload,source_document_id=EXCLUDED.source_document_id
                """,job.tenantId(),job.connectionId(),job.marketplaceId(),sku,value(report,row,"fnsku"),
                value(report,row,"asin"),value(report,row,"condition","condition-type"),
                zero(integer(value(report,row,"afn-fulfillable-quantity","fulfillable-quantity"))),
                zero(integer(value(report,row,"afn-inbound-working-quantity","inbound-working-quantity"))),
                zero(integer(value(report,row,"afn-inbound-shipped-quantity","inbound-shipped-quantity"))),
                zero(integer(value(report,row,"afn-inbound-receiving-quantity","inbound-receiving-quantity"))),
                zero(integer(value(report,row,"afn-reserved-quantity","reserved-quantity"))),
                zero(integer(value(report,row,"afn-unsellable-quantity","unfulfillable-quantity"))),
                zero(integer(value(report,row,"afn-researching-quantity","researching-quantity"))),
                zero(integer(value(report,row,"afn-total-quantity","total-quantity"))),snapshot,rowJson(report,row),source);
            count++;
        }return count;
    }

    private long inventoryLedger(AmazonSyncStore.Job job,String text,UUID source){
        Parsed report=parse(text);
        ZoneId zone=AmazonMarketplaceTime.zone(job.marketplaceId());
        var start=job.windowStart().atZone(zone).toLocalDate();var end=job.windowEnd().atZone(zone).toLocalDate();
        jdbc.update("""
            UPDATE amazon_inventory_events SET active=false,superseded_at=now()
            WHERE tenant_id=? AND marketplace_connection_id=? AND active=true
              AND event_marketplace_date BETWEEN ? AND ?
            """,job.tenantId(),job.connectionId(),start,end);
        Map<String,Integer> occurrences=new HashMap<>();long count=0;
        for(int i=0;i<report.rows().size();i++){
            String[] row=report.rows().get(i);
            var eventTime=time(value(report,row,"date-and-time","date","event-date"),job.marketplaceId());
            String eventType=value(report,row,"event-type","event");Integer quantity=integer(value(report,row,"quantity"));
            if(eventTime.instant()==null||blank(eventType)||quantity==null){
                reject(job,source,i+2,"Ledger row requires a valid date, event type, and quantity",row);continue;
            }
            String fingerprint=hashFields(eventTime.sourceValue(),eventType,value(report,row,"msku","sku","seller-sku"),
                value(report,row,"fnsku"),value(report,row,"asin"),value(report,row,"fulfillment-center","fulfillment-center-id"),
                value(report,row,"disposition"),value(report,row,"reference-id"),String.valueOf(quantity));
            int occurrence=occurrences.merge(fingerprint,1,Integer::sum);String key=occurrenceKey(fingerprint,occurrence);
            jdbc.update("""
                INSERT INTO amazon_inventory_events(tenant_id,marketplace_connection_id,marketplace_id,event_key,
                    event_date,event_marketplace_date,event_type,seller_sku,fnsku,asin,fulfillment_center,disposition,
                    reference_id,quantity,raw_payload,source_document_id,row_fingerprint,occurrence_number,
                    report_window_start,report_window_end,active,superseded_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,true,NULL)
                ON CONFLICT(tenant_id,marketplace_connection_id,event_key) DO UPDATE SET
                    event_date=EXCLUDED.event_date,event_marketplace_date=EXCLUDED.event_marketplace_date,
                    event_type=EXCLUDED.event_type,seller_sku=EXCLUDED.seller_sku,fnsku=EXCLUDED.fnsku,
                    asin=EXCLUDED.asin,fulfillment_center=EXCLUDED.fulfillment_center,
                    disposition=EXCLUDED.disposition,reference_id=EXCLUDED.reference_id,quantity=EXCLUDED.quantity,
                    raw_payload=EXCLUDED.raw_payload,source_document_id=EXCLUDED.source_document_id,
                    report_window_start=EXCLUDED.report_window_start,report_window_end=EXCLUDED.report_window_end,
                    active=true,superseded_at=NULL
                """,job.tenantId(),job.connectionId(),job.marketplaceId(),key,eventTime.instant(),
                eventTime.marketplaceDate(),eventType,value(report,row,"msku","sku","seller-sku"),value(report,row,"fnsku"),
                value(report,row,"asin"),value(report,row,"fulfillment-center","fulfillment-center-id"),
                value(report,row,"disposition"),value(report,row,"reference-id"),quantity,rowJson(report,row),source,
                fingerprint,occurrence,start,end);
            count++;
        }return count;
    }

    private long customerShipments(AmazonSyncStore.Job job,String text,UUID source){
        Parsed report=parse(text);Map<String,Integer> occurrences=new HashMap<>();long count=0;
        for(int i=0;i<report.rows().size();i++){
            String[] row=report.rows().get(i);String order=value(report,row,"amazon-order-id");
            String shipment=value(report,row,"shipment-id");String item=value(report,row,"shipment-item-id");
            var shipped=time(value(report,row,"shipment-date"),job.marketplaceId());
            String fingerprint=hashFields(order,shipment,item,value(report,row,"amazon-order-item-id"),
                value(report,row,"sku","seller-sku"),shipped.sourceValue(),value(report,row,"quantity-shipped"));
            int occurrence=occurrences.merge(fingerprint,1,Integer::sum);String key=!blank(item)?occurrenceKey(item,occurrence):occurrenceKey(fingerprint,occurrence);
            jdbc.update("""
                INSERT INTO amazon_customer_shipments(tenant_id,marketplace_connection_id,shipment_key,shipment_id,
                    shipment_item_id,amazon_order_id,amazon_order_item_id,seller_sku,asin,shipment_date,
                    shipment_marketplace_date,quantity,fulfillment_center,fulfillment_channel,sales_channel,currency,
                    item_price,item_tax,shipping_price,shipping_tax,raw_payload,source_document_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                ON CONFLICT(tenant_id,marketplace_connection_id,shipment_key) DO UPDATE SET
                    shipment_date=EXCLUDED.shipment_date,shipment_marketplace_date=EXCLUDED.shipment_marketplace_date,
                    quantity=EXCLUDED.quantity,item_price=EXCLUDED.item_price,item_tax=EXCLUDED.item_tax,
                    shipping_price=EXCLUDED.shipping_price,shipping_tax=EXCLUDED.shipping_tax,
                    raw_payload=EXCLUDED.raw_payload,source_document_id=EXCLUDED.source_document_id,updated_at=now()
                """,job.tenantId(),job.connectionId(),key,shipment,item,order,value(report,row,"amazon-order-item-id"),
                value(report,row,"sku","seller-sku"),value(report,row,"asin"),shipped.instant(),shipped.marketplaceDate(),
                zero(integer(value(report,row,"quantity-shipped","quantity"))),
                value(report,row,"fulfillment-center-id","fulfillment-center"),value(report,row,"fulfillment-channel"),
                value(report,row,"sales-channel"),currency(value(report,row,"currency")),decimal(value(report,row,"item-price")),
                decimal(value(report,row,"item-tax")),decimal(value(report,row,"shipping-price")),
                decimal(value(report,row,"shipping-tax")),rowJson(report,row),source);
            count++;
        }return count;
    }

    private long returns(AmazonSyncStore.Job job,String text,UUID source){
        Parsed report=parse(text);Map<String,Integer> occurrences=new HashMap<>();long count=0;
        for(int i=0;i<report.rows().size();i++){
            String[] row=report.rows().get(i);var returned=time(value(report,row,"return-date"),job.marketplaceId());
            String fingerprint=hashFields(returned.sourceValue(),value(report,row,"order-id","amazon-order-id"),
                value(report,row,"sku","seller-sku"),value(report,row,"fnsku"),value(report,row,"asin"),
                value(report,row,"quantity"),value(report,row,"detailed-disposition"),value(report,row,"reason"),
                value(report,row,"license-plate-number"));
            int occurrence=occurrences.merge(fingerprint,1,Integer::sum);String key=occurrenceKey(fingerprint,occurrence);
            jdbc.update("""
                INSERT INTO amazon_returns(tenant_id,marketplace_connection_id,return_key,amazon_order_id,
                    amazon_order_item_id,seller_sku,fnsku,asin,return_date,return_marketplace_date,quantity,
                    fulfillment_center,detailed_disposition,reason,status,license_plate_number,raw_payload,source_document_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                ON CONFLICT(tenant_id,marketplace_connection_id,return_key) DO UPDATE SET
                    status=EXCLUDED.status,quantity=EXCLUDED.quantity,raw_payload=EXCLUDED.raw_payload,
                    source_document_id=EXCLUDED.source_document_id,updated_at=now()
                """,job.tenantId(),job.connectionId(),key,value(report,row,"order-id","amazon-order-id"),
                value(report,row,"amazon-order-item-id","order-item-id"),value(report,row,"sku","seller-sku"),
                value(report,row,"fnsku"),value(report,row,"asin"),returned.instant(),returned.marketplaceDate(),
                zero(integer(value(report,row,"quantity"))),value(report,row,"fulfillment-center-id","fulfillment-center"),
                value(report,row,"detailed-disposition"),value(report,row,"reason"),value(report,row,"status"),
                value(report,row,"license-plate-number"),rowJson(report,row),source);
            count++;
        }return count;
    }

    private long reimbursements(AmazonSyncStore.Job job,String text,UUID source){
        Parsed report=parse(text);Map<String,Integer> occurrences=new HashMap<>();long count=0;
        for(int i=0;i<report.rows().size();i++){
            String[] row=report.rows().get(i);var approved=time(value(report,row,"approval-date"),job.marketplaceId());
            String fingerprint=hashFields(value(report,row,"reimbursement-id"),value(report,row,"case-id"),
                value(report,row,"amazon-order-id"),value(report,row,"sku","seller-sku"),value(report,row,"fnsku"),
                value(report,row,"reason"),value(report,row,"amount-total"),value(report,row,"quantity-reimbursed-cash"),
                value(report,row,"quantity-reimbursed-inventory"));
            int occurrence=occurrences.merge(fingerprint,1,Integer::sum);String key=occurrenceKey(fingerprint,occurrence);
            jdbc.update("""
                INSERT INTO amazon_reimbursements(tenant_id,marketplace_connection_id,reimbursement_key,
                    reimbursement_id,approval_date,approval_marketplace_date,case_id,amazon_order_id,reason,seller_sku,
                    fnsku,asin,condition_type,quantity_cash,quantity_inventory,amount_per_unit,amount_total,currency,
                    source_document_id,raw_payload)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(tenant_id,marketplace_connection_id,reimbursement_key) DO UPDATE SET
                    amount_total=EXCLUDED.amount_total,quantity_cash=EXCLUDED.quantity_cash,
                    quantity_inventory=EXCLUDED.quantity_inventory,source_document_id=EXCLUDED.source_document_id,
                    raw_payload=EXCLUDED.raw_payload,updated_at=now()
                """,job.tenantId(),job.connectionId(),key,value(report,row,"reimbursement-id"),approved.instant(),
                approved.marketplaceDate(),value(report,row,"case-id"),value(report,row,"amazon-order-id"),
                value(report,row,"reason"),value(report,row,"sku","seller-sku"),value(report,row,"fnsku"),
                value(report,row,"asin"),value(report,row,"condition"),
                zero(integer(value(report,row,"quantity-reimbursed-cash"))),
                zero(integer(value(report,row,"quantity-reimbursed-inventory"))),
                decimal(value(report,row,"amount-per-unit")),decimal(value(report,row,"amount-total")),
                currency(value(report,row,"currency-unit","currency")),source,rowJson(report,row));
            count++;
        }return count;
    }

    private long feeEstimates(AmazonSyncStore.Job job,String text,UUID source){
        Parsed report=parse(text);long count=0;Timestamp effective=Timestamp.from(Instant.now());
        for(int i=0;i<report.rows().size();i++){
            String[] row=report.rows().get(i);String sku=value(report,row,"sku","seller-sku");
            if(blank(sku)){reject(job,source,i+2,"Missing seller SKU",row);continue;}
            jdbc.update("""
                INSERT INTO amazon_fee_estimates(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,
                    fnsku,asin,currency,estimated_fee_total,referral_fee,fulfillment_fee,effective_at,
                    raw_payload,source_document_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?)
                ON CONFLICT(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,effective_at) DO UPDATE SET
                    estimated_fee_total=EXCLUDED.estimated_fee_total,referral_fee=EXCLUDED.referral_fee,
                    fulfillment_fee=EXCLUDED.fulfillment_fee,raw_payload=EXCLUDED.raw_payload,
                    source_document_id=EXCLUDED.source_document_id
                """,job.tenantId(),job.connectionId(),job.marketplaceId(),sku,value(report,row,"fnsku"),
                value(report,row,"asin"),currency(value(report,row,"currency")),
                decimal(value(report,row,"estimated-fee-total")),
                decimal(value(report,row,"estimated-referral-fee-per-unit")),
                decimal(value(report,row,"expected-fulfillment-fee-per-unit","estimated-fulfillment-fee-per-unit")),
                effective,rowJson(report,row),source);
            count++;
        }return count;
    }

    private long finances(AmazonSyncStore.Job job,JsonNode root,UUID source){
        JsonNode transactionsNode=root.path("payload").path("transactions");long count=0;
        if(!transactionsNode.isArray())return 0;
        for(JsonNode transaction:transactionsNode){
            String key=text(transaction,"transactionId","transactionIdentifier");
            if(blank(key))key=sha256(transaction.toString());
            String type=text(transaction,"transactionType","type");if(blank(type))type="UNKNOWN";
            var posted=time(text(transaction,"postedDate","postedDateTime"),job.marketplaceId());
            JsonNode total=transaction.path("totalAmount");
            BigDecimal amount=decimal(text(total,"currencyAmount","amount"));
            String currency=currency(text(total,"currencyCode","currency"));
            jdbc.update("""
                INSERT INTO amazon_financial_transactions(tenant_id,marketplace_connection_id,transaction_key,
                    transaction_type,posted_date,posted_marketplace_date,amazon_order_id,shipment_id,seller_sku,
                    description,quantity,amount,currency,marketplace_id,breakdown,raw_payload,source_document_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                ON CONFLICT(tenant_id,marketplace_connection_id,transaction_key) DO UPDATE SET
                    transaction_type=EXCLUDED.transaction_type,posted_date=EXCLUDED.posted_date,
                    posted_marketplace_date=EXCLUDED.posted_marketplace_date,amount=EXCLUDED.amount,
                    currency=EXCLUDED.currency,breakdown=EXCLUDED.breakdown,raw_payload=EXCLUDED.raw_payload,
                    source_document_id=EXCLUDED.source_document_id,updated_at=now()
                """,job.tenantId(),job.connectionId(),key,type,posted.instant(),posted.marketplaceDate(),
                related(transaction,"ORDER_ID","orderId"),related(transaction,"SHIPMENT_ID","shipmentId"),
                text(transaction,"sellerSku","sku"),text(transaction,"description"),
                integer(text(transaction,"quantity")),amount,currency,job.marketplaceId(),transaction.toString(),
                transaction.toString(),source);
            count++;
        }return count;
    }

    private String rowJson(Parsed report,String[] row){
        var object=json.createObjectNode();
        report.indexes().forEach((name,index)->object.put(name,index<row.length?row[index]:""));
        return object.toString();
    }

    private void reject(AmazonSyncStore.Job job,UUID source,int rowNumber,String reason,String[] row){
        jdbc.update("""
            INSERT INTO amazon_import_rejections(tenant_id,marketplace_connection_id,source_document_id,
                dataset,row_number,reason,raw_row) VALUES(?,?,?,?,?,?,?)
            """,job.tenantId(),job.connectionId(),source,job.type(),rowNumber,reason,String.join("\t",row));
    }

    private static String text(JsonNode node,String...names){
        for(String name:names){JsonNode value=node.path(name);if(!value.isMissingNode()&&!value.isNull()&&!value.asText().isBlank())return value.asText();}
        return null;
    }

    private static String related(JsonNode transaction,String identifierName,String fallback){
        String direct=text(transaction,fallback);if(!blank(direct))return direct;
        JsonNode identifiers=transaction.path("relatedIdentifiers");
        if(identifiers.isArray())for(JsonNode identifier:identifiers){
            String name=text(identifier,"relatedIdentifierName","name");
            if(identifierName.equalsIgnoreCase(name))return text(identifier,"relatedIdentifierValue","value");
        }
        return null;
    }

    private Parsed parse(String text){
        String[] lines=text.split("\\R");if(lines.length==0)return new Parsed(Map.of(),List.of());
        String[] headers=lines[0].replace("\uFEFF","").split("\\t",-1);Map<String,Integer> indexes=new HashMap<>();
        for(int i=0;i<headers.length;i++)indexes.put(normalize(unquote(headers[i])),i);
        List<String[]> rows=new ArrayList<>();for(int i=1;i<lines.length;i++)if(!lines[i].isBlank()){
            String[] values=lines[i].split("\\t",-1);for(int column=0;column<values.length;column++)values[column]=unquote(values[column]);
            rows.add(values);
        }
        return new Parsed(indexes,rows);
    }
    private static String value(Parsed report,String[] row,String...names){for(String name:names){Integer index=report.indexes().get(normalize(name));if(index!=null&&index<row.length&&!row[index].isBlank())return row[index].trim();}return null;}
    private static String normalize(String value){return value.trim().toLowerCase(Locale.ROOT).replace('_','-').replace(' ','-');}
    static String unquote(String value){
        String clean=value==null?"":value.trim();
        if(clean.length()>=2&&clean.startsWith("\"")&&clean.endsWith("\""))
            clean=clean.substring(1,clean.length()-1).replace("\"\"","\"");
        return clean;
    }
    private static BigDecimal decimal(String value){try{return blank(value)?null:new BigDecimal(value.replace(",",""));}catch(Exception ex){return null;}}
    private static Integer integer(String value){try{return blank(value)?null:Integer.valueOf(value.trim());}catch(Exception ex){return null;}}
    private static int zero(Integer value){return value==null?0:value;}
    private static AmazonMarketplaceTime.ParsedTime time(String value,String marketplaceId){return AmazonMarketplaceTime.parse(value,marketplaceId);}
    private static String currency(String value){return blank(value)?null:value.substring(0,Math.min(3,value.length())).toUpperCase(Locale.ROOT);}
    private static String nullIfBlank(String value){return blank(value)?null:value;}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static long countRows(String text){return text.isBlank()?0:text.lines().skip(1).filter(line->!line.isBlank()).count();}
    static String hashFields(String...values){return sha256(String.join("\u001f",java.util.Arrays.stream(values).map(value->value==null?"":value.trim()).toList()));}
    static String occurrenceKey(String fingerprint,int occurrence){return fingerprint+":"+occurrence;}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException(ex);}}
    private static String stableItemId(String order,String sku,String asin){try{return "derived-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((order+'|'+sku+'|'+asin).getBytes(StandardCharsets.UTF_8))).substring(0,40);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private void setTenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private record Parsed(Map<String,Integer> indexes,List<String[]> rows){}
}
