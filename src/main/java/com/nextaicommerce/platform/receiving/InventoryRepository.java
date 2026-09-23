package com.nextaicommerce.platform.receiving;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InventoryRepository {
    private static final DateTimeFormatter LEDGER_TIME = DateTimeFormatter.ofPattern("MM/dd/yy · h:mm a");
    static final String RESERVABLE_ORDER_STATUS_SQL =
        "upper(trim(coalesce(orders.order_status,''))) IN ('PENDING','UNSHIPPED')";
    private final JdbcTemplate jdbc;
    public InventoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record InventoryView(UUID itemId,String productName,String accountSku,String identifier,
            LocalDate expirationDate,BigDecimal quantity,BigDecimal reservedQuantity,BigDecimal unitCost,String currency,String flowRule,
            String plannedAction,String actionNotes,String costStatus,BigDecimal initiallyReceived,Instant firstReceivedAt,Instant lastMovementAt,
            UUID locationId,String locationCode,String locationName,String imageUrl,String vendorItemCode,
            BigDecimal totalQuantity,BigDecimal totalReservedQuantity,int positionCount,String brand,String asin){
        public InventoryView(UUID itemId,String productName,String accountSku,String identifier,
                LocalDate expirationDate,BigDecimal quantity,BigDecimal reservedQuantity,BigDecimal unitCost,String currency,String flowRule,
                String plannedAction,String actionNotes,String costStatus,BigDecimal initiallyReceived,Instant firstReceivedAt,Instant lastMovementAt){
            this(itemId,productName,accountSku,identifier,expirationDate,quantity,reservedQuantity,unitCost,currency,flowRule,
                plannedAction,actionNotes,costStatus,initiallyReceived,firstReceivedAt,lastMovementAt,null,"MAIN","Main storage",null,null,
                quantity,reservedQuantity,1,null,null);
        }
        public String quantityUnits(){return quantity.setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String reservedUnits(){return reservedQuantity.setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String availableUnits(){return quantity.subtract(reservedQuantity).max(BigDecimal.ZERO).setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String initiallyReceivedUnits(){return initiallyReceived.setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String totalQuantityUnits(){return totalQuantity.setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String totalAvailableUnits(){return totalQuantity.subtract(totalReservedQuantity).max(BigDecimal.ZERO).setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String flowLabel(){return "FEFO".equalsIgnoreCase(flowRule)?"Use earliest expiry first":"Use oldest stock first";}
        public String unitCostDisplay(){return unitCost==null?null:unitCost.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String firstReceivedDisplay(){return firstReceivedAt==null?"Not recorded":LEDGER_TIME.format(firstReceivedAt.atZone(ZoneId.systemDefault()));}
        public String lastMovementDisplay(){return lastMovementAt==null?"Not recorded":LEDGER_TIME.format(lastMovementAt.atZone(ZoneId.systemDefault()));}
        public long daysRemaining(LocalDate today){return expirationDate==null?Long.MAX_VALUE:ChronoUnit.DAYS.between(today,expirationDate);}
        public boolean marketplaceStoppedByPlan(){return "HOLD".equals(plannedAction)||"REMOVE".equals(plannedAction)||"DONATE".equals(plannedAction);}
        public String shelfStatus(LocalDate today,int minimumSellableDays,int warningDays){
            if(expirationDate==null)return "UNDATED";
            long days=daysRemaining(today);
            if(days<0)return "EXPIRED";
            if(days<=minimumSellableDays)return "CANNOT_SELL";
            if(days<=warningDays)return "ACT_SOON";
            return "HEALTHY";
        }
        public String shelfLabel(LocalDate today,int minimumSellableDays,int warningDays){return switch(shelfStatus(today,minimumSellableDays,warningDays)){
            case "EXPIRED" -> "Expired";
            case "CANNOT_SELL" -> "Cannot sell";
            case "ACT_SOON" -> "Act soon";
            case "HEALTHY" -> "Healthy";
            default -> "No expiration";
        };}
        public String expirationMessage(LocalDate today){
            if(expirationDate==null)return "FIFO inventory";
            long days=daysRemaining(today);
            if(days<0)return Math.abs(days)+" day"+(Math.abs(days)==1?"":"s")+" expired";
            if(days==0)return "Expires today";
            return days+" day"+(days==1?"":"s")+" left";
        }
        public String actionLabel(){return plannedAction==null?null:switch(plannedAction){
            case "DISCOUNT" -> "Sale plan saved";
            case "DONATE" -> "Donation planned";
            case "HOLD" -> "Held from sale";
            case "REMOVE" -> "Physical removal planned";
            default -> "Team review requested";
        };}
    }
    public record ShelfLifePolicy(int minimumSellableDays,int warningDays,boolean autoZeroMarketplaceSellable,
            boolean autoSaleEnabled,BigDecimal defaultSaleDiscountPercent,int saleStartDaysBeforeExpiration,
            int saleDurationDays){
        public ShelfLifePolicy(int minimumSellableDays,int warningDays){
            this(minimumSellableDays,warningDays,true,true,new BigDecimal("10.00"),warningDays,7);
        }
    }
    public record SalePlanDefaults(BigDecimal discountPercent,int startDays,int durationDays,
            boolean productOverride){}
    /** A catalogue match with no positive position is deliberately separate from an unknown search. */
    public boolean hasPositiveInventory(UUID tenantId,UUID itemId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        Boolean found=jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM inventory_ledger_entries
                WHERE tenant_id=? AND account_catalog_item_id=?
                GROUP BY location_id,expiration_date
                HAVING sum(quantity)>0
            )
            """,Boolean.class,tenantId,itemId);
        return Boolean.TRUE.equals(found);
    }
    public record MarketplaceActionResult(int queued,int skippedFba,int missingSku){}
    public record PhysicalCountRow(int rowNumber,String code,BigDecimal quantity,LocalDate expiration,String locationCode){
        public PhysicalCountRow(int rowNumber,String code,BigDecimal quantity,LocalDate expiration){
            this(rowNumber,code,quantity,expiration,"");
        }
    }
    public record LedgerView(UUID id,UUID itemId,Instant occurredAt,String productName,String accountSku,String identifier,
            String entryType,BigDecimal quantity,LocalDate expirationDate,BigDecimal unitCost,String currency,
            String sourceType,String sourceReference,String notes,String createdBy,String costStatus){
        public String occurredDisplay(){return LEDGER_TIME.format(occurredAt.atZone(ZoneId.systemDefault()));}
        public String quantityUnits(){
            String units=quantity.abs().setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();
            return quantity.signum()==0?"0":quantity.signum()>0?"+"+units:"-"+units;
        }
        public String movementLabel(){
            if("PHYSICAL_COUNT".equals(sourceType)||isPhysicalCountNote())return "Physical count";
            return switch(entryType){
            case "RECEIPT" -> "Received";
            case "SALE","SHIPMENT" -> "Order shipped";
            case "SHIPMENT_UNRECORDED" -> "Shipped";
            case "SHARED_STOCK_SALES" -> "Shared-stock sales";
            case "RESERVATION_RELEASED" -> "Reservation released";
            case "ORDER_STATUS_REVIEW" -> "Order status review";
            case "RETURN" -> "Customer return";
            case "ADJUSTMENT" -> "Adjustment";
            case "TRANSFER" -> "Transfer";
            case "REMOVAL" -> "Removed";
            default -> title(entryType);
        };}
        public String sourceLabel(){
            if("PHYSICAL_COUNT".equals(sourceType)||isPhysicalCountNote())return "Physical count";
            return switch(sourceType){
            case "RECEIVING" -> "Invoice receiving";
            case "AMAZON_ORDER" -> "Amazon order";
            case "WALMART_ORDER" -> "Walmart order";
            case "MANUAL" -> "Manual update";
            case "ADJUSTMENT" -> "Inventory adjustment";
            case "PHYSICAL_COUNT" -> "Physical count";
            case "LOCATION_TRANSFER" -> "Location transfer";
            case "RECEIVING_CORRECTION" -> "Receiving correction";
            default -> title(sourceType);
        };}
        public String description(){
            if(java.util.Set.of("SHARED_STOCK_SALES","RESERVATION_RELEASED","ORDER_STATUS_REVIEW").contains(entryType))return notes;
            if("SHIPMENT_UNRECORDED".equals(entryType))return "No stock deducted.";
            if("RETURN".equals(entryType)&&notes!=null&&notes.startsWith("Packing mark undone"))return notes;
            if("PHYSICAL_COUNT".equals(sourceType)||isPhysicalCountNote())
                return quantity.signum()<0?"Physical count reduced the recorded stock.":"Physical count confirmed stock on hand.";
            if("AMAZON_ORDER".equals(sourceType))return "Stock left the warehouse for this Amazon order.";
            if("WALMART_ORDER".equals(sourceType))return "Stock left the warehouse for this Walmart order.";
            if("LOCATION_TRANSFER".equals(sourceType))
                return quantity.signum()<0?"Moved out to another storage location.":"Moved in from another storage location.";
            if("RECEIVING".equals(sourceType)){
                if(notes!=null&&notes.startsWith("Over-shipped"))return "Extra vendor units were received into inventory at zero cost.";
                if(notes!=null&&notes.startsWith("Soon-to-expire"))return "Vendor stock was received into this dated inventory batch.";
                if(notes!=null&&notes.startsWith("Expired"))return "Expired vendor stock was recorded in this batch and marked cannot sell.";
                return "Vendor invoice receipt added stock to this location.";
            }
            if("RETURN".equals(entryType))return "Customer return added back to stock.";
            return notes==null||notes.isBlank()?null:notes;
        }
        private boolean isPhysicalCountNote(){return notes!=null&&(notes.startsWith("Physical count snapshot")||notes.startsWith("Physical count upload"));}
        public String sourceUrl(){
            if(sourceReference==null||sourceReference.isBlank())return null;
            if("AMAZON_ORDER".equals(sourceType))return "/app/orders?q="+sourceReference;
            if("PHYSICAL_COUNT".equals(sourceType))return "/app/inventory/physical-counts/history?importId="+sourceReference;
            return null;
        }
        public String unitCostDisplay(){return unitCost==null?null:unitCost.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
        private static String title(String value){
            if(value==null||value.isBlank())return "System";
            String clean=value.toLowerCase().replace('_',' ');
            return Character.toUpperCase(clean.charAt(0))+clean.substring(1);
        }
    }
    public record LedgerSummary(long movements,long receipts,long adjustments){}
    public record ReservationView(String orderId,String orderStatus,String sellerSku,int orderedSkus,
            BigDecimal eachesPerSku,BigDecimal reservedEaches,BigDecimal requiredEaches,BigDecimal shortageEaches,
            LocalDate expirationDate,String locationCode,String locationName,Instant reservedAt){}
    public record LedgerPage(List<LedgerView> rows,long total,int page,int pageSize){
        public int totalPages(){return Math.max(1,(int)Math.ceil((double)total/pageSize));}
        public boolean hasPrevious(){return page>0;}
        public boolean hasNext(){return page+1<totalPages();}
        public int displayPage(){return page+1;}
        public long firstItem(){return total==0?0:(long)page*pageSize+1;}
        public long lastItem(){return Math.min(total,(long)(page+1)*pageSize);}
    }
    public record AdjustmentItem(UUID id,String name,String itemCode,boolean expirationRequired,UUID defaultLocationId,String imageUrl){}
    @Transactional(readOnly=true)
    public List<AdjustmentItem> adjustmentItems(UUID tenantId,List<UUID> ids){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        if(ids.isEmpty()||ids.size()>20)throw new IllegalArgumentException("Choose between one and twenty catalogue items.");
        var args=new java.util.ArrayList<Object>();args.add(tenantId);args.addAll(ids);
        return jdbc.query("""
            SELECT item.id,coalesce(item.display_name,product.canonical_name) name,item.account_sku,
                   product.requires_expiration_date,assignment.location_id,
                   CASE WHEN EXISTS(SELECT 1 FROM account_catalog_product_images image
                       WHERE image.tenant_id=item.tenant_id AND image.account_catalog_item_id=item.id)
                     THEN '/app/catalog/products/'||item.id||'/image' END image_url
            FROM account_catalog_items item JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=item.tenant_id
                AND assignment.account_catalog_item_id=item.id AND assignment.is_default
            WHERE item.tenant_id=? AND item.status='ACTIVE' AND item.id IN (%s)
            ORDER BY name,item.id
            """.formatted(String.join(",",java.util.Collections.nCopies(ids.size(),"?"))),
            (rs,n)->new AdjustmentItem(rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("account_sku"),
                rs.getBoolean("requires_expiration_date"),rs.getObject("location_id",UUID.class),rs.getString("image_url")),args.toArray());
    }

    @Transactional(readOnly=true)
    public List<InventoryView> inventory(UUID tenantId){return inventory(tenantId,null);}

    @Transactional(readOnly=true)
    public List<InventoryView> inventory(UUID tenantId,List<UUID> itemIds){
        String filter=itemIds==null?null:"{"+String.join(",",itemIds.stream().map(UUID::toString).toList())+"}";
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        return jdbc.query("""
            WITH positions AS MATERIALIZED (
                SELECT ledger.tenant_id,ledger.account_catalog_item_id,ledger.location_id,ledger.expiration_date,
                       sum(ledger.quantity) quantity,max(ledger.unit_cost) unit_cost,max(ledger.currency) currency,
                       CASE WHEN bool_or(ledger.cost_status='PROVISIONAL') THEN 'PROVISIONAL' ELSE 'FINAL' END cost_status,
                       coalesce(sum(ledger.quantity) FILTER (WHERE ledger.entry_type='RECEIPT' AND ledger.quantity>0),0) initially_received,
                       min(ledger.occurred_at) FILTER (WHERE ledger.entry_type='RECEIPT' AND ledger.quantity>0) first_received,
                       max(ledger.occurred_at) last_movement,0::numeric uncovered_demand
                FROM inventory_ledger_entries ledger
                WHERE ledger.tenant_id=? AND (?::uuid[] IS NULL OR ledger.account_catalog_item_id=ANY(?::uuid[]))
                GROUP BY ledger.tenant_id,ledger.account_catalog_item_id,ledger.location_id,ledger.expiration_date
                HAVING sum(ledger.quantity)>0
            ), active_reservations AS MATERIALIZED (
                SELECT reservation.tenant_id,reservation.account_catalog_item_id,reservation.location_id,reservation.expiration_date,
                       sum(reservation.quantity) quantity
                FROM order_inventory_reservations reservation
                JOIN amazon_orders orders ON orders.tenant_id=reservation.tenant_id
                  AND orders.marketplace_connection_id=reservation.marketplace_connection_id
                  AND orders.amazon_order_id=reservation.amazon_order_id
                WHERE reservation.tenant_id=? AND (?::uuid[] IS NULL OR reservation.account_catalog_item_id=ANY(?::uuid[])) AND reservation.status='ACTIVE'
                  AND %s
                GROUP BY reservation.tenant_id,reservation.account_catalog_item_id,reservation.location_id,reservation.expiration_date
            ), uncovered_order_demand AS MATERIALIZED (
                SELECT orders.tenant_id,component.account_catalog_item_id,location.id location_id,
                       sum(greatest((item.quantity_ordered-item.quantity_shipped)*component.quantity
                           -coalesce(allocated.quantity,0),0)) quantity
                FROM amazon_orders orders
                JOIN amazon_order_items item ON item.tenant_id=orders.tenant_id
                  AND item.marketplace_connection_id=orders.marketplace_connection_id AND item.amazon_order_id=orders.amazon_order_id
                JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
                  AND mapping.marketplace_connection_id=item.marketplace_connection_id AND mapping.marketplace_sku=item.seller_sku
                  AND mapping.status='ACTIVE'
                JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
                  AND component.marketplace_sku_mapping_id=mapping.id
                JOIN LATERAL (
                    SELECT candidate.id FROM warehouse_locations candidate
                    LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=component.tenant_id
                      AND assignment.account_catalog_item_id=component.account_catalog_item_id AND assignment.location_id=candidate.id
                    WHERE candidate.tenant_id=component.tenant_id AND candidate.status='ACTIVE'
                    ORDER BY assignment.is_default DESC NULLS LAST,candidate.created_at LIMIT 1
                ) location ON true
                LEFT JOIN LATERAL (
                    SELECT sum(reservation.quantity) quantity FROM order_inventory_reservations reservation
                    WHERE reservation.tenant_id=item.tenant_id AND reservation.amazon_order_item_id=item.id
                      AND reservation.account_catalog_item_id=component.account_catalog_item_id AND reservation.status='ACTIVE'
                ) allocated ON true
                WHERE orders.tenant_id=? AND (?::uuid[] IS NULL OR component.account_catalog_item_id=ANY(?::uuid[])) AND %s
                GROUP BY orders.tenant_id,component.account_catalog_item_id,location.id
                HAVING sum(greatest((item.quantity_ordered-item.quantity_shipped)*component.quantity
                    -coalesce(allocated.quantity,0),0))>0
            ), display_positions AS MATERIALIZED (
                SELECT * FROM positions
                UNION ALL
                SELECT reservation.tenant_id,reservation.account_catalog_item_id,reservation.location_id,reservation.expiration_date,
                       0::numeric quantity,NULL::numeric unit_cost,NULL::text currency,'FINAL' cost_status,
                       0::numeric initially_received,NULL::timestamptz first_received,NULL::timestamptz last_movement,0::numeric uncovered_demand
                FROM active_reservations reservation
                WHERE NOT EXISTS (
                    SELECT 1 FROM positions position
                    WHERE position.tenant_id=reservation.tenant_id
                      AND position.account_catalog_item_id=reservation.account_catalog_item_id
                      AND position.location_id=reservation.location_id
                      AND position.expiration_date IS NOT DISTINCT FROM reservation.expiration_date
                )
                UNION ALL
                SELECT demand.tenant_id,demand.account_catalog_item_id,demand.location_id,NULL::date,
                       0::numeric quantity,NULL::numeric unit_cost,NULL::text currency,'FINAL' cost_status,
                       0::numeric initially_received,NULL::timestamptz first_received,NULL::timestamptz last_movement,demand.quantity
                FROM uncovered_order_demand demand
            )
            SELECT item.id,coalesce(item.display_name,product.canonical_name),item.account_sku,identifier.identifier_value,
                   position.expiration_date,position.quantity,coalesce(reserved.quantity,0)+position.uncovered_demand,
                   position.unit_cost,position.currency,
                   CASE WHEN position.expiration_date IS NULL THEN 'FIFO' ELSE 'FEFO' END,
                   action.action_type,action.notes,
                   position.cost_status,position.initially_received,position.first_received,position.last_movement,
                   location.id,location.code,location.name,
                   CASE WHEN uploaded.account_catalog_item_id IS NOT NULL
                     THEN '/app/catalog/products/'||item.id||'/image' ELSE marketplace.image_url END image_url,
                   offer.vendor_item_code,
                   sum(position.quantity) OVER (PARTITION BY position.account_catalog_item_id) total_quantity,
                   sum(coalesce(reserved.quantity,0)+position.uncovered_demand) OVER (PARTITION BY position.account_catalog_item_id) total_reserved,
                   count(*) OVER (PARTITION BY position.account_catalog_item_id) position_count,
                   product.brand,marketplace.asin
            FROM display_positions position
            JOIN account_catalog_items item ON item.tenant_id=position.tenant_id AND item.id=position.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            JOIN warehouse_locations location ON location.tenant_id=position.tenant_id AND location.id=position.location_id
            LEFT JOIN account_catalog_product_images uploaded ON uploaded.tenant_id=item.tenant_id AND uploaded.account_catalog_item_id=item.id
            LEFT JOIN LATERAL (SELECT identifier_value FROM global_product_identifiers gi
                WHERE gi.global_product_id=product.id ORDER BY is_primary DESC,created_at LIMIT 1) identifier ON true
            LEFT JOIN LATERAL (SELECT vendor_item_code FROM vendor_catalog_offers current_offer
                WHERE current_offer.tenant_id=item.tenant_id AND current_offer.account_catalog_item_id=item.id
                  AND current_offer.effective_to IS NULL
                ORDER BY current_offer.is_default DESC,current_offer.updated_at DESC LIMIT 1) offer ON true
            LEFT JOIN inventory_expiration_actions action ON action.tenant_id=position.tenant_id
                AND action.account_catalog_item_id=item.id AND action.expiration_date=position.expiration_date
                AND action.status='PLANNED'
            LEFT JOIN active_reservations reserved ON reserved.tenant_id=position.tenant_id
                AND reserved.account_catalog_item_id=position.account_catalog_item_id
                AND reserved.location_id=position.location_id
                AND reserved.expiration_date IS NOT DISTINCT FROM position.expiration_date
            LEFT JOIN LATERAL (
                SELECT listing.image_url,coalesce(listing.asin,mapping.asin) asin FROM marketplace_sku_mappings mapping
                JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
                  AND component.marketplace_sku_mapping_id=mapping.id
                LEFT JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id
                  AND listing.marketplace_connection_id=mapping.marketplace_connection_id
                  AND upper(listing.seller_sku)=upper(mapping.marketplace_sku)
                WHERE mapping.tenant_id=item.tenant_id AND mapping.status='ACTIVE'
                  AND component.account_catalog_item_id=item.id
                  AND (SELECT count(*) FROM marketplace_sku_mapping_components all_components
                    WHERE all_components.tenant_id=mapping.tenant_id AND all_components.marketplace_sku_mapping_id=mapping.id)=1
                ORDER BY listing.last_seen_at DESC NULLS LAST LIMIT 1
            ) marketplace ON true
            ORDER BY position.expiration_date NULLS LAST,lower(coalesce(item.display_name,product.canonical_name))
            """.formatted(RESERVABLE_ORDER_STATUS_SQL,RESERVABLE_ORDER_STATUS_SQL),(rs,row)->new InventoryView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getObject(5,LocalDate.class),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getString(9),rs.getString(10),
                rs.getString(11),rs.getString(12),rs.getString(13),rs.getBigDecimal(14),
                rs.getTimestamp(15)==null?null:rs.getTimestamp(15).toInstant(),rs.getTimestamp(16)==null?null:rs.getTimestamp(16).toInstant(),
                rs.getObject(17,UUID.class),rs.getString(18),rs.getString(19),rs.getString(20),rs.getString(21),
                rs.getBigDecimal(22),rs.getBigDecimal(23),rs.getInt(24),rs.getString(25),rs.getString(26)),tenantId,filter,filter,tenantId,filter,filter,tenantId,filter,filter);
    }

    @Transactional(readOnly=true)
    public ShelfLifePolicy shelfLifePolicy(UUID tenantId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        return jdbc.query("""
            SELECT minimum_sellable_days,warning_days,auto_zero_marketplace_sellable,auto_sale_enabled,
                   default_sale_discount_percent,sale_start_days_before_expiration,sale_duration_days
            FROM inventory_shelf_life_policies WHERE tenant_id=?
            """,rs->rs.next()?new ShelfLifePolicy(rs.getInt(1),rs.getInt(2),rs.getBoolean(3),rs.getBoolean(4),
                rs.getBigDecimal(5),rs.getInt(6),rs.getInt(7)):new ShelfLifePolicy(10,30),tenantId);
    }

    @Transactional
    public void saveShelfLifePolicy(UUID tenantId,String actorEmail,int minimumSellableDays,int warningDays){
        saveShelfLifePolicy(tenantId,actorEmail,minimumSellableDays,warningDays,true,true,new BigDecimal("10.00"),warningDays,7);
    }

    @Transactional
    public void saveShelfLifePolicy(UUID tenantId,String actorEmail,int minimumSellableDays,int warningDays,
            boolean autoZeroMarketplaceSellable,boolean autoSaleEnabled,BigDecimal defaultSaleDiscountPercent,
            int saleStartDaysBeforeExpiration,int saleDurationDays){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        if(minimumSellableDays<0||minimumSellableDays>365)
            throw new IllegalArgumentException("Minimum sellable life must be between 0 and 365 days.");
        if(warningDays<=minimumSellableDays||warningDays>730)
            throw new IllegalArgumentException("Act-soon alert must be later than the minimum sellable life and no more than 730 days.");
        validateSalePlan(defaultSaleDiscountPercent,saleStartDaysBeforeExpiration,saleDurationDays);
        if(saleStartDaysBeforeExpiration<=minimumSellableDays)
            throw new IllegalArgumentException("The sale window must start before the minimum sellable-life cutoff.");
        if(saleStartDaysBeforeExpiration>warningDays)
            throw new IllegalArgumentException("The default sale must begin inside the act-soon warning window.");
        jdbc.update("""
            INSERT INTO inventory_shelf_life_policies (tenant_id,minimum_sellable_days,warning_days,
                auto_zero_marketplace_sellable,auto_sale_enabled,default_sale_discount_percent,
                sale_start_days_before_expiration,sale_duration_days,updated_by)
            VALUES (?,?,?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
            ON CONFLICT (tenant_id) DO UPDATE SET minimum_sellable_days=EXCLUDED.minimum_sellable_days,
                warning_days=EXCLUDED.warning_days,auto_zero_marketplace_sellable=EXCLUDED.auto_zero_marketplace_sellable,
                auto_sale_enabled=EXCLUDED.auto_sale_enabled,
                default_sale_discount_percent=EXCLUDED.default_sale_discount_percent,
                sale_start_days_before_expiration=EXCLUDED.sale_start_days_before_expiration,
                sale_duration_days=EXCLUDED.sale_duration_days,updated_by=EXCLUDED.updated_by,updated_at=now()
            """,tenantId,minimumSellableDays,warningDays,autoZeroMarketplaceSellable,autoSaleEnabled,
                defaultSaleDiscountPercent,saleStartDaysBeforeExpiration,saleDurationDays,actorEmail);
    }

    @Transactional(readOnly=true)
    public SalePlanDefaults salePlanDefaults(UUID tenantId,UUID itemId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        ShelfLifePolicy account=shelfLifePolicy(tenantId);
        return jdbc.query("""
            SELECT discount_percent,sale_start_days_before_expiration,sale_duration_days
            FROM inventory_shelf_life_product_overrides
            WHERE tenant_id=? AND account_catalog_item_id=?
            """,rs->rs.next()?new SalePlanDefaults(rs.getBigDecimal(1),rs.getInt(2),rs.getInt(3),true)
                :new SalePlanDefaults(account.defaultSaleDiscountPercent(),account.saleStartDaysBeforeExpiration(),
                    account.saleDurationDays(),false),tenantId,itemId);
    }

    @Transactional
    public void planExpirationAction(UUID tenantId,String actorEmail,UUID itemId,LocalDate expirationDate,
            String actionType,String notes){
        planExpirationAction(tenantId,actorEmail,itemId,expirationDate,actionType,null,notes);
    }

    @Transactional
    public void planExpirationAction(UUID tenantId,String actorEmail,UUID itemId,LocalDate expirationDate,
            String actionType,String removalMethod,String notes){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        requireAvailablePosition(tenantId,itemId,expirationDate);
        jdbc.update("UPDATE inventory_expiration_actions SET status='CANCELLED',updated_at=now() WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=? AND status='PLANNED'",
            tenantId,itemId,expirationDate);
        if("CLEAR".equals(actionType))return;
        if(!List.of("DISCOUNT","DONATE","HOLD","REMOVE","OTHER").contains(actionType))
            throw new IllegalArgumentException("Choose a valid inventory action.");
        if(removalMethod!=null&&!List.of("DONATE","DISPOSE","RETURN_TO_VENDOR","OTHER").contains(removalMethod))
            throw new IllegalArgumentException("Choose a valid inventory disposition.");
        jdbc.update("""
            INSERT INTO inventory_expiration_actions (tenant_id,account_catalog_item_id,expiration_date,
                action_type,removal_method,notes,created_by)
            VALUES (?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
            """,tenantId,itemId,expirationDate,actionType,removalMethod,clean(notes),actorEmail);
    }

    @Transactional
    public int saveSalePlan(UUID tenantId,String actorEmail,UUID itemId,LocalDate expirationDate,
            BigDecimal discountPercent,int startDays,int durationDays,boolean rememberForProduct,
            List<String> skuTargets){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        requireAvailablePosition(tenantId,itemId,expirationDate);
        validateSalePlan(discountPercent,startDays,durationDays);
        Map<String,BigDecimal> requested=parseSkuTargets(skuTargets,discountPercent);
        List<String> mapped=mappedSellerSkus(tenantId,itemId);
        Map<String,String> canonical=new LinkedHashMap<>();
        mapped.forEach(value->canonical.put(value.toUpperCase(java.util.Locale.ROOT),value));
        requested.entrySet().removeIf(entry->!canonical.containsKey(entry.getKey().toUpperCase(java.util.Locale.ROOT)));
        if(requested.isEmpty())throw new IllegalArgumentException("Choose at least one mapped Marketplace SKU.");
        jdbc.update("UPDATE inventory_expiration_actions SET status='CANCELLED',updated_at=now() WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=? AND status='PLANNED'",
            tenantId,itemId,expirationDate);
        Instant start=expirationDate.minusDays(startDays).atStartOfDay(ZoneId.systemDefault()).toInstant();
        if(start.isBefore(Instant.now()))start=Instant.now();
        Instant end=start.plus(durationDays,ChronoUnit.DAYS);
        UUID actionId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory_expiration_actions (id,tenant_id,account_catalog_item_id,expiration_date,
                action_type,discount_percent,starts_at,ends_at,notes,created_by)
            VALUES (?,?,?,?,'DISCOUNT',?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
            """,actionId,tenantId,itemId,expirationDate,discountPercent,java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(end),"Shelf-life sale plan · production publishing follows account settings",actorEmail);
        requested.forEach((sku,percent)->jdbc.update("""
            INSERT INTO inventory_expiration_action_targets (tenant_id,action_id,seller_sku,discount_percent)
            VALUES (?,?,?,?)
            """,tenantId,actionId,canonical.get(sku.toUpperCase(java.util.Locale.ROOT)),percent));
        if(rememberForProduct)jdbc.update("""
            INSERT INTO inventory_shelf_life_product_overrides (tenant_id,account_catalog_item_id,
                discount_percent,sale_start_days_before_expiration,sale_duration_days,updated_by)
            VALUES (?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
            ON CONFLICT (tenant_id,account_catalog_item_id) DO UPDATE SET
                discount_percent=EXCLUDED.discount_percent,
                sale_start_days_before_expiration=EXCLUDED.sale_start_days_before_expiration,
                sale_duration_days=EXCLUDED.sale_duration_days,updated_by=EXCLUDED.updated_by,updated_at=now()
            """,tenantId,itemId,discountPercent,startDays,durationDays,actorEmail);
        return requested.size();
    }

    private void requireAvailablePosition(UUID tenantId,UUID itemId,LocalDate expirationDate){
        if(itemId==null||expirationDate==null)throw new IllegalArgumentException("Choose an expiration-dated inventory position.");
        Integer positions=jdbc.queryForObject("""
            SELECT count(*) FROM (
                SELECT 1 FROM inventory_ledger_entries
                WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date=?
                GROUP BY account_catalog_item_id,expiration_date HAVING sum(quantity)>0
            ) available_position
            """,Integer.class,tenantId,itemId,expirationDate);
        if(positions==null||positions==0)throw new IllegalArgumentException("This inventory position is no longer available.");
    }

    private static void validateSalePlan(BigDecimal discountPercent,int startDays,int durationDays){
        if(discountPercent==null||discountPercent.compareTo(BigDecimal.ZERO)<=0||discountPercent.compareTo(new BigDecimal("100"))>=0||discountPercent.scale()>2)
            throw new IllegalArgumentException("Enter a discount between 0 and 100% with no more than two decimal places.");
        if(startDays<1||startDays>730)throw new IllegalArgumentException("Sale timing must be between 1 and 730 days before expiration.");
        if(durationDays<1||durationDays>180)throw new IllegalArgumentException("Sale duration must be between 1 and 180 days.");
    }

    private static Map<String,BigDecimal> parseSkuTargets(List<String> targets,BigDecimal fallback){
        Map<String,BigDecimal> parsed=new LinkedHashMap<>();
        if(targets==null)return parsed;
        for(String target:targets){
            if(target==null)continue;
            int separator=target.indexOf('\t');
            String sku=separator<0?target:target.substring(separator+1);
            BigDecimal percent=fallback;
            if(separator>0)try{percent=new BigDecimal(target.substring(0,separator));}catch(NumberFormatException ignored){}
            sku=sku.trim();validateSalePlan(percent,1,1);
            if(!sku.isBlank())parsed.put(sku,percent);
        }
        return parsed;
    }

    private List<String> mappedSellerSkus(UUID tenantId,UUID itemId){
        return jdbc.query("""
            SELECT DISTINCT mapping.marketplace_sku
            FROM marketplace_sku_mappings mapping
            LEFT JOIN marketplace_sku_mapping_components component
              ON component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id
            WHERE mapping.tenant_id=? AND mapping.status='ACTIVE'
              AND (component.account_catalog_item_id=? OR
                   (component.account_catalog_item_id IS NULL AND mapping.account_catalog_item_id=?))
            ORDER BY mapping.marketplace_sku
            """,(rs,row)->rs.getString(1),tenantId,itemId,itemId);
    }

    /** Records an unexpected physical item that was not present on the vendor invoice. */
    @Transactional
    public void receiveUninvoicedItem(UUID tenantId,String actorEmail,UUID itemId,BigDecimal quantity,
            LocalDate expirationDate,UUID locationId,String notes){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        lockStockCorrection(tenantId);
        if(itemId==null)throw new IllegalArgumentException("Choose a product from the account catalogue.");
        if(quantity==null||quantity.signum()<=0||quantity.stripTrailingZeros().scale()>0)
            throw new IllegalArgumentException("Enter a positive whole number of eaches received.");
        ProductReceiptTarget target=jdbc.query("""
            SELECT product.requires_expiration_date,coalesce(offer.currency,'USD')
            FROM account_catalog_items item
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (
                SELECT currency FROM vendor_catalog_offers current_offer
                WHERE current_offer.tenant_id=item.tenant_id
                  AND current_offer.account_catalog_item_id=item.id
                  AND current_offer.effective_to IS NULL
                ORDER BY current_offer.is_default DESC,current_offer.updated_at DESC LIMIT 1
            ) offer ON true
            WHERE item.tenant_id=? AND item.id=? AND item.status='ACTIVE'
            """,rs->rs.next()?new ProductReceiptTarget(rs.getBoolean(1),rs.getString(2)):null,tenantId,itemId);
        if(target==null)throw new IllegalArgumentException("This account catalogue product is no longer active.");
        if(target.expirationRequired()&&expirationDate==null)
            throw new IllegalArgumentException("Enter the expiration date required for this product.");
        UUID resolvedLocation=resolveItemLocation(tenantId,itemId,locationId);
        UUID sourceId=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO inventory_ledger_entries (tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
                expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,
                notes,created_by,cost_status)
            VALUES (?,?,?,'RECEIPT',?,?,0,?,'MANUAL',?,now(),?,
                coalesce(?, 'Unexpected vendor item received without an invoice'),
                (SELECT id FROM app_users WHERE lower(email)=lower(?)),'FINAL')
            """,tenantId,itemId,resolvedLocation,quantity,expirationDate,target.currency(),sourceId,
            "manual-uninvoiced-receipt:"+sourceId,clean(notes),actorEmail);
    }

    public void receiveUninvoicedItem(UUID tenantId,String actorEmail,UUID itemId,BigDecimal quantity,
            LocalDate expirationDate,String notes){receiveUninvoicedItem(tenantId,actorEmail,itemId,quantity,expirationDate,null,notes);}

    @Transactional
    public void receiveAndReconcile(UUID tenantId,String actor,UUID itemId,BigDecimal quantity,LocalDate expirationDate,
            UUID locationId,String notes,com.nextaicommerce.platform.orders.OrderRepository orders){
        receiveUninvoicedItem(tenantId,actor,itemId,quantity,expirationDate,locationId,notes);
        if(orders!=null)orders.reconcileTenantAfterPhysicalCount(tenantId);
    }

    @Transactional
    public void adjustAndReconcile(UUID tenantId,String actor,UUID itemId,LocalDate expirationDate,UUID locationId,
            BigDecimal quantity,String reason,String notes,com.nextaicommerce.platform.orders.OrderRepository orders){
        adjustInventory(tenantId,actor,itemId,expirationDate,locationId,quantity,reason,notes);
        if(orders!=null)orders.reconcileTenantAfterPhysicalCount(tenantId);
    }

    /** Adds a traceable count correction without ever letting a position go below its reserved quantity. */
    @Transactional
    public void adjustInventory(UUID tenantId,String actorEmail,UUID itemId,LocalDate expirationDate,
            UUID locationId,BigDecimal quantityChange,String reason,String notes){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        lockStockCorrection(tenantId);
        if(itemId==null||quantityChange==null||quantityChange.signum()==0||quantityChange.stripTrailingZeros().scale()>0)
            throw new IllegalArgumentException("Enter a whole-number inventory adjustment other than zero.");
        if(!List.of("COUNT_CORRECTION","DAMAGE","LOSS","DONATION","REMOVAL","RETURN_TO_VENDOR","OTHER").contains(reason))
            throw new IllegalArgumentException("Choose a reason for this inventory adjustment.");
        ProductReceiptTarget target=jdbc.query("""
            SELECT product.requires_expiration_date,coalesce(offer.currency,'USD')
            FROM account_catalog_items item JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (SELECT currency FROM vendor_catalog_offers offer
              WHERE offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id
                AND offer.effective_to IS NULL ORDER BY offer.is_default DESC,offer.updated_at DESC LIMIT 1) offer ON true
            WHERE item.tenant_id=? AND item.id=? AND item.status='ACTIVE'
            """,rs->rs.next()?new ProductReceiptTarget(rs.getBoolean(1),rs.getString(2)):null,tenantId,itemId);
        if(target==null)throw new IllegalArgumentException("This account catalogue product is no longer active.");
        if(target.expirationRequired()&&expirationDate==null)
            throw new IllegalArgumentException("Choose the expiration batch for this product.");
        UUID resolvedLocation=resolveItemLocation(tenantId,itemId,locationId);
        BigDecimal onHand=jdbc.queryForObject("""
            SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries
            WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ?
            """,BigDecimal.class,tenantId,itemId,resolvedLocation,expirationDate);
        BigDecimal reserved=jdbc.queryForObject("""
            SELECT coalesce(sum(reservation.quantity),0) FROM order_inventory_reservations reservation
            JOIN amazon_orders orders ON orders.tenant_id=reservation.tenant_id
              AND orders.marketplace_connection_id=reservation.marketplace_connection_id
              AND orders.amazon_order_id=reservation.amazon_order_id
            WHERE reservation.tenant_id=? AND reservation.account_catalog_item_id=? AND reservation.location_id=?
              AND reservation.expiration_date IS NOT DISTINCT FROM ? AND reservation.status='ACTIVE'
              AND %s
            """.formatted(RESERVABLE_ORDER_STATUS_SQL),BigDecimal.class,tenantId,itemId,resolvedLocation,expirationDate);
        if(quantityChange.signum()<0&&onHand.add(quantityChange).compareTo(reserved)<0)
            throw new IllegalArgumentException("This would reduce on-hand below reserved orders. Release or ship those orders first.");
        UUID sourceId=UUID.randomUUID();
        String detail=reason.replace('_',' ').toLowerCase(java.util.Locale.ROOT);
        jdbc.update("""
            INSERT INTO inventory_ledger_entries (tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,
              unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            VALUES (?,?,?,'ADJUSTMENT',?,?,NULL,?,'ADJUSTMENT',?,now(),?, ?,
              (SELECT id FROM app_users WHERE lower(email)=lower(?)),'FINAL')
            """,tenantId,itemId,resolvedLocation,quantityChange,expirationDate,target.currency(),sourceId,
            "manual-adjustment:"+sourceId,clean(notes)==null?"Inventory adjustment: "+detail:detail+" · "+clean(notes),actorEmail);
    }

    @Transactional
    public void adjustInventory(UUID tenantId,String actorEmail,UUID itemId,LocalDate expirationDate,
            BigDecimal quantityChange,String reason,String notes){adjustInventory(tenantId,actorEmail,itemId,expirationDate,null,quantityChange,reason,notes);}

    private void lockStockCorrection(UUID tenantId){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?::text,0))",Boolean.class,tenantId)))
            throw new IllegalArgumentException("Inventory is being updated. Nothing changed; please try again in a moment.");
        jdbc.execute("SET LOCAL lock_timeout='750ms'");
    }

    /** Moves an unreserved quantity from one expiration position without rewriting its audit history. */
    @Transactional
    public void moveInventoryPosition(UUID tenantId,String actorEmail,UUID itemId,UUID sourceLocationId,
            UUID destinationLocationId,LocalDate expirationDate,BigDecimal quantity,boolean makeDefault){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        lockStockCorrection(tenantId);
        if(itemId==null||sourceLocationId==null||destinationLocationId==null)
            throw new IllegalArgumentException("Choose the inventory position and its new location.");
        if(quantity==null||quantity.signum()<=0||quantity.stripTrailingZeros().scale()>0)
            throw new IllegalArgumentException("Enter a whole-number quantity greater than zero.");
        UUID destination=resolveItemLocation(tenantId,itemId,destinationLocationId);
        if(sourceLocationId.equals(destination)){
            if(makeDefault)setDefaultLocation(tenantId,itemId,destination);
            else throw new IllegalArgumentException("This batch is already at that location.");
            return;
        }
        BigDecimal onHand=jdbc.queryForObject("""
            SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries
            WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=?
              AND expiration_date IS NOT DISTINCT FROM ?
            """,BigDecimal.class,tenantId,itemId,sourceLocationId,expirationDate);
        if(onHand==null||onHand.signum()<=0)throw new IllegalArgumentException("This inventory position is no longer available.");
        BigDecimal reserved=jdbc.queryForObject("""
            SELECT coalesce(sum(reservation.quantity),0) FROM order_inventory_reservations reservation
            JOIN amazon_orders orders ON orders.tenant_id=reservation.tenant_id
              AND orders.marketplace_connection_id=reservation.marketplace_connection_id
              AND orders.amazon_order_id=reservation.amazon_order_id
            WHERE reservation.tenant_id=? AND reservation.account_catalog_item_id=? AND reservation.location_id=?
              AND reservation.expiration_date IS NOT DISTINCT FROM ? AND reservation.status='ACTIVE'
              AND %s
            """.formatted(RESERVABLE_ORDER_STATUS_SQL),BigDecimal.class,tenantId,itemId,sourceLocationId,expirationDate);
        BigDecimal available=onHand.subtract(reserved==null?BigDecimal.ZERO:reserved).max(BigDecimal.ZERO);
        if(quantity.compareTo(available)>0)
            throw new IllegalArgumentException("Only "+available.setScale(0,java.math.RoundingMode.DOWN).toPlainString()+" unreserved each can move. The remaining stock is committed to open orders.");
        var value=jdbc.query("""
            SELECT unit_cost,currency,cost_status FROM inventory_ledger_entries
            WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=?
              AND expiration_date IS NOT DISTINCT FROM ? AND quantity>0
            ORDER BY occurred_at DESC LIMIT 1
            """,rs->rs.next()?new Object[]{rs.getBigDecimal(1),rs.getString(2),rs.getString(3)}:new Object[]{null,"USD","FINAL"},
            tenantId,itemId,sourceLocationId,expirationDate);
        UUID transferId=UUID.randomUUID();
        UUID actor=jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)",UUID.class,actorEmail);
        jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
              expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            VALUES (?,?,?,'TRANSFER',?,?,?,?,'LOCATION_TRANSFER',?,now(),?, 'Moved to another storage location',?,?)
            """,tenantId,itemId,sourceLocationId,quantity.negate(),expirationDate,value[0],value[1],transferId,
            "location-transfer-out:"+transferId,actor,value[2]);
        jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
              expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            VALUES (?,?,?,'TRANSFER',?,?,?,?,'LOCATION_TRANSFER',?,now(),?, 'Moved from another storage location',?,?)
            """,tenantId,itemId,destination,quantity,expirationDate,value[0],value[1],transferId,
            "location-transfer-in:"+transferId,actor,value[2]);
        if(makeDefault)setDefaultLocation(tenantId,itemId,destination);
    }

    @Transactional
    public void changeExpiration(UUID tenantId,String actorEmail,UUID itemId,UUID locationId,LocalDate oldDate,
            LocalDate newDate,BigDecimal expectedQuantity,com.nextaicommerce.platform.orders.OrderRepository orders){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());lockStockCorrection(tenantId);
        if(newDate==null||java.util.Objects.equals(oldDate,newDate))throw new IllegalArgumentException("Choose a different expiration date.");
        if(jdbc.queryForObject("SELECT count(*) FROM account_catalog_items WHERE tenant_id=? AND id=?",Long.class,tenantId,itemId)!=1)
            throw new IllegalArgumentException("This item is unavailable in this account.");
        if(jdbc.queryForObject("SELECT count(*) FROM inventory_expiration_actions WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date IN (?,?) AND status='PLANNED'",Long.class,tenantId,itemId,oldDate,newDate)>0)
            throw new IllegalArgumentException("This expiration date has an active sale, hold or removal plan. Clear that plan before correcting the date, then review it again afterward.");
        BigDecimal quantity=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ?",BigDecimal.class,tenantId,itemId,locationId,oldDate);
        if(quantity.signum()<=0)throw new IllegalArgumentException("There is no shelf stock in this batch to correct.");
        if(expectedQuantity==null||quantity.compareTo(expectedQuantity)!=0)throw new IllegalArgumentException("The quantity changed while you were editing. Refresh and review the batch before saving.");
        var value=jdbc.query("SELECT unit_cost,currency,cost_status FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND location_id=? AND expiration_date IS NOT DISTINCT FROM ? AND quantity>0 ORDER BY occurred_at DESC LIMIT 1",
            rs->rs.next()?new Object[]{rs.getBigDecimal(1),rs.getString(2),rs.getString(3)}:new Object[]{null,"USD","FINAL"},tenantId,itemId,locationId,oldDate);
        UUID correction=UUID.randomUUID();String notes="Expiration corrected from "+(oldDate==null?"undated":oldDate)+" to "+newDate+". Shelf quantity unchanged.";
        UUID actor=jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)",UUID.class,actorEmail);
        for(int direction:new int[]{-1,1})jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,
            unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            VALUES (?,?,?,'TRANSFER',?,?,?,?,'EXPIRATION_CORRECTION',?,now(),?,?,?,?)
            """,tenantId,itemId,locationId,quantity.multiply(BigDecimal.valueOf(direction)),direction<0?oldDate:newDate,
            value[0],value[1],correction,"expiration-correction:"+correction+":"+direction,notes,actor,value[2]);
        jdbc.update("DELETE FROM order_inventory_reservations WHERE tenant_id=? AND account_catalog_item_id=? AND status='ACTIVE'",tenantId,itemId);
        orders.reconcileTenantAfterPhysicalCount(tenantId);
    }

    private void setDefaultLocation(UUID tenantId,UUID itemId,UUID locationId){
        jdbc.update("UPDATE account_catalog_item_locations SET is_default=false WHERE tenant_id=? AND account_catalog_item_id=? AND is_default",tenantId,itemId);
        jdbc.update("""
            INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
            VALUES (?,?,?,true) ON CONFLICT (tenant_id,account_catalog_item_id,location_id)
            DO UPDATE SET is_default=true,updated_at=now()
            """,tenantId,itemId,locationId);
    }

    private UUID resolveItemLocation(UUID tenantId,UUID itemId,UUID requested){
        UUID resolved=jdbc.query("""
            SELECT location.id FROM warehouse_locations location
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=location.tenant_id
              AND assignment.location_id=location.id AND assignment.account_catalog_item_id=?
            WHERE location.tenant_id=? AND location.status='ACTIVE'
              AND ((? IS NULL AND assignment.is_default) OR location.id=?) LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,itemId,tenantId,requested,requested);
        if(resolved==null)throw new IllegalArgumentException("Choose an active inventory location.");
        jdbc.update("""
            INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
            VALUES (?,?,?,false) ON CONFLICT DO NOTHING
            """,tenantId,itemId,resolved);
        return resolved;
    }

    /** Resolves an uploaded count code. A vendor choice is required only when that code is ambiguous. */
    @Transactional(readOnly=true)
    public UUID resolvePhysicalCountItem(UUID tenantId,UUID vendorId,String code){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        String normalized=code.replaceAll("[^A-Za-z0-9]","").toUpperCase(java.util.Locale.ROOT);
        List<UUID> matches=jdbc.query("""
            SELECT DISTINCT item.id FROM account_catalog_items item
            JOIN vendor_catalog_offers offer ON offer.tenant_id=item.tenant_id AND offer.account_catalog_item_id=item.id AND offer.effective_to IS NULL
            LEFT JOIN global_product_identifiers identifier ON identifier.global_product_id=item.global_product_id
            WHERE item.tenant_id=? AND item.status='ACTIVE' AND (? IS NULL OR offer.vendor_id=?)
              AND (upper(regexp_replace(offer.vendor_item_code,'[^A-Za-z0-9]','','g'))=?
                   OR upper(regexp_replace(coalesce(item.account_sku,''),'[^A-Za-z0-9]','','g'))=?
                   OR upper(regexp_replace(coalesce(identifier.identifier_value,''),'[^A-Za-z0-9]','','g'))=?)
            """,(rs,row)->rs.getObject(1,UUID.class),tenantId,vendorId,vendorId,normalized,normalized,normalized);
        if(matches.isEmpty())throw new IllegalArgumentException("No active catalogue product matches uploaded code “"+code+"”.");
        if(matches.size()>1&&vendorId==null)throw new IllegalArgumentException("Uploaded code “"+code+"” matches more than one vendor. Choose the vendor and upload again.");
        if(matches.size()>1)throw new IllegalArgumentException("Uploaded code “"+code+"” still matches more than one catalogue product for this vendor.");
        return matches.getFirst();
    }

    @Transactional
    public void reconcilePhysicalCount(UUID tenantId,String actorEmail,UUID itemId,BigDecimal counted,LocalDate expiration,int rowNumber){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        BigDecimal onHand=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM inventory_ledger_entries WHERE tenant_id=? AND account_catalog_item_id=? AND expiration_date IS NOT DISTINCT FROM ?",BigDecimal.class,tenantId,itemId,expiration);
        BigDecimal delta=counted.subtract(onHand);
        if(delta.signum()!=0)adjustInventory(tenantId,actorEmail,itemId,expiration,null,delta,"COUNT_CORRECTION","Physical count upload · row "+rowNumber);
    }

    /** Applies one authoritative count snapshot in set-based SQL, including zeroing omitted lots for touched products. */
    @Transactional
    public int reconcilePhysicalCountSnapshot(UUID tenantId,String actorEmail,UUID importId,UUID vendorId,List<PhysicalCountRow> rows){
        return reconcilePhysicalCountSnapshot(tenantId,actorEmail,importId,vendorId,rows,false);
    }

    @Transactional
    public int reconcilePhysicalCountSnapshot(UUID tenantId,String actorEmail,UUID importId,UUID vendorId,List<PhysicalCountRow> rows,boolean replaceMissing){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        Boolean lockAcquired=jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?::text,0))",Boolean.class,tenantId);
        if(!Boolean.TRUE.equals(lockAcquired))throw new PhysicalCountBusyException();
        jdbc.execute("SET LOCAL lock_timeout='3s'");
        jdbc.execute("SET LOCAL statement_timeout='15s'");
        jdbc.execute("CREATE TEMP TABLE physical_count_stage (row_number integer,code text,normalized_code text,counted numeric(16,4),expiration_date date,location_code text,normalized_location text) ON COMMIT DROP");
        jdbc.batchUpdate("INSERT INTO physical_count_stage(row_number,code,normalized_code,counted,expiration_date,location_code,normalized_location) VALUES (?,?,?,?,?,?,?)",
            rows,500,(statement,row)->{statement.setInt(1,row.rowNumber());statement.setString(2,row.code());statement.setString(3,row.code().replaceAll("[^A-Za-z0-9]","").toUpperCase(java.util.Locale.ROOT));statement.setBigDecimal(4,row.quantity());if(row.expiration()==null)statement.setNull(5,java.sql.Types.DATE);else statement.setObject(5,row.expiration());String location=row.locationCode()==null?"":row.locationCode().trim();statement.setString(6,location);statement.setString(7,location.toUpperCase(java.util.Locale.ROOT));});
        var unknownLocation=jdbc.query("""
            SELECT stage.row_number,stage.location_code FROM physical_count_stage stage
            LEFT JOIN warehouse_locations location ON location.tenant_id=? AND upper(location.code)=stage.normalized_location
              AND location.status='ACTIVE'
            WHERE stage.normalized_location<>'' AND location.id IS NULL ORDER BY stage.row_number LIMIT 1
            """,(rs,row)->new Object[]{rs.getInt(1),rs.getString(2)},tenantId).stream().findFirst().orElse(null);
        if(unknownLocation!=null)throw new IllegalArgumentException("Row "+unknownLocation[0]+" uses location “"+unknownLocation[1]+"”, which is not an active location code for this account. Add it in Account Catalogue, use an active location code, or leave the location blank to use the product default.");
        jdbc.update("""
            CREATE TEMP TABLE physical_count_code_lookup ON COMMIT DROP AS
            SELECT item.id item_id,upper(regexp_replace(item.account_sku,'[^A-Za-z0-9]','','g')) normalized_code
            FROM account_catalog_items item WHERE item.tenant_id=? AND item.status='ACTIVE' AND item.account_sku IS NOT NULL
              AND (CAST(? AS uuid) IS NULL OR EXISTS (SELECT 1 FROM vendor_catalog_offers selected_vendor
                WHERE selected_vendor.tenant_id=item.tenant_id AND selected_vendor.account_catalog_item_id=item.id
                  AND selected_vendor.vendor_id=CAST(? AS uuid) AND selected_vendor.effective_to IS NULL))
            UNION
            SELECT item.id,upper(regexp_replace(identifier.identifier_value,'[^A-Za-z0-9]','','g'))
            FROM account_catalog_items item JOIN global_product_identifiers identifier ON identifier.global_product_id=item.global_product_id
            WHERE item.tenant_id=? AND item.status='ACTIVE'
              AND (CAST(? AS uuid) IS NULL OR EXISTS (SELECT 1 FROM vendor_catalog_offers selected_vendor
                WHERE selected_vendor.tenant_id=item.tenant_id AND selected_vendor.account_catalog_item_id=item.id
                  AND selected_vendor.vendor_id=CAST(? AS uuid) AND selected_vendor.effective_to IS NULL))
            UNION
            SELECT offer.account_catalog_item_id,upper(regexp_replace(offer.vendor_item_code,'[^A-Za-z0-9]','','g'))
            FROM vendor_catalog_offers offer JOIN account_catalog_items item ON item.tenant_id=offer.tenant_id AND item.id=offer.account_catalog_item_id
            WHERE offer.tenant_id=? AND item.status='ACTIVE' AND offer.effective_to IS NULL
              AND (CAST(? AS uuid) IS NULL OR offer.vendor_id=CAST(? AS uuid))
            """,tenantId,vendorId,vendorId,tenantId,vendorId,vendorId,tenantId,vendorId,vendorId);
        jdbc.execute("CREATE INDEX physical_count_code_lookup_code_idx ON physical_count_code_lookup(normalized_code,item_id)");
        jdbc.update("""
            CREATE TEMP TABLE physical_count_candidates ON COMMIT DROP AS
            SELECT DISTINCT stage.row_number,stage.code,stage.counted,stage.expiration_date,item.id item_id,
                   product.requires_expiration_date,location.id location_id
            FROM physical_count_stage stage
            JOIN physical_count_code_lookup code_lookup ON code_lookup.normalized_code=stage.normalized_code
            JOIN account_catalog_items item ON item.tenant_id=? AND item.id=code_lookup.item_id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            JOIN warehouse_locations location ON location.tenant_id=item.tenant_id AND location.status='ACTIVE'
              AND (upper(location.code)=stage.normalized_location OR (stage.normalized_location='' AND EXISTS (
                SELECT 1 FROM account_catalog_item_locations default_assignment
                WHERE default_assignment.tenant_id=item.tenant_id AND default_assignment.account_catalog_item_id=item.id
                  AND default_assignment.location_id=location.id AND default_assignment.is_default)))
            """,tenantId);
        var bad=jdbc.query("""
            SELECT stage.row_number,stage.code,count(DISTINCT candidate.item_id) matches
            FROM physical_count_stage stage LEFT JOIN physical_count_candidates candidate ON candidate.row_number=stage.row_number
            GROUP BY stage.row_number,stage.code HAVING count(DISTINCT candidate.item_id)<>1 ORDER BY stage.row_number LIMIT 1
            """,(rs,row)->new Object[]{rs.getInt(1),rs.getString(2),rs.getInt(3)}).stream().findFirst().orElse(null);
        if(bad!=null){int matches=(Integer)bad[2];throw new IllegalArgumentException(matches==0
            ?"Row "+bad[0]+" code “"+bad[1]+"” does not match an active catalogue product."
            :"Row "+bad[0]+" code “"+bad[1]+"” matches multiple products. Choose the vendor and upload again.");}
        jdbc.execute("""
            CREATE TEMP TABLE physical_count_resolved ON COMMIT DROP AS
            SELECT min(row_number) row_number,max(code) code,sum(counted) counted,expiration_date,item_id,
                   bool_or(requires_expiration_date) requires_expiration_date,location_id
            FROM physical_count_candidates
            GROUP BY item_id,location_id,expiration_date
            """);
        var missingDate=jdbc.query("SELECT row_number,code FROM physical_count_resolved WHERE requires_expiration_date AND expiration_date IS NULL ORDER BY row_number LIMIT 1",
            (rs,row)->new Object[]{rs.getInt(1),rs.getString(2)}).stream().findFirst().orElse(null);
        if(missingDate!=null)throw new IllegalArgumentException("Row "+missingDate[0]+" code “"+missingDate[1]+"” requires an expiration date.");
        // Physical reality wins over reservations. Rebuild demand after applying the count;
        // packed goods are absent from this shelf-only snapshot.
        jdbc.update("""
            DELETE FROM order_inventory_reservations reservation WHERE tenant_id=? AND status='ACTIVE'
              AND EXISTS (SELECT 1 FROM physical_count_resolved counted
                  WHERE counted.item_id=reservation.account_catalog_item_id
                    AND (? OR (counted.location_id=reservation.location_id
                        AND counted.expiration_date IS NOT DISTINCT FROM reservation.expiration_date)))
            """,tenantId,replaceMissing);
        jdbc.update("""
            INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
            SELECT DISTINCT ?,item_id,location_id,false FROM physical_count_resolved
            ON CONFLICT (tenant_id,account_catalog_item_id,location_id) DO NOTHING
            """,tenantId);
        jdbc.update("""
            WITH current_position AS (
              SELECT ledger.account_catalog_item_id item_id,ledger.location_id,ledger.expiration_date,sum(ledger.quantity) on_hand
              FROM inventory_ledger_entries ledger
              WHERE ledger.tenant_id=? AND EXISTS (SELECT 1 FROM physical_count_resolved r WHERE r.item_id=ledger.account_catalog_item_id)
              GROUP BY ledger.account_catalog_item_id,ledger.location_id,ledger.expiration_date HAVING sum(ledger.quantity)<>0
            ), desired AS (
              SELECT item_id,location_id,expiration_date,counted,row_number FROM physical_count_resolved
              UNION ALL
              SELECT current.item_id,current.location_id,current.expiration_date,0,NULL FROM current_position current
              WHERE ? AND NOT EXISTS (SELECT 1 FROM physical_count_resolved r WHERE r.item_id=current.item_id
                AND r.location_id=current.location_id AND r.expiration_date IS NOT DISTINCT FROM current.expiration_date)
            ), delta AS (
              SELECT desired.*,desired.counted-coalesce(current.on_hand,0) quantity
              FROM desired LEFT JOIN current_position current ON current.item_id=desired.item_id
                AND current.location_id=desired.location_id
                AND current.expiration_date IS NOT DISTINCT FROM desired.expiration_date
            )
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,
              unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            SELECT ?,delta.item_id,delta.location_id,'ADJUSTMENT',delta.quantity,delta.expiration_date,
              coalesce(existing_cost.unit_cost,default_offer.buying_cost),coalesce(existing_cost.currency,default_offer.currency,'USD'),
              'PHYSICAL_COUNT',?,now(),'physical-count:'||?||':'||delta.item_id||':'||delta.location_id||':'||coalesce(delta.expiration_date::text,'none'),
              CASE WHEN delta.row_number IS NULL THEN 'Physical count reduced stock because this batch was absent from the submitted count.'
                   ELSE 'Quantity confirmed by physical count.' END,
              (SELECT id FROM app_users WHERE lower(email)=lower(?)),'FINAL'
            FROM delta
            LEFT JOIN LATERAL (SELECT ledger.unit_cost,ledger.currency FROM inventory_ledger_entries ledger
              WHERE ledger.tenant_id=? AND ledger.account_catalog_item_id=delta.item_id
                AND ledger.location_id=delta.location_id
                AND ledger.expiration_date IS NOT DISTINCT FROM delta.expiration_date AND ledger.unit_cost IS NOT NULL
              ORDER BY ledger.occurred_at DESC,ledger.created_at DESC LIMIT 1) existing_cost ON true
            LEFT JOIN LATERAL (SELECT round(offer.list_cost*(1-offer.discount_rate/100),4) buying_cost,offer.currency
              FROM vendor_catalog_offers offer WHERE offer.tenant_id=? AND offer.account_catalog_item_id=delta.item_id
                AND offer.effective_to IS NULL AND (CAST(? AS uuid) IS NULL OR offer.vendor_id=CAST(? AS uuid))
              ORDER BY offer.is_default DESC,offer.updated_at DESC LIMIT 1) default_offer ON true
            """,tenantId,replaceMissing,tenantId,importId,importId.toString(),actorEmail,tenantId,tenantId,vendorId,vendorId);
        var mismatch=jdbc.query("""
            SELECT resolved.row_number,resolved.code,resolved.counted,coalesce(actual.on_hand,0)
            FROM physical_count_resolved resolved
            LEFT JOIN LATERAL (
                SELECT sum(ledger.quantity) on_hand
                FROM inventory_ledger_entries ledger
                WHERE ledger.tenant_id=? AND ledger.account_catalog_item_id=resolved.item_id
                  AND ledger.location_id=resolved.location_id
                  AND ledger.expiration_date IS NOT DISTINCT FROM resolved.expiration_date
            ) actual ON true
            WHERE coalesce(actual.on_hand,0)<>resolved.counted
            ORDER BY resolved.row_number LIMIT 1
            """,(rs,row)->new Object[]{rs.getInt(1),rs.getString(2),rs.getBigDecimal(3),rs.getBigDecimal(4)},tenantId)
            .stream().findFirst().orElse(null);
        if(mismatch!=null)throw new IllegalStateException("Physical count verification failed at row "+mismatch[0]+" for code “"+mismatch[1]+"”. Expected "+mismatch[2]+" each but ledger has "+mismatch[3]+" each. Nothing was applied.");
        return rows.size();
    }

    /** Queues seller-fulfilled Amazon stock changes. FBA inventory is intentionally never overwritten. */
    @Transactional
    public MarketplaceActionResult queueMarketplaceAvailabilityZero(UUID tenantId,String actorEmail,UUID itemId,
            LocalDate expirationDate,String reason){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        List<MarketplaceListingTarget> targets=marketplaceTargets(tenantId,itemId);
        int queued=0,skippedFba=0;
        for(MarketplaceListingTarget target:targets){
            if(target.fba()){skippedFba++;continue;}
            jdbc.update("""
                INSERT INTO amazon_listing_actions(tenant_id,marketplace_connection_id,account_catalog_item_id,seller_sku,
                    marketplace_id,action_type,payload,created_by)
                VALUES (?,?,?,?,?,'ZERO_MFN_QUANTITY',jsonb_build_object('reason',?, 'expirationDate',?),
                    (SELECT id FROM app_users WHERE lower(email)=lower(?)))
                """,tenantId,target.connectionId(),itemId,target.sellerSku(),target.marketplaceId(),reason,
                expirationDate.toString(),actorEmail);
            queued++;
        }
        return new MarketplaceActionResult(queued,skippedFba,targets.isEmpty()?1:0);
    }

    @Transactional
    public MarketplaceActionResult queueSalePrice(UUID tenantId,String actorEmail,UUID itemId,BigDecimal salePrice,
            Instant startsAt,Instant endsAt){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        if(salePrice==null||salePrice.signum()<=0||salePrice.scale()>2)
            throw new IllegalArgumentException("Enter a sale price with no more than two decimal places.");
        if(startsAt==null||endsAt==null||!endsAt.isAfter(startsAt))
            throw new IllegalArgumentException("Choose a sale end time after the sale starts.");
        List<MarketplaceListingTarget> targets=marketplaceTargets(tenantId,itemId);int queued=0;
        for(MarketplaceListingTarget target:targets){
            jdbc.update("""
                INSERT INTO amazon_listing_actions(tenant_id,marketplace_connection_id,account_catalog_item_id,seller_sku,
                    marketplace_id,action_type,payload,execute_at,created_by)
                VALUES (?,?,?,?,?,'SALE_PRICE',jsonb_build_object('price',?, 'startsAt',?, 'endsAt',?),?,
                    (SELECT id FROM app_users WHERE lower(email)=lower(?)))
                """,tenantId,target.connectionId(),itemId,target.sellerSku(),target.marketplaceId(),salePrice,
                startsAt.toString(),endsAt.toString(),java.sql.Timestamp.from(startsAt),actorEmail);queued++;
        }
        return new MarketplaceActionResult(queued,0,targets.isEmpty()?1:0);
    }

    private List<MarketplaceListingTarget> marketplaceTargets(UUID tenantId,UUID itemId){
        return jdbc.query("""
            SELECT DISTINCT connection.id,listing.seller_sku,listing.marketplace_id,listing.fulfillment_channel
            FROM marketplace_sku_mappings mapping
            JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
              AND component.marketplace_sku_mapping_id=mapping.id
            JOIN marketplace_connections connection ON connection.tenant_id=mapping.tenant_id
              AND connection.id=mapping.marketplace_connection_id AND connection.channel='AMAZON' AND connection.status='ACTIVE'
            JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id
              AND listing.marketplace_connection_id=mapping.marketplace_connection_id AND listing.seller_sku=mapping.marketplace_sku
            WHERE mapping.tenant_id=? AND component.account_catalog_item_id=? AND mapping.status='ACTIVE'
            """,(rs,row)->new MarketplaceListingTarget(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getString(4)!=null&&rs.getString(4).toUpperCase(java.util.Locale.ROOT).contains("AMAZON")),tenantId,itemId);
    }

    @Transactional(readOnly=true)
    public LedgerSummary ledgerSummary(UUID tenantId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        return jdbc.queryForObject("""
            SELECT count(*),count(*) FILTER (WHERE entry_type='RECEIPT'),
                   count(*) FILTER (WHERE entry_type='ADJUSTMENT')
            FROM inventory_ledger_entries WHERE tenant_id=?
            """,(rs,row)->new LedgerSummary(rs.getLong(1),rs.getLong(2),rs.getLong(3)),tenantId);
    }

    /** Returns one bounded ledger page. Search is performed in the database so the browser never loads the full audit history. */
    @Transactional(readOnly=true)
    public LedgerPage ledgerPage(UUID tenantId,String search,int requestedPage,int requestedPageSize){
        return ledgerPage(tenantId,search,requestedPage,requestedPageSize,null);
    }
    @Transactional(readOnly=true)
    public LedgerPage ledgerPage(UUID tenantId,String search,int requestedPage,int requestedPageSize,UUID itemId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        String query=search==null?"":search.trim();
        String pattern="%"+query+"%";
        int pageSize=Math.max(10,Math.min(requestedPageSize,200));
        String joins="""
            FROM inventory_ledger_entries ledger
            JOIN account_catalog_items item ON item.tenant_id=ledger.tenant_id AND item.id=ledger.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (SELECT identifier_value FROM global_product_identifiers gi
                WHERE gi.global_product_id=product.id ORDER BY is_primary DESC,created_at LIMIT 1) identifier ON true
            LEFT JOIN receiving_line_receipts receipt ON ledger.source_type='RECEIVING'
                AND receipt.tenant_id=ledger.tenant_id AND receipt.id=ledger.source_id
            LEFT JOIN purchase_order_items po_item ON po_item.tenant_id=receipt.tenant_id
                AND po_item.id=receipt.purchase_order_item_id
            LEFT JOIN purchase_orders po ON po.tenant_id=po_item.tenant_id AND po.id=po_item.purchase_order_id
            LEFT JOIN receiving_documents document ON document.tenant_id=po.tenant_id
                AND document.id=po.receiving_document_id
            LEFT JOIN amazon_orders amazon_order ON ledger.source_type='AMAZON_ORDER'
                AND amazon_order.tenant_id=ledger.tenant_id AND amazon_order.id=ledger.source_id
            LEFT JOIN physical_count_imports physical_count ON ledger.source_type='PHYSICAL_COUNT'
                AND physical_count.tenant_id=ledger.tenant_id AND physical_count.id=ledger.source_id
            LEFT JOIN app_users actor ON actor.id=ledger.created_by
            WHERE ledger.tenant_id=? AND (?::uuid IS NULL OR item.id=?::uuid) AND (?='' OR
                coalesce(item.display_name,product.canonical_name) ILIKE ? OR coalesce(product.brand,'') ILIKE ? OR
                coalesce(item.account_sku,'') ILIKE ? OR
                coalesce(identifier.identifier_value,'') ILIKE ? OR coalesce(ledger.entry_type,'') ILIKE ? OR
                coalesce(ledger.source_type,'') ILIKE ? OR coalesce(po.po_number,'') ILIKE ? OR
                coalesce(document.document_number,'') ILIKE ? OR coalesce(amazon_order.amazon_order_id,'') ILIKE ? OR
                coalesce(physical_count.original_filename,'') ILIKE ? OR
                coalesce(ledger.notes,'') ILIKE ? OR coalesce(actor.email,'') ILIKE ? OR
                EXISTS (SELECT 1 FROM marketplace_sku_mappings mapping WHERE mapping.tenant_id=item.tenant_id
                  AND mapping.status='ACTIVE' AND coalesce(mapping.asin,'') ILIKE ?))
            """;
        Object[] filterArguments={tenantId,itemId,itemId,query,pattern,pattern,pattern,pattern,pattern,pattern,pattern,pattern,pattern,pattern,pattern,pattern,pattern};
        Long counted=jdbc.queryForObject("SELECT count(*) "+joins,Long.class,filterArguments);
        long total=counted==null?0:counted;
        int page=Math.max(0,requestedPage);
        if(total>0&&(long)page*pageSize>=total)page=(int)((total-1)/pageSize);
        List<Object> rowArguments=new java.util.ArrayList<>(java.util.Arrays.asList(filterArguments));
        rowArguments.add(pageSize);rowArguments.add((long)page*pageSize);
        List<LedgerView> rows=jdbc.query("""
            SELECT ledger.id,item.id,ledger.occurred_at,coalesce(item.display_name,product.canonical_name),
                   item.account_sku,identifier.identifier_value,ledger.entry_type,ledger.quantity,
                   ledger.expiration_date,ledger.unit_cost,ledger.currency,ledger.source_type,
                   coalesce(po.po_number,document.document_number,amazon_order.amazon_order_id,physical_count.id::text),
                   ledger.notes,actor.email,ledger.cost_status
            """+joins+" ORDER BY ledger.occurred_at DESC,ledger.created_at DESC,ledger.id DESC LIMIT ? OFFSET ?",
            (rs,row)->new LedgerView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant(),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getBigDecimal(8),rs.getObject(9,LocalDate.class),
                rs.getBigDecimal(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),
                rs.getString(16)),rowArguments.toArray());
        return new LedgerPage(rows,total,page,pageSize);
    }
    @Transactional(readOnly=true)
    public List<LedgerView> movements(UUID tenantId,UUID itemId,LocalDate expirationDate,UUID locationId){
        return movements(tenantId,itemId,expirationDate,locationId,false,0);
    }
    /** Item scope includes dated and FIFO batches; page before joining audit details. */
    @Transactional(readOnly=true)
    public List<LedgerView> movements(UUID tenantId,UUID itemId,LocalDate expirationDate,UUID locationId,boolean allItems,int page){
        var args=new java.util.ArrayList<Object>();args.add(tenantId);args.add(itemId);
        String filter="";
        if(!allItems){filter+=" AND expiration_date IS NOT DISTINCT FROM ?";args.add(expirationDate);}
        if(!allItems&&locationId!=null){filter+=" AND location_id=?";args.add(locationId);}
        args.add((long)Math.max(0,Math.min(10000,page))*100);
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        return jdbc.query("""
            WITH movements AS (SELECT * FROM inventory_ledger_entries
              WHERE tenant_id=? AND account_catalog_item_id=?
            """+filter+" ORDER BY occurred_at DESC,created_at DESC,id DESC LIMIT 100 OFFSET ?)"+"""
            SELECT ledger.id,item.id,ledger.occurred_at,coalesce(item.display_name,product.canonical_name),
                   item.account_sku,identifier.identifier_value,ledger.entry_type,ledger.quantity,
                   ledger.expiration_date,ledger.unit_cost,ledger.currency,ledger.source_type,
                   coalesce(po.po_number,document.document_number,amazon_order.amazon_order_id,physical_count.id::text),
                   ledger.notes,actor.email,ledger.cost_status
            FROM movements ledger
            JOIN account_catalog_items item ON item.tenant_id=ledger.tenant_id AND item.id=ledger.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            LEFT JOIN LATERAL (SELECT identifier_value FROM global_product_identifiers gi
                WHERE gi.global_product_id=product.id ORDER BY is_primary DESC,created_at LIMIT 1) identifier ON true
            LEFT JOIN receiving_line_receipts receipt ON ledger.source_type='RECEIVING'
                AND receipt.tenant_id=ledger.tenant_id AND receipt.id=ledger.source_id
            LEFT JOIN purchase_order_items po_item ON po_item.tenant_id=receipt.tenant_id
                AND po_item.id=receipt.purchase_order_item_id
            LEFT JOIN purchase_orders po ON po.tenant_id=po_item.tenant_id AND po.id=po_item.purchase_order_id
            LEFT JOIN receiving_documents document ON document.tenant_id=po.tenant_id
                AND document.id=po.receiving_document_id
            LEFT JOIN amazon_orders amazon_order ON ledger.source_type='AMAZON_ORDER'
                AND amazon_order.tenant_id=ledger.tenant_id AND amazon_order.id=ledger.source_id
            LEFT JOIN physical_count_imports physical_count ON ledger.source_type='PHYSICAL_COUNT'
                AND physical_count.tenant_id=ledger.tenant_id AND physical_count.id=ledger.source_id
            LEFT JOIN app_users actor ON actor.id=ledger.created_by
            ORDER BY ledger.occurred_at DESC,ledger.created_at DESC,ledger.id DESC
            """,(rs,row)->new LedgerView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant(),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getBigDecimal(8),rs.getObject(9,LocalDate.class),
                rs.getBigDecimal(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getString(15),
                rs.getString(16)),args.toArray());
    }
    public List<LedgerView> movements(UUID tenantId,UUID itemId,LocalDate expirationDate){
        return movements(tenantId,itemId,expirationDate,null);
    }
    @Transactional(readOnly=true)
    public List<ReservationView> reservations(UUID tenantId,UUID itemId,LocalDate expirationDate,UUID locationId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
        return jdbc.query("""
            SELECT reservation.amazon_order_id,orders.order_status,item.seller_sku,item.quantity_ordered,
                   coalesce(component.quantity,CASE WHEN item.quantity_ordered>0
                     THEN totals.quantity/item.quantity_ordered ELSE totals.quantity END),
                   sum(reservation.quantity),coalesce(component.quantity,0)*item.quantity_ordered,
                   greatest(coalesce(component.quantity,0)*item.quantity_ordered-totals.quantity,0),
                   reservation.expiration_date,location.code,location.name,min(reservation.created_at)
            FROM order_inventory_reservations reservation
            JOIN amazon_orders orders ON orders.tenant_id=reservation.tenant_id
              AND orders.marketplace_connection_id=reservation.marketplace_connection_id
              AND orders.amazon_order_id=reservation.amazon_order_id
            JOIN amazon_order_items item ON item.tenant_id=reservation.tenant_id
              AND item.id=reservation.amazon_order_item_id
            JOIN warehouse_locations location ON location.tenant_id=reservation.tenant_id
              AND location.id=reservation.location_id
            LEFT JOIN LATERAL (
              SELECT mapping_component.quantity FROM marketplace_sku_mappings mapping
              JOIN marketplace_sku_mapping_components mapping_component
                ON mapping_component.tenant_id=mapping.tenant_id
               AND mapping_component.marketplace_sku_mapping_id=mapping.id
              WHERE mapping.tenant_id=reservation.tenant_id
                AND mapping.marketplace_connection_id=reservation.marketplace_connection_id
                AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
                AND mapping_component.account_catalog_item_id=reservation.account_catalog_item_id
              ORDER BY mapping_component.sort_order LIMIT 1
            ) component ON true
            LEFT JOIN LATERAL (
              SELECT coalesce(sum(active.quantity),0) quantity FROM order_inventory_reservations active
              WHERE active.tenant_id=reservation.tenant_id AND active.amazon_order_item_id=reservation.amazon_order_item_id
                AND active.account_catalog_item_id=reservation.account_catalog_item_id AND active.status='ACTIVE'
            ) totals ON true
            WHERE reservation.tenant_id=? AND reservation.account_catalog_item_id=? AND reservation.status='ACTIVE'
              AND %s
              AND (?::date IS NULL OR reservation.expiration_date IS NOT DISTINCT FROM ?::date)
              AND (?::uuid IS NULL OR reservation.location_id=?::uuid)
            GROUP BY reservation.amazon_order_id,orders.order_status,item.seller_sku,item.quantity_ordered,
              component.quantity,totals.quantity,reservation.expiration_date,location.code,location.name
            ORDER BY min(reservation.created_at),reservation.amazon_order_id
            """.formatted(RESERVABLE_ORDER_STATUS_SQL),(rs,row)->new ReservationView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),
                rs.getObject(9,LocalDate.class),rs.getString(10),rs.getString(11),rs.getTimestamp(12).toInstant()),
            tenantId,itemId,expirationDate,expirationDate,locationId,locationId);
    }
    private static String clean(String value){return value==null||value.isBlank()?null:value.trim();}
    private record ProductReceiptTarget(boolean expirationRequired,String currency){}
    private record MarketplaceListingTarget(UUID connectionId,String sellerSku,String marketplaceId,boolean fba){}
}
