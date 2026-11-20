package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static com.nextaicommerce.platform.shopify.ShopifyOrderLifecycle.Ownership;

/** Fixed, read-only, PII-minimized Orders contract. Nested truncation is an explicit hold, never completeness. */
@Component
public class ShopifyOrderClient {
    public static final String REVISION="orders-2026-10-v1";
    public static final String QUERY="""
        query LocalOrders($after:String,$search:String!) {
          shop { id currencyCode }
          orders(first:1,after:$after,sortKey:UPDATED_AT,query:$search) {
            pageInfo { hasNextPage endCursor }
            nodes { id name createdAt updatedAt cancelledAt test displayFinancialStatus displayFulfillmentStatus
              currentTotalPriceSet { shopMoney { amount currencyCode } }
              lineItems(first:20) { pageInfo { hasNextPage } nodes {
                id title sku quantity currentQuantity unfulfilledQuantity requiresShipping
                variant { id } image { url }
                discountedTotalSet { shopMoney { amount currencyCode } }
              } }
              fulfillmentOrders(first:5,displayable:false) { pageInfo { hasNextPage } nodes {
                id status lineItems(first:20) { pageInfo { hasNextPage } nodes {
                  id remainingQuantity totalQuantity lineItem { id }
                } }
              } }
              fulfillmentsCount { count precision }
              fulfillments(first:5) { id status createdAt updatedAt totalQuantity
                fulfillmentOrders(first:5) { pageInfo { hasNextPage } nodes { id } }
                fulfillmentLineItems(first:20) { pageInfo { hasNextPage } nodes { id quantity lineItem { id } } }
              }
            }
          }
        }
        """;
    public static final String RECIPIENT_QUERY="""
        query ShippingRecipient($id:ID!) {
          shop { id currencyCode }
          order(id:$id) { id cancelledAt displayFulfillmentStatus phone
            shippingAddress { name firstName lastName phone address1 address2 city provinceCode zip countryCodeV2 }
          }
        }
        """;
    /** Explicit shipping-dialog read only: never part of sync or source archives. */
    public com.nextaicommerce.platform.shipping.OrderRecipient recipient(UUID tenant,UUID connection,String gid){
        if(gid==null||!gid.matches("gid://shopify/Order/[0-9]+"))throw bad("Invalid Shopify order identity.");
        var response=connections.readOrderRecipient(tenant,connection,json.writeValueAsString(Map.of("query",RECIPIENT_QUERY,"variables",Map.of("id",gid))));
        return parseRecipient(response,gid);
    }
    static com.nextaicommerce.platform.shipping.OrderRecipient parseRecipient(ShopifyConnectionService.CatalogResponse response,String gid){
        var root=new ObjectMapper().readTree(response.body());
        if(root.has("errors"))throw bad("Shopify shipping address is unavailable or not permitted. Enter the order address manually.");
        var data=root.path("data");var order=data.path("order");
        if(!response.shopId().equals(data.path("shop").path("id").asText())||!response.currency().equals(data.path("shop").path("currencyCode").asText())||!gid.equals(order.path("id").asText()))throw bad("Shopify recipient identity did not match this store and order.");
        var a=order.path("shippingAddress");if(!a.isObject())throw bad("Shopify has no usable shipping address. Enter the order address manually.");
        String name=recipientField(a,"name");if(name.isBlank())name=(recipientField(a,"firstName")+" "+recipientField(a,"lastName")).trim();
        String phone=recipientField(a,"phone");if(phone.isBlank())phone=recipientField(order,"phone");
        return new com.nextaicommerce.platform.shipping.OrderRecipient(name,phone,recipientField(a,"address1"),recipientField(a,"address2"),"",recipientField(a,"city"),recipientField(a,"provinceCode"),recipientField(a,"zip"),recipientField(a,"countryCodeV2"),"");
    }
    private static String recipientField(JsonNode value,String name){String text=value.path(name).asText("").trim();if(text.length()>500||text.chars().anyMatch(Character::isISOControl))throw bad("Unsupported recipient field.");return text;}
    public record Money(BigDecimal amount,String currency) {}
    public record Line(String gid,String variant,String sku,String title,String image,int ordered,int current,int unfulfilled,
                       boolean shipping,Ownership ownership,Money total,List<ShopifyOrderLifecycle.Fulfillment> fulfillments) {}
    public record Order(String gid,String name,Instant created,Instant updated,Instant cancelled,boolean test,
                        String payment,String fulfillment,Money total,boolean complete,String review,List<Line> lines) {}
    public record Page(List<Order> orders,String next,boolean more,double cost,double available,double restoreRate) {}
    private final ShopifyConnectionService connections;
    private final ObjectMapper json=new ObjectMapper();
    public ShopifyOrderClient(ShopifyConnectionService connections){this.connections=connections;}
    public ShopifyConnectionService.CatalogResponse fetch(UUID tenant,UUID connection,String after,Instant start,Instant end,Runnable reserve){
        if(start==null||end==null||!end.isAfter(start))throw new IllegalArgumentException("Invalid Orders read window.");
        var variables=new LinkedHashMap<String,Object>();variables.put("after",after);
        variables.put("search","updated_at:>='"+start+"' updated_at:<='"+end+"'");
        return connections.readOrders(tenant,connection,json.writeValueAsString(Map.of("query",QUERY,"variables",variables)),reserve);
    }
    public Page parse(ShopifyConnectionService.CatalogResponse response,String after){
        var root=json.readTree(response.body());
        if(root.has("errors"))throw bad("Shopify Orders response is incomplete or not authorized.");
        var data=root.path("data");
        if(!response.shopId().equals(text(data.path("shop"),"id"))||!response.currency().equals(text(data.path("shop"),"currencyCode")))
            throw bad("Shopify shop identity or currency changed.");
        var orders=data.path("orders");var nodes=nodes(orders,1);boolean more=bool(orders.path("pageInfo"),"hasNextPage");
        String next=nullable(orders.path("pageInfo"),"endCursor");
        if(more&&(next==null||next.isBlank()||next.equals(after)||nodes.isEmpty()))throw bad("Shopify Orders cursor did not advance.");
        var result=new ArrayList<Order>();for(var n:nodes){
            var o=order(n);
            if(!response.currency().equals(o.total().currency())||o.lines().stream().anyMatch(l->!response.currency().equals(l.total().currency())))
                throw bad("Shopify shop-money currency changed.");
            result.add(o);
        }
        var cost=root.path("extensions").path("cost");double requested=cost.path("requestedQueryCost").asDouble(-1);
        if(!Double.isFinite(requested)||requested<0||requested>1000)throw bad("Shopify Orders query cost is invalid.");
        return new Page(List.copyOf(result),next,more,requested,cost.path("throttleStatus").path("currentlyAvailable").asDouble(0),cost.path("throttleStatus").path("restoreRate").asDouble(0));
    }
    private Order order(JsonNode n){
        String id=gid(n,"Order"),name=text(n,"name");Instant created=time(n,"createdAt"),updated=time(n,"updatedAt");
        if(updated.isBefore(created))throw bad("Shopify Orders revision predates creation.");
        String cancellation=nullable(n,"cancelledAt");Instant cancelled=cancellation==null?null:Instant.parse(cancellation);
        boolean test=bool(n,"test");String payment=nullable(n,"displayFinancialStatus");String fulfillment=text(n,"displayFulfillmentStatus");
        var total=money(n.path("currentTotalPriceSet"));
        var lines=nodes(n.path("lineItems"),20);var fos=nodes(n.path("fulfillmentOrders"),5);
        boolean complete=complete(n.path("lineItems"))&&complete(n.path("fulfillmentOrders"));
        var knownLines=new HashSet<String>();for(var line:lines)if(!knownLines.add(gid(line,"LineItem")))throw bad("Duplicate Shopify order line.");
        var merchantIds=new HashSet<String>();var assigned=new HashMap<String,Integer>();var mentioned=new HashSet<String>();
        // Transport rejects broader fulfillment scopes. The visible assignments are therefore merchant-managed.
        for(var fo:fos){
            if(!merchantIds.add(gid(fo,"FulfillmentOrder")))throw bad("Duplicate Shopify fulfillment order.");
            String status=text(fo,"status");var fol=nodes(fo.path("lineItems"),20);complete&=complete(fo.path("lineItems"));
            var ids=new HashSet<String>();
            for(var line:fol){
                if(!ids.add(gid(line,"FulfillmentOrderLineItem")))throw bad("Duplicate fulfillment-order line.");
                String item=gid(line.path("lineItem"),"LineItem");if(!knownLines.contains(item))complete=false;
                int remaining=integer(line,"remainingQuantity"),quantity=integer(line,"totalQuantity");
                if(remaining>quantity)throw bad("Fulfillment assignment quantity is inconsistent.");
                mentioned.add(item);
                if(Set.of("OPEN","IN_PROGRESS","SCHEDULED","ON_HOLD").contains(status))assigned.merge(item,remaining,Math::addExact);
            }
        }
        var events=new HashMap<String,List<ShopifyOrderLifecycle.Fulfillment>>();var fulfillments=n.path("fulfillments");
        if(!fulfillments.isArray()||fulfillments.size()>5)throw bad("Missing Shopify fulfillments.");
        var count=n.path("fulfillmentsCount");
        complete&="EXACT".equals(text(count,"precision"))&&integer(count,"count")==fulfillments.size();
        var fulfillmentIds=new HashSet<String>();
        for(var f:fulfillments){
            String fid=gid(f,"Fulfillment");if(!fulfillmentIds.add(fid))throw bad("Duplicate fulfillment.");
            String status=text(f,"status");Instant occurred=time(f,"createdAt");time(f,"updatedAt");
            var refs=nodes(f.path("fulfillmentOrders"),5);complete&=complete(f.path("fulfillmentOrders"));
            boolean merchant=!refs.isEmpty();for(var ref:refs)merchant&=merchantIds.contains(gid(ref,"FulfillmentOrder"));
            var fl=nodes(f.path("fulfillmentLineItems"),20);complete&=complete(f.path("fulfillmentLineItems"));
            var eventIds=new HashSet<String>();int sum=0;
            for(var e:fl){
                String eid=gid(e,"FulfillmentLineItem");if(!eventIds.add(eid))throw bad("Duplicate fulfillment line.");
                String item=gid(e.path("lineItem"),"LineItem");if(!knownLines.contains(item))complete=false;
                int qty=integer(e,"quantity");sum=Math.addExact(sum,qty);
                events.computeIfAbsent(item,k->new ArrayList<>()).add(new ShopifyOrderLifecycle.Fulfillment(fid,eid,qty,status,merchant?Ownership.MERCHANT:Ownership.UNKNOWN,occurred));
            }
            complete&=sum==integer(f,"totalQuantity");
        }
        var result=new ArrayList<Line>();
        for(var line:lines){
            String lid=gid(line,"LineItem");int qty=integer(line,"quantity"),current=integer(line,"currentQuantity"),open=integer(line,"unfulfilledQuantity");
            if(open>qty||current>qty)throw bad("Shopify order line quantities are inconsistent.");
            boolean shipping=bool(line,"requiresShipping");
            Ownership owner=mentioned.contains(lid)&&assigned.getOrDefault(lid,0)==open?Ownership.MERCHANT:Ownership.UNKNOWN;
            var variant=line.path("variant");if(!line.has("variant"))throw bad("Missing variant evidence.");
            String variantId=variant.isNull()?null:gid(variant,"ProductVariant");
            var fs=events.getOrDefault(lid,List.of()).stream().sorted(Comparator.comparing(ShopifyOrderLifecycle.Fulfillment::key)).toList();
            int shipped=fs.stream().filter(f->"SUCCESS".equals(f.status())).mapToInt(ShopifyOrderLifecycle.Fulfillment::quantity).sum();
            if(shipped>qty||shipped+open>qty)complete=false;
            if(!line.has("sku")||(!line.path("sku").isNull()&&!line.path("sku").isString()))throw bad("Missing Shopify SKU evidence.");
            result.add(new Line(lid,variantId,line.path("sku").isNull()?null:line.path("sku").asText(),text(line,"title"),ShopifyCatalogClient.image(line.path("image").path("url").asText(null)),qty,current,open,shipping,owner,money(line.path("discountedTotalSet")),fs));
        }
        result.sort(Comparator.comparing(Line::gid));
        return new Order(id,name,created,updated,cancelled,test,payment==null?"UNKNOWN":payment,fulfillment,total,complete,
            complete?null:"Order lines or fulfillment evidence exceed this bounded read or are incomplete; warehouse operations held.",List.copyOf(result));
    }
    private static JsonNode nodes(JsonNode connection,int limit){
        var n=connection.path("nodes");if(!n.isArray()||n.size()>limit)throw bad("Shopify Orders connection is malformed.");
        bool(connection.path("pageInfo"),"hasNextPage");return n;
    }
    private static boolean complete(JsonNode connection){return !bool(connection.path("pageInfo"),"hasNextPage");}
    private static Money money(JsonNode set){
        var m=set.path("shopMoney");BigDecimal amount=new BigDecimal(text(m,"amount"));String currency=text(m,"currencyCode");
        if(amount.signum()<0||amount.precision()>19||amount.scale()>4||!currency.matches("[A-Z]{3}"))throw bad("Unsupported Shopify money.");
        return new Money(amount,currency);
    }
    private static String text(JsonNode n,String field){if(!n.path(field).isString()||n.path(field).asText().isBlank())throw bad("Missing Shopify "+field+".");return n.path(field).asText();}
    private static String nullable(JsonNode n,String field){if(!n.has(field))throw bad("Missing Shopify "+field+".");return n.path(field).isNull()?null:text(n,field);}
    private static boolean bool(JsonNode n,String field){if(!n.path(field).isBoolean())throw bad("Missing Shopify "+field+".");return n.path(field).asBoolean();}
    private static int integer(JsonNode n,String field){var v=n.path(field);if(!v.isIntegralNumber()||!v.canConvertToInt()||v.asInt()<0)throw bad("Invalid Shopify "+field+".");return v.asInt();}
    private static String gid(JsonNode n,String kind){String id=text(n,"id");if(!id.matches("gid://shopify/"+kind+"/[0-9]+"))throw bad("Invalid Shopify "+kind+" identity.");return id;}
    private static Instant time(JsonNode n,String field){return Instant.parse(text(n,field));}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
