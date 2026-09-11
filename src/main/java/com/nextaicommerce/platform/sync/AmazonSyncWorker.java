package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.sync.AmazonSpApiClient.AmazonApiException;
import com.nextaicommerce.platform.orders.OrderRepository;
import com.nextaicommerce.platform.marketplace.MarketplaceSkuAutoMapper;
import java.time.Duration;
import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AmazonSyncWorker {
    private static final Logger log=LoggerFactory.getLogger(AmazonSyncWorker.class);
    private static final Map<String,String> REPORT_TYPES=Map.ofEntries(
        Map.entry("LISTINGS_SNAPSHOT","GET_MERCHANT_LISTINGS_ALL_DATA"),
        Map.entry("ORDERS_30_DAY","GET_FLAT_FILE_ALL_ORDERS_DATA_BY_ORDER_DATE_GENERAL"),
        Map.entry("INVENTORY_SNAPSHOT","GET_FBA_MYI_ALL_INVENTORY_DATA"),
        Map.entry("INVENTORY_LEDGER_30_DAY","GET_LEDGER_DETAIL_VIEW_DATA"),
        Map.entry("FBA_CUSTOMER_SHIPMENTS_30_DAY","GET_AMAZON_FULFILLED_SHIPMENTS_DATA_GENERAL"),
        Map.entry("RETURNS_30_DAY","GET_FBA_FULFILLMENT_CUSTOMER_RETURNS_DATA"),
        Map.entry("REIMBURSEMENTS_30_DAY","GET_FBA_REIMBURSEMENTS_DATA"),
        Map.entry("FEES_SNAPSHOT","GET_FBA_ESTIMATED_FBA_FEES_TXT_DATA"));
    private final AmazonSyncStore store; private final AmazonSpApiClient amazon; private final AmazonReportNormalizer normalizer;
    private final MarketplaceSkuAutoMapper skuMapper; private final OrderRepository orders; private final boolean enabled;
    private AmazonCompetitivePricingService competitivePricing;
    private final String workerId="worker-"+UUID.randomUUID();
    public AmazonSyncWorker(AmazonSyncStore store,AmazonSpApiClient amazon,AmazonReportNormalizer normalizer,
            MarketplaceSkuAutoMapper skuMapper,OrderRepository orders,
            @Value("${app.amazon.sync-enabled:true}") boolean enabled){
        this.store=store;this.amazon=amazon;this.normalizer=normalizer;this.skuMapper=skuMapper;
        this.orders=orders;this.enabled=enabled;
    }
    @Autowired(required=false)
    void configureCompetitivePricing(AmazonCompetitivePricingService service){this.competitivePricing=service;}

    @Scheduled(fixedDelayString="${app.amazon.worker-delay-ms:5000}")
    public void processNext(){
        if(!enabled)return;
        processClaimed(store.claim(workerId+"-general"));
    }

    @Scheduled(fixedDelayString="${app.amazon.order-worker-delay-ms:2000}",initialDelayString="${app.amazon.order-worker-initial-delay-ms:1000}")
    public void processOrderRefresh(){
        if(!enabled)return;
        processClaimed(store.claimOrderRefresh(workerId+"-orders"));
    }

    private void processClaimed(AmazonSyncStore.Job job){
        if(job==null)return;
        log.info("{} started — {}. Data window: {} to {}. Attempt {}.",runLabel(job),stageLabel(job.type()),
            job.windowStart(),job.windowEnd(),job.attempts()+1);
        try{process(job);}
        catch(PermanentSyncException ex){store.fail(job,ex);}
        catch(AmazonApiException ex){store.retryOrFail(job,ex,ex.status()==429?ex.retryAfter():null);}
        catch(Exception ex){store.retryOrFail(job,ex,null);}
    }

    private void process(AmazonSyncStore.Job job){
        if("VERIFY_SELLER".equals(job.type())){
            var response=amazon.get(job.tenantId(),job.connectionId(),"/sellers/v1/marketplaceParticipations");
            boolean marketplaceFound=marketplaceEnabled(response.json(),job.marketplaceId());
            if(!marketplaceFound)throw new PermanentSyncException("Amazon US is not enabled for this seller account.");
            log.info("Amazon store verified: run={} connection={} marketplace={}",
                job.runId(),job.connectionId(),job.marketplaceId());
            store.saveJsonAndComplete(job,"marketplace-participations",response.body(),response.requestId(),source->1);return;
        }
        if("ORDER_ITEMS_30_DAY".equals(job.type())){store.complete(job,0);return;}
        if("ORDERS_API_DELTA".equals(job.type())){importOrderChanges(job);return;}
        if("FINANCES_30_DAY".equals(job.type())){
            importFinances(job);return;
        }
        if("FINAL_RECONCILIATION".equals(job.type())){
            skuMapper.mapConnection(job.tenantId(),job.connectionId());
            var result=orders.reconcile(job.tenantId(),job.connectionId());
            log.info("{} inventory reconciliation complete — {} open order(s) reserve {} eaches; "
                    +"{} order(s) need stock; {} order(s) need SKU mapping; "
                    +"{} open Amazon-fulfilled order(s) use Amazon inventory.",
                runLabel(job),result.reservedOrders(),result.reservedEaches(),result.shortageOrders(),
                result.unmappedOrders(),result.amazonFulfilledOpenOrders());
            store.finishRun(job,reconciliationMessage(result));return;
        }
        String reportType=REPORT_TYPES.get(job.type());
        if(reportType==null){store.complete(job,0);return;}
        if(job.reportId()==null||job.reportId().isBlank()){
            boolean windowed=job.type().contains("30_DAY");
            String reportId=amazon.createReport(job.tenantId(),job.connectionId(),reportType,
                job.marketplaceId(),windowed?job.windowStart():null,windowed?job.windowEnd():null);
            log.info("{} requested an Amazon report — type: {}; Amazon report ID: {}.",
                runLabel(job),reportType,reportId);
            store.reportRequested(job,reportType,reportId);return;
        }
        var report=amazon.get(job.tenantId(),job.connectionId(),"/reports/2021-06-30/reports/"+job.reportId());
        String status=report.json().path("processingStatus").asText();
        if("CANCELLED".equals(status)||"FATAL".equals(status)){
            if(job.required())throw new IllegalStateException("Amazon could not prepare a required setup report.");
            store.skipUnavailableReport(job,status,"Amazon could not prepare this optional report at this time.");return;
        }
        if(!"DONE".equals(status)){
            Duration elapsed=job.reportRequestedAt()==null?Duration.ZERO:Duration.between(job.reportRequestedAt(),Instant.now());
            boolean delayed=reportDelayed(elapsed);
            boolean nonBlocking=reportNonBlocking(elapsed);
            Duration nextCheck=reportPollDelay(elapsed);
            log.info("{} is waiting for Amazon to prepare the report — status: {}; elapsed: {} minute(s); "
                    +"next check in {} second(s){}.",runLabel(job),status,elapsed.toMinutes(),nextCheck.toSeconds(),
                nonBlocking?"; other work may continue meanwhile":delayed?"; Amazon is taking longer than usual":"");
            store.waitForReport(job,status,nextCheck,delayed,nonBlocking);return;
        }
        String documentId=report.json().path("reportDocumentId").asText();
        log.info("{} report is ready — secure download is starting. Amazon report ID: {}.",
            runLabel(job),job.reportId());
        Instant downloadStarted=Instant.now();
        String document=amazon.downloadDocument(job.tenantId(),job.connectionId(),documentId);
        log.info("{} report download complete — {} KB received in {} second(s). Local import is starting.",
            runLabel(job),Math.max(1,document.getBytes(StandardCharsets.UTF_8).length/1024),
            Duration.between(downloadStarted,Instant.now()).toSeconds());
        Instant importStarted=Instant.now();
        store.saveDocumentAndComplete(job,documentId,document,report.requestId(),
            source->normalizer.normalize(job,document,source));
        log.info("{} local report import complete in {} second(s).",runLabel(job),
            Duration.between(importStarted,Instant.now()).toSeconds());
        if("LISTINGS_SNAPSHOT".equals(job.type())&&competitivePricing!=null){
            try{long prices=competitivePricing.refresh(job);log.info("{} Buy Box enrichment complete — {} SKU price(s) updated.",runLabel(job),prices);}
            catch(Exception ex){log.warn("{} Buy Box enrichment was unavailable; listing refresh remains complete: {}",runLabel(job),ex.getMessage());}
        }
    }

    private void importFinances(AmazonSyncStore.Job job){
        String nextToken=null;long total=0;
        for(int page=1;page<=250;page++){
            String path="/finances/2024-06-19/transactions?postedAfter="+encode(job.windowStart().toString())+
                "&postedBefore="+encode(job.windowEnd().toString())+"&marketplaceId="+encode(job.marketplaceId());
            if(nextToken!=null)path+="&nextToken="+encode(nextToken);
            var response=amazon.get(job.tenantId(),job.connectionId(),path);
            int currentPage=page;
            total+=store.saveJsonPage(job,"transactions-"+job.windowStart()+"-page-"+page,response.body(),response.requestId(),
                source->normalizer.normalizeJson(job,response.json(),source));
            nextToken=response.json().path("payload").path("nextToken").asText();
            if(nextToken==null||nextToken.isBlank()){store.complete(job,total);return;}
            log.info("Amazon finances page imported: connection={} page={} recordsSoFar={}; requesting next page",
                job.connectionId(),currentPage,total);
            try{Thread.sleep(2100);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Financial import interrupted.",ex);}
        }
        throw new IllegalStateException("Amazon finances exceeded the safe 250-page import limit.");
    }

    private void importOrderChanges(AmazonSyncStore.Job job){
        String nextToken=null;long total=0;int orderPages=0;Set<String> orderIds=new HashSet<>();
        for(int page=1;page<=100;page++){
            String path;
            if(nextToken==null){
                path="/orders/v0/orders?MarketplaceIds="+encode(job.marketplaceId())+
                    "&LastUpdatedAfter="+encode(job.windowStart().toString())+
                    "&LastUpdatedBefore="+encode(job.windowEnd().toString())+
                    "&MaxResultsPerPage=100";
            }else path="/orders/v0/orders?NextToken="+encode(nextToken);
            var response=amazon.get(job.tenantId(),job.connectionId(),path);
            orderPages=page;
            total+=store.saveJsonPage(job,"orders-delta-"+job.windowEnd()+"-page-"+page,
                response.body(),response.requestId(),source->normalizer.normalizeOrderPage(job,response.json(),source));
            response.json().path("payload").path("Orders").forEach(order->{
                String id=order.path("AmazonOrderId").asText();if(!id.isBlank())orderIds.add(id);
            });
            nextToken=response.json().path("payload").path("NextToken").asText();
            if(nextToken==null||nextToken.isBlank())break;
        }
        if(nextToken!=null&&!nextToken.isBlank())throw new IllegalStateException("Amazon order changes exceeded the safe 100-page limit; the update will retry instead of silently completing with missing orders.");
        log.info("{} found {} new or changed order(s) across {} page(s). Item details are next.",
            runLabel(job),orderIds.size(),orderPages);
        int itemPage=0;
        for(String orderId:orderIds){
            String itemToken=null;int orderItemPage=0;
            do{
                String path=itemToken==null?"/orders/v0/orders/"+encode(orderId)+"/orderItems":
                    "/orders/v0/orders/"+encode(orderId)+"/orderItems?NextToken="+encode(itemToken);
                var response=amazon.get(job.tenantId(),job.connectionId(),path);int currentPage=++itemPage;orderItemPage++;
                total+=store.saveJsonPage(job,"order-items-delta-"+orderId+"-page-"+currentPage,
                    response.body(),response.requestId(),source->normalizer.normalizeOrderItems(job,orderId,response.json(),source));
                itemToken=response.json().path("payload").path("NextToken").asText();
                pauseOrderItems();
            }while(itemToken!=null&&!itemToken.isBlank()&&orderItemPage<100);
            if(itemToken!=null&&!itemToken.isBlank())throw new IllegalStateException("Amazon order items exceeded the safe page limit for order "+orderId+"; the update will retry.");
        }
        log.info("{} Amazon import complete — {} changed order(s), {} item page(s), {} row(s). "
                +"Inventory reconciliation is next.",runLabel(job),orderIds.size(),itemPage,total);
        store.complete(job,total);
    }

    private static void pauseOrderItems(){
        try{Thread.sleep(2100);}catch(InterruptedException ex){Thread.currentThread().interrupt();
            throw new IllegalStateException("Order update interrupted.",ex);}
    }

    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String stageLabel(String type){return switch(type){
        case "ORDERS_API_DELTA"->"Checking new and changed orders through Orders API";
        case "ORDERS_30_DAY"->"Downloading and importing the Amazon orders report";
        case "ORDER_ITEMS_30_DAY"->"Order items are included in the orders report; no extra Amazon call needed";
        case "INVENTORY_SNAPSHOT"->"Downloading current FBA inventory report";
        case "LISTINGS_SNAPSHOT"->"Downloading Amazon listings report";
        case "FINAL_RECONCILIATION"->"Finishing local database reconciliation; no Amazon call";
        default->type.toLowerCase().replace('_',' ');};}
    private static String runLabel(AmazonSyncStore.Job job){
        String id=job.runId().toString().substring(0,8);
        String purpose=switch(job.profile()){
            case "ORDER_CHANGES"->"Amazon order refresh";
            case "STARTUP_ORDERS"->"Application-start 30-day order check";
            case "RECENT_ORDER_RECONCILIATION"->"Six-hour order safety check";
            case "ORDER_LIFECYCLE"->"Daily 30-day order check";
            default->"Amazon "+job.profile().toLowerCase().replace('_',' ');
        };
        return purpose+" ["+id+"]";
    }
    private static String reconciliationMessage(OrderRepository.ReconciliationResult result){
        return "Amazon order check complete · "+result.reservedOrders()+" open order(s) reserve "
            +result.reservedEaches()+" eaches · "+result.shortageOrders()+" stock shortage(s) · "
            +result.unmappedOrders()+" need SKU mapping";
    }

    static boolean marketplaceEnabled(tools.jackson.databind.JsonNode response, String marketplaceId) {
        return response.path("payload").findValues("id").stream()
            .anyMatch(node -> marketplaceId.equals(node.asText()));
    }

    static boolean reportDelayed(Duration elapsed) {
        return elapsed.compareTo(Duration.ofMinutes(30)) >= 0;
    }

    static boolean reportNonBlocking(Duration elapsed) {
        return elapsed.compareTo(Duration.ofMinutes(60)) >= 0;
    }

    static Duration reportPollDelay(Duration elapsed) {
        if (elapsed.compareTo(Duration.ofMinutes(15)) < 0) return Duration.ofSeconds(45);
        if (elapsed.compareTo(Duration.ofMinutes(30)) < 0) return Duration.ofMinutes(2);
        return Duration.ofMinutes(5);
    }

    static final class PermanentSyncException extends RuntimeException {
        PermanentSyncException(String message) { super(message); }
    }
}
