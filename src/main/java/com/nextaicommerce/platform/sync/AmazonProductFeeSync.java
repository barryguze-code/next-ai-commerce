package com.nextaicommerce.platform.sync;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Existing queue, one exact seller-SKU read per claim; disabled by default. */
@Component
public class AmazonProductFeeSync {
    private final AmazonSpApiClient amazon;private final AmazonProductFeeJobs jobs;private final boolean enabled;
    public AmazonProductFeeSync(AmazonSpApiClient amazon,AmazonProductFeeJobs jobs,
            @Value("${app.amazon.product-fees-enabled:false}") boolean enabled){this.amazon=amazon;this.jobs=jobs;this.enabled=enabled;}
    public void processPage(AmazonSyncStore.Job job){
        if(!enabled){jobs.stop(job,"FEE_COLLECTION_DISABLED");return;}
        if(job.reportId()!=null&&!job.reportId().isBlank()){jobs.stop(job,"FEE_LEGACY_REPORT_REQUIRES_REVIEW");return;}
        AmazonProductFeeJobs.Target target=null;
        try{
            target=jobs.next(job);if(target==null)return;
            var basis=jobs.basis(job,target);if(jobs.reuse(job,target,basis)||!jobs.allowed(job,target.sku()))return;
            String identifier=job.id()+":"+UUID.randomUUID();
            var request=basis.deepCopy();request.remove("_platformSellerId");request.put("Identifier",identifier);
            String body="{\"FeesEstimateRequest\":"+request+"}";
            try(var permit=jobs.permit(job,target.sku())){
                var response=amazon.quoteProductFee(job.tenantId(),job.connectionId(),target.sku(),body,permit);
                var result=response.json().path("payload").path("FeesEstimateResult");var id=result.path("FeesEstimateIdentifier");
                boolean matched=target.sku().equals(id.path("IdValue").asText())&&"SellerSKU".equals(id.path("IdType").asText())
                    &&job.marketplaceId().equals(id.path("MarketplaceId").asText())&&identifier.equals(id.path("SellerInputIdentifier").asText());
                if(id.has("IsAmazonFulfilled")&&(!id.path("IsAmazonFulfilled").isBoolean()||!id.path("IsAmazonFulfilled").asBoolean()))matched=false;
                if(id.has("PriceToEstimateFees")){
                    var quoted=id.path("PriceToEstimateFees");
                    matched=matched&&matchesPrice(quoted.path("ListingPrice"),target.price(),target.currency())
                        &&matchesPrice(quoted.path("Shipping"),BigDecimal.ZERO,target.currency());
                }
                var fees="Success".equalsIgnoreCase(result.path("Status").asText())&&matched?amounts(result.path("FeesEstimate"),target.currency()):null;
                if(fees==null){jobs.failed(job,target.sku(),new IllegalStateException("Incomplete or conflicting fee response"),response.body(),response.requestId(),response.rateLimit());return;}
                jobs.publish(job,target,basis,result,fees,response);
            }
        }catch(AmazonSpApiClient.VariationDeferredException deferred){/* Durable deferral/stop was recorded by the permit. */}
        catch(RuntimeException error){
            var read=error instanceof AmazonSpApiClient.FeeReadException e?e:null;
            jobs.failed(job,target==null?"":target.sku(),error,read==null?null:read.body(),read==null?null:read.requestId(),read==null?null:read.rateLimit());
        }
    }
    private static boolean matchesPrice(JsonNode value,BigDecimal amount,String currency){
        return currency.equals(value.path("CurrencyCode").asText())&&value.path("Amount").isNumber()
            &&amount.compareTo(value.path("Amount").decimalValue())==0;
    }
    record Amounts(BigDecimal total,BigDecimal referral,BigDecimal fulfillment){}
    static Amounts amounts(JsonNode estimate,String currency){
        BigDecimal total=amount(estimate.path("TotalFeesEstimate"),currency),referral=null,fulfillment=null;
        for(JsonNode fee:estimate.path("FeeDetailList")){
            var value=amount(fee.has("FinalFee")?fee.path("FinalFee"):fee.path("FeeAmount"),currency);
            if("ReferralFee".equals(fee.path("FeeType").asText()))referral=value;
            if("FBAFees".equals(fee.path("FeeType").asText()))fulfillment=value;
        }
        return total==null||referral==null||fulfillment==null?null:new Amounts(total,referral,fulfillment);
    }
    private static BigDecimal amount(JsonNode money,String currency){
        if(!currency.equals(money.path("CurrencyCode").asText())||!money.path("Amount").isNumber())return null;
        BigDecimal value=money.path("Amount").decimalValue();return value.signum()<0?null:value;
    }
}
