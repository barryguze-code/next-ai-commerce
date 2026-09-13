package com.nextaicommerce.platform.sync;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Read-only Buy Box enrichment kept outside interactive Marketplace SKU requests. */
@Component
public class AmazonCompetitivePricingService {
    private static final Logger log=LoggerFactory.getLogger(AmazonCompetitivePricingService.class);
    private static final int AMAZON_BATCH_SIZE=20;
    private final JdbcTemplate jdbc;
    private final AmazonSpApiClient amazon;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private static long nextPricingRequestNanos;

    public AmazonCompetitivePricingService(JdbcTemplate jdbc,AmazonSpApiClient amazon,TransactionTemplate transactions,
            ObjectMapper json){
        this.jdbc=jdbc;this.amazon=amazon;this.transactions=transactions;this.json=json;
    }

    public long refresh(AmazonSyncStore.Job job){
        return refresh(job.tenantId(),job.connectionId(),job.marketplaceId());
    }

    public long refresh(UUID tenantId,UUID connectionId,String marketplaceId){
        // Claim before reading or calling Amazon, including on imports and after restarts.
        Boolean claimed=transactions.execute(status->{setTenant(tenantId);return jdbc.update("""
            UPDATE marketplace_connections SET buy_box_refresh_after=now()+interval '3 hours'
            WHERE tenant_id=? AND id=?
              AND (buy_box_refresh_after IS NULL OR buy_box_refresh_after<=now())
            """,tenantId,connectionId)==1;});
        if(!Boolean.TRUE.equals(claimed))return 0;
        log.info("Buy Box refresh started — this store will not refresh again for at least 3 hours.");
        try{return refreshPrices(tenantId,connectionId,marketplaceId);}
        catch(AmazonSpApiClient.AmazonApiException ex){
            if(ex.status()==429){
                long seconds=Math.max(10800,ex.retryAfter()==null?0:ex.retryAfter().toSeconds()+1);
                transactions.executeWithoutResult(status->{setTenant(tenantId);jdbc.update("""
                    UPDATE marketplace_connections
                    SET buy_box_refresh_after=GREATEST(buy_box_refresh_after,now()+(? * interval '1 second'))
                    WHERE tenant_id=? AND id=?
                    """,seconds,tenantId,connectionId);});
                log.warn("Amazon pricing rate limit reached — no immediate retries; refresh deferred at least {} seconds.",seconds);
            }
            throw ex;
        }
    }

    private long refreshPrices(UUID tenantId,UUID connectionId,String marketplaceId){
        List<ListingKey> listings=listings(tenantId,connectionId);
        List<String> asins=listings.stream().map(ListingKey::asin).filter(value->value!=null&&!value.isBlank()).distinct().toList();
        List<String> skus=listings.stream().filter(value->value.asin()==null||value.asin().isBlank()).map(ListingKey::sellerSku).toList();
        long updated=0;
        List<String> missingAsins=new ArrayList<>();
        for(QuerySet query:new QuerySet[]{new QuerySet("Asin","Asins",asins,true),new QuerySet("Sku","Skus",skus,false)}){
            for(int start=0;start<query.identifiers().size();start+=AMAZON_BATCH_SIZE){
                pacePricingRequest(10000);
                List<String> batch=query.identifiers().subList(start,Math.min(query.identifiers().size(),start+AMAZON_BATCH_SIZE));
                String path="/products/pricing/v0/competitivePrice?MarketplaceId="+encode(marketplaceId)+
                    "&ItemType="+query.itemType()+"&CustomerType=Consumer&"+query.parameter()+"="+
                    String.join(",",batch.stream().map(AmazonCompetitivePricingService::encode).toList());
                var response=amazon.get(tenantId,connectionId,path);
                List<BuyBoxPrice> prices=readPrices(response.json(),query.byAsin());
                updated+=savePrices(tenantId,connectionId,marketplaceId,prices,query.byAsin());
                if(query.byAsin()){
                    Set<String> returned=new HashSet<>(prices.stream().map(BuyBoxPrice::identifier).toList());
                    batch.stream().filter(identifier->!returned.contains(identifier)).forEach(missingAsins::add);
                }
                if(prices.size()<batch.size())log.info("Amazon competitive pricing returned Buy Box prices for {} of {} requested {}(s).",prices.size(),batch.size(),query.itemType());
            }
        }
        for(int start=0;start<missingAsins.size();start+=AMAZON_BATCH_SIZE){
            pacePricingRequest(30000);
            List<String> batch=missingAsins.subList(start,Math.min(missingAsins.size(),start+AMAZON_BATCH_SIZE));
            var response=offerBatch(tenantId,connectionId,batch,marketplaceId);
            List<BuyBoxPrice> prices=readOfferPrices(response.json());
            updated+=savePrices(tenantId,connectionId,marketplaceId,prices,true);
            if(prices.size()<batch.size())log.info("Amazon offer pricing returned a current Buy Box for {} of {} fallback ASIN(s).",prices.size(),batch.size());
        }
        return updated;
    }

