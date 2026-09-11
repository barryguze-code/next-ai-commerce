package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper;
import com.nextaicommerce.platform.orders.OrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AmazonSyncWorkerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void readsMarketplaceIdFromSellersApiPayloadArray() throws Exception {
        var response = json.readTree("""
            {"payload":[{"marketplace":{"id":"ATVPDKIKX0DER","name":"Amazon.com"},
              "participation":{"isParticipating":true}}]}
            """);
        assertThat(AmazonSyncWorker.marketplaceEnabled(response, "ATVPDKIKX0DER")).isTrue();
        assertThat(AmazonSyncWorker.marketplaceEnabled(response, "A1F83G8C2ARO7P")).isFalse();
    }

    @Test
    void backsOffLongRunningAmazonReportsAndMarksThemDelayed() {
        assertThat(AmazonSyncWorker.reportPollDelay(Duration.ofMinutes(14))).isEqualTo(Duration.ofSeconds(45));
        assertThat(AmazonSyncWorker.reportPollDelay(Duration.ofMinutes(15))).isEqualTo(Duration.ofMinutes(2));
        assertThat(AmazonSyncWorker.reportPollDelay(Duration.ofMinutes(30))).isEqualTo(Duration.ofMinutes(5));
        assertThat(AmazonSyncWorker.reportDelayed(Duration.ofMinutes(29))).isFalse();
        assertThat(AmazonSyncWorker.reportDelayed(Duration.ofMinutes(30))).isTrue();
        assertThat(AmazonSyncWorker.reportNonBlocking(Duration.ofMinutes(59))).isFalse();
        assertThat(AmazonSyncWorker.reportNonBlocking(Duration.ofMinutes(60))).isTrue();
    }

    @Test
    void finalStageMapsAndReconcilesBeforeCompletingTheRun() {
        AmazonSyncStore store=mock(AmazonSyncStore.class);
        MarketplaceSkuAutoMapper mapper=mock(MarketplaceSkuAutoMapper.class);
        OrderRepository orders=mock(OrderRepository.class);
        UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),run=UUID.randomUUID();
        var job=new AmazonSyncStore.Job(UUID.randomUUID(),tenant,run,connection,"FINAL_RECONCILIATION",
            "ORDER_CHANGES","ATVPDKIKX0DER",Instant.now().minusSeconds(900),Instant.now(),null,0,0,false,null);
        when(store.claim(anyString())).thenReturn(job);
        when(orders.reconcile(tenant,connection)).thenReturn(
            new OrderRepository.ReconciliationResult(2,new BigDecimal("24"),0,0,0));
        var worker=new AmazonSyncWorker(store,mock(AmazonSpApiClient.class),mock(AmazonReportNormalizer.class),
            mapper,orders,true);

        worker.processNext();

        verify(mapper).mapConnection(tenant,connection);
        verify(orders).reconcile(tenant,connection);
        verify(store).finishRun(job,"Amazon order check complete · 2 open order(s) reserve 24 eaches · "
            +"0 stock shortage(s) · 0 need SKU mapping");
    }
}
