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
            Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState,ShipmentCosts costs,Long shippedCases,Long looseUnits,boolean itemsComplete,String shipFromPostalCode,String shipFromCity,Instant importedCreation) {
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState,ShipmentCosts costs,Long shippedCases,Long looseUnits,boolean itemsComplete,String shipFromPostalCode,String shipFromCity){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,createdAt,shipFromState,costs,shippedCases,looseUnits,itemsComplete,shipFromPostalCode,shipFromCity,null);}
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState,ShipmentCosts costs,Long shippedCases,Long looseUnits,boolean itemsComplete,String shipFromPostalCode){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,createdAt,shipFromState,costs,shippedCases,looseUnits,itemsComplete,shipFromPostalCode,null);}
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState,ShipmentCosts costs,Long shippedCases,Long looseUnits,boolean itemsComplete){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,createdAt,shipFromState,costs,shippedCases,looseUnits,itemsComplete,null);}
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState,ShipmentCosts costs){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,createdAt,shipFromState,costs,null,null,true);}
        public Shipment(String id,String name,String status,String destination,String shipFrom,Instant updatedAt,long skuCount,long shipped,long received,Instant createdAt,String shipFromState){this(id,name,status,destination,shipFrom,updatedAt,skuCount,shipped,received,createdAt,shipFromState,new ShipmentCosts(null,null,null,shipped));}
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
        public String createdPrimaryDisplay(){String value=createdDisplay();return value.equals("Not supplied")?value:value.split(" ",2)[0];}
        public boolean createdFromName(){return createdAt==null&&createdDisplay().contains("shipment name");}
        public String updatedDisplay(){return display(updatedAt);}
    }
    public record ShipmentItem(String sku,String fnsku,String title,int shipped,int received,String imageUrl){}
    public record ShipmentCosts(BigDecimal transportation,BigDecimal placement,String currency,long shippedUnits){
        /** Display rounding only; source amounts and per-unit calculations retain their precision. */
        public String displayAmount(BigDecimal amount){
            if(amount==null || currency==null || currency.isBlank()) return "—";
            try{
                var formatter=java.text.NumberFormat.getCurrencyInstance(Locale.US);
                formatter.setCurrency(java.util.Currency.getInstance(currency));
                formatter.setMinimumFractionDigits(2);formatter.setMaximumFractionDigits(2);
                formatter.setRoundingMode(java.math.RoundingMode.HALF_UP);
                return formatter.format(amount);
            }catch(IllegalArgumentException invalidCurrency){return "—";}
        }

        public BigDecimal getTotal(){return transportation==null||placement==null||!validCurrency(currency)?null:transportation.add(placement);}
        public BigDecimal getPerUnit(){return getTotal()==null||shippedUnits<=0?null:
            getTotal().divide(BigDecimal.valueOf(shippedUnits),4,java.math.RoundingMode.HALF_UP);}
        private static boolean validCurrency(String currency){
            if(currency==null||currency.isBlank())return false;
            try{java.util.Currency.getInstance(currency);return true;}
            catch(IllegalArgumentException invalid){return false;}
        }
        /** Sum complete child evidence once; absent charges are unknown, never an inferred zero. */
        static ShipmentCosts aggregate(Collection<ShipmentCosts> children,int expectedChildren){
            long units=children.stream().mapToLong(ShipmentCosts::shippedUnits).sum();
            if(expectedChildren<=0||children.size()!=expectedChildren||children.stream().anyMatch(c->!validCurrency(c.currency())))
                return new ShipmentCosts(null,null,null,units);
            String currency=children.iterator().next().currency();
            if(children.stream().anyMatch(c->!currency.equals(c.currency())))return new ShipmentCosts(null,null,null,units);
            BigDecimal transportation=children.stream().anyMatch(c->c.transportation()==null)?null:
                children.stream().map(ShipmentCosts::transportation).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal placement=children.stream().anyMatch(c->c.placement()==null)?null:
                children.stream().map(ShipmentCosts::placement).reduce(BigDecimal.ZERO,BigDecimal::add);
            return new ShipmentCosts(transportation,placement,currency,units);
        }
    }
    public record ShipmentItems(List<ShipmentItem> rows,long total,int page,int size,ShipmentCosts costs){}
    /** Posted transaction totals are counted once, never again through nested breakdown rollups.
     * Amazon's inbound service-fee records use ORDER_ID for the exact FBA shipment reference.
     * Absent fee categories stay unknown; credit/reversal signs reduce the net cost.
     */
    private static final String CHARGE_TOTALS="""
            SELECT CASE WHEN count(DISTINCT currency)=1 AND bool_and(amount IS NOT NULL AND currency IS NOT NULL) THEN sum(-amount) FILTER
                     (WHERE description='FBAPostInboundTransportation') END transportation,
                   CASE WHEN count(DISTINCT currency)=1 AND bool_and(amount IS NOT NULL AND currency IS NOT NULL) THEN sum(-amount) FILTER
                     (WHERE description='FBAInboundConvenience') END placement,
                   CASE WHEN count(DISTINCT currency)=1 THEN min(currency) END currency
            FROM amazon_financial_transactions
            WHERE tenant_id=s.tenant_id AND marketplace_connection_id=s.marketplace_connection_id AND amazon_order_id=s.shipment_id
              AND transaction_type IN ('ServiceFee','ServiceFee - Reversal')
              AND description IN ('FBAPostInboundTransportation','FBAInboundConvenience')
              AND raw_payload->>'transactionStatus' IN ('RELEASED','DEFERRED_RELEASED')
            """;
    private ShipmentCosts shipmentCosts(UUID tenant,UUID connection,String shipment){
        return jdbc.queryForObject("""
            SELECT charges.*,totals.shipped_units
            FROM (SELECT ?::uuid tenant_id,?::uuid marketplace_connection_id,?::text shipment_id) s
            LEFT JOIN LATERAL (
            """+CHARGE_TOTALS+"""
            ) charges ON true
            LEFT JOIN LATERAL (SELECT coalesce(sum(quantity_shipped),0) shipped_units FROM amazon_fba_shipment_items
                WHERE tenant_id=s.tenant_id AND marketplace_connection_id=s.marketplace_connection_id AND shipment_id=s.shipment_id) totals ON true
            """,(rs,n)->new ShipmentCosts(rs.getBigDecimal("transportation"),rs.getBigDecimal("placement"),rs.getString("currency"),rs.getLong("shipped_units")),tenant,connection,shipment);
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
    public record ParentItem(String sku,String fnsku,String title,long shipped,long received,String imageUrl){}
    public record ParentItems(List<ParentItem> rows,long total,int page,int size,ShipmentCosts costs,int shipments){}
    /** Exact SKU union across the complete verified group, including personally hidden children. */
    @Transactional(readOnly=true)
    public ParentItems parentItems(UUID tenant,UUID connection,List<String> references,String search,int requestedPage){
        scope(tenant);if(references.isEmpty()||references.size()>1000)throw new IllegalArgumentException("Review a complete shipping plan of at most 1000 shipments.");
        if(completedShipmentItems(tenant,connection,references).size()!=references.size())throw new IllegalArgumentException("Some shipment items are incomplete. No partial plan quantities are shown.");
        String q=search==null?"":search.trim(),match="%"+q.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String from="""
          FROM (SELECT seller_sku,CASE WHEN count(DISTINCT fnsku)=1 AND bool_and(fnsku IS NOT NULL) THEN min(fnsku) END fnsku,
            sum(quantity_shipped)::bigint shipped,sum(quantity_received)::bigint received
            FROM amazon_fba_shipment_items WHERE tenant_id=? AND marketplace_connection_id=? AND shipment_id=ANY(?::text[]) GROUP BY seller_sku) i
          LEFT JOIN LATERAL (SELECT l.item_name,l.image_url FROM amazon_listings l WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.seller_sku=i.seller_sku ORDER BY l.marketplace_id LIMIT 1) product ON true
          WHERE (?='' OR i.seller_sku ILIKE ? ESCAPE '!' OR i.fnsku ILIKE ? ESCAPE '!' OR product.item_name ILIKE ? ESCAPE '!')
          """;
        Object[] args={tenant,connection,references.toArray(String[]::new),tenant,connection,q,match,match,match};
        long total=jdbc.queryForObject("SELECT count(*) "+from,Long.class,args);int page=Math.min(Math.max(0,requestedPage),(int)Math.max(0,(total-1)/100));var values=new ArrayList<>(Arrays.asList(args));values.add(page*100);
        var rows=jdbc.query("SELECT i.*,product.item_name,product.image_url "+from+" ORDER BY i.seller_sku LIMIT 100 OFFSET ?",(rs,n)->new ParentItem(rs.getString("seller_sku"),rs.getString("fnsku"),rs.getString("item_name"),rs.getLong("shipped"),rs.getLong("received"),rs.getString("image_url")),values.toArray());
        var costs=jdbc.queryForObject("SELECT CASE WHEN count(DISTINCT currency)=1 AND bool_and(transportation IS NOT NULL AND currency IS NOT NULL) THEN sum(transportation) END transportation, CASE WHEN count(DISTINCT currency)=1 AND bool_and(placement IS NOT NULL AND currency IS NOT NULL) THEN sum(placement) END placement,CASE WHEN count(DISTINCT currency)=1 AND bool_and(currency IS NOT NULL) THEN min(currency) END currency, coalesce(sum(shipped),0) shipped FROM (SELECT s.shipment_id,charges.*, (SELECT sum(quantity_shipped) FROM amazon_fba_shipment_items i WHERE i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id) shipped FROM amazon_fba_shipments s LEFT JOIN LATERAL ("+CHARGE_TOTALS+") charges ON true WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.shipment_id=ANY(?::text[])) scoped",(rs,n)->new ShipmentCosts(rs.getBigDecimal("transportation"),rs.getBigDecimal("placement"),rs.getString("currency"),rs.getLong("shipped")),tenant,connection,references.toArray(String[]::new));
        return new ParentItems(rows,total,page,100,costs,references.size());
    }
    public record ShipmentPage(List<Shipment> rows,long total,int page,int size) {
        public int pages(){return Math.max(1,(int)((total+size-1)/size));}
        public boolean previous(){return page>0;}
        public boolean next(){return page+1<pages();}
    }

    @Transactional(readOnly=true)
    public ShipmentPage shipments(UUID tenant,UUID connection,String search,int requestedPage,int requestedSize) {return shipments(tenant,connection,search,requestedPage,requestedSize,null,"",null,false);}

    @Transactional(readOnly=true)
    public ShipmentPage shipments(UUID tenant,UUID connection,String search,int requestedPage,int requestedSize,String email,String status,UUID warehouse,boolean removed) {return shipments(tenant,connection,search,requestedPage,requestedSize,email,status,warehouse,removed,null);}

    private ShipmentPage shipments(UUID tenant,UUID connection,String search,int requestedPage,int requestedSize,String email,String status,UUID warehouse,boolean removed,List<String> references) {
        scope(tenant);int size=TablePaging.size(requestedSize);
        var filter=shipmentFilter(tenant,connection,search,email,status,warehouse,removed);String where=filter.sql();var arguments=new ArrayList<Object>(Arrays.asList(filter.args()));
        if(references!=null){where+=" AND s.shipment_id=ANY(?::text[])";arguments.add(references.toArray(String[]::new));size=Math.max(1,references.size());}
        Object[] args=arguments.toArray();
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) "+where,Long.class,args));
        int page=Math.min(Math.max(0,requestedPage),(int)Math.max(0,(total-1)/size));
        var params=new ArrayList<>(Arrays.asList(args));params.add(size);params.add((long)page*size);
        var rows=jdbc.query("""
            WITH selected AS (SELECT s.*
            """+where+"""
                ORDER BY coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) DESC NULLS LAST,s.shipment_id DESC LIMIT ? OFFSET ?)
            SELECT s.*,jsonb_exists(s.raw_payload,'itemsCheckedAt') items_complete,coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) display_date,totals.skus,totals.shipped,totals.received,totals.shipped_cases,totals.loose_units,charges.transportation,charges.placement,charges.currency,
                nullif(s.raw_payload#>>'{ShipFromAddress,StateOrProvinceCode}','') ship_from_state,
                nullif(s.raw_payload#>>'{ShipFromAddress,PostalCode}','') ship_from_postal_code,nullif(s.raw_payload#>>'{ShipFromAddress,City}','') ship_from_city,
                nullif(concat_ws(', ',nullif(s.raw_payload#>>'{ShipFromAddress,City}',''),
                    nullif(concat_ws(' ',nullif(s.raw_payload#>>'{ShipFromAddress,StateOrProvinceCode}',''),
                        nullif(s.raw_payload#>>'{ShipFromAddress,PostalCode}','')),''),
                    nullif(s.raw_payload#>>'{ShipFromAddress,CountryCode}','')),'') ship_from
            FROM selected s LEFT JOIN LATERAL (
                SELECT count(*) skus,coalesce(sum(quantity_shipped),0) shipped,
                    coalesce(sum(quantity_received),0) received,
                    CASE WHEN count(*)>0 AND bool_and(coalesce(quantity_shipped=0 OR quantity_in_case>0,false)) THEN sum(quantity_shipped / nullif(quantity_in_case,0)) END shipped_cases,
                    CASE WHEN count(*)>0 AND bool_and(coalesce(quantity_shipped=0 OR quantity_in_case>0,false)) THEN sum(quantity_shipped % nullif(quantity_in_case,0)) END loose_units FROM amazon_fba_shipment_items i
                WHERE i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id
                    AND i.shipment_id=s.shipment_id) totals ON true
            LEFT JOIN LATERAL (
            """+CHARGE_TOTALS+"""
            ) charges ON true
            ORDER BY coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) DESC NULLS LAST,s.shipment_id DESC
            """,(rs,n)->new Shipment(rs.getString("shipment_id"),rs.getString("shipment_name"),
                rs.getString("shipment_status"),rs.getString("destination_fulfillment_center"),
                rs.getString("ship_from"),instant(rs.getTimestamp("updated_date")),rs.getLong("skus"),
                rs.getLong("shipped"),rs.getLong("received"),instant(rs.getTimestamp("display_date")),rs.getString("ship_from_state"),new ShipmentCosts(rs.getBigDecimal("transportation"),rs.getBigDecimal("placement"),rs.getString("currency"),rs.getLong("shipped")),(Long)rs.getObject("shipped_cases"),(Long)rs.getObject("loose_units"),rs.getBoolean("items_complete"),rs.getString("ship_from_postal_code"),rs.getString("ship_from_city"),instant(rs.getTimestamp("created_date"))),params.toArray());
        return new ShipmentPage(rows,total,page,size);
    }

    private record ShipmentFilter(String sql,Object[] args){}
    private ShipmentFilter shipmentFilter(UUID tenant,UUID connection,String search,String email,String status,UUID warehouse,boolean removed){
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
        where+="""
            AND EXISTS(SELECT 1 FROM fba_shipment_user_preferences p JOIN app_users u ON u.id=p.user_id
                WHERE p.tenant_id=s.tenant_id AND p.marketplace_connection_id=s.marketplace_connection_id AND p.shipment_id=s.shipment_id AND lower(u.email)=lower(?))=?
            AND (?='' OR s.shipment_status=? OR (?='DIFFERENCE' AND jsonb_exists(s.raw_payload,'itemsCheckedAt') AND EXISTS(
                SELECT 1 FROM amazon_fba_shipment_items i WHERE i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id
                GROUP BY i.shipment_id HAVING sum(i.quantity_shipped)<>sum(i.quantity_received))))
            AND (?::uuid IS NULL OR (SELECT CASE WHEN count(*)=1 THEN min(w.id::text) END FROM warehouses w WHERE w.tenant_id=s.tenant_id AND w.status='ACTIVE'
                AND upper(w.country_code)=upper(s.raw_payload#>>'{ShipFromAddress,CountryCode}')
                AND nullif(upper(replace(btrim(w.postal_code),' ','')),'')=upper(replace(btrim(s.raw_payload#>>'{ShipFromAddress,PostalCode}'),' ','')))=?::text)
            """;
        Object[] args={tenant,connection,q,match,match,match,match,match,match,email,removed,status,status,status,warehouse,warehouse};
        return new ShipmentFilter(where,args);
    }
    @Transactional(readOnly=true)
    public List<String> matchingShipmentReferences(UUID tenant,UUID connection,String search,String email,String status,UUID warehouse,boolean removed){
        scope(tenant);var filter=shipmentFilter(tenant,connection,search,email,status,warehouse,removed);
        var ids=jdbc.queryForList("SELECT s.shipment_id "+filter.sql()+" ORDER BY coalesce(s.created_date,CASE WHEN s.raw_payload->>'LastUpdatedDate' IS NOT NULL THEN s.updated_date END) DESC NULLS LAST,s.shipment_id DESC LIMIT 50001",String.class,filter.args());
        if(ids.size()>50000)throw new IllegalArgumentException("Narrow the shipment filters before grouping this many records.");return ids;
    }
    @Transactional(readOnly=true)
    public ShipmentPage shipmentReferences(UUID tenant,UUID connection,List<String> references,String email,boolean removed){
        if(references.isEmpty())return new ShipmentPage(List.of(),0,0,25);
        if(references.size()>1000)throw new IllegalArgumentException("Narrow the filters to show at most 1000 matching children on one page.");
        return shipments(tenant,connection,"",0,references.size(),email,"",null,removed,references);
    }
    public record CompletedShipmentItems(Set<String> skus,long shipped,long received){}
    @Transactional(readOnly=true)
    public Map<String,CompletedShipmentItems> completedShipmentItems(UUID tenant,UUID connection,List<String> references){
        scope(tenant);if(references.isEmpty())return Map.of();if(references.size()>1000)throw new IllegalArgumentException("Narrow the shipment filters.");
        var args=new ArrayList<Object>(List.of(tenant,connection));args.addAll(references);var result=new HashMap<String,CompletedShipmentItems>();
        var evidence=jdbc.queryForList("SELECT s.shipment_id,s.raw_payload->>'itemEvidenceVersion' evidence_version,(s.raw_payload->'itemEvidence')::text item_evidence,s.raw_payload->>'itemsCheckedAt' observed_at,i.seller_sku,i.fnsku,i.quantity_shipped,i.quantity_received FROM amazon_fba_shipments s LEFT JOIN amazon_fba_shipment_items i ON i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.shipment_id IN ("+String.join(",",Collections.nCopies(references.size(),"?"))+")",args.toArray());
        var groups=new LinkedHashMap<String,List<Map<String,Object>>>();for(var row:evidence)groups.computeIfAbsent(row.get("shipment_id").toString(),k->new ArrayList<>()).add(row);
        for(var entry:groups.entrySet())if(FbaChildObservations.observed(entry.getValue().getFirst().get("observed_at"))!=null&&FbaChildObservations.completedEvidence(entry.getValue())){var skus=new HashSet<String>();boolean valid=true;long sent=0,receivedUnits=0;for(var row:entry.getValue()){if(!(row.get("seller_sku") instanceof String sku)||sku.isBlank()||!skus.add(sku)||!(row.get("quantity_shipped") instanceof Number shipped)||shipped.longValue()<0||!(row.get("quantity_received") instanceof Number received)||received.longValue()<0)valid=false;else{sent=Math.addExact(sent,shipped.longValue());receivedUnits=Math.addExact(receivedUnits,received.longValue());}}if(valid)result.put(entry.getKey(),new CompletedShipmentItems(Set.copyOf(skus),sent,receivedUnits));}return result;
    }
    private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private static Instant instant(java.sql.Timestamp value){return value==null?null:value.toInstant();}
    private static String display(Instant value){return value==null?"Not supplied":java.time.format.DateTimeFormatter
        .ofPattern("MM/dd/yyyy hh:mm a 'UTC'",Locale.US).withZone(java.time.ZoneOffset.UTC).format(value);}
}
