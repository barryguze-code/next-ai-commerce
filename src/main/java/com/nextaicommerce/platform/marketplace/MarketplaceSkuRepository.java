package com.nextaicommerce.platform.marketplace;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MarketplaceSkuRepository {
    private static final DateTimeFormatter SYNC_TIME=DateTimeFormatter.ofPattern("MM/dd/yy · h:mm a");
    /** Seller-fulfilled availability is derived locally: the least available mapped component limits a bundle. */
    private static final String LOCAL_MAPPING_AVAILABILITY = """
              LEFT JOIN LATERAL (
                SELECT floor(min(greatest(coalesce(sellable.quantity,0)-coalesce(reserved.quantity,0),0)
                    / nullif(component.quantity,0))) available
                FROM (
                  SELECT part.account_catalog_item_id,part.quantity
                  FROM marketplace_sku_mapping_components part
                  WHERE part.tenant_id=mapping.tenant_id AND part.marketplace_sku_mapping_id=mapping.id
                  UNION ALL
                  SELECT mapping.account_catalog_item_id,greatest(mapping.quantity_per_marketplace_unit,1)
                  WHERE mapping.account_catalog_item_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM marketplace_sku_mapping_components part
                    WHERE part.tenant_id=mapping.tenant_id AND part.marketplace_sku_mapping_id=mapping.id)
                ) component
                LEFT JOIN LATERAL (
                  SELECT sum(position.quantity) quantity
                  FROM (
                    SELECT ledger.location_id,ledger.expiration_date,sum(ledger.quantity) quantity
                    FROM inventory_ledger_entries ledger
                    WHERE ledger.tenant_id=mapping.tenant_id
                      AND ledger.account_catalog_item_id=component.account_catalog_item_id
                    GROUP BY ledger.location_id,ledger.expiration_date
                    HAVING sum(ledger.quantity)>0
                  ) position
                  LEFT JOIN inventory_shelf_life_policies policy
                    ON policy.tenant_id=mapping.tenant_id
                  WHERE (position.expiration_date IS NULL
                    OR NOT coalesce(policy.auto_zero_marketplace_sellable,true)
                    OR position.expiration_date>current_date+coalesce(policy.minimum_sellable_days,10))
                    AND NOT EXISTS (
                      SELECT 1 FROM inventory_expiration_actions action
                      WHERE action.tenant_id=mapping.tenant_id
                        AND action.account_catalog_item_id=component.account_catalog_item_id
                        AND action.expiration_date=position.expiration_date
                        AND action.status='PLANNED'
                        AND action.action_type IN ('HOLD','REMOVE','DONATE')
                    )
                ) sellable ON true
                LEFT JOIN LATERAL (
                  SELECT sum(reservation.quantity) quantity FROM order_inventory_reservations reservation
                  JOIN amazon_orders open_order ON open_order.tenant_id=reservation.tenant_id
                    AND open_order.marketplace_connection_id=reservation.marketplace_connection_id
                    AND open_order.amazon_order_id=reservation.amazon_order_id
                  WHERE reservation.tenant_id=mapping.tenant_id
                    AND reservation.account_catalog_item_id=component.account_catalog_item_id
                    AND reservation.status='ACTIVE'
                    AND upper(trim(coalesce(open_order.order_status,''))) IN ('PENDING','UNSHIPPED')
                ) reserved ON true
              ) local_inventory ON mapping.id IS NOT NULL
            """;
    private final JdbcTemplate jdbc;
    public MarketplaceSkuRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record SkuSummary(long total,long active,long outOfStock,long inactive,long amazonProblems,
            long deleted,long unmapped,long availableUnits){}
    public record MappingComponentView(UUID itemId,String productName,String vendorItemCode,
            String accountSku,BigDecimal quantity){
        public String selectionLabel(){
            String code=vendorItemCode==null||vendorItemCode.isBlank()?"No vendor code":vendorItemCode;
            String sku=accountSku==null||accountSku.isBlank()?"":" · "+accountSku;
            return code+" · "+productName+sku;
        }
    }
    public record SkuView(String sellerSku,String asin,String title,String imageUrl,String listingStatus,
            String conditionType,String fulfillmentChannel,BigDecimal price,String currency,Integer merchantQuantity,
            String fnsku,Integer fbaAvailable,Integer inboundQuantity,Integer reservedQuantity,Integer unfulfillableQuantity,
            long week4Sales,long week3Sales,long week2Sales,long currentWeekSales,
            long sales30Days,BigDecimal revenue30Days,BigDecimal estimatedFees,String feeCurrency,
            String mappedProduct,String mappingStatus,String mappedVendorItemCode,BigDecimal mappingQuantity,
            String marketplaceId,Instant lastSeenAt,String shippingTemplate,BigDecimal customerShipping30Days,
            BigDecimal calculatedAvailable,String localInventoryPlan,BigDecimal buyBoxPrice,
            String buyBoxCurrency,Instant buyBoxUpdatedAt){
        public SkuView(String sellerSku,String asin,String title,String imageUrl,String listingStatus,
                String conditionType,String fulfillmentChannel,BigDecimal price,String currency,Integer merchantQuantity,
                String fnsku,Integer fbaAvailable,Integer inboundQuantity,Integer reservedQuantity,Integer unfulfillableQuantity,
                long week4Sales,long week3Sales,long week2Sales,long currentWeekSales,
                long sales30Days,BigDecimal revenue30Days,BigDecimal estimatedFees,String feeCurrency,
                String mappedProduct,String mappingStatus,String mappedVendorItemCode,BigDecimal mappingQuantity,
                String marketplaceId,Instant lastSeenAt,String shippingTemplate,BigDecimal customerShipping30Days){
            this(sellerSku,asin,title,imageUrl,listingStatus,conditionType,fulfillmentChannel,price,currency,merchantQuantity,
                fnsku,fbaAvailable,inboundQuantity,reservedQuantity,unfulfillableQuantity,week4Sales,week3Sales,week2Sales,
                currentWeekSales,sales30Days,revenue30Days,estimatedFees,feeCurrency,mappedProduct,mappingStatus,
                mappedVendorItemCode,mappingQuantity,marketplaceId,lastSeenAt,shippingTemplate,customerShipping30Days,
                null,null,null,null,null);
        }
        public String operationalStatus(){
            String state=listingStatus==null?"":listingStatus.toUpperCase(java.util.Locale.ROOT);
            if(state.equals("REMOVED"))return "REMOVED";
            if(state.contains("DELETE"))return "DELETED";
            if(state.contains("SUSPEND")||state.contains("SUPPRESS")||state.contains("BLOCK")||state.contains("PROBLEM"))return "AMAZON_PROBLEM";
            if(availableQuantity()<=0)return "OOS";
            return "ACTIVE".equals(state)?"ACTIVE":"INACTIVE";
        }
        public String statusLabel(){return switch(operationalStatus()){
            case "ACTIVE" -> "Active";case "OOS" -> "Out of stock";
            case "AMAZON_PROBLEM" -> "Amazon suspended";case "REMOVED" -> "Removed by Amazon";case "DELETED" -> "Deleted";
            default -> "Inactive · stock";
        };}
        public String displayTitle(){return title==null||title.isBlank()?sellerSku:title;}
        public boolean fba(){return fulfillmentChannel!=null&&fulfillmentChannel.toUpperCase().contains("AMAZON");}
        public int availableQuantity(){return calculatedAvailable==null?(fba()?zero(fbaAvailable):zero(merchantQuantity)):calculatedAvailable.intValue();}
        public int safeInboundQuantity(){return zero(inboundQuantity);}
        public int safeReservedQuantity(){return zero(reservedQuantity);}
        public int safeUnfulfillableQuantity(){return zero(unfulfillableQuantity);}
        public String fulfillmentLabel(){return fba()?"FBA":"FBM";}
        public BigDecimal averageCustomerShipping(){
            if(fba()||customerShipping30Days==null||sales30Days<=0)return BigDecimal.ZERO;
            return customerShipping30Days.divide(BigDecimal.valueOf(sales30Days),2,RoundingMode.HALF_UP);
        }
        public String mappingLabel(){return mappedProduct==null?"Unmapped":"Mapped";}
        public String inventoryPlanLabel(){return localInventoryPlan==null?null:switch(localInventoryPlan){
            case "HOLD" -> "Held locally";
            case "DONATE" -> "Donation planned";
            case "REMOVE" -> "Physical removal planned";
            case "DISCOUNT" -> "Sale plan saved";
            default -> "Inventory plan saved";
        };}
        public String weeklySalesLabel(){return week4Sales+" | "+week3Sales+" | "+week2Sales+" | "+currentWeekSales;}
        public String mappingDetail(){
            if(mappedProduct==null)return null;
            String code=mappedVendorItemCode==null||mappedVendorItemCode.isBlank()?"":mappedVendorItemCode;
            String pack=mappingQuantity==null||mappingQuantity.compareTo(BigDecimal.ONE)==0?"":" · "+mappingQuantity.stripTrailingZeros().toPlainString()+" each";
            return code+pack;
        }
        public String lastSyncedLabel(){return lastSeenAt==null?"Not synced":SYNC_TIME.format(lastSeenAt.atZone(ZoneId.systemDefault()));}
        public String amazonUrl(){return asin==null||asin.isBlank()?null:"https://www."+amazonDomain(marketplaceId)+"/dp/"+encode(asin);}
        public String sellerCentralUrl(){return "https://"+sellerCentralDomain(marketplaceId)+"/myinventory/inventory?searchTerm="+encode(sellerSku);}
        private static int zero(Integer value){return value==null?0:value;}
    }
    public record SkuPage(List<SkuView> rows,long total,int page,int pageSize){
        public int totalPages(){return Math.max(1,(int)Math.ceil((double)total/pageSize));}
        public boolean hasPrevious(){return page>0;}
        public boolean hasNext(){return page+1<totalPages();}
        public int displayPage(){return page+1;}
        public long firstItem(){return total==0?0:(long)page*pageSize+1;}
        public long lastItem(){return Math.min(total,(long)(page+1)*pageSize);}
    }

    @Transactional(readOnly=true)
    public Map<String,List<MappingComponentView>> mappingComponents(UUID tenantId,UUID connectionId){
        return mappingComponents(tenantId,connectionId,null);
    }

    @Transactional(readOnly=true)
    public Map<String,List<MappingComponentView>> mappingComponents(UUID tenantId,UUID connectionId,List<String> sellerSkus){
        setTenant(tenantId);
        if(sellerSkus!=null&&sellerSkus.isEmpty())return Map.of();
        String skuFilter=sellerSkus==null?"": " AND mapping.marketplace_sku IN ("+
            String.join(",",java.util.Collections.nCopies(sellerSkus.size(),"?"))+")";
        List<Object> parameters=new ArrayList<>();parameters.add(tenantId);parameters.add(connectionId);
        if(sellerSkus!=null)parameters.addAll(sellerSkus);
        List<Object[]> rows=jdbc.query("""
            SELECT mapping.marketplace_sku,component.account_catalog_item_id,
                   coalesce(item.display_name,product.canonical_name) product_name,
                   offer.vendor_item_code,item.account_sku,component.quantity
            FROM marketplace_sku_mappings mapping
            JOIN marketplace_sku_mapping_components component
              ON component.tenant_id=mapping.tenant_id
             AND component.marketplace_sku_mapping_id=mapping.id
            JOIN account_catalog_items item ON item.tenant_id=component.tenant_id
             AND item.id=component.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (SELECT vendor_item_code FROM vendor_catalog_offers offer
                WHERE offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id
                  AND offer.effective_to IS NULL
                ORDER BY offer.is_default DESC,offer.updated_at DESC LIMIT 1) offer ON true
            WHERE mapping.tenant_id=? AND mapping.marketplace_connection_id=? AND mapping.status='ACTIVE'
            """+skuFilter+"""
            ORDER BY mapping.marketplace_sku,component.sort_order,component.created_at
            """,(rs,row)->new Object[]{rs.getString(1),new MappingComponentView(rs.getObject(2,UUID.class),
                rs.getString(3),rs.getString(4),rs.getString(5),rs.getBigDecimal(6))},parameters.toArray());
        Map<String,List<MappingComponentView>> result=new LinkedHashMap<>();
        for(Object[] row:rows)result.computeIfAbsent((String)row[0],ignored->new ArrayList<>())
            .add((MappingComponentView)row[1]);
        return result;
    }

    @Transactional
    public void saveMapping(UUID tenantId,UUID connectionId,String sellerSku,
            List<String> productIds,List<String> quantities){
        setTenant(tenantId);
        if(sellerSku==null||sellerSku.isBlank())throw new IllegalArgumentException("Marketplace SKU is required.");
        String asin=jdbc.query("""
            SELECT asin FROM amazon_listings
            WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=?
            """,rs->rs.next()?rs.getString(1):null,tenantId,connectionId,sellerSku);
        if(asin==null){
            Boolean listingExists=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM amazon_listings
                  WHERE tenant_id=? AND marketplace_connection_id=? AND seller_sku=?)
                """,Boolean.class,tenantId,connectionId,sellerSku);
            if(!Boolean.TRUE.equals(listingExists))throw new IllegalArgumentException("This marketplace SKU is no longer available in the selected store.");
        }
        if(productIds==null||quantities==null||productIds.size()!=quantities.size()||productIds.isEmpty())
            throw new IllegalArgumentException("Choose at least one catalogue product and quantity.");
        if(productIds.size()>20)throw new IllegalArgumentException("A bundle can contain up to 20 catalogue products.");
        Map<UUID,BigDecimal> components=new LinkedHashMap<>();
        for(int index=0;index<productIds.size();index++){
            UUID itemId;
            try{itemId=UUID.fromString(productIds.get(index));}
            catch(Exception ex){throw new IllegalArgumentException("Choose a product from the account catalogue.");}
            BigDecimal quantity;
            try{quantity=new BigDecimal(quantities.get(index));}
            catch(Exception ex){throw new IllegalArgumentException("Enter a whole quantity for every product.");}
            if(quantity.signum()<=0||quantity.stripTrailingZeros().scale()>0||quantity.compareTo(BigDecimal.valueOf(10000))>0)
                throw new IllegalArgumentException("Product quantities must be whole numbers between 1 and 10,000.");
            components.merge(itemId,quantity,BigDecimal::add);
        }
        String itemPlaceholders=String.join(",",java.util.Collections.nCopies(components.size(),"?"));
        List<Object> itemParameters=new ArrayList<>();itemParameters.add(tenantId);itemParameters.addAll(components.keySet());
        Integer activeItems=jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=? AND status='ACTIVE' AND id IN ("+
            itemPlaceholders+")",Integer.class,itemParameters.toArray());
        if(activeItems==null||activeItems!=components.size())throw new IllegalArgumentException("A selected catalogue product is no longer active.");
        var first=components.entrySet().iterator().next();
        UUID mappingId=jdbc.queryForObject("""
            INSERT INTO marketplace_sku_mappings
                (tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,asin,
                 quantity_per_marketplace_unit,status,mapping_source,auto_map_blocked)
            VALUES (?,?,?,?,?,?,'ACTIVE','MANUAL',false)
            ON CONFLICT (tenant_id,marketplace_connection_id,marketplace_sku) DO UPDATE SET
                account_catalog_item_id=EXCLUDED.account_catalog_item_id,asin=EXCLUDED.asin,
                quantity_per_marketplace_unit=EXCLUDED.quantity_per_marketplace_unit,status='ACTIVE',
                mapping_source='MANUAL',auto_map_blocked=false,updated_at=now()
            RETURNING id
            """,UUID.class,tenantId,connectionId,first.getKey(),sellerSku,asin,first.getValue());
        jdbc.update("DELETE FROM marketplace_sku_mapping_components WHERE tenant_id=? AND marketplace_sku_mapping_id=?",
            tenantId,mappingId);
        int order=0;List<Object[]> componentWrites=new ArrayList<>();
        for(var component:components.entrySet())componentWrites.add(new Object[]{tenantId,mappingId,component.getKey(),component.getValue(),order++});
        jdbc.batchUpdate("""
            INSERT INTO marketplace_sku_mapping_components
                (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order)
            VALUES (?,?,?,?,?)
            """,componentWrites);
    }

    @Transactional
    public void clearMapping(UUID tenantId,UUID connectionId,String sellerSku){
        setTenant(tenantId);
        int updated=jdbc.update("""
            UPDATE marketplace_sku_mappings SET status='INACTIVE',mapping_source='MANUAL',
                auto_map_blocked=true,updated_at=now()
            WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_sku=?
            """,tenantId,connectionId,sellerSku);
        if(updated==0)throw new IllegalArgumentException("This marketplace SKU does not have a saved mapping.");
    }

    @Transactional(readOnly=true)
    public SkuSummary summary(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        return jdbc.query("""
            WITH source_rows AS (
              SELECT listing.listing_status,listing.platform_status,
                     CASE WHEN upper(coalesce(listing.fulfillment_channel,'')) LIKE '%AMAZON%'
                          THEN coalesce(inventory.fulfillable_quantity,0) ELSE coalesce(local_inventory.available,listing.quantity,0) END available,
                     mapping.id mapping_id
              FROM amazon_listings listing
              LEFT JOIN LATERAL (SELECT fulfillable_quantity FROM amazon_inventory_snapshots snapshot
                  WHERE snapshot.tenant_id=listing.tenant_id AND snapshot.marketplace_connection_id=listing.marketplace_connection_id
                    AND snapshot.marketplace_id=listing.marketplace_id AND snapshot.seller_sku=listing.seller_sku
                  ORDER BY snapshot.snapshot_at DESC LIMIT 1) inventory ON true
              LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=listing.tenant_id
                  AND mapping.marketplace_connection_id=listing.marketplace_connection_id
                  AND mapping.marketplace_sku=listing.seller_sku AND mapping.status='ACTIVE'
            """+LOCAL_MAPPING_AVAILABILITY+"""
              WHERE listing.tenant_id=? AND listing.marketplace_connection_id=?
            ), rows AS (
              SELECT *,CASE
                WHEN platform_status='REMOVED' THEN 'REMOVED'
                WHEN platform_status='DELETED' OR upper(coalesce(listing_status,'')) LIKE '%DELET%' THEN 'DELETED'
                WHEN upper(coalesce(listing_status,'')) ~ '(SUSPEND|SUPPRESS|BLOCK|PROBLEM)' THEN 'AMAZON_PROBLEM'
                WHEN available<=0 THEN 'OOS'
                WHEN upper(coalesce(listing_status,''))='ACTIVE' THEN 'ACTIVE'
                ELSE 'INACTIVE' END operational_status
              FROM source_rows
            )
            SELECT count(*),count(*) FILTER (WHERE operational_status='ACTIVE'),
                   count(*) FILTER (WHERE operational_status='OOS'),
                   count(*) FILTER (WHERE operational_status='INACTIVE'),
                   count(*) FILTER (WHERE operational_status='AMAZON_PROBLEM'),
                   count(*) FILTER (WHERE operational_status IN ('DELETED','REMOVED')),
                   count(*) FILTER (WHERE mapping_id IS NULL),coalesce(sum(available),0)
            FROM rows
            """,rs->rs.next()?new SkuSummary(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),
                rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getLong(8))
                :new SkuSummary(0,0,0,0,0,0,0,0),tenantId,connectionId);
    }

    @Transactional(readOnly=true)
    public SkuPage list(UUID tenantId,UUID connectionId,String search,String status,String sort,String direction,
            int requestedPage,int pageSize){
        setTenant(tenantId);String query=search==null?"":search.trim();String filter=normalizeStatus(status);
        int size=Math.max(25,Math.min(pageSize,200));int page=Math.max(requestedPage,0);int offset=page*size;
        String sql="""
            WITH sales_by_sku AS (
              SELECT item.tenant_id,item.marketplace_connection_id,item.seller_sku,
                     coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '28 days' AND orders.purchase_date<now()-interval '21 days'),0) week_4,
                     coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '21 days' AND orders.purchase_date<now()-interval '14 days'),0) week_3,
                     coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '14 days' AND orders.purchase_date<now()-interval '7 days'),0) week_2,
                     coalesce(sum(item.quantity_ordered) FILTER (WHERE orders.purchase_date>=now()-interval '7 days'),0) current_week,
                     coalesce(sum(item.quantity_ordered),0) units,coalesce(sum(item.item_price),0) revenue,
                     coalesce(sum(item.shipping_price),0) customer_shipping
              FROM amazon_order_items item JOIN amazon_orders orders
                ON orders.tenant_id=item.tenant_id AND orders.marketplace_connection_id=item.marketplace_connection_id
               AND orders.amazon_order_id=item.amazon_order_id
              WHERE item.tenant_id=? AND item.marketplace_connection_id=?
                AND orders.purchase_date>=now()-interval '30 days'
                AND upper(coalesce(orders.order_status,'')) NOT IN ('CANCELLED','CANCELED')
              GROUP BY item.tenant_id,item.marketplace_connection_id,item.seller_sku
            ), sku_rows AS (
              SELECT listing.seller_sku,listing.asin,listing.item_name,listing.image_url,listing.listing_status,
                     listing.condition_type,listing.fulfillment_channel,listing.price,listing.currency,listing.quantity,
                     inventory.fnsku,inventory.fulfillable_quantity,
                     coalesce(inventory.inbound_working_quantity,0)+coalesce(inventory.inbound_shipped_quantity,0)+coalesce(inventory.inbound_receiving_quantity,0) inbound_quantity,
                     inventory.reserved_quantity,inventory.unfulfillable_quantity,
                     coalesce(sales.week_4,0) week_4,coalesce(sales.week_3,0) week_3,
                     coalesce(sales.week_2,0) week_2,coalesce(sales.current_week,0) current_week,
                     coalesce(sales.units,0) sales_30_days,coalesce(sales.revenue,0) revenue_30_days,
                     fees.estimated_fee_total,fees.currency fee_currency,
                     coalesce(account_item.display_name,product.canonical_name) mapped_product,mapping.status mapping_status,
                     mapped_offer.vendor_item_code,mapping.quantity_per_marketplace_unit,
                     listing.marketplace_id,listing.last_seen_at,listing.merchant_shipping_group,
                     coalesce(sales.customer_shipping,0) customer_shipping,
                     CASE WHEN upper(coalesce(listing.fulfillment_channel,'')) LIKE '%AMAZON%'
                          THEN coalesce(inventory.fulfillable_quantity,0) ELSE coalesce(local_inventory.available,listing.quantity,0) END available,
                     CASE WHEN listing.platform_status='REMOVED' THEN 'REMOVED'
                          WHEN listing.platform_status='DELETED' OR upper(coalesce(listing.listing_status,'')) LIKE '%DELET%' THEN 'DELETED'
                          WHEN upper(coalesce(listing.listing_status,'')) ~ '(SUSPEND|SUPPRESS|BLOCK|PROBLEM)' THEN 'AMAZON_PROBLEM'
                          WHEN (CASE WHEN upper(coalesce(listing.fulfillment_channel,'')) LIKE '%AMAZON%'
                                THEN coalesce(inventory.fulfillable_quantity,0) ELSE coalesce(local_inventory.available,listing.quantity,0) END)<=0 THEN 'OOS'
                          WHEN upper(coalesce(listing.listing_status,''))='ACTIVE' THEN 'ACTIVE'
                          ELSE 'INACTIVE' END operational_status,
                     inventory_plan.action_type local_inventory_plan,listing.buy_box_price,
                     listing.buy_box_currency,listing.buy_box_updated_at
              FROM amazon_listings listing
              LEFT JOIN LATERAL (SELECT fnsku,fulfillable_quantity,inbound_working_quantity,inbound_shipped_quantity,
                      inbound_receiving_quantity,reserved_quantity,unfulfillable_quantity
                  FROM amazon_inventory_snapshots snapshot
                  WHERE snapshot.tenant_id=listing.tenant_id AND snapshot.marketplace_connection_id=listing.marketplace_connection_id
                    AND snapshot.marketplace_id=listing.marketplace_id AND snapshot.seller_sku=listing.seller_sku
                  ORDER BY snapshot.snapshot_at DESC LIMIT 1) inventory ON true
              LEFT JOIN sales_by_sku sales ON sales.tenant_id=listing.tenant_id
                  AND sales.marketplace_connection_id=listing.marketplace_connection_id
                  AND sales.seller_sku=listing.seller_sku
              LEFT JOIN LATERAL (SELECT estimated_fee_total,currency FROM amazon_fee_estimates fee
                  WHERE fee.tenant_id=listing.tenant_id AND fee.marketplace_connection_id=listing.marketplace_connection_id
                    AND fee.marketplace_id=listing.marketplace_id AND fee.seller_sku=listing.seller_sku
                  ORDER BY fee.effective_at DESC LIMIT 1) fees ON true
              LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=listing.tenant_id
                  AND mapping.marketplace_connection_id=listing.marketplace_connection_id
                  AND mapping.marketplace_sku=listing.seller_sku AND mapping.status='ACTIVE'
            """+LOCAL_MAPPING_AVAILABILITY+"""
              LEFT JOIN account_catalog_items account_item ON account_item.tenant_id=mapping.tenant_id
                  AND account_item.id=mapping.account_catalog_item_id
              LEFT JOIN global_catalog_products product ON product.id=account_item.global_product_id
              LEFT JOIN LATERAL (SELECT offer.vendor_item_code FROM vendor_catalog_offers offer
                  WHERE offer.tenant_id=account_item.tenant_id AND offer.account_catalog_item_id=account_item.id
                    AND offer.effective_to IS NULL
                  ORDER BY offer.is_default DESC,offer.updated_at DESC LIMIT 1) mapped_offer ON true
              LEFT JOIN LATERAL (
                  SELECT action.action_type
                  FROM (
                    SELECT component.account_catalog_item_id
                    FROM marketplace_sku_mapping_components component
                    WHERE component.tenant_id=mapping.tenant_id
                      AND component.marketplace_sku_mapping_id=mapping.id
                    UNION
                    SELECT mapping.account_catalog_item_id
                    WHERE mapping.account_catalog_item_id IS NOT NULL
                  ) mapped_item
                  JOIN inventory_expiration_actions action
                    ON action.tenant_id=listing.tenant_id
                   AND action.account_catalog_item_id=mapped_item.account_catalog_item_id
                   AND action.status='PLANNED'
                  ORDER BY CASE action.action_type WHEN 'HOLD' THEN 1 WHEN 'REMOVE' THEN 2
                           WHEN 'DONATE' THEN 3 WHEN 'DISCOUNT' THEN 4 ELSE 5 END,
                           action.updated_at DESC
                  LIMIT 1
              ) inventory_plan ON true
              WHERE listing.tenant_id=? AND listing.marketplace_connection_id=?
            )
            SELECT *,count(*) over() filtered_total FROM sku_rows
            WHERE (?='' OR seller_sku ILIKE '%'||?||'%' OR coalesce(asin,'') ILIKE '%'||?||'%'
                      OR coalesce(item_name,'') ILIKE '%'||?||'%')
              AND (?='ALL' OR operational_status=? OR (?='DELETED' AND operational_status='REMOVED') OR (?='UNMAPPED' AND mapped_product IS NULL))
            """+" ORDER BY CASE WHEN operational_status='DELETED' THEN 1 ELSE 0 END, "+orderClause(sort,direction)+" LIMIT ? OFFSET ?";
        List<Object[]> raw=jdbc.query(sql,(rs,row)->new Object[]{new SkuView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(33),rs.getString(6),rs.getString(7),rs.getBigDecimal(8),rs.getString(9),
                (Integer)rs.getObject(10),rs.getString(11),(Integer)rs.getObject(12),(Integer)rs.getObject(13),
                (Integer)rs.getObject(14),(Integer)rs.getObject(15),rs.getLong(16),rs.getLong(17),rs.getLong(18),rs.getLong(19),
                rs.getLong(20),rs.getBigDecimal(21),rs.getBigDecimal(22),rs.getString(23),rs.getString(24),
                rs.getString(25),rs.getString(26),rs.getBigDecimal(27),rs.getString(28),
                rs.getTimestamp(29)==null?null:rs.getTimestamp(29).toInstant(),rs.getString(30),rs.getBigDecimal(31),rs.getBigDecimal(32),
                rs.getString(34),rs.getBigDecimal(35),rs.getString(36),
                rs.getTimestamp(37)==null?null:rs.getTimestamp(37).toInstant()),rs.getLong(38)},
                tenantId,connectionId,tenantId,connectionId,query,query,query,query,filter,filter,filter,filter,size,offset);
        long total=raw.isEmpty()?0:(long)raw.getFirst()[1];
        List<SkuView> rows=raw.stream().map(row->(SkuView)row[0]).toList();
        if(total>0&&offset>=total){page=Math.max(0,(int)((total-1)/size));return list(tenantId,connectionId,query,filter,sort,direction,page,size);}
        return new SkuPage(rows,total,page,size);
    }

    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private static String normalizeStatus(String value){return List.of("ALL","ACTIVE","OOS","INACTIVE","AMAZON_PROBLEM","DELETED","UNMAPPED").contains(value)?value:"ALL";}
    private static String orderClause(String sort,String direction){
        String dir="DESC".equalsIgnoreCase(direction)?"DESC":"ASC";
        String column=switch(sort==null?"":sort){
            case "listing" -> "lower(coalesce(item_name,seller_sku))";
            case "sku" -> "lower(seller_sku)";
            case "status" -> "operational_status";
            case "price" -> "price";
            case "buyBox" -> "buy_box_price";
            case "fulfillment" -> "fulfillment_channel";
            case "available" -> "available";
            case "fourWeek" -> "(week_4+week_3+week_2+current_week)";
            case "sales30" -> "sales_30_days";
            case "fees" -> "estimated_fee_total";
            case "profit" -> "lower(seller_sku)";
            case "catalog" -> "mapped_product";
            default -> "CASE operational_status WHEN 'AMAZON_PROBLEM' THEN 1 WHEN 'OOS' THEN 2 WHEN 'INACTIVE' THEN 3 WHEN 'ACTIVE' THEN 4 ELSE 5 END";
        };
        return column+" "+dir+" NULLS LAST, lower(coalesce(item_name,seller_sku)),seller_sku";
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String amazonDomain(String marketplace){return MarketplaceLinks.amazonDomain(marketplace);}
    private static String sellerCentralDomain(String marketplace){return "sellercentral."+amazonDomain(marketplace);}
}
