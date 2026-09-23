package com.nextaicommerce.platform.sync;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

class UnresolvedOrderWorkerTest {
 final UnresolvedOrderRepository repo=mock(UnresolvedOrderRepository.class);
 final AmazonSpApiClient amazon=mock(AmazonSpApiClient.class);
 final TransactionTemplate tx=new TransactionTemplate(){@Override public void executeWithoutResult(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action){action.accept(new SimpleTransactionStatus());}};
 final UnresolvedOrderWorker worker=new UnresolvedOrderWorker(repo,amazon,tx);
 final UnresolvedOrderRepository.Check check=new UnresolvedOrderRepository.Check(UUID.randomUUID(),UUID.randomUUID(),"111-1234567-1234567",0);
 void response(String id,String status,String date){var json=new ObjectMapper().createObjectNode();json.putObject("payload").put("AmazonOrderId",id).put("OrderStatus",status).put("LastUpdateDate",date);when(amazon.get(any(),any(),anyString())).thenReturn(new AmazonSpApiClient.ApiResponse(200,"test",json,""));}
 @Test void confirmedCancellationIsApplied(){response(check.orderId(),"Canceled","2026-09-22T12:00:00Z");worker.check(check);verify(repo).confirmed(check,"Canceled",Instant.parse("2026-09-22T12:00:00Z"));verifyNoMoreInteractions(repo);verify(amazon,never()).patch(any(),any(),anyString(),anyString());}
 @Test void missingOrderDoesNotMeanCancellation(){when(amazon.get(any(),any(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(404,null,"Missing"));worker.check(check);verify(repo).failed(eq(check),eq("UNCONFIRMED"),contains("not confirmed cancellation"),anyInt());verify(repo,never()).confirmed(any(),anyString(),any());}
 @Test void stillPendingKeepsStatusEvenAfterThirtyDays(){response(check.orderId(),"Pending","2026-08-01T12:00:00Z");worker.check(check);verify(repo).confirmed(eq(check),eq("Pending"),any());}
 @Test void wrongOrderOrMissingTimestampCannotReleaseStock(){response("other-order","Canceled","2026-09-22T12:00:00Z");worker.check(check);response(check.orderId(),"Canceled","");worker.check(check);verify(repo,never()).confirmed(any(),anyString(),any());verify(repo,times(2)).failed(eq(check),eq("RETRY"),anyString(),anyInt());}
 @Test void throttlingHonorsRetryAfter(){when(amazon.get(any(),any(),anyString())).thenThrow(new AmazonSpApiClient.AmazonApiException(429,Duration.ofMinutes(10),"Rate limit"));worker.check(check);verify(repo).failed(eq(check),eq("RETRY"),anyString(),eq(600));}
 @Test void unknownStatusCannotReleaseStock(){response(check.orderId(),"Unknown", "2026-09-22T12:00:00Z");worker.check(check);verify(repo,never()).confirmed(any(),anyString(),any());}
}
