package com.nextaicommerce.platform.sync;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Sends small, explicitly approved listing changes one at a time to stay well inside SP-API limits. */
@Component
@ConditionalOnProperty(prefix="app.amazon",name="listing-actions-enabled",havingValue="true")
public class AmazonListingActionWorker {
    private static final Logger log=LoggerFactory.getLogger(AmazonListingActionWorker.class);
    private final JdbcTemplate jdbc; private final TransactionTemplate transactions;
    private final AmazonSpApiClient amazon; private final ObjectMapper json;
    AmazonListingActionWorker(JdbcTemplate jdbc,TransactionTemplate transactions,AmazonSpApiClient amazon,ObjectMapper json){
        this.jdbc=jdbc;this.transactions=transactions;this.amazon=amazon;this.json=json;
    }

    @Scheduled(fixedDelayString="${app.amazon.listing-action-delay-ms:15000}",initialDelayString="${app.amazon.listing-action-initial-delay-ms:20000}")
    public void sendDueActions(){
        for(UUID tenantId:jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",(rs,row)->rs.getObject(1,UUID.class))){
            try{Action action=transactions.execute(status->claim(tenantId));if(action!=null)send(action);}
            catch(RuntimeException ex){log.error("Amazon listing action worker failed for tenant={}",tenantId,ex);}
        }
    }

    private Action claim(UUID tenantId){
        setTenant(tenantId);
        List<Action> due=jdbc.query("""
            SELECT id,marketplace_connection_id,account_catalog_item_id,seller_sku,marketplace_id,action_type,payload,attempts
            FROM amazon_listing_actions WHERE tenant_id=? AND status='QUEUED' AND execute_at<=now()
            ORDER BY execute_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
            """,(rs,row)->new Action(rs.getObject(1,UUID.class),tenantId,rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8)),tenantId);
        if(due.isEmpty())return null;
        Action action=due.getFirst();jdbc.update("UPDATE amazon_listing_actions SET status='RUNNING',attempts=attempts+1,updated_at=now() WHERE tenant_id=? AND id=?",tenantId,action.id());
        return action;
    }

    private void send(Action action){
        try{
            String sellerId=transactions.execute(status->{setTenant(action.tenantId());return jdbc.query("SELECT seller_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",rs->rs.next()?rs.getString(1):null,action.tenantId(),action.connectionId());});
            if(sellerId==null)throw new IllegalStateException("The Amazon connection is no longer active.");
            JsonNode payload=json.readTree(action.payload());
            var body=json.createObjectNode().put("productType","PRODUCT");var patches=body.putArray("patches");var patch=patches.addObject().put("op","replace");
            if("ZERO_MFN_QUANTITY".equals(action.type())){
                patch.put("path","/attributes/fulfillment_availability");var availability=patch.putArray("value").addObject();
                availability.put("fulfillment_channel_code","DEFAULT").put("quantity",0).put("marketplace_id",action.marketplaceId());
            }else if("SALE_PRICE".equals(action.type())){
                patch.put("path","/attributes/purchasable_offer");var offer=patch.putArray("value").addObject();
                offer.put("marketplace_id",action.marketplaceId()).put("currency","USD");var schedule=offer.putArray("our_price").addObject().putArray("schedule").addObject();
                schedule.put("value_with_tax",payload.path("price").decimalValue());schedule.put("start_at",payload.path("startsAt").asText());schedule.put("end_at",payload.path("endsAt").asText());
            }else throw new IllegalStateException("Unsupported Amazon listing action.");
            String path="/listings/2021-08-01/items/"+encode(sellerId)+"/"+encode(action.sellerSku())+"?marketplaceIds="+encode(action.marketplaceId());
            amazon.patch(action.tenantId(),action.connectionId(),path,json.writeValueAsString(body));
            transactions.executeWithoutResult(status->{setTenant(action.tenantId());jdbc.update("UPDATE amazon_listing_actions SET status='COMPLETED',completed_at=now(),last_error=NULL,updated_at=now() WHERE tenant_id=? AND id=?",action.tenantId(),action.id());});
            log.info("Amazon listing action completed: type={} sku={} marketplace={}",action.type(),action.sellerSku(),action.marketplaceId());
        }catch(Exception ex){
            transactions.executeWithoutResult(status->{setTenant(action.tenantId());int attempts=action.attempts()+1;boolean terminal=attempts>=4;
                jdbc.update("UPDATE amazon_listing_actions SET status=?,execute_at=now()+make_interval(secs=>?),last_error=?,updated_at=now() WHERE tenant_id=? AND id=?",
                    terminal?"FAILED":"QUEUED",terminal?0:Math.min(300,15*(1<<Math.min(attempts,4))),message(ex),action.tenantId(),action.id());});
            log.warn("Amazon listing action {} for SKU {} was not applied: {}",action.type(),action.sellerSku(),message(ex));
        }
    }
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static String message(Exception ex){String value=ex.getMessage()==null?"Amazon did not accept the listing update.":ex.getMessage();return value.length()>480?value.substring(0,480):value;}
    private record Action(UUID id,UUID tenantId,UUID connectionId,UUID itemId,String sellerSku,String marketplaceId,String type,String payload,int attempts){}
}