    private String itemOffersRequest(List<String> asins,String marketplaceId){
        try{
            var root=json.createObjectNode();var requests=root.putArray("requests");
            for(String asin:asins)requests.addObject()
                .put("uri","/products/pricing/v0/items/"+encode(asin)+"/offers")
                .put("method","GET").put("MarketplaceId",marketplaceId).put("ItemCondition","New")
                .put("CustomerType","Consumer");
            return json.writeValueAsString(root);
        }catch(Exception ex){throw new IllegalStateException("Could not prepare Amazon offer pricing request.",ex);}
    }

    private AmazonSpApiClient.ApiResponse offerBatch(UUID tenantId,UUID connectionId,List<String> asins,String marketplaceId){
        return amazon.post(tenantId,connectionId,"/batches/products/pricing/v0/itemOffers",
            itemOffersRequest(asins,marketplaceId));
    }

    private List<ListingKey> listings(UUID tenantId,UUID connectionId){
        List<ListingKey> result=transactions.execute(status->{setTenant(tenantId);return jdbc.query("""
                SELECT seller_sku,asin FROM amazon_listings
                WHERE tenant_id=? AND marketplace_connection_id=?
                  AND platform_status NOT IN ('DELETED','REMOVED')
                ORDER BY (SELECT max(item.created_at) FROM amazon_order_items item
                    WHERE item.tenant_id=amazon_listings.tenant_id
                      AND item.marketplace_connection_id=amazon_listings.marketplace_connection_id
                      AND (item.seller_sku=amazon_listings.seller_sku OR
                        (amazon_listings.asin IS NOT NULL AND item.asin=amazon_listings.asin))) DESC NULLS LAST,
                  seller_sku
                """,(rs,row)->new ListingKey(rs.getString(1),rs.getString(2)),tenantId,connectionId);});
        return result==null?List.of():result;
    }

    private long savePrices(UUID tenantId,UUID connectionId,String marketplaceId,List<BuyBoxPrice> prices,boolean byAsin){
        Long result=transactions.execute(status->{setTenant(tenantId);long updated=0;
            for(BuyBoxPrice price:prices)updated+=jdbc.update("""
                UPDATE amazon_listings SET buy_box_price=?,buy_box_currency=?,buy_box_updated_at=now(),updated_at=now()
                WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND %s=?
                """.formatted(byAsin?"asin":"seller_sku"),price.amount(),price.currency(),tenantId,connectionId,marketplaceId,price.identifier());
            return updated;});
        return result==null?0:result;
    }

    static List<BuyBoxPrice> readPrices(JsonNode root){
        return readPrices(root,false);
    }

    static List<BuyBoxPrice> readPrices(JsonNode root,boolean preferAsin){
        List<BuyBoxPrice> prices=new ArrayList<>();
        JsonNode payload=field(root,"payload","Payload");
        if(!payload.isArray())return prices;
        for(JsonNode item:payload){
            String identifier=preferAsin?text(item,"ASIN","asin","SellerSKU","sellerSKU","sellerSku"):
                text(item,"SellerSKU","sellerSKU","sellerSku","ASIN","asin");
            JsonNode product=field(item,"Product","product");
            JsonNode competitive=field(product,"CompetitivePricing","competitivePricing");
            JsonNode candidates=field(competitive,"CompetitivePrices","competitivePrices");
            JsonNode selected=null;
            if(candidates.isArray())for(JsonNode candidate:candidates){
                String id=text(candidate,"CompetitivePriceId","competitivePriceId");
                String condition=text(candidate,"condition","Condition");
                if(("1".equals(id)||id.isBlank())&&(condition.isBlank()||"new".equalsIgnoreCase(condition))){selected=candidate;break;}
            }
            JsonNode money=selected==null?null:field(field(selected,"Price","price"),"LandedPrice","landedPrice");
            if(money==null||money.isMissingNode())money=selected==null?null:field(field(selected,"Price","price"),"ListingPrice","listingPrice");
            if(money==null||money.isMissingNode()){
                JsonNode buyBoxes=field(competitive,"BuyBoxPrices","buyBoxPrices");
                if(buyBoxes.isArray()&&buyBoxes.size()>0){JsonNode box=buyBoxes.get(0);money=field(box,"LandedPrice","landedPrice");
                    if(money.isMissingNode())money=field(box,"ListingPrice","listingPrice");}
            }
            BigDecimal amount=decimal(money,"Amount","amount");String currency=text(money,"CurrencyCode","currencyCode");
            if(!identifier.isBlank()&&amount!=null&&!currency.isBlank())prices.add(new BuyBoxPrice(identifier,amount,currency));
        }
        return prices;
    }

