package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.receiving.InventoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only physical eaches, not Amazon fulfillment stock or sellable SKU/bundle units. */
@Service
public class MarketplaceWarehouseAvailability {
    private final JdbcTemplate jdbc;
    private final InventoryRepository inventory;
    public MarketplaceWarehouseAvailability(JdbcTemplate jdbc, InventoryRepository inventory) {
        this.jdbc=jdbc;this.inventory=inventory;
    }
    public record Product(String sku,UUID itemId,String name,String code,BigDecimal caseSize) {}
    public record Place(UUID itemId,UUID locationId,UUID warehouseId,String warehouse,String warehouseName,boolean assigned) {}
    public record Position(UUID itemId,UUID locationId,LocalDate expiry,BigDecimal quantity,BigDecimal reserved) {}
    public record Line(UUID itemId,String product,String code,UUID warehouseId,String warehouse,String warehouseName,BigDecimal eaches,BigDecimal caseSize) {
        public String quantityDisplay(){
            String each=number(eaches)+" each";
            if(caseSize==null||caseSize.compareTo(BigDecimal.ONE)<=0||caseSize.stripTrailingZeros().scale()>0)return each;
            BigDecimal cases=eaches.divideToIntegralValue(caseSize),loose=eaches.remainder(caseSize);
            return each+" · "+number(cases)+(cases.compareTo(BigDecimal.ONE)==0?" case":" cases")+(loose.signum()>0?" + "+number(loose)+" each":"");
        }
        public String quantityTitle(){return caseSize!=null&&caseSize.compareTo(BigDecimal.ONE)>0&&caseSize.stripTrailingZeros().scale()<=0
            ?number(caseSize)+" each per current catalogue case; case equivalent, not sealed-carton count"
            :"Case size unavailable or single-unit pack; quantity shown in eaches";}
    }
    public record Availability(String state,List<Line> lines,boolean multipleProducts) {
        public Availability {lines=List.copyOf(lines);}
        static Availability notMapped(){return new Availability("NOT_MAPPED",List.of(),false);}
    }
    static List<String> eligibleKeys(Collection<MarketplaceSkuRepository.SkuView> rows,Map<String,MarketplaceInventoryEligibility.Decision> decisions){
        return rows.stream().filter(MarketplaceSkuRepository.SkuView::fba)
            .filter(row->decisions==null||decisions.get(row.sellerSku())==null||!decisions.get(row.sellerSku()).parent())
            .map(MarketplaceSkuRepository.SkuView::sellerSku).filter(Objects::nonNull).distinct().toList();
    }
    private static String number(BigDecimal value){return value.stripTrailingZeros().toPlainString();}
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
    @Transactional(readOnly=true)
    public Map<String,Availability> load(UUID tenant,UUID connection,Collection<String> requested) {
        Objects.requireNonNull(tenant);Objects.requireNonNull(connection);
        var skus=requested.stream().filter(Objects::nonNull).distinct().toList();
        if(skus.isEmpty())return Map.of();
        if(skus.size()>1000)throw new IllegalArgumentException("Warehouse availability exceeds page budget");
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        var args=new ArrayList<Object>();args.add(tenant);args.add(connection);args.addAll(skus);
        var products=jdbc.query("""
            SELECT mapping.marketplace_sku,item.id,coalesce(item.display_name,product.canonical_name) product_name,
                   coalesce(nullif(item.account_sku,''),item.id::text) code,product.units_per_case
            FROM marketplace_sku_mappings mapping
            JOIN LATERAL (
              SELECT part.account_catalog_item_id FROM marketplace_sku_mapping_components part
              WHERE part.tenant_id=mapping.tenant_id AND part.marketplace_sku_mapping_id=mapping.id
              UNION ALL
              SELECT mapping.account_catalog_item_id WHERE mapping.account_catalog_item_id IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM marketplace_sku_mapping_components part
                  WHERE part.tenant_id=mapping.tenant_id AND part.marketplace_sku_mapping_id=mapping.id)
            ) component ON true
            JOIN account_catalog_items item ON item.tenant_id=mapping.tenant_id AND item.id=component.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=item.global_product_id
            WHERE mapping.tenant_id=? AND mapping.marketplace_connection_id=? AND mapping.status='ACTIVE'
              AND mapping.marketplace_sku IN (
            """+marks(skus.size())+") ORDER BY mapping.marketplace_sku,item.id",
            (r,n)->new Product(r.getString(1),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getBigDecimal(5)),args.toArray());
        var ids=products.stream().map(Product::itemId).distinct().toList();
        if(ids.isEmpty()){var empty=new LinkedHashMap<String,Availability>();skus.forEach(s->empty.put(s,Availability.notMapped()));return empty;}
        if(ids.size()>10_000)throw new IllegalArgumentException("Warehouse mapping exceeds product budget");
        var positions=inventory.inventory(tenant,ids).stream().map(p->new Position(p.itemId(),p.locationId(),p.expirationDate(),p.quantity(),p.reservedQuantity())).toList();
        var locations=positions.stream().map(Position::locationId).filter(Objects::nonNull).distinct().toList();
        if(locations.size()>10_000)throw new IllegalArgumentException("Warehouse availability exceeds location budget");
        var placeArgs=new ArrayList<Object>();placeArgs.addAll(ids);placeArgs.add(tenant);placeArgs.addAll(locations);
        var places=jdbc.query("""
            SELECT assignment.account_catalog_item_id,location.id,warehouse.id,
                   coalesce(nullif(concat_ws(' ',nullif(btrim(warehouse.postal_code),''),nullif(btrim(warehouse.state_or_province),'')),''),warehouse.name) warehouse_label,warehouse.name warehouse_name,
                   location.status='ACTIVE' AND warehouse.status='ACTIVE' assigned
            FROM warehouse_locations location
            JOIN warehouses warehouse ON warehouse.tenant_id=location.tenant_id AND warehouse.id=location.warehouse_id
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=location.tenant_id
              AND assignment.location_id=location.id AND assignment.account_catalog_item_id IN (
            """+marks(ids.size())+") WHERE location.tenant_id=? AND (assignment.account_catalog_item_id IS NOT NULL"
                +(locations.isEmpty()?"":" OR location.id IN ("+marks(locations.size())+")")+")",
            (r,n)->new Place(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getBoolean(6)),placeArgs.toArray());
        return aggregate(skus,products,positions,places);
    }
    private record PositionKey(UUID item,UUID location,LocalDate expiry) {}
    private record WarehouseKey(UUID item,UUID warehouse) {}
    /** Inventory's zero-quantity demand overlay can repeat a NULL-expiry reservation join. Net each physical position once. */
    static Map<String,Availability> aggregate(List<String> skus,List<Product> products,List<Position> positions,List<Place> places) {
        var metadata=new LinkedHashMap<UUID,Place>();places.forEach(p->metadata.put(p.locationId(),p));
        var distinct=new LinkedHashMap<PositionKey,Position>();
        for(var p:positions){var key=new PositionKey(p.itemId(),p.locationId(),p.expiry());distinct.merge(key,p,(a,b)->new Position(a.itemId(),a.locationId(),a.expiry(),a.quantity().max(b.quantity()),a.reserved().max(b.reserved())));}
        var totals=new LinkedHashMap<WarehouseKey,BigDecimal>();var unknown=new HashSet<UUID>();
        for(var p:distinct.values()){
            var place=metadata.get(p.locationId());if(place==null){unknown.add(p.itemId());continue;}
            totals.merge(new WarehouseKey(p.itemId(),place.warehouseId()),p.quantity().subtract(p.reserved()),BigDecimal::add);
        }
        for(var p:places)if(p.assigned()&&p.itemId()!=null)totals.putIfAbsent(new WarehouseKey(p.itemId(),p.warehouseId()),BigDecimal.ZERO);
        var result=new LinkedHashMap<String,Availability>();
        for(String sku:skus){var mapped=products.stream().filter(p->p.sku().equals(sku)).collect(java.util.stream.Collectors.toMap(Product::itemId,p->p,(a,b)->a,LinkedHashMap::new));
            if(mapped.isEmpty()){result.put(sku,Availability.notMapped());continue;}
            var lines=new ArrayList<Line>();boolean unavailable=false;
            for(var product:mapped.values()){
                if(unknown.contains(product.itemId()))unavailable=true;
                var keys=totals.keySet().stream().filter(k->k.item().equals(product.itemId())).toList();if(keys.isEmpty())unavailable=true;
                for(var key:keys){var place=places.stream().filter(p->p.warehouseId().equals(key.warehouse())).findFirst().orElseThrow();lines.add(new Line(product.itemId(),product.name(),product.code(),key.warehouse(),place.warehouse(),place.warehouseName(),totals.get(key).max(BigDecimal.ZERO),product.caseSize()));}
            }
            lines.sort(Comparator.comparing(Line::warehouse).thenComparing(Line::product).thenComparing(l->l.itemId().toString()));
            result.put(sku,new Availability(unavailable?"UNAVAILABLE":"AVAILABLE",lines,mapped.size()>1));
        }
        return result;
    }
}
