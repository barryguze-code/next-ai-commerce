package com.nextaicommerce.platform.sync;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AmazonProductFeeResponseTest {
    final ObjectMapper json=new ObjectMapper();
    final AmazonProductFeeJobs jobs=mock(AmazonProductFeeJobs.class);final AmazonSpApiClient api=mock(AmazonSpApiClient.class);
    final AmazonSyncStore.Job job=new AmazonSyncStore.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"FEES_SNAPSHOT","FBA_FEES","ATVPDKIKX0DER",Instant.now(),Instant.now(),null,0,0,false,null,"fixture");
    final AmazonProductFeeJobs.Target target=new AmazonProductFeeJobs.Target("00001",new BigDecimal("30.00"),"USD");
    @ParameterizedTest @ValueSource(strings={"none","sku","market","FBA","price","shipping"})
    void onlyCorrelatedCompatibleResponsesCanPublish(String mismatch){
        var basis=json.createObjectNode().put("MarketplaceId",job.marketplaceId()).put("IsAmazonFulfilled",true);
        var price=basis.putObject("PriceToEstimateFees");price.putObject("ListingPrice").put("CurrencyCode","USD").put("Amount",new BigDecimal("30.00"));price.putObject("Shipping").put("CurrencyCode","USD").put("Amount",BigDecimal.ZERO);
        when(jobs.next(job)).thenReturn(target);when(jobs.basis(job,target)).thenReturn(basis);when(jobs.allowed(job,target.sku())).thenReturn(true);when(jobs.permit(job,target.sku())).thenReturn(jobs.new Permit(job,target.sku()));
        when(api.quoteProductFee(any(),any(),anyString(),anyString(),any())).thenAnswer(call->{
            var request=json.readTree((String)call.getArgument(3)).path("FeesEstimateRequest");
            ObjectNode result=json.createObjectNode().put("Status","Success");var identity=result.putObject("FeesEstimateIdentifier");
            identity.put("IdValue",mismatch.equals("sku")?"0001":target.sku()).put("IdType","SellerSKU").put("MarketplaceId",mismatch.equals("market")?"OTHER":job.marketplaceId()).put("SellerInputIdentifier",request.path("Identifier").asText()).put("IsAmazonFulfilled",!mismatch.equals("FBA"));
            ObjectNode quoted=price.deepCopy();if(mismatch.equals("price"))quoted.withObject("ListingPrice").put("Amount",31);if(mismatch.equals("shipping"))quoted.withObject("Shipping").put("Amount",1);identity.set("PriceToEstimateFees",quoted);
            result.set("FeesEstimate",json.readTree("{\"TotalFeesEstimate\":{\"Amount\":8,\"CurrencyCode\":\"USD\"},\"FeeDetailList\":[{\"FeeType\":\"ReferralFee\",\"FeeAmount\":{\"Amount\":3,\"CurrencyCode\":\"USD\"}},{\"FeeType\":\"FBAFees\",\"FeeAmount\":{\"Amount\":5,\"CurrencyCode\":\"USD\"}}]}"));
            var root=json.createObjectNode();root.putObject("payload").set("FeesEstimateResult",result);return new AmazonSpApiClient.ApiResponse(200,"synthetic",root,root.toString());
        });
        new AmazonProductFeeSync(api,jobs,true).processPage(job);
        if(mismatch.equals("none"))verify(jobs).publish(eq(job),eq(target),eq(basis),any(),any(),any());
        else{verify(jobs,never()).publish(any(),any(),any(),any(),any(),any());verify(jobs).failed(eq(job),eq(target.sku()),any(),anyString(),eq("synthetic"),isNull());}
    }
    @Test void negativeMoneyIsUnknownWhileKnownZeroIsValid(){
        var fees=json.readTree("{\"TotalFeesEstimate\":{\"Amount\":0,\"CurrencyCode\":\"USD\"},\"FeeDetailList\":[{\"FeeType\":\"ReferralFee\",\"FeeAmount\":{\"Amount\":0,\"CurrencyCode\":\"USD\"}},{\"FeeType\":\"FBAFees\",\"FeeAmount\":{\"Amount\":0,\"CurrencyCode\":\"USD\"}}]}");
        assertThat(AmazonProductFeeSync.amounts(fees,"USD").fulfillment()).isZero();((ObjectNode)fees.path("TotalFeesEstimate")).put("Amount",-1);assertThat(AmazonProductFeeSync.amounts(fees,"USD")).isNull();
    }
}
