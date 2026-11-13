package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import com.nextaicommerce.platform.receiving.InventoryRepository;
import com.nextaicommerce.platform.marketplace.MarketplaceWarehouseAvailability.*;

class MarketplaceWarehouseAvailabilityTest {
    final UUID item=UUID.randomUUID(),other=UUID.randomUUID(),loc=UUID.randomUUID(),secondLoc=UUID.randomUUID(),secondWarehouse=UUID.randomUUID(),warehouse=UUID.randomUUID();
    static BigDecimal n(String s){return new BigDecimal(s);}
    Product product(String sku,UUID id,String size){return new Product(sku,id,"Product "+sku,"CODE-"+sku,size==null?null:n(size));}
    Position position(UUID id,UUID l,String expiry,String qty,String reserved){return new Position(id,l,expiry==null?null:LocalDate.parse(expiry),n(qty),n(reserved));}
    Place place(UUID id,UUID l,UUID w,String label){return new Place(id,l,w,label,"Warehouse "+label,true);}
    @Test void netsBatchesAndNullExpiryDemandOnceBeforeClampingPerWarehouse(){
        var positions=List.of(position(item,loc,null,"40","10"),position(item,loc,null,"40","10"),position(item,loc,null,"0","15"),
            position(item,loc,"2027-01-01","5","8"),position(item,secondLoc,"2027-02-01","2","0"));
        var zeroLoc=UUID.randomUUID();var data=MarketplaceWarehouseAvailability.aggregate(List.of("sku"),List.of(product("sku",item,"12")),positions,
            List.of(place(item,loc,warehouse,"77377 TX"),place(item,secondLoc,warehouse,"77377 TX"),place(item,zeroLoc,secondWarehouse,"10001 NY")));
        var stock=data.get("sku");assertThat(stock.state()).isEqualTo("AVAILABLE");
        assertThat(stock.lines()).hasSize(2);assertThat(stock.lines().stream().filter(l->l.warehouseId().equals(warehouse)).findFirst().orElseThrow().eaches()).isEqualByComparingTo("24");
        assertThat(stock.lines().stream().filter(l->l.warehouseId().equals(warehouse)).findFirst().orElseThrow().quantityDisplay()).isEqualTo("24 each · 2 cases");
        assertThat(stock.lines().stream().filter(l->l.warehouseId().equals(secondWarehouse)).findFirst().orElseThrow().eaches()).isZero();
    }
    @Test void bundleProductsStaySeparateAndMissingLocationOrMappingIsNotZero(){
        var p=MarketplaceWarehouseAvailability.aggregate(List.of("bundle","unmapped","no-location"),List.of(product("bundle",item,"12"),product("bundle",other,"2.5"),product("no-location",UUID.randomUUID(),null)),
            List.of(position(item,loc,null,"24","0"),position(other,loc,null,"10","0")),List.of(place(item,loc,warehouse,"77377 TX")));
        assertThat(p.get("bundle").multipleProducts()).isTrue();assertThat(p.get("bundle").lines()).extracting(Line::quantityDisplay).containsExactlyInAnyOrder("24 each · 2 cases","10 each");
        assertThat(p.get("unmapped").state()).isEqualTo("NOT_MAPPED");assertThat(p.get("no-location").state()).isEqualTo("UNAVAILABLE");assertThat(p.get("no-location").lines()).isEmpty();
        var missing=MarketplaceWarehouseAvailability.aggregate(List.of("sku"),List.of(product("sku",item,"12")),List.of(position(item,UUID.randomUUID(),null,"5","0")),List.of());
        assertThat(missing.get("sku").state()).isEqualTo("UNAVAILABLE");
    }
    @Test void caseEquivalentsUseOnlyReliableCurrentCatalogueCases(){
        for(BigDecimal size:Arrays.asList(null,n("0"),n("1"),n("2.5")))assertThat(new Line(item,"Product","CODE",warehouse,"ZIP","Name",n("18"),size).quantityDisplay()).isEqualTo("18 each");
        var line=new Line(item,"Product","CODE",warehouse,"ZIP","Name",n("18"),n("12"));assertThat(line.quantityDisplay()).isEqualTo("18 each · 1 case + 6 each");assertThat(line.quantityTitle()).contains("not sealed-carton");
    }
    record Read(String sql,List<Object> args){}
    static class CaptureJdbc extends JdbcTemplate {
        final List<Read> reads=new ArrayList<>();List<Product> products=List.of();List<Place> places=List.of();
        @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){reads.add(new Read(sql,Arrays.asList(args)));return null;}
        @SuppressWarnings("unchecked") @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){reads.add(new Read(sql,Arrays.asList(args)));return (List<T>)(sql.contains("FROM marketplace_sku_mappings")?products:places);}
    }
    @Test void batchesUseTenantConnectionAndOnlyMappedItemIdsWithLegacyFallback(){
        UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID();var jdbc=new CaptureJdbc();jdbc.products=List.of(product("sku",item,"12"));jdbc.places=List.of(place(item,loc,warehouse,"77377 TX"));var inventory=mock(InventoryRepository.class);
        when(inventory.inventory(tenant,List.of(item))).thenReturn(List.of());var service=new MarketplaceWarehouseAvailability(jdbc,inventory);assertThat(service.load(tenant,connection,List.of("sku","sku")).get("sku").lines().getFirst().eaches()).isZero();
        verify(inventory).inventory(tenant,List.of(item));assertThat(jdbc.reads).hasSize(3);var mapping=jdbc.reads.get(1);assertThat(mapping.args()).containsExactly(tenant,connection,"sku");assertThat(mapping.sql()).contains("mapping.status='ACTIVE'","part.tenant_id=mapping.tenant_id","NOT EXISTS","item.tenant_id=mapping.tenant_id");var location=jdbc.reads.get(2);assertThat(location.args()).containsExactly(item,tenant);assertThat(location.sql()).contains("warehouse.tenant_id=location.tenant_id","assignment.tenant_id=location.tenant_id","location.tenant_id=?");
        jdbc.reads.clear();assertThat(service.load(tenant,connection,List.of())).isEmpty();assertThat(jdbc.reads).isEmpty();
        assertThatThrownBy(()->service.load(tenant,connection,java.util.stream.IntStream.range(0,1001).mapToObj(Integer::toString).toList())).isInstanceOf(IllegalArgumentException.class);assertThat(jdbc.reads).isEmpty();
    }
    @Test void onlyFbaNonParentKeysAndExplicitExportStatesAreUsed(){
        var fba=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","fba","fulfillmentChannel","AMAZON_NA"));var parent=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","parent","fulfillmentChannel","AMAZON_NA"));var fbm=MarketplaceSkuCsvExportTest.sku(Map.of("sellerSku","fbm","fulfillmentChannel","DEFAULT"));
        assertThat(MarketplaceWarehouseAvailability.eligibleKeys(List.of(fba,parent,fbm),Map.of("parent",new MarketplaceInventoryEligibility.Decision("parent","US","PARENT",false,"",null)))).containsExactly("fba");
        assertThat(MarketplaceSkuCsvExport.warehouseText(false,false,null)).isEqualTo("Not applicable");assertThat(MarketplaceSkuCsvExport.warehouseText(true,true,null)).isEqualTo("Not applicable");assertThat(MarketplaceSkuCsvExport.warehouseText(true,false,null)).isEqualTo("Unavailable");assertThat(MarketplaceSkuCsvExport.warehouseText(true,false,Availability.notMapped())).isEqualTo("Not mapped");assertThat(MarketplaceSkuCsvExport.emptySchema(1,List.of()).get("warehouse-available")).hasSize(1);
    }
}
