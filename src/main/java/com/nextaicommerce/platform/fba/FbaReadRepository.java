package com.nextaicommerce.platform.fba;

import com.nextaicommerce.platform.marketplace.MarketplaceSkuRepository;
import com.nextaicommerce.platform.web.TablePaging;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only FBA projection. Amazon inventory is never posted to the local warehouse ledger. */
@Repository
public class FbaReadRepository {
    private final JdbcTemplate jdbc;
    public FbaReadRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Stock(String sku,Integer available,Integer working,Integer shipped,Integer receiving,
            Integer reserved,Integer unfulfillable,Instant observedAt,BigDecimal warehouseAvailable,
            BigDecimal fulfillmentFee,String currency,Instant feeObservedAt,Integer fcTransfer) {
        public Long totalInbound(){return working==null||shipped==null||receiving==null?null:(long)working+shipped+receiving;}
        public boolean stale() { return observedAt==null || observedAt.isBefore(Instant.now().minusSeconds(86400)); }
        public String observedDisplay(){return display(observedAt);}
        public String feeObservedDisplay(){return display(feeObservedAt);}
    }

    /** One bounded query for the visible page, never a network request or per-row repository call. */
    @Transactional(readOnly=true)
    public Map<String,Stock> stocks(UUID tenant,UUID connection,Collection<String> requested) {
        var skus=requested.stream().filter(Objects::nonNull).distinct().toList();
        if(skus.isEmpty())return Map.of();
        if(skus.size()>1000)throw new IllegalArgumentException("FBA inventory lookup exceeds page budget");
        scope(tenant);
        var args=new ArrayList<Object>();args.add(tenant);args.add(connection);args.addAll(skus);
        String sql="""
            SELECT listing.seller_sku,inventory.fulfillable_quantity,inventory.inbound_working_quantity,
                inventory.inbound_shipped_quantity,inventory.inbound_receiving_quantity,
                inventory.reserved_quantity,inventory.unfulfillable_quantity,inventory.snapshot_at,
                local_inventory.available warehouse_available,fee.fulfillment_fee,fee.currency,fee.effective_at,
                CASE WHEN inventory.raw_payload->>'afn-fc-transfer-quantity' ~ '^[0-9]{1,9}$'
                    THEN (inventory.raw_payload->>'afn-fc-transfer-quantity')::integer END fc_transfer
            FROM amazon_listings listing
            LEFT JOIN LATERAL (SELECT * FROM amazon_inventory_snapshots s
                WHERE s.tenant_id=listing.tenant_id AND s.marketplace_connection_id=listing.marketplace_connection_id
                AND s.marketplace_id=listing.marketplace_id AND s.seller_sku=listing.seller_sku
                ORDER BY snapshot_at DESC LIMIT 1) inventory ON true
            LEFT JOIN LATERAL (SELECT fulfillment_fee,currency,effective_at FROM amazon_fee_estimates f
                WHERE f.tenant_id=listing.tenant_id AND f.marketplace_connection_id=listing.marketplace_connection_id
                AND f.marketplace_id=listing.marketplace_id AND f.seller_sku=listing.seller_sku
                  AND f.currency=coalesce(nullif(trim(listing.currency),''),(SELECT currency_code FROM marketplace_definitions WHERE channel='AMAZON' AND marketplace_identifier=listing.marketplace_id))
                  AND (NOT(jsonb_exists(f.raw_payload,'currency')) OR f.raw_payload->>'currency'=f.currency)
              AND f.currency=(SELECT currency_code FROM marketplace_definitions WHERE channel='AMAZON' AND marketplace_identifier=f.marketplace_id)
              AND (NOT jsonb_exists(f.raw_payload,'amazon-store') OR f.raw_payload->>'amazon-store'=(SELECT country_code FROM marketplace_definitions WHERE channel='AMAZON' AND marketplace_identifier=f.marketplace_id))
              AND f.marketplace_id=(SELECT marketplace_identifier FROM marketplace_connections WHERE tenant_id=listing.tenant_id AND id=listing.marketplace_connection_id)
                ORDER BY effective_at DESC LIMIT 1) fee ON true
            LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=listing.tenant_id
                AND mapping.marketplace_connection_id=listing.marketplace_connection_id
                AND mapping.marketplace_sku=listing.seller_sku AND mapping.status='ACTIVE'
            """+MarketplaceSkuRepository.LOCAL_MAPPING_AVAILABILITY+"""
            WHERE listing.tenant_id=? AND listing.marketplace_connection_id=?
                AND (upper(coalesce(listing.fulfillment_channel,'')) LIKE '%AMAZON%'
                    OR upper(listing.fulfillment_channel) IN ('FBA','AFN'))
                AND listing.seller_sku IN (
            """+String.join(",",Collections.nCopies(skus.size(),"?"))+")";
        var result=new LinkedHashMap<String,Stock>();
        jdbc.query(sql,rs->{var stock=new Stock(rs.getString("seller_sku"),
            (Integer)rs.getObject("fulfillable_quantity"),(Integer)rs.getObject("inbound_working_quantity"),
            (Integer)rs.getObject("inbound_shipped_quantity"),(Integer)rs.getObject("inbound_receiving_quantity"),
            (Integer)rs.getObject("reserved_quantity"),(Integer)rs.getObject("unfulfillable_quantity"),
            instant(rs.getTimestamp("snapshot_at")),rs.getBigDecimal("warehouse_available"),
            rs.getBigDecimal("fulfillment_fee"),rs.getString("currency"),instant(rs.getTimestamp("effective_at")),(Integer)rs.getObject("fc_transfer"));
            result.put(stock.sku(),stock);},args.toArray());
        return result;
    }

