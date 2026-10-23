package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import com.nextaicommerce.platform.fba.FbaReadRepository;
import com.nextaicommerce.platform.profit.*;
import tools.jackson.databind.ObjectMapper;

class MarketplaceSkuCsvExportTest {
    final MarketplaceSkuCsvExport exporter=new MarketplaceSkuCsvExport(null,null,null,null,null,new ObjectMapper());
    static final Instant T=Instant.parse("2026-10-19T12:00:00Z");
    static MarketplaceSkuRepository.SkuView sku(Map<String,Object> values){
        try{var fields=MarketplaceSkuRepository.SkuView.class.getRecordComponents();var types=Arrays.stream(fields).map(f->f.getType()).toArray(Class<?>[]::new);Object[] args=new Object[fields.length];for(int i=0;i<fields.length;i++)args[i]=values.getOrDefault(fields[i].getName(),fields[i].getType()==long.class?0L:null);return MarketplaceSkuRepository.SkuView.class.getDeclaredConstructor(types).newInstance(args);}catch(Exception e){throw new AssertionError(e);}
    }
    MarketplaceSkuRepository.SkuView fba(){return sku(Map.of("sellerSku","0000147","marketplaceId","US","fulfillmentChannel","AMAZON_NA","listingStatus","ACTIVE","week4Sales",1L,"week3Sales",2L,"week2Sales",3L,"currentWeekSales",4L,"fourWeekOrders",5L));}
    @Test void scalarCellsPreserveIdentifiersUnicodeQuotesAndNegativeNumbers(){
        assertThat(MarketplaceSkuCsvExport.cell("0000147")).isEqualTo("\"0000147\"");
        assertThat(MarketplaceSkuCsvExport.cell(" title, \"Crème\"\nline ")).isEqualTo("\" title, \"\"Crème\"\"\nline \"");
        for(String s:List.of("=SUM(1)","+1","-ID","@A1"," \t=cmd","\rvalue"))assertThat(MarketplaceSkuCsvExport.cell(s)).startsWith("\"'");
        assertThat(MarketplaceSkuCsvExport.cell(new BigDecimal("-12.34"))).isEqualTo("\"-12.34\"");assertThat(MarketplaceSkuCsvExport.cell(null)).isEqualTo("\"\"");
    }
    @Test void incompleteStockNeverBecomesZeroOrDoubleCounted(){
        var st=new FbaReadRepository.Stock("0000147",27,0,160,null,0,0,T,BigDecimal.TEN,null,null,null,0);
        var row=exporter.project(fba(),st,List.of(),null,null,Map.of(),0,T).fields();
        assertThat(row.get("Available units")).isEqualTo(27);assertThat(row.get("Inbound units")).isNull();assertThat(row.get("Inbound units state")).isEqualTo("PENDING");assertThat(row.get("Reserved units")).isEqualTo(0);assertThat(row.get("FC transfer units")).isEqualTo(0);assertThat(row.get("Four-week units")).isEqualTo(10L);
        assertThat(row.get("Week 1 start UTC")).isEqualTo(T.minusSeconds(28*86400));assertThat(row.get("Week 4 end UTC")).isEqualTo(T);assertThat(row.get("Estimated profit per listing unit")).isNull();assertThat(row.get("Profit calculation state")).isEqualTo("Unknown");
    }
    @Test void merchantUnknownAndNotApplicableAreDistinct(){
        var s=sku(Map.of("sellerSku","x","marketplaceId","US","fulfillmentChannel","DEFAULT","listingStatus","ACTIVE"));
        var row=exporter.project(s,null,List.of(),null,null,Map.of(),0,T).fields();assertThat(row.get("Available units")).isNull();assertThat(row.get("Available units state")).isEqualTo("UNKNOWN");assertThat(row.get("Inbound units state")).isEqualTo("NOT_APPLICABLE");assertThat(row.get("Fulfillment")).isEqualTo("Unknown");
    }
    @Test void mappingComponentsRemainScalarAndCurrencyRefundsDoNotMultiplyRows(){
        var p=new MarketplaceSkuRepository.MappingComponentView(UUID.randomUUID(),"Tea","V-1","0001",new BigDecimal("2"));
        String history="""
          [{"currency":"USD","amount":-10,"units":1,"unknownAmounts":0,"unknownUnits":0,"totalOrders":2,"unknownOrders":1},
           {"currency":"USD","amount":-5,"units":1,"unknownAmounts":1,"unknownUnits":0,"totalOrders":2,"unknownOrders":1},
           {"currency":"CAD","amount":-7,"units":null,"unknownAmounts":0,"unknownUnits":1,"totalOrders":2,"unknownOrders":1}]
          """;
        var row=exporter.project(fba(),null,List.of(p),null,history,Map.of(),1,T).fields();assertThat(row.get("Mapped item 1 account SKU")).isEqualTo("0001");assertThat(row.get("Mapped item 1 vendor item code")).isEqualTo("V-1");assertThat(row.get("Refund amount USD (4 weeks)")).isNull();assertThat(row.get("Refund amount USD (4 weeks) state")).isEqualTo("UNKNOWN");assertThat(row.get("Refund units USD (4 weeks)")).isEqualTo(new BigDecimal("2"));assertThat(row.get("Refunded orders (4 weeks)")).isEqualTo(2L);assertThat(row.get("Refund records without order ID")).isEqualTo(1L);
    }
    @Test void profitUsesExistingTypedBreakdownAndState(){
        var breakdown=new ProfitEstimate.Breakdown(null,null,null,null,null,new BigDecimal("-2.50"));
        var view=new ProfitRepository.View("0000147","SKU","Tea","USD",LocalDate.of(2026,10,19),List.of(),List.of(),breakdown,"Existing estimate note",null);
        var row=exporter.project(fba(),null,List.of(),view,null,Map.of(),0,T).fields();assertThat(row.get("Estimated profit per listing unit")).isEqualTo(new BigDecimal("-2.50"));assertThat(row.get("Profit calculation state")).isEqualTo("Estimated");assertThat(row.get("Profit calculation note")).isEqualTo(view.note());
    }
    @Test void schemaHasStableHeadersAndExplicitGroupExpansion(){
        var groups=MarketplaceSkuCsvExport.emptySchema(3,new TreeSet<>(List.of("USD","CAD")));assertThat(groups.get("product-details")).contains("Mapped item 3 code","Mapped item 3 quantity");assertThat(groups.get("4w-sales")).contains("Week 1 units (oldest)","Week 4 units (latest)").doesNotContain("Week 4 end UTC","Refund amount CAD (4 weeks)");assertThat(groups.get("profit")).containsExactly("Estimated profit per SKU","Profit currency");
    }
    @Test void invalidColumnsFiltersAndCancellationFailClosed(){
        assertThatThrownBy(()->MarketplaceSkuCsvExport.validate(new MarketplaceSkuCsvExport.Request("","ALL","status","asc",Map.of(),List.of("actions"),Map.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->MarketplaceSkuCsvExport.check(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(MarketplaceSkuCsvExport.matches("27 units",Map.of("type","number","min","28"))).isFalse();assertThat(MarketplaceSkuCsvExport.matches("Crème Tea","crème")).isTrue();
    }
    @Test void byteLimitFailsBeforeWritingAnyOverLimitBytes(){
        var full=new ByteArrayOutputStream(){@Override public synchronized int size(){return MarketplaceSkuCsvExport.MAX_BYTES;}};
        assertThatThrownBy(()->MarketplaceSkuCsvExport.write(full,"x")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50 MiB");assertThat(full.toByteArray()).isEmpty();
    }
    @Test void conditionSchemaIsNotDuplicated(){
        var g=MarketplaceSkuCsvExport.emptySchema(2,List.of());var order=new LinkedHashSet<String>();order.addAll(g.get("status"));order.addAll(g.get("condition"));assertThat(order.stream().filter(x->x.equals("Condition")).count()).isEqualTo(1);
        assertThatThrownBy(()->exporter.project(fba(),null,List.of(),null,"{}",Map.of(),0,T)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void textAndNumericPredicatesRetainExistingFilterBehavior(){
        assertThat(MarketplaceSkuCsvExport.matches("$1,234.50 Buy Box $2,000",Map.of("type","number","min","1200","max","1300"))).isTrue();
        assertThat(MarketplaceSkuCsvExport.matches("Snapshot: 10/19/26",Map.of("type","date","min","2026-10-18","max","2026-10-20"))).isTrue();
        assertThat(MarketplaceSkuCsvExport.matches("Active FBA New","inactive")).isFalse();
    }
    @Test void threeComponentsRetainDisplayedOrderInExportAndFilters(){
        var p1=new MarketplaceSkuRepository.MappingComponentView(new UUID(0,1),"One","V1","0001",BigDecimal.ONE);
        var p2=new MarketplaceSkuRepository.MappingComponentView(new UUID(0,2),"Two","V2","0002",new BigDecimal("2"));
        var p3=new MarketplaceSkuRepository.MappingComponentView(new UUID(0,3),"Three","V3","0003",new BigDecimal("3"));
        var s=sku(Map.of("sellerSku","BUNDLE","marketplaceId","US","mappedProduct","Bundle"));
        var projected=exporter.project(s,null,List.of(p3,p1,p2),null,null,Map.of(),0,T);
        assertThat(projected.fields().get("Mapped item count")).isEqualTo(3);assertThat(projected.fields().get("Mapped item 1 account SKU")).isEqualTo("0003");assertThat(projected.fields().get("Mapped item 3 quantity per listing unit")).isEqualTo(new BigDecimal("2"));assertThat(projected.filters().get("product-details")).contains("V3 × 3 V1 × 1 V2 × 2");
    }
    @Test void zeroShippingRequiresCompleteCurrencyEvidence(){
        var s=sku(Map.of("sellerSku","x","marketplaceId","US","fourWeekShippingAverage",BigDecimal.ZERO));
        var known=exporter.project(s,null,List.of(),null,null,Map.of("shipping_count",1L,"shipping_complete",true,"shipping_currency","USD","fulfillment_channel","DEFAULT"),0,T).fields();assertThat(known.get("Average customer shipping per unit (4 weeks)")).isEqualTo(BigDecimal.ZERO);assertThat(known.get("Customer shipping currency")).isEqualTo("USD");
        var unknown=exporter.project(s,null,List.of(),null,null,Map.of("shipping_count",1L,"shipping_complete",true,"fulfillment_channel",""),0,T).fields();assertThat(unknown.get("Average customer shipping per unit (4 weeks)")).isNull();assertThat(unknown.get("Fulfillment")).isEqualTo("Unknown");
    }
    @Test void ownerAmendmentRemovesAllCompanionQualityHeadersWithoutReplacingThem(){
        var groups=MarketplaceSkuCsvExport.emptySchema(3,List.of("CAD","USD"));
        var headers=groups.values().stream().flatMap(List::stream).toList();
        assertThat(headers).noneMatch(h->h.toLowerCase().endsWith(" state")||h.startsWith("Refund records")||h.equals("Sales coverage"));
        assertThat(headers).contains("Listing status","Local inventory plan","Fulfillment method","Condition","Estimated profit per SKU","Profit currency");
        var bytes=new ByteArrayOutputStream();MarketplaceSkuCsvExport.writeRecord(bytes,List.of("Available units","Inbound units"));MarketplaceSkuCsvExport.writeRecord(bytes,Arrays.asList(0,null));assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).endsWith("\"0\",\"\"\r\n");
    }
    @Test void visibleSchemaExcludesHiddenDetailsAndOptionalGroups(){
        var g=MarketplaceSkuCsvExport.emptySchema(1,List.of("USD"),false);
        assertThat(g.get("product-details")).containsExactly("Product title","Seller SKU","ASIN","Parent SKU","Parent ASIN","Mapped item 1 code","Mapped item 1 quantity");
        assertThat(g.get("available")).containsExactly("Available units");
        assertThat(MarketplaceSkuCsvExport.emptySchema(1,List.of(),true).get("available")).containsExactly("Available units","Inbound units","FC transfer units","Reserved units");
        var headers=g.values().stream().flatMap(List::stream).toList();
        assertThat(headers).noneMatch(h->h.contains("observed")||h.contains("UTC")||h.contains("Refund")||h.contains("fee")||h.contains("note")||h.contains("account SKU")||h.contains("vendor item"));
        assertThat(headers).doesNotContain("Marketplace ID","Mapping status","Mapped item count","Operational status","Inventory freshness","Warehouse available SKU units");
    }
    @Test void mappingUsesOnlyDisplayedCodeAndPreservesLeadingZeros(){
        var a=new MarketplaceSkuRepository.MappingComponentView(new UUID(0,9),"A","V9","hidden",BigDecimal.ONE);
        var b=new MarketplaceSkuRepository.MappingComponentView(new UUID(0,1),"B",null,"0001",new BigDecimal("2"));
        var row=exporter.project(fba(),null,List.of(a,b),null,null,Map.of(),0,T).fields();
        assertThat(row.get("Mapped item 1 code")).isEqualTo("V9");
        assertThat(row.get("Mapped item 2 code")).isEqualTo("0001");
        assertThat(row.get("Mapped item 2 quantity")).isEqualTo(new BigDecimal("2"));
    }

    @Test void marketplaceSafetyMasksParentAndUnresolvedStockWithoutChangingVisibleSchema(){
        var st=new FbaReadRepository.Stock("0000147",23,0,160,0,0,0,T,BigDecimal.TEN,null,null,null,0);
        var child=exporter.project(fba(),st,List.of(),null,null,Map.of(),0,T);
        var d=new MarketplaceInventoryEligibility.Decision("0000147","US","ELIGIBLE",true,"VERIFIED",AmazonVariationRelationshipProvider.State.CHILD);
        MarketplaceSkuCsvExport.applyInventoryPolicy(child,d);assertThat(child.fields().get("Available units")).isEqualTo(23);
        var parent=exporter.project(fba(),st,List.of(),null,null,Map.of(),0,T);
        MarketplaceSkuCsvExport.applyInventoryPolicy(parent,new MarketplaceInventoryEligibility.Decision("0000147","US","PARENT",false,"NON_SELLABLE_PARENT",AmazonVariationRelationshipProvider.State.PARENT));
        assertThat(parent.fields().get("Available units")).isNull();assertThat(parent.fields().get("Inbound units")).isNull();assertThat(parent.fields().get("Four-week units")).isNull();assertThat(parent.fields().get("Listing status")).isEqualTo("Parent SKU");assertThat(parent.fields().get("FNSKU")).isNull();assertThat(parent.fields().get("Condition")).isNull();assertThat(parent.filters().get("status")).isEqualTo("Parent SKU");assertThat(parent.filters().get("fnsku")).isEqualTo("Not applicable");
        assertThat(st.available()).isEqualTo(23);assertThat(st.totalInbound()).isEqualTo(160);
    }

}
