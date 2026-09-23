package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.nextaicommerce.platform.sync.InventoryPublicationRepository.Pending;

class InventoryPublicationWorkerTest {
 final InventoryPublicationRepository repo=mock(InventoryPublicationRepository.class);
 final AmazonSpApiClient amazon=mock(AmazonSpApiClient.class);final ObjectMapper json=new ObjectMapper();
 InventoryPublicationWorker worker(String mode,boolean local,boolean writes){return new InventoryPublicationWorker(repo,mock(TransactionTemplate.class),amazon,json,mode,local,writes,false);}
 Pending pending(int q,String status,int attempts){return new Pending(UUID.randomUUID(),UUID.randomUUID(),"ATVPDKIKX0DER","sku / +",q,2,status,attempts,"seller");}
 AmazonSpApiClient.ApiResponse response(String body){return new AmazonSpApiClient.ApiResponse(200,"test",json.readTree(body),body);}
 @Test void localCanNeverEnableLiveWrites(){assertThatThrownBy(()->worker("LIVE",true,true)).isInstanceOf(IllegalStateException.class);assertThatThrownBy(()->worker("LIVE",false,false)).isInstanceOf(IllegalStateException.class);}
 @Test void dryRunDoesNotReadOrWriteAmazon(){var p=pending(0,"PENDING",0);worker("DRY_RUN",true,false).process(p);verifyNoInteractions(amazon);verify(repo).result(eq(p),eq("DRY_RUN"),eq(0),eq(0),anyString(),isNull());}
 @Test void acceptedIsNotConfirmedAndPatchOnlyMergesQuantity(){var p=pending(0,"PENDING",0);when(amazon.patch(any(),any(),anyString(),anyString())).thenReturn(response("{\"status\":\"ACCEPTED\"}"));worker("LIVE",false,true).process(p);verify(repo).result(p,"VERIFYING",1,10,null,null);var body=org.mockito.ArgumentCaptor.forClass(String.class);verify(amazon).patch(eq(p.tenant()),eq(p.connection()),contains("sku%20%2F%20%2B"),body.capture());assertThat(body.getValue()).contains("\"op\":\"merge\"","\"quantity\":0").doesNotContain("lead_time","restock_date");}
 @Test void liveQuantityConfirmsInsteadOfSubmittedAttributes(){var p=pending(0,"VERIFYING",1);when(amazon.get(any(),any(),anyString())).thenReturn(response("{\"attributes\":{\"quantity\":99},\"fulfillmentAvailability\":[{\"fulfillmentChannelCode\":\"DEFAULT\",\"quantity\":0}]}"));worker("LIVE",false,true).process(p);verify(repo).result(p,"CONFIRMED",0,900,null,0);verify(amazon,never()).patch(any(),any(),anyString(),anyString());}
 @Test void lowerQuantityNeverRestocksPotentialUnimportedSale(){var p=pending(5,"VERIFYING",1);when(amazon.get(any(),any(),anyString())).thenReturn(response("{\"fulfillmentAvailability\":[{\"fulfillmentChannelCode\":\"DEFAULT\",\"quantity\":3}]}"));worker("LIVE",false,true).process(p);verify(repo).result(eq(p),eq("ATTENTION"),eq(2),eq(300),anyString(),eq(3));verify(amazon,never()).patch(any(),any(),anyString(),anyString());}
 @Test void zeroRemainsRetryableAfterThreeFailures(){var p=pending(0,"ATTENTION",4);when(amazon.get(any(),any(),anyString())).thenReturn(response("{\"fulfillmentAvailability\":[{\"fulfillmentChannelCode\":\"DEFAULT\",\"quantity\":2}]}"));worker("LIVE",false,true).process(p);verify(repo).result(eq(p),eq("PENDING"),eq(5),anyInt(),contains("Urgent"),eq(2));}
 @Test void failureIsDurableNotCompleted(){var p=pending(0,"PENDING",0);when(amazon.patch(any(),any(),anyString(),anyString())).thenThrow(new IllegalStateException("429"));worker("LIVE",false,true).process(p);verify(repo).result(eq(p),eq("RETRY"),eq(1),anyInt(),anyString(),isNull());}
 @Test void invalidSubmissionIsNotSuccess(){var p=pending(0,"PENDING",0);when(amazon.patch(any(),any(),anyString(),anyString())).thenReturn(response("{\"status\":\"INVALID\"}"));worker("LIVE",false,true).process(p);verify(repo).result(eq(p),eq("RETRY"),eq(1),anyInt(),anyString(),isNull());}
}