    public record Shipment(String id,String name,String status,String destination,String shipFrom,
            Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState) {
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,createdAt,null);}
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,null);}
        public String createdDisplay(){
            if(createdAt!=null)return display(createdAt);
            var match=java.util.regex.Pattern.compile("\\((\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\)").matcher(name==null?"":name);
            if(match.find())try{
                var parsed=java.time.LocalDateTime.parse(match.group(1),java.time.format.DateTimeFormatter
                    .ofPattern("MM/dd/uuuu HH:mm",Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT));
                return parsed.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a",Locale.US))
                    +" · shipment name (timezone unspecified)";
            }catch(java.time.format.DateTimeParseException ignored){ }
            return "Not supplied";
        }
        public String updatedDisplay(){return display(updatedAt);}
    }
    public record ShipmentItem(String sku,String fnsku,String title,int shipped,int received,String imageUrl){}
    public record ShipmentCosts(BigDecimal transportation,BigDecimal placement,String currency,long shippedUnits){
        public BigDecimal getPerUnit(){return transportation==null||placement==null||shippedUnits<=0?null:
            transportation.add(placement).divide(BigDecimal.valueOf(shippedUnits),4,java.math.RoundingMode.HALF_UP);}
    }
    public record ShipmentItems(List<ShipmentItem> rows,long total,int page,int size,ShipmentCosts costs){}
    /** Posted transaction totals are counted once, never again through nested breakdown rollups.
     * Amazon's inbound service-fee records use ORDER_ID for the exact FBA shipment reference.
     * Absent fee categories stay unknown; credit/reversal signs reduce the net cost.
     */
    private ShipmentCosts shipmentCosts(UUID tenant,UUID connection,String shipment){
        return jdbc.queryForObject("""
            SELECT CASE WHEN count(DISTINCT currency)=1 AND bool_and(amount IS NOT NULL AND currency IS NOT NULL) THEN sum(-amount) FILTER
                     (WHERE description='FBAPostInboundTransportation') END transportation,
                   CASE WHEN count(DISTINCT currency)=1 AND bool_and(amount IS NOT NULL AND currency IS NOT NULL) THEN sum(-amount) FILTER
                     (WHERE description='FBAInboundConvenience') END placement,
                   CASE WHEN count(DISTINCT currency)=1 THEN min(currency) END currency,
                   (SELECT coalesce(sum(quantity_shipped),0) FROM amazon_fba_shipment_items
                     WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?) shipped_units
            FROM amazon_financial_transactions
            WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
              AND transaction_type IN ('ServiceFee','ServiceFee - Reversal')
              AND description IN ('FBAPostInboundTransportation','FBAInboundConvenience')
              AND raw_payload->>'transactionStatus' IN ('RELEASED','DEFERRED_RELEASED')
            """,(rs,n)->new ShipmentCosts(rs.getBigDecimal("transportation"),rs.getBigDecimal("placement"),rs.getString("currency"),rs.getLong("shipped_units")),tenant,connection,shipment,tenant,connection,shipment);
    }
    @Transactional(readOnly=true)
    public ShipmentItems items(UUID tenant,UUID connection,String shipment,String search,int requestedPage){
        scope(tenant);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM amazon_fba_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=?)",Boolean.class,tenant,connection,shipment)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        String q=search==null?"":search.trim(),match="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String from="""
            FROM amazon_fba_shipment_items i LEFT JOIN LATERAL (
              SELECT l.item_name,l.image_url FROM amazon_listings l WHERE l.tenant_id=i.tenant_id
                AND l.marketplace_connection_id=i.marketplace_connection_id AND l.seller_sku=i.seller_sku
              ORDER BY l.marketplace_id LIMIT 1) product ON true
            WHERE i.tenant_id=? AND i.marketplace_connection_id=? AND i.shipment_id=?
              AND (?='' OR i.seller_sku ILIKE ? ESCAPE '!' OR i.fnsku ILIKE ? ESCAPE '!'
                OR product.item_name ILIKE ? ESCAPE '!')
            """;
        Object[] args={tenant,connection,shipment,q,match,match,match};
        long total=jdbc.queryForObject("SELECT count(*) "+from,Long.class,args);
        int page=Math.min(Math.max(0,requestedPage),(int)Math.max(0,(total-1)/100));
        var values=new ArrayList<>(Arrays.asList(args));values.add(page*100);
        var rows=jdbc.query("SELECT i.*,product.item_name,product.image_url "+from+" ORDER BY i.seller_sku LIMIT 100 OFFSET ?",(rs,n)->new ShipmentItem(rs.getString("seller_sku"),rs.getString("fnsku"),rs.getString("item_name"),rs.getInt("quantity_shipped"),rs.getInt("quantity_received"),rs.getString("image_url")),values.toArray());
        return new ShipmentItems(rows,total,page,100,shipmentCosts(tenant,connection,shipment));
    }
    public record ShipmentPage(List<Shipment> rows,long total,int page,int size) {
        public int pages(){return Math.max(1,(int)((total+size-1)/size));}
        public boolean previous(){return page>0;}
        public boolean next(){return page+1<pages();}
    }

    @Transactional(readOnly=true)
    public ShipmentPage shipments(UUID tenant,UUID connection,String search,int requestedPage,int requestedSize) {
        scope(tenant);int size=TablePaging.size(requestedSize);
        String q=search==null?"":search.trim();
        String match="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String where="""
            FROM amazon_fba_shipments s WHERE s.tenant_id=? AND s.marketplace_connection_id=?
            AND (?='' OR s.shipment_id ILIKE ? ESCAPE '!' OR s.shipment_name ILIKE ? ESCAPE '!'
                OR s.destination_fulfillment_center ILIKE ? ESCAPE '!'
                OR EXISTS(SELECT 1 FROM amazon_fba_shipment_items i WHERE i.tenant_id=s.tenant_id
                    AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id
                    AND (i.seller_sku ILIKE ? ESCAPE '!' OR i.fnsku ILIKE ? ESCAPE '!'
                        OR EXISTS(SELECT 1 FROM amazon_listings l WHERE l.tenant_id=i.tenant_id
                          AND l.marketplace_connection_id=i.marketplace_connection_id AND l.seller_sku=i.seller_sku
                          AND l.item_name ILIKE ? ESCAPE '!'))))
            """;
        Object[] args={tenant,connection,q,match,match,match,match,match,match};
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) "+where,Long.class,args));
        int page=Math.min(Math.max(0,requestedPage),(int)Math.max(0,(total-1)/size));
        var params=new ArrayList<>(Arrays.asList(args));params.add(size);params.add((long)page*size);
        var rows=jdbc.query("""
            WITH selected AS (SELECT s.*
            """+where+"""
                ORDER BY coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) DESC NULLS LAST,s.shipment_id DESC LIMIT ? OFFSET ?)
            SELECT s.*,coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) display_date,totals.skus,totals.shipped,totals.received,
                nullif(s.raw_payload#>>'{ShipFromAddress,StateOrProvinceCode}','') ship_from_state,
                nullif(concat_ws(', ',nullif(s.raw_payload#>>'{ShipFromAddress,City}',''),
                    nullif(s.raw_payload#>>'{ShipFromAddress,StateOrProvinceCode}',''),
                    nullif(s.raw_payload#>>'{ShipFromAddress,CountryCode}','')),'') ship_from
            FROM selected s LEFT JOIN LATERAL (
                SELECT count(*) skus,coalesce(sum(quantity_shipped),0) shipped,
                    coalesce(sum(quantity_received),0) received FROM amazon_fba_shipment_items i
                WHERE i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id
                    AND i.shipment_id=s.shipment_id) totals ON true
            ORDER BY coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) DESC NULLS LAST,s.shipment_id DESC
            """,(rs,n)->new Shipment(rs.getString("shipment_id"),rs.getString("shipment_name"),
                rs.getString("shipment_status"),rs.getString("destination_fulfillment_center"),
                rs.getString("ship_from"),instant(rs.getTimestamp("updated_date")),rs.getLong("skus"),
                rs.getLong("shipped"),rs.getLong("received"),instant(rs.getTimestamp("display_date")),rs.getString("ship_from_state")),params.toArray());
        return new ShipmentPage(rows,total,page,size);
    }
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private static Instant instant(java.sql.Timestamp value){return value==null?null:value.toInstant();}
    private static String display(Instant value){return value==null?"Not supplied":java.time.format.DateTimeFormatter
        .ofPattern("MM/dd/yyyy hh:mm a 'UTC'",Locale.US).withZone(java.time.ZoneOffset.UTC).format(value);}
}
