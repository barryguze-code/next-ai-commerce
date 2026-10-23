package com.nextaicommerce.platform.marketplace;

import com.nextaicommerce.platform.fba.FbaReadRepository;
import com.nextaicommerce.platform.orders.SkuRefundHistory;
import com.nextaicommerce.platform.profit.ProfitRepository;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Local read-only export. Never calls synchronization, providers or inventory writers. */
@Service
public class MarketplaceSkuCsvExport {
    static final int MAX_ROWS=10_000, MAX_BYTES=50*1024*1024;
    static final Set<String> GROUPS=Set.of("product-details","status","price-shipping","available","4w-sales","shipping-template","condition","fnsku","inbound","reserved","unfulfillable","profit");
    final MarketplaceSkuRepository listings; final FbaReadRepository stock; final SkuRefundHistory refunds;
    final ProfitRepository profits; final JdbcTemplate jdbc; final ObjectMapper json;
    public record Request(String q,String status,String sort,String direction,Map<String,String> predicates,List<String> columns,Map<String,Object> columnFilters) {}
    public record Artifact(byte[] bytes,int rows,Instant asOf) {}
    record Row(LinkedHashMap<String,Object> fields,Map<String,String> filters) {}
    public MarketplaceSkuCsvExport(MarketplaceSkuRepository listings,FbaReadRepository stock,SkuRefundHistory refunds,ProfitRepository profits,JdbcTemplate jdbc,ObjectMapper json){this.listings=listings;this.stock=stock;this.refunds=refunds;this.profits=profits;this.jdbc=jdbc;this.json=json;}

