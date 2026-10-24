package com.nextaicommerce.platform.sync;

import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Exact response correlation; incomplete siblings remain retryable, never numeric zero. */
final class AmazonProductFeeBatch {
    record Request(AmazonProductFeeJobs.Target target,ObjectNode basis,String identifier) {}
    record Accepted(Request request,JsonNode result,AmazonProductFeeSync.Amounts amounts) {}
    record Result(List<Accepted> accepted,List<Request> unresolved) {}
    static Result verify(List<Request> requests,JsonNode response) {
        var accepted=new ArrayList<Accepted>();var unresolved=new ArrayList<Request>();
        if(!response.isArray()){return new Result(List.of(),List.copyOf(requests));}
        Map<String,List<JsonNode>> byId=new HashMap<>();
        for(var r:response)byId.computeIfAbsent(r.path("FeesEstimateIdentifier").path("SellerInputIdentifier").asText(),x->new ArrayList<>()).add(r);
        // Unexpected identifiers invalidate the envelope; a response cannot publish to a different target.
        Set<String> expected=new HashSet<>();requests.forEach(r->expected.add(r.identifier()));
        if(!expected.containsAll(byId.keySet()))return new Result(List.of(),List.copyOf(requests));
        for(var r:requests) {
            var candidates=byId.getOrDefault(r.identifier(),List.of());
            JsonNode result=candidates.size()==1?candidates.getFirst():null;
            var id=result==null?null:result.path("FeesEstimateIdentifier");
            boolean valid=id!=null&&"Success".equals(result.path("Status").asText())&&"SellerSKU".equals(id.path("IdType").asText())
                &&r.target().sku().equals(id.path("IdValue").asText())&&r.basis().path("MarketplaceId").asText().equals(id.path("MarketplaceId").asText())
                &&r.basis().path("_platformSellerId").asText().equals(id.path("SellerId").asText())
                &&id.path("IsAmazonFulfilled").isBoolean()&&id.path("IsAmazonFulfilled").asBoolean()
                &&money(id.path("PriceToEstimateFees").path("ListingPrice"),r.target().price(),r.target().currency())
                &&money(id.path("PriceToEstimateFees").path("Shipping"),BigDecimal.ZERO,r.target().currency());
            var amounts=valid?AmazonProductFeeSync.amounts(result.path("FeesEstimate"),r.target().currency()):null;
            if(amounts==null)unresolved.add(r);else accepted.add(new Accepted(r,result,amounts));
        }
        return new Result(List.copyOf(accepted),List.copyOf(unresolved));
    }
    private static boolean money(JsonNode n,BigDecimal value,String currency){return currency.equals(n.path("CurrencyCode").asText())&&n.path("Amount").isNumber()&&value.compareTo(n.path("Amount").decimalValue())==0;}
}
