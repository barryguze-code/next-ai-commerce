package com.nextaicommerce.platform.sync;

import java.util.*;
import java.math.BigDecimal;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** Exact response correlation; missing/service-error siblings may retry, unsupported evidence never becomes zero. */
final class AmazonProductFeeBatch {
    static final String CONTRACT="FINAL_FEE_V2_US_STANDARD_FBA";
    record Request(AmazonProductFeeJobs.Target target,ObjectNode basis,String identifier) {}
    record Accepted(Request request,JsonNode result,AmazonProductFeeSync.Amounts amounts) {}
    record Rejected(Request request,String reason) {}
    record Result(List<Accepted> accepted,List<Request> unresolved,List<Rejected> rejected) {
        Result(List<Accepted> accepted,List<Request> unresolved){this(accepted,unresolved,List.of());}
        List<Request> retryable(){var terminal=new HashSet<Request>();rejected.forEach(r->terminal.add(r.request()));return unresolved.stream().filter(r->!terminal.contains(r)).toList();}
    }
    private static Result rejectEnvelope(List<Request> requests,String reason){return new Result(List.of(),List.copyOf(requests),requests.stream().map(r->new Rejected(r,reason)).toList());}
    static Result verify(List<Request> requests,JsonNode response) {
        var accepted=new ArrayList<Accepted>();var unresolved=new ArrayList<Request>();var rejected=new ArrayList<Rejected>();
        if(!response.isArray()){return rejectEnvelope(requests,"INVALID_RESPONSE_ENVELOPE");}
        Map<String,List<JsonNode>> byId=new HashMap<>();
        for(var r:response)byId.computeIfAbsent(r.path("FeesEstimateIdentifier").path("SellerInputIdentifier").asText(),x->new ArrayList<>()).add(r);
        // Unexpected identifiers invalidate the envelope; a response cannot publish to a different target.
        Set<String> expected=new HashSet<>();requests.forEach(r->expected.add(r.identifier()));
        if(requests.isEmpty()||requests.size()>20||expected.size()!=requests.size()||!expected.containsAll(byId.keySet()))return rejectEnvelope(requests,"RESPONSE_IDENTIFIER_CONFLICT");
        for(var r:requests) {
            var candidates=byId.getOrDefault(r.identifier(),List.of());
            JsonNode result=candidates.size()==1?candidates.getFirst():null;
            if(candidates.isEmpty()){unresolved.add(r);continue;}
            if(candidates.size()!=1){unresolved.add(r);rejected.add(new Rejected(r,"RESPONSE_IDENTIFIER_CONFLICT"));continue;}
            String status=result.path("Status").asText();
            if("ServiceError".equals(status)){unresolved.add(r);continue;}
            if(!"Success".equals(status)){unresolved.add(r);rejected.add(new Rejected(r,"ClientError".equals(status)?"AMAZON_CLIENT_ERROR":"INVALID_RESPONSE_STATUS"));continue;}
            var id=result.path("FeesEstimateIdentifier");
            boolean valid=id!=null&&"Success".equals(result.path("Status").asText())&&"SellerSKU".equals(id.path("IdType").asText())
                &&r.target().sku().equals(id.path("IdValue").asText())&&r.basis().path("MarketplaceId").asText().equals(id.path("MarketplaceId").asText())
                &&r.basis().path("_platformSellerId").asText().equals(id.path("SellerId").asText())
                &&id.path("IsAmazonFulfilled").isBoolean()&&id.path("IsAmazonFulfilled").asBoolean()
                &&money(id.path("PriceToEstimateFees").path("ListingPrice"),r.target().price(),r.target().currency())
                &&money(id.path("PriceToEstimateFees").path("Shipping"),BigDecimal.ZERO,r.target().currency())
                &&(!id.has("OptionalFulfillmentProgram")||id.path("OptionalFulfillmentProgram").asText().equals(r.basis().path("OptionalFulfillmentProgram").asText("FBA_CORE")));
            var amounts=valid?strictAmounts(result.path("FeesEstimate"),r.target().currency()):null;
            if(amounts==null){unresolved.add(r);rejected.add(new Rejected(r,!valid?"RESPONSE_BASIS_CONFLICT":unsupportedReason(result.path("FeesEstimate"),r.target().currency(),0)));}
            else accepted.add(new Accepted(r,result,amounts));
        }
        return new Result(List.copyOf(accepted),List.copyOf(unresolved),List.copyOf(rejected));
    }
    private static String unsupportedReason(JsonNode node,String currency,int depth){
        if(depth>7)return "UNSUPPORTED_FEE_ASSESSMENT";
        if(node.isObject()){
            for(String key:List.of("FeePromotion","TaxAmount"))if(node.has(key)){
                var extra=value(node.path(key),currency);
                if(extra!=null&&extra.signum()!=0)return "FeePromotion".equals(key)?"UNSUPPORTED_FEE_PROMOTION":"UNSUPPORTED_FEE_TAX";
            }
        }
        if(node.isObject()||node.isArray())for(var child:node){var reason=unsupportedReason(child,currency,depth+1);if(!"UNSUPPORTED_FEE_ASSESSMENT".equals(reason))return reason;}
        return "UNSUPPORTED_FEE_ASSESSMENT";
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
    /** US standard fulfillment was formerly called pick-and-pack (Amazon US help GPDC3KPYAGDTVDJP).
     * This bounded interpretation is not an enum alias: nonzero other components, programs,
     * unknown fields, taxes or promotions cannot qualify. The legacy reader is unchanged. */
    static BigDecimal comparableFba(JsonNode estimate,String currency,JsonNode basis){
        var exact=comparableFba(estimate,currency);if(exact!=null)return exact;
        if(!"USD".equals(currency)||!"ATVPDKIKX0DER".equals(basis.path("MarketplaceId").asText())
            ||!basis.path("IsAmazonFulfilled").isBoolean()||!basis.path("IsAmazonFulfilled").asBoolean()
            ||(basis.has("OptionalFulfillmentProgram")&&!"FBA_CORE".equals(basis.path("OptionalFulfillmentProgram").asText()))
            ||strictAmounts(estimate,currency)==null)return null;
        for(var fee:estimate.path("FeeDetailList")){
            String type=fee.path("FeeType").asText();
            if(!Set.of("ReferralFee","FBAFees","VariableClosingFee","PerItemFee").contains(type))return null;
            if(Set.of("VariableClosingFee","PerItemFee").contains(type)&&finalAmount(fee,currency,0).signum()!=0)return null;
        }
        for(var fee:estimate.path("FeeDetailList"))if("FBAFees".equals(fee.path("FeeType").asText())){
            if(!knownFeeFields(fee,true))return null;
            var children=fee.path("IncludedFeeDetailList");if(!children.isArray()||children.size()!=3)return null;
            Map<String,BigDecimal> parts=new HashMap<>();
            for(var child:children){if(!knownFeeFields(child,false))return null;parts.put(child.path("FeeType").asText(),finalAmount(child,currency,0));}
            if(!parts.keySet().equals(Set.of("FBAPickAndPack","FBAWeightHandling","FBAOrderHandling"))
                ||parts.get("FBAWeightHandling").signum()!=0||parts.get("FBAOrderHandling").signum()!=0)return null;
            var pick=parts.get("FBAPickAndPack");return pick.compareTo(finalAmount(fee,currency,0))==0?pick:null;
        }
        return null;
    }
    private static boolean knownFeeFields(JsonNode fee,boolean aggregate){
        var allowed=Set.of("FeeType","FeeAmount","FinalFee","FeePromotion","TaxAmount","IncludedFeeDetailList");
        for(var field:fee.properties())if(!allowed.contains(field.getKey())||(!aggregate&&"IncludedFeeDetailList".equals(field.getKey())))return false;
        for(String key:List.of("FeeAmount","FinalFee","FeePromotion","TaxAmount"))if(fee.has(key))for(var field:fee.path(key).properties())if(!Set.of("CurrencyCode","Amount").contains(field.getKey()))return false;
        return true;
    }
    private static BigDecimal value(JsonNode n,String currency){
        if(!currency.equals(n.path("CurrencyCode").asText())||!n.path("Amount").isNumber())return null;
        var amount=n.path("Amount").decimalValue();return amount.signum()>=0?amount:null;
    }
    private static boolean money(JsonNode n,BigDecimal value,String currency){return currency.equals(n.path("CurrencyCode").asText())&&n.path("Amount").isNumber()&&value.compareTo(n.path("Amount").decimalValue())==0;}
}