    static void validate(Request r){
        if(r==null||r.q()==null||r.q().length()>1000||r.columns()==null||r.columns().size()>12||new HashSet<>(r.columns()).size()!=r.columns().size()||!GROUPS.containsAll(r.columns())||r.columnFilters()==null||r.columnFilters().size()>12||!GROUPS.containsAll(r.columnFilters().keySet()))throw new IllegalArgumentException("Invalid export columns or filters.");
        if(!r.columns().contains("product-details"))throw new IllegalArgumentException("Product details are required.");
        for(Object value:r.columnFilters().values())if(value instanceof String s){if(s.length()>1000)throw new IllegalArgumentException("Filter too long.");}else if(value instanceof Map<?,?> m){if(!Set.of("type","min","max").containsAll(m.keySet())||!Set.of("number","date").contains(m.get("type")))throw new IllegalArgumentException("Invalid column filter.");for(Object v:m.values())if(!(v instanceof String)||v.toString().length()>100)throw new IllegalArgumentException("Invalid filter value.");for(String key:List.of("min","max")){Object v=m.get(key);if(v==null||v.toString().isEmpty())continue;try{if("date".equals(m.get("type")))LocalDate.parse(v.toString());else if(!Double.isFinite(Double.parseDouble(v.toString())))throw new IllegalArgumentException();}catch(RuntimeException ex){throw new IllegalArgumentException("Invalid column filter bound.");}}}else throw new IllegalArgumentException("Invalid column filter.");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=60)
    public Artifact generate(UUID tenant,UUID connection,Request request){
        validate(request);long deadline=System.nanoTime()+60_000_000_000L;
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        Instant anchor=jdbc.queryForObject("SELECT transaction_timestamp()",java.sql.Timestamp.class).toInstant();
        // Refuse mismatched DST/calendar-day windows rather than change listing semantics.
        for(int days:List.of(7,14,21,28)){
            Instant sqlBoundary=jdbc.queryForObject("SELECT transaction_timestamp() - (? * interval '1 day')",java.sql.Timestamp.class,days).toInstant();
            if(!sqlBoundary.equals(anchor.minusSeconds((long)days*86400)))throw new IllegalArgumentException("Sales and refund window timezone mismatch. Export stopped.");
        }
        var inventoryPolicy=listings.inventoryPolicy(tenant,connection);
        var parentContexts=listings.relationshipPolicy(tenant,connection).children();
        List<Row> output=new ArrayList<>();Set<String> identities=new HashSet<>();int components=1;boolean hasFba=false;SortedSet<String> currencies=new TreeSet<>();Long expectedTotal=null;
        for(int page=0;;page++){
            check(deadline);
            var batch=listings.list(tenant,connection,request.q(),request.status(),request.sort(),request.direction(),page,100,request.predicates());
            if(batch.total()>MAX_ROWS)throw new IllegalArgumentException("More than 10,000 listings before column filters. Narrow search or server filters.");
            if(expectedTotal==null)expectedTotal=batch.total();
            if(batch.total()!=expectedTotal||batch.rows().size()>100)throw new IllegalArgumentException("Inconsistent listing page. Export stopped.");
            if(batch.rows().size()<100&&(long)page*100+batch.rows().size()<expectedTotal)throw new IllegalArgumentException("Incomplete listing page. Export stopped.");
            var keys=batch.rows().stream().map(MarketplaceSkuRepository.SkuView::sellerSku).toList();
            if(keys.isEmpty())break;
            var stocks=stock.stocks(tenant,connection,batch.rows().stream().filter(MarketplaceSkuRepository.SkuView::fba).map(MarketplaceSkuRepository.SkuView::sellerSku).toList());
            var mappings=listings.mappingComponents(tenant,connection,keys);
            var evidence=rawEvidence(tenant,connection,keys);
            var history=refunds.fourWeeks(tenant,connection,keys);
            var estimates=request.columns().contains("profit")?profits.skus(tenant,connection,keys,anchor.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()):Map.<String,ProfitRepository.View>of();
            for(var sku:batch.rows()){
                check(deadline);
                if(sku.marketplaceId()==null||sku.marketplaceId().isBlank()||!identities.add(sku.marketplaceId()+"\u0000"+sku.sellerSku()))throw new IllegalArgumentException("Ambiguous listing identity. Export stopped.");
                var parts=new ArrayList<>(mappings.getOrDefault(sku.sellerSku(),List.of()));
                if(parts.size()>100)throw new IllegalArgumentException("Mapping exceeds 100 components. Export stopped.");
                var raw=evidence.get(sku.sellerSku());
                if(raw==null)throw new IllegalArgumentException("Listing evidence unavailable. Export stopped.");
                if(parts.isEmpty()&&sku.mappedProduct()!=null&&raw.get("item_id") instanceof UUID id){raw=new HashMap<>(raw);raw.put("legacy_mapping",true);parts.add(new MarketplaceSkuRepository.MappingComponentView(id,sku.mappedProduct(),sku.mappedVendorItemCode(),(String)raw.get("account_sku"),sku.mappingQuantity()));}
                long unknownCurrency=((Number)raw.get("unknown_currency")).longValue();
                var row=project(sku,stocks.get(sku.sellerSku()),parts,estimates.get(sku.sellerSku()),history.get(sku.sellerSku()),raw,unknownCurrency,anchor);
                var parent=parentContexts.get(sku.sellerSku());
                row.fields().put("Parent SKU",parent==null?null:parent.sku());row.fields().put("Parent ASIN",parent==null?null:parent.asin());
                if(inventoryPolicy!=null)applyInventoryPolicy(row,inventoryPolicy.get(sku.sellerSku()));
                if(!request.columnFilters().entrySet().stream().allMatch(e->matches(row.filters().getOrDefault(e.getKey(),""),e.getValue())))continue;
                if(output.size()==MAX_ROWS)throw new IllegalArgumentException("More than 10,000 matching listings. Narrow the filters.");
                output.add(row);components=Math.max(components,parts.size());hasFba|=sku.fba();
                for(String name:row.fields().keySet())if(request.columns().contains("4w-sales")&&name.startsWith("Refund amount ")&&name.endsWith(" (4 weeks)"))currencies.add(name.substring(14,name.length()-10));
                if(currencies.size()>10)throw new IllegalArgumentException("More than 10 refund currencies. Narrow the filters.");
            }
            if((long)(page+1)*100>=batch.total())break;
        }
        // Schema is computed from the complete set before publication, never from a current page.
        var prototype=emptySchema(components,currencies,hasFba);LinkedHashSet<String> selected=new LinkedHashSet<>();
        for(String group:request.columns())selected.addAll(prototype.get(group));
        for(Row row:output)for(String c:currencies)for(String prefix:List.of("Refund amount ","Refund units ")){String field=prefix+c+" (4 weeks)";if(((Number)row.fields().get("Refund records with unknown currency")).longValue()>0)number(row.fields(),field,null,"UNKNOWN");else if(!row.fields().containsKey(field))number(row.fields(),field,BigDecimal.ZERO,"UNKNOWN");}
        List<String> headers=List.copyOf(selected);ByteArrayOutputStream bytes=new ByteArrayOutputStream();write(bytes,"\uFEFF");writeRecord(bytes,headers);
        for(Row row:output){check(deadline);writeRecord(bytes,headers.stream().map(k->row.fields().get(k)).toList());}
        check(deadline);return new Artifact(bytes.toByteArray(),output.size(),anchor);
    }
    Map<String,Map<String,Object>> rawEvidence(UUID tenant,UUID connection,List<String> keys){
        String placeholders=String.join(",",Collections.nCopies(keys.size(),"?"));var args=new ArrayList<Object>();args.add(tenant);args.add(connection);args.addAll(keys);
        var rows=jdbc.queryForList("""
            SELECT l.seller_sku,l.quantity,l.currency,l.fulfillment_channel,m.account_catalog_item_id item_id,a.account_sku,
                (SELECT count(*) FROM amazon_sku_refunds r WHERE r.tenant_id=l.tenant_id AND r.marketplace_connection_id=l.marketplace_connection_id AND r.seller_sku=l.seller_sku
                 AND r.posted_date>=now()-interval '28 days' AND r.posted_date<=now() AND (r.currency IS NULL OR r.currency='')) unknown_currency,
                shipping.shipping_count,shipping.shipping_currency,shipping.shipping_complete
            FROM amazon_listings l
            JOIN marketplace_connections c ON c.tenant_id=l.tenant_id AND c.id=l.marketplace_connection_id AND c.marketplace_identifier=l.marketplace_id
            LEFT JOIN marketplace_sku_mappings m ON m.tenant_id=l.tenant_id AND m.marketplace_connection_id=l.marketplace_connection_id AND m.marketplace_sku=l.seller_sku AND m.status='ACTIVE'
            LEFT JOIN account_catalog_items a ON a.tenant_id=m.tenant_id AND a.id=m.account_catalog_item_id
            LEFT JOIN LATERAL (
                SELECT count(*) shipping_count,bool_and(i.shipping_price IS NOT NULL) shipping_complete,CASE WHEN count(DISTINCT o.currency)=1 AND bool_and(o.currency IS NOT NULL AND o.currency<>'') THEN min(o.currency) END shipping_currency
                FROM amazon_order_items i JOIN amazon_orders o ON o.tenant_id=i.tenant_id AND o.marketplace_connection_id=i.marketplace_connection_id AND o.amazon_order_id=i.amazon_order_id
                WHERE i.tenant_id=l.tenant_id AND i.marketplace_connection_id=l.marketplace_connection_id AND i.seller_sku=l.seller_sku
                 AND o.purchase_date>=now()-interval '28 days' AND o.purchase_date<=now() AND upper(coalesce(o.order_status,'')) NOT IN ('CANCELLED','CANCELED')
            ) shipping ON true
            WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.seller_sku IN (
            """+placeholders+")",args.toArray());
        Map<String,Map<String,Object>> result=new HashMap<>();for(var row:rows)if(result.put((String)row.get("seller_sku"),row)!=null)throw new IllegalArgumentException("Ambiguous store listing evidence.");return result;
    }
    static void check(long deadline){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IllegalArgumentException("Export timed out or was cancelled. Narrow the filters and retry.");}
    static void write(ByteArrayOutputStream out,String value){byte[] b=value.getBytes(StandardCharsets.UTF_8);if((long)out.size()+b.length>MAX_BYTES)throw new IllegalArgumentException("CSV exceeds 50 MiB. Narrow the filters.");out.writeBytes(b);}
    static void writeRecord(ByteArrayOutputStream out,List<?> values){for(int i=0;i<values.size();i++){if(i>0)write(out,",");write(out,cell(values.get(i)));}write(out,"\r\n");}
    static String cell(Object value){String text=value==null?"":value instanceof BigDecimal d?d.toPlainString():value.toString();if(!(value instanceof Number)&&(text.matches("(?s)^[\\p{Z}\\s\\p{Cc}]*[=+\\-@].*")||(!text.isEmpty()&&Character.isISOControl(text.charAt(0)))))text="'"+text;return "\""+text.replace("\"","\"\"")+"\"";}
    static String normalize(String s){return s.replaceAll("\\s+"," ").trim();}
    static boolean matches(String text,Object filter){
        text=normalize(text);if(filter instanceof String s)return text.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT));
        if(!(filter instanceof Map<?,?> m))return true;
        try{double value;if("date".equals(m.get("type"))){var match=Pattern.compile("(\\d{2})/(\\d{2})/(\\d{2,4})").matcher(text);if(!match.find())return false;String year=match.group(3);value=LocalDate.parse((year.length()==2?"20":"")+year+"-"+match.group(1)+"-"+match.group(2)).toEpochDay();}
            else{var match=Pattern.compile("[-+]?\\d+(?:\\.\\d+)?").matcher(text.replace(",",""));if(!match.find())return false;value=Double.parseDouble(match.group());}
            for(String key:List.of("min","max")){String bound=Objects.toString(m.get(key),"");if(bound.isEmpty())continue;double v="date".equals(m.get("type"))?LocalDate.parse(bound).toEpochDay():Double.parseDouble(bound);if(!Double.isFinite(v)||("min".equals(key)?value<v:value>v))return false;}return true;
        }catch(RuntimeException ex){return false;}
    }
    static Map<String,List<String>> emptySchema(int n,Collection<String> currencies){
        return emptySchema(n,currencies,true);
    }
    /** Selected main-table groups only; transient drilldowns never change the CSV schema. */
    static Map<String,List<String>> emptySchema(int n,Collection<String> currencies,boolean hasFba){
        Map<String,List<String>> g=new LinkedHashMap<>();
        g.put("product-details",new ArrayList<>(List.of("Product title","Seller SKU","ASIN","Parent SKU","Parent ASIN")));
        for(int i=1;i<=n;i++)g.get("product-details").addAll(List.of("Mapped item "+i+" code","Mapped item "+i+" quantity"));
        g.put("status",List.of("Listing status","Local inventory plan","Fulfillment method","Condition"));
        g.put("price-shipping",List.of("Listing price","Listing currency","Average customer shipping per unit","Buy Box price","Buy Box currency"));
        g.put("available",hasFba?List.of("Available units","Inbound units","FC transfer units","Reserved units"):List.of("Available units"));
        g.put("4w-sales",List.of("Four-week orders","Four-week units","Week 1 units (oldest)","Week 2 units","Week 3 units","Week 4 units (latest)"));
        g.put("shipping-template",List.of("Shipping template"));g.put("condition",List.of("Condition"));g.put("fnsku",List.of("FNSKU"));
        for(String name:List.of("inbound","reserved","unfulfillable"))g.put(name,List.of(name.substring(0,1).toUpperCase()+name.substring(1)+" units"));
        g.put("profit",List.of("Estimated profit per SKU","Profit currency"));return g;
    }
    static void numericHeaders(List<String> list,String h){list.add(h);}
    static void number(Map<String,Object> f,String name,Object value,String missing){f.put(name,value);f.put(name+" state",value==null?missing:"KNOWN");}
    Row project(MarketplaceSkuRepository.SkuView s,FbaReadRepository.Stock st,List<MarketplaceSkuRepository.MappingComponentView> parts,ProfitRepository.View profit,String refundJson,Map<String,Object> raw,long unknownCurrency,Instant anchor){
        var f=new LinkedHashMap<String,Object>();var text=new HashMap<String,String>();boolean fba=s.fba();String na=fba?"PENDING":"NOT_APPLICABLE";
        f.put("Seller SKU",s.sellerSku());f.put("Marketplace ID",s.marketplaceId());f.put("Product title",s.title());f.put("ASIN",s.asin());f.put("Mapping status",s.mappingLabel());f.put("Mapped item count",s.mappedProduct()==null?0:parts.isEmpty()?null:parts.size());
        var orderedParts=new ArrayList<>(parts);
        for(int i=0;i<orderedParts.size();i++){var p=orderedParts.get(i);String pre="Mapped item "+(i+1);f.put(pre+" account SKU",p.accountSku());f.put(pre+" vendor item code",p.vendorItemCode());number(f,pre+" quantity per listing unit",p.quantity(),"UNKNOWN");}
        // Match the visible mapping order and the template's vendor-code/account-SKU fallback.
        for(int i=0;i<orderedParts.size();i++){var p=orderedParts.get(i);String pre="Mapped item "+(i+1);
            f.put(pre+" code",Boolean.TRUE.equals(raw.get("legacy_mapping"))?p.vendorItemCode():p.vendorItemCode()!=null?p.vendorItemCode():p.accountSku());
            f.put(pre+" quantity",p.quantity()==null?null:p.quantity().setScale(0,java.math.RoundingMode.HALF_UP));}
        f.put("Listing status",s.listingStatus());f.put("Operational status",s.statusLabel());f.put("Local inventory plan",s.localInventoryPlan());f.put("Fulfillment",raw.get("fulfillment_channel")==null||raw.get("fulfillment_channel").toString().isBlank()?"Unknown":s.fulfillmentLabel());f.put("Condition",s.conditionType()==null||s.conditionType().isBlank()?null:s.conditionLabel());
        number(f,"Listing price",s.price(),"UNKNOWN");f.put("Listing currency",raw.get("currency"));
        // Existing aggregate has no verified per-order currency or missing-value coverage.
        number(f,"Average customer shipping per unit (4 weeks)",fba||raw.get("shipping_currency")==null||!Boolean.TRUE.equals(raw.get("shipping_complete"))||((Number)raw.getOrDefault("shipping_count",0)).longValue()==0?null:s.fourWeekShippingAverage(),fba?"NOT_APPLICABLE":"UNKNOWN");f.put("Customer shipping currency",raw.get("shipping_currency"));
        number(f,"Buy Box reference price",s.buyBoxPrice(),"UNKNOWN");f.put("Buy Box currency",s.buyBoxCurrency());f.put("Buy Box observed at UTC",s.buyBoxUpdatedAt());
        Object available=fba?(st==null?null:st.available()):(s.mappedProduct()!=null||raw.get("quantity")!=null?(s.calculatedAvailable()!=null?s.calculatedAvailable():s.merchantQuantity()):null);
        number(f,"Available units",available,"UNKNOWN");
        number(f,"Inbound units",fba&&st!=null?st.totalInbound():null,na);number(f,"FC transfer units",fba&&st!=null?st.fcTransfer():null,na);number(f,"Reserved units",fba&&st!=null?st.reserved():null,na);number(f,"Unfulfillable units",fba&&st!=null?st.unfulfillable():null,na);
        number(f,"Inbound working units",fba&&st!=null?st.working():null,na);number(f,"Inbound shipped units",fba&&st!=null?st.shipped():null,na);number(f,"Inbound receiving units",fba&&st!=null?st.receiving():null,na);number(f,"Warehouse available SKU units",fba&&st!=null?st.warehouseAvailable():null,fba?"UNKNOWN":"NOT_APPLICABLE");
        f.put("Inventory observed at UTC",fba&&st!=null?st.observedAt():null);f.put("Inventory freshness",fba&&st!=null&&st.observedAt()!=null?(st.observedAt().isBefore(anchor.minusSeconds(86400))?"Stale":"Fresh"):"Unknown");
        number(f,"Estimated FBA fee per unit",fba&&st!=null?st.fulfillmentFee():null,na);f.put("FBA fee currency",fba&&st!=null?st.currency():null);f.put("FBA fee observed at UTC",fba&&st!=null?st.feeObservedAt():null);
        number(f,"Four-week orders",s.fourWeekOrders(),"UNKNOWN");number(f,"Four-week units",s.fourWeekUnits(),"UNKNOWN");long[] weeks={s.week4Sales(),s.week3Sales(),s.week2Sales(),s.currentWeekSales()};
        for(int i=1;i<=4;i++){number(f,"Week "+i+" units",weeks[i-1],"UNKNOWN");f.put("Week "+i+" start UTC",anchor.minusSeconds((long)(35-i*7)*86400));f.put("Week "+i+" end UTC",anchor.minusSeconds((long)(28-i*7)*86400));}
        f.put("Sales as of UTC",anchor);f.put("Sales coverage","Imported history");
        var refundRows=refundJson==null?json.createArrayNode():json.readTree(refundJson);if(!refundRows.isArray())throw new IllegalArgumentException("Invalid refund data. Export stopped.");
        Map<String,BigDecimal> amounts=new TreeMap<>(),units=new TreeMap<>();Set<String> partialAmount=new HashSet<>(),partialUnits=new HashSet<>();long unknownAmount=0,unknownUnits=0,orders=0,unknownOrders=0;
        for(var item:refundRows){String c=item.path("currency").asString("");long ua=item.path("unknownAmounts").asLong(),uu=item.path("unknownUnits").asLong();unknownAmount+=ua;unknownUnits+=uu;orders=Math.max(orders,item.path("totalOrders").asLong());unknownOrders=Math.max(unknownOrders,item.path("unknownOrders").asLong());
            if(c.isBlank())continue;if(!c.matches("[A-Z]{3}"))throw new IllegalArgumentException("Invalid refund currency. Export stopped.");
            amounts.merge(c,item.path("amount").isNumber()?item.path("amount").decimalValue():BigDecimal.ZERO,BigDecimal::add);units.merge(c,item.path("units").isNumber()?item.path("units").decimalValue():BigDecimal.ZERO,BigDecimal::add);
            if(ua>0||!item.path("amount").isNumber())partialAmount.add(c);if(uu>0||!item.path("units").isNumber())partialUnits.add(c);
        }
        for(String c:amounts.keySet()){number(f,"Refund amount "+c+" (4 weeks)",partialAmount.contains(c)?null:amounts.get(c),"UNKNOWN");number(f,"Refund units "+c+" (4 weeks)",partialUnits.contains(c)?null:units.get(c),"UNKNOWN");}
        f.put("Refunded orders (4 weeks)",orders);f.put("Refund records without order ID",unknownOrders);f.put("Refund records with unknown amount",unknownAmount);f.put("Refund records with unknown units",unknownUnits);f.put("Refund records with unknown currency",unknownCurrency);
        f.put("Shipping template",s.shippingTemplate());f.put("FNSKU",s.fnsku());f.put("FNSKU state",s.fnsku()!=null&&!s.fnsku().isBlank()?"KNOWN":fba?"UNKNOWN":"NOT_APPLICABLE");
        f.put("Estimated profit per listing unit",profit==null||profit.totals()==null?null:profit.totals().profit());f.put("Profit currency",profit==null?null:profit.currency());
        f.put("Profit calculation state",profit==null||profit.currency()==null||profit.currency().isBlank()?"Unknown":!profit.currency().equals("USD")?"Unsupported currency":profit.totals()!=null&&profit.totals().complete()?"Estimated":"Pending");f.put("Profit calculation note",profit==null?null:profit.note());
        // Only these friendly aliases are published; evidence fields above stay internal.
        f.put("Product title",s.displayTitle());f.put("Listing status",s.statusLabel());
        f.put("Local inventory plan",s.inventoryPlanLabel());f.put("Fulfillment method",f.get("Fulfillment"));
        f.put("Average customer shipping per unit",!fba&&s.averageCustomerShipping().signum()>0?f.get("Average customer shipping per unit (4 weeks)"):null);
        f.put("Buy Box price",s.buyBoxPrice());f.put("Estimated profit per SKU",f.get("Estimated profit per listing unit"));
        f.put("Week 1 units (oldest)",s.week4Sales());f.put("Week 4 units (latest)",s.currentWeekSales());
        var uiParts=Boolean.TRUE.equals(raw.get("legacy_mapping"))?List.<MarketplaceSkuRepository.MappingComponentView>of():parts;StringBuilder mappingText=new StringBuilder();for(var p:uiParts)mappingText.append((p.vendorItemCode()==null||p.vendorItemCode().isBlank()?p.accountSku():p.vendorItemCode())).append(" × ").append(p.quantity()==null?"":p.quantity().setScale(0,java.math.RoundingMode.HALF_UP)).append(' ');
        text.put("product-details",normalize(s.displayTitle()+" "+s.sellerSku()+" "+Objects.toString(s.asin(),"")+" "+mappingText+(s.mappedProduct()==null?"Needs mapping Unmapped":uiParts.isEmpty()?Objects.toString(s.mappingDetail(),""):"")));
        text.put("status",normalize(s.statusLabel()+" "+Objects.toString(s.inventoryPlanLabel(),"")+" "+s.fulfillmentLabel()+" "+s.conditionLabel()));
        text.put("price-shipping",normalize((s.price()==null?"Not supplied":money(s.price(),s.currency()))+(!fba&&s.averageCustomerShipping().signum()>0?" + "+money(s.averageCustomerShipping(),s.currency())+" shipping":"")+" Buy Box "+(s.buyBoxPrice()==null?"—":money(s.buyBoxPrice(),s.buyBoxCurrency()==null?s.currency():s.buyBoxCurrency()))));
        text.put("available",fba?stockText(s.sellerSku(),st,anchor):Integer.toString(s.availableQuantity()));text.put("4w-sales",s.fourWeekOrders()+" / "+s.fourWeekUnits()+" "+s.weeklySalesLabel());
        text.put("shipping-template",Objects.toString(s.shippingTemplate(),"Not supplied"));text.put("condition",Objects.toString(s.conditionType(),"Not supplied"));text.put("fnsku",Objects.toString(s.fnsku(),"Not applicable"));text.put("inbound",Integer.toString(s.safeInboundQuantity()));text.put("reserved",Integer.toString(s.safeReservedQuantity()));text.put("unfulfillable",Integer.toString(s.safeUnfulfillableQuantity()));text.put("profit","Review costs");
        return new Row(f,text);
    }
    static void applyInventoryPolicy(Row row,MarketplaceInventoryEligibility.Decision d){
        if(d==null||d.applicable())return;
        var fields=row.fields();
        for(String h:List.of("Available units","Inbound units","FC transfer units","Reserved units","Unfulfillable units","Inbound working units","Inbound shipped units","Inbound receiving units","Warehouse available SKU units"))fields.put(h,null);
        row.filters().put("available",d.label()+(d.inventoryConflict()?" Source inventory identity needs review":""));row.filters().put("inbound",d.label());row.filters().put("reserved",d.label());row.filters().put("unfulfillable",d.label());
        if(d.parent()){
            fields.keySet().stream().filter(h->h.startsWith("Mapped item ")).toList().forEach(h->fields.put(h,null));
            for(String h:List.of("Local inventory plan","Fulfillment method","Condition","Shipping template","FNSKU","Listing price","Listing currency","Average customer shipping per unit","Buy Box price","Buy Box currency","Four-week orders","Four-week units","Week 1 units (oldest)","Week 2 units","Week 3 units","Week 4 units (latest)","Estimated profit per SKU","Profit currency"))fields.put(h,null);
            fields.put("Listing status","Parent SKU");row.filters().put("product-details",normalize(Objects.toString(fields.get("Product title"),"")+" "+Objects.toString(fields.get("Seller SKU"),"")+" "+Objects.toString(fields.get("ASIN"),"")+" Parent SKU Variation family · not a sellable offer Associated child variations"));row.filters().put("status","Parent SKU");row.filters().put("condition","Not applicable");row.filters().put("fnsku","Not applicable");row.filters().put("shipping-template","Not applicable");row.filters().put("price-shipping","Not applicable");row.filters().put("4w-sales","Not applicable");row.filters().put("profit","Not applicable");
        }
    }
    static String money(BigDecimal n,String code){String symbol=switch(code==null?"USD":code){case "USD"->"$";case "CAD"->"CA$";case "GBP"->"£";case "EUR"->"€";default->code+" ";};return symbol+String.format(Locale.US,"%,.2f",n);}
    static String stockText(String sku,FbaReadRepository.Stock s,Instant anchor){
        String result="Available "+(s==null||s.available()==null?"Pending":s.available())+" Inbound "+(s==null||s.totalInbound()==null?"Pending":s.totalInbound())+" FC transfer "+(s==null||s.fcTransfer()==null?"Pending":s.fcTransfer())+" Reserved "+(s==null||s.reserved()==null?"Pending":s.reserved())+" Stock & fees FBA inventory "+sku;
        if(s==null||s.observedAt()==null)result+=" Waiting for an Amazon inventory snapshot.";else result+=" Snapshot: "+s.observedDisplay()+(s.observedAt().isBefore(anchor.minusSeconds(86400))?" May be outdated · over 24 hours old":"");
        if(s!=null)result+=" Available at Amazon "+Objects.toString(s.available(),"Pending")+" Inbound · working "+Objects.toString(s.working(),"Pending")+" Inbound · shipped "+Objects.toString(s.shipped(),"Pending")+" Inbound · receiving "+Objects.toString(s.receiving(),"Pending")+" Reserved total "+Objects.toString(s.reserved(),"Pending")+" FC transfer "+Objects.toString(s.fcTransfer(),"Pending")+" Unfulfillable "+Objects.toString(s.unfulfillable(),"Pending")+" Warehouse available "+(s.warehouseAvailable()==null?"Needs mapping":s.warehouseAvailable().setScale(0,java.math.RoundingMode.HALF_UP))+" Estimated FBA fee / unit "+(s.fulfillmentFee()==null?"Pending":Objects.toString(s.currency(),"")+" "+String.format(Locale.US,"%,.2f",s.fulfillmentFee()));
        if(s!=null&&s.feeObservedAt()!=null)result+=" Fee snapshot: "+s.feeObservedDisplay();return normalize(result+" Warehouse availability is in complete SKU units across mapped locations. Amazon quantities are separate; reserved subcategories must not be added twice. FBA shipments →");
    }
}
