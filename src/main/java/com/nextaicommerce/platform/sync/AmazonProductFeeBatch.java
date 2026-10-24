package com.nextaicommerce.platform.sync;

import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Exact response correlation; incomplete siblings remain retryable, never numeric zero. */
final class AmazonProductFeeBatch {
    static final String CONTRACT="FINAL_FEE_V1";
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
        if(requests.isEmpty()||requests.size()>20||expected.size()!=requests.size()||!expected.containsAll(byId.keySet()))return new Result(List.of(),List.copyOf(requests));
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
            var amounts=valid?strictAmounts(result.path("FeesEstimate"),r.target().currency()):null;
            if(amounts==null)unresolved.add(r);else accepted.add(new Accepted(r,result,amounts));
        }
        return new Result(List.copyOf(accepted),List.copyOf(unresolved));
    }
    // The new batch path requires Amazon's FinalFee contract. Legacy single-item imports are unchanged.
    private static AmazonProductFeeSync.Amounts strictAmounts(JsonNode estimate,String currency){
        var total=value(estimate.path("TotalFeesEstimate"),currency);var fees=estimate.path("FeeDetailList");
        if(total==null||!fees.isArray()||fees.isEmpty())return null;
        BigDecimal sum=BigDecimal.ZERO,referral=null,fba=null;Set<String> types=new HashSet<>();
        for(var fee:fees){
            String type=fee.path("FeeType").asText("");var amount=finalAmount(fee,currency,0);
            if(type.isBlank()||!types.add(type)||amount==null)return null;
            sum=sum.add(amount);if("ReferralFee".equals(type))referral=amount;if("FBAFees".equals(type))fba=amount;
        }
        return sum.compareTo(total)==0&&referral!=null&&fba!=null?new AmazonProductFeeSync.Amounts(total,referral,fba):null;
    }
    private static BigDecimal finalAmount(JsonNode fee,String currency,int depth){
        if(depth>5)return null;
        var base=value(fee.path("FeeAmount"),currency);var amount=value(fee.path("FinalFee"),currency);
        if(base==null||amount==null||base.compareTo(amount)!=0)return null;
        // Nonzero fee taxes/promotions require a separately verified assessment contract.
        for(String key:List.of("TaxAmount","FeePromotion"))if(fee.has(key)){
            var extra=value(fee.path(key),currency);if(extra==null||extra.signum()!=0)return null;
        }
        if(fee.has("IncludedFeeDetailList")){
            var children=fee.path("IncludedFeeDetailList");if(!children.isArray())return null;
            if(!children.isEmpty()){
                BigDecimal sum=BigDecimal.ZERO;Set<String> types=new HashSet<>();
                for(var child:children){String type=child.path("FeeType").asText("");var value=finalAmount(child,currency,depth+1);
                    if(type.isBlank()||!types.add(type)||value==null)return null;sum=sum.add(value);}
                if(sum.compareTo(amount)!=0)return null;
            }
        }
        return amount;
    }
    static BigDecimal comparableFba(JsonNode estimate,String currency){
        if(strictAmounts(estimate,currency)==null)return null;
        for(var fee:estimate.path("FeeDetailList"))if("FBAFees".equals(fee.path("FeeType").asText())){
            var children=fee.path("IncludedFeeDetailList");
            if(children.isArray()&&children.size()==1&&"FBAPerUnitFulfillmentFee".equals(children.get(0).path("FeeType").asText()))return finalAmount(children.get(0),currency,0);
        }
        return null;
    }
    private static BigDecimal value(JsonNode n,String currency){
        if(!currency.equals(n.path("CurrencyCode").asText())||!n.path("Amount").isNumber())return null;
        var amount=n.path("Amount").decimalValue();return amount.signum()>=0?amount:null;
    }
    private static boolean money(JsonNode n,BigDecimal value,String currency){return currency.equals(n.path("CurrencyCode").asText())&&n.path("Amount").isNumber()&&value.compareTo(n.path("Amount").decimalValue())==0;}
}
