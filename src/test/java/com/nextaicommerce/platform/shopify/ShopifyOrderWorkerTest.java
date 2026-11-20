package com.nextaicommerce.platform.shopify;

import com.nextaicommerce.platform.web.ShopifyConnectionService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ShopifyOrderWorkerTest {
    final ShopifyOrderJobs jobs=mock(ShopifyOrderJobs.class);final ShopifyOrderClient client=mock(ShopifyOrderClient.class);
    final ShopifyOrderWorker worker=new ShopifyOrderWorker(jobs,client);
    final Instant now=Instant.parse("2026-10-27T10:00:00Z");
    final ShopifyOrderJobs.Job job=new ShopifyOrderJobs.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"owner",1,Map.of("connectionRevision",now.toString(),"start",now.minusSeconds(3600).toString(),"end",now.toString(),"actor","owner@example.invalid"));
    @Test void revokedRevisionCannotPublishOrTouchStock(){
        when(client.fetch(any(),any(),any(),any(),any(),any())).thenReturn(new ShopifyConnectionService.CatalogResponse("{}","shop","USD",now.plusSeconds(1)));
        assertThat(worker.process(job)).isFalse();verify(jobs).pauseRevoked(job);verify(jobs,never()).publish(any(),any(),any());
    }
    @Test void rateLimitUsesProviderRetryTime(){
        var rate=mock(ShopifyConnectionService.RateLimited.class);when(rate.retryAt()).thenReturn(now.plusSeconds(120));
        when(client.fetch(any(),any(),any(),any(),any(),any())).thenThrow(rate);when(jobs.retry(job,rate.retryAt())).thenReturn(true);
        assertThat(worker.process(job)).isTrue();verify(jobs).retry(job,now.plusSeconds(120));verify(jobs,never()).publish(any(),any(),any());
    }
    @Test void malformedDataIsArchivedThroughFencedFailureInsteadOfPublished(){
        var response=new ShopifyConnectionService.CatalogResponse("{bad","shop","USD",now);
        when(client.fetch(any(),any(),any(),any(),any(),any())).thenReturn(response);when(client.parse(response,null)).thenThrow(new IllegalArgumentException("Malformed Orders"));
        assertThat(worker.process(job)).isFalse();verify(jobs).fail(job,"{bad","Malformed Orders");verify(jobs,never()).publish(any(),any(),any());
    }
    @Test void leaseLostAfterHttpNeverPublishesPartialStock(){
        var response=new ShopifyConnectionService.CatalogResponse("{}","shop","USD",now);var page=new ShopifyOrderClient.Page(List.of(),null,false,1,1000,50);
        when(client.fetch(any(),any(),any(),any(),any(),any())).thenReturn(response);when(client.parse(response,null)).thenReturn(page);when(jobs.publish(job,"{}",page)).thenThrow(new ShopifyOrderJobs.LostLease());
        assertThat(worker.process(job)).isFalse();verify(jobs).pauseRevoked(job);
    }
    @Test void defaultOffWorkerDoesNoWork(){worker.start(job.tenant(),job.connection(),job.id());verifyNoInteractions(client);verify(jobs,never()).claim(any(),any(),any());}
}