    /** Reads the actual Featured Offer from Amazon's item-offers fallback response. */
    static List<BuyBoxPrice> readOfferPrices(JsonNode root){
        List<BuyBoxPrice> prices=new ArrayList<>();
        JsonNode responses=field(root,"responses","Responses");
        if(!responses.isArray())return prices;
        for(JsonNode response:responses){
            JsonNode body=field(response,"body","Body");JsonNode payload=field(body,"payload","Payload");
            String asin=text(payload,"ASIN","asin");JsonNode money=null;
            JsonNode summary=field(payload,"Summary","summary");
            JsonNode buyBoxes=field(summary,"BuyBoxPrices","buyBoxPrices");
            if(buyBoxes.isArray())for(JsonNode box:buyBoxes){
                String condition=text(box,"condition","Condition");
                if(condition.isBlank()||"new".equalsIgnoreCase(condition)){
                    money=field(box,"LandedPrice","landedPrice");
                    if(money.isMissingNode())money=field(box,"ListingPrice","listingPrice");
                    if(!money.isMissingNode())break;
                }
            }
            if(money==null||money.isMissingNode()){
                JsonNode offers=field(payload,"Offers","offers");
                if(offers.isArray())for(JsonNode offer:offers){
                    if(!bool(offer,"IsBuyBoxWinner","isBuyBoxWinner"))continue;
                    JsonNode listing=field(offer,"ListingPrice","listingPrice");
                    BigDecimal listingAmount=decimal(listing,"Amount","amount");
                    String currency=text(listing,"CurrencyCode","currencyCode");
                    if(listingAmount!=null&&!currency.isBlank()){
                        BigDecimal shipping=decimal(field(offer,"Shipping","shipping"),"Amount","amount");
                        prices.add(new BuyBoxPrice(asin,listingAmount.add(shipping==null?BigDecimal.ZERO:shipping),currency));
                    }
                    money=null;break;
                }
                if(prices.stream().anyMatch(price->price.identifier().equals(asin)))continue;
            }
            BigDecimal amount=decimal(money,"Amount","amount");String currency=text(money,"CurrencyCode","currencyCode");
            if(!asin.isBlank()&&amount!=null&&!currency.isBlank())prices.add(new BuyBoxPrice(asin,amount,currency));
        }
        return prices;
    }

    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private static JsonNode field(JsonNode node,String... names){
        if(node==null)return tools.jackson.databind.node.MissingNode.getInstance();
        for(String name:names){JsonNode value=node.path(name);if(!value.isMissingNode())return value;}
        return tools.jackson.databind.node.MissingNode.getInstance();
    }
    private static String text(JsonNode node,String... names){JsonNode value=field(node,names);return value.isValueNode()?value.asText(""):"";}
    private static boolean bool(JsonNode node,String... names){JsonNode value=field(node,names);return value.isBoolean()&&value.asBoolean();}
    private static BigDecimal decimal(JsonNode node,String... names){String value=text(node,names);if(value.isBlank())return null;try{return new BigDecimal(value);}catch(NumberFormatException ignored){return null;}}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static synchronized void pacePricingRequest(long intervalMillis){
        long remaining=nextPricingRequestNanos-System.nanoTime();
        if(remaining>0)pause((remaining+999999)/1000000);
        nextPricingRequestNanos=System.nanoTime()+Duration.ofMillis(intervalMillis).toNanos();
    }
    private static void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Buy Box fallback refresh interrupted.",ex);}}

    record ListingKey(String sellerSku,String asin){}
    record QuerySet(String itemType,String parameter,List<String> identifiers,boolean byAsin){}
    record BuyBoxPrice(String identifier,BigDecimal amount,String currency){}
}
