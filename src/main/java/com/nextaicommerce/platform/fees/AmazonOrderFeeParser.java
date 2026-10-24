package com.nextaicommerce.platform.fees;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Narrow FINANCES parser. Unsupported/ambiguous evidence remains visible and never becomes zero. */
public final class AmazonOrderFeeParser {
    public record Charge(String transaction, String path, String order, String sku, String type, String currency,
                         BigDecimal signed, BigDecimal expense, Integer quantity, String fulfillment,
                         Instant posted, String state, String reason, JsonNode evidence) {}
    public List<Charge> parse(JsonNode transaction, String marketplace, String seller) {
        String market = transaction.path("marketplaceDetails").path("marketplaceId").asText("");
        if (market.isBlank()) market = transaction.path("sellingPartnerMetadata").path("marketplaceId").asText("");
        String owner = transaction.path("sellingPartnerMetadata").path("sellingPartnerId").asText("");
        String metadataMarket=transaction.path("sellingPartnerMetadata").path("marketplaceId").asText("");
        boolean scoped = marketplace.equals(market) && seller.equals(owner)
                && (metadataMarket.isBlank() || marketplace.equals(metadataMarket));
        String key = transaction.path("transactionId").asText("");
        String order = scoped ? related(transaction, "ORDER_ID") : null;
        Instant posted = null;
        try { posted = Instant.parse(transaction.path("postedDate").asText()); } catch (RuntimeException ignored) {}
        String status = transaction.path("transactionStatus").asText("");
        boolean released = Set.of("RELEASED", "DEFERRED_RELEASED").contains(status);
        boolean shipment = "Shipment".equalsIgnoreCase(transaction.path("transactionType").asText());
        var result = new ArrayList<Charge>();
        int i = 0;
        for (JsonNode item : transaction.path("items")) {
            List<JsonNode> contexts = new ArrayList<>();
            for (JsonNode c : item.path("contexts")) if ("ProductContext".equals(c.path("contextType").asText())) contexts.add(c);
            var c = contexts.size() == 1 ? contexts.getFirst() : null;
            String sku = c == null ? null : c.path("sku").asText(null);
            Integer quantity = c != null && c.path("quantityShipped").canConvertToInt()
                    && c.path("quantityShipped").isIntegralNumber() && c.path("quantityShipped").intValue() > 0
                    ? c.path("quantityShipped").intValue() : null;
            String fulfillment = c != null && "AFN".equals(c.path("fulfillmentNetwork").asText()) ? "FBA" : null;
            String reason = !scoped ? "Seller or marketplace evidence unavailable" : !released ? "Charge not released"
                    : !shipment ? "Adjustment requires explicit linkage" : key.isBlank() || order == null || sku == null || sku.isBlank() || quantity == null || fulfillment == null || posted == null
                    ? "Order-item basis incomplete" : "Released shipment component";
            boolean monetaryComplete=consistentCurrency(item);
            boolean supported = reason.equals("Released shipment component") && monetaryComplete;
            if(!monetaryComplete)reason="Currency, duplicated component or hierarchy total unresolved";
            int before = result.size();
            walk(item.path("breakdowns"), "items/" + i + "/breakdowns", result, key, order, sku, quantity,
                    fulfillment, posted, supported, shipment, reason, item, Area.ITEM, 0);
            if (result.size() == before) result.add(new Charge(key, "items/"+i, order, sku, "UNKNOWN", null,
                    null, null, quantity, fulfillment, posted, "UNRESOLVED", "Fee components unavailable", item));
            // A released transaction can still contain incomplete economic evidence.
            if (result.subList(before,result.size()).stream().anyMatch(x -> !"SUPPORTED".equals(x.state()))) {
                for(int k=before;k<result.size();k++) {
                    var x=result.get(k);
                    if("SUPPORTED".equals(x.state())) result.set(k,new Charge(x.transaction(),x.path(),x.order(),x.sku(),x.type(),x.currency(),
                            x.signed(),null,x.quantity(),x.fulfillment(),x.posted(),"UNRESOLVED","Incomplete sibling charge evidence",x.evidence()));
                }
            }
            i++;
        }
        if (result.isEmpty()) result.add(new Charge(key, "transaction", order, null, "UNKNOWN", null, null,
                null, null, null, posted, "UNRESOLVED", "Item evidence unavailable", transaction));
        return List.copyOf(result);
    }
    private void walk(JsonNode nodes, String path, List<Charge> out, String key, String order, String sku,
                      Integer quantity, String fulfillment, Instant posted, boolean supported, boolean shipment,
                      String reason, JsonNode item, Area area, int depth) {
        if (depth > 12) { out.add(new Charge(key,path,order,sku,"UNKNOWN",null,null,null,quantity,fulfillment,posted,
                "UNRESOLVED","Fee hierarchy exceeds supported depth",item)); return; }
        int i = 0;
        for (JsonNode n : nodes) {
            String p = path + "/" + i++;
            String name = n.path("breakdownType").asText();
            String type = "Commission".equals(name) ? "REFERRAL" : "FBAPerUnitFulfillmentFee".equals(name) ? "FBA" : null;
            if (type != null && (area==Area.ITEM || area==Area.FEES)) {
                var money = n.path("breakdownAmount");
                String currency = money.path("currencyCode").asText(null);
                BigDecimal amount = money.path("currencyAmount").isNumber() ? money.path("currencyAmount").decimalValue() : null;
                boolean valid = supported && currency != null && currency.matches("[A-Z]{3}") && amount != null && amount.signum() <= 0
                        && baseMatches(n, currency, amount);
                out.add(new Charge(key,p,order,sku,type,currency,amount,valid ? amount.negate() : null,quantity,
                        fulfillment,posted,valid ? "SUPPORTED" : shipment ? "UNRESOLVED" : "ADJUSTMENT",
                        valid ? reason : supported ? "Component sign or hierarchy unresolved" : reason,n));
                // A recognized parent and its children must never both contribute.
            } else if (n.path("breakdowns").isArray() && !n.path("breakdowns").isEmpty()) {
                Area child=childArea(area,name);
                walk(n.path("breakdowns"),p+"/breakdowns",out,key,order,sku,quantity,fulfillment,posted,supported,shipment,reason,item,child,depth+1);
            } else if (!nonFee(area,name)) {
                out.add(new Charge(key,p,order,sku,"UNKNOWN",null,null,null,quantity,fulfillment,posted,
                        "UNRESOLVED","Unsupported fee component: "+name,n));
            }
        }
    }
    private enum Area { ITEM, FEES, PRODUCT, SHIPPING, TAX, UNKNOWN }
    private static Area childArea(Area area,String name) {
        if(area==Area.ITEM&&"AmazonFees".equals(name)||(area==Area.ITEM||area==Area.FEES)&&"FBAFees".equals(name))return Area.FEES;
        if(area==Area.ITEM&&"ProductCharges".equals(name))return Area.PRODUCT;
        if((area==Area.ITEM||area==Area.PRODUCT)&&"Shipping".equals(name))return Area.SHIPPING;
        if((area==Area.ITEM||area==Area.PRODUCT||area==Area.SHIPPING)&&"Tax".equals(name))return Area.TAX;
        return Area.UNKNOWN;
    }
    private static boolean nonFee(Area area,String name) {
        return (area==Area.ITEM&&Set.of("Principal","OurPricePrincipal","Tax","Shipping","ShippingTax").contains(name))
            || (area==Area.PRODUCT&&Set.of("Principal","OurPricePrincipal").contains(name))
            || (area==Area.SHIPPING&&Set.of("ShippingPrincipal","ShippingTax").contains(name))
            || (area==Area.TAX&&Set.of("OurPriceTax","MarketplaceFacilitatorTax-Principal","ShippingTax","MarketplaceFacilitatorTax-Shipping").contains(name));
    }
    private static boolean baseMatches(JsonNode fee,String currency,BigDecimal amount) {
        var children=fee.path("breakdowns");
        if(children.isMissingNode())return true;
        if(!children.isArray())return false;
        if(children.isEmpty())return true;
        if(children.size()!=1)return false;
        var base=children.get(0);var money=base.path("breakdownAmount");
        return "Base".equals(base.path("breakdownType").asText())&&currency.equals(money.path("currencyCode").asText())
            &&money.path("currencyAmount").isNumber()&&amount.compareTo(money.path("currencyAmount").decimalValue())==0
            &&(!base.has("breakdowns")||(base.path("breakdowns").isArray()&&base.path("breakdowns").isEmpty()));
    }
    private static boolean consistentCurrency(JsonNode item) {
        Set<String> currencies=new HashSet<>();return currencies(item.path("breakdowns"),currencies,0)&&currencies.size()==1;
    }
    private static boolean currencies(JsonNode nodes,Set<String> found,int depth) {
        if(depth>12||!nodes.isArray())return false;
        Set<String> names=new HashSet<>();
        for(var node:nodes){
            String type=node.path("breakdownType").asText("");
            if(!names.add(type)&&Set.of("ProductCharges","AmazonFees","FBAFees","Shipping","Tax","OurPricePrincipal","ShippingPrincipal","Commission","FBAPerUnitFulfillmentFee").contains(type))return false;
            if(node.has("breakdownAmount")){
                var m=node.path("breakdownAmount");String currency=m.path("currencyCode").asText("");
                if(!currency.matches("[A-Z]{3}")||!m.path("currencyAmount").isNumber())return false;
                found.add(currency);
            }
            if(node.has("breakdowns")){
                var children=node.path("breakdowns");if(!currencies(children,found,depth+1))return false;
                if(!children.isEmpty()){
                    var parent=node.path("breakdownAmount");if(!parent.path("currencyAmount").isNumber())return false;
                    BigDecimal sum=BigDecimal.ZERO;
                    for(var child:children){var amount=child.path("breakdownAmount");
                        if(!parent.path("currencyCode").asText().equals(amount.path("currencyCode").asText())||!amount.path("currencyAmount").isNumber())return false;
                        sum=sum.add(amount.path("currencyAmount").decimalValue());}
                    if(sum.compareTo(parent.path("currencyAmount").decimalValue())!=0)return false;
                }
            }
        }
        return found.size()<=1;
    }
    private static String related(JsonNode transaction, String name) {
        Set<String> found = new HashSet<>();
        for (var id : transaction.path("relatedIdentifiers")) if (name.equals(id.path("relatedIdentifierName").asText())) {
            String value = id.path("relatedIdentifierValue").asText(""); if (!value.isBlank()) found.add(value);
        }
        return found.size() == 1 ? found.iterator().next() : null;
    }
}
