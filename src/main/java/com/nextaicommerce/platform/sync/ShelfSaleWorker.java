package com.nextaicommerce.platform.sync;

import java.math.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

/** Production-only temporary discounts. Never replaces base prices or somebody else's promotion. */
@Component @Profile("prod & !local")
@ConditionalOnProperty(name="app.amazon.shelf-sale-enabled",havingValue="true")
public class ShelfSaleWorker {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ShelfSaleWorker.class);
 private final ShelfSaleRepository repo;private final TransactionTemplate tx;private final AmazonSpApiClient amazon;private final ObjectMapper json;
 private final Set<UUID> connections;
 public ShelfSaleWorker(ShelfSaleRepository repo,TransactionTemplate tx,AmazonSpApiClient amazon,ObjectMapper json,
   @Value("${app.local-development:false}") boolean local,@Value("${app.amazon.write-enabled:false}") boolean writes,
   @Value("${app.amazon.shelf-sale-connections:}") String allowed){
  if(local||!writes)throw new IllegalStateException("Sale price publication is prohibited outside production writes.");
  connections=Arrays.stream(allowed.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
  if(connections.isEmpty())throw new IllegalStateException("Sale price publication requires explicit connection scope.");
  this.repo=repo;this.tx=tx;this.amazon=amazon;this.json=json;
 }
 @Scheduled(fixedDelay=60000,initialDelay=60000) public void discover(){
  for(var tenant:repo.tenants())for(var connection:connections)try{tx.executeWithoutResult(s->repo.discover(tenant,connection));}catch(Exception e){log.warn("Sale discovery failed for tenant {}",tenant,e);}
 }
 @Scheduled(fixedDelay=2000,initialDelay=90000) public void tick(){
  for(var tenant:repo.tenants())try{tx.executeWithoutResult(s->repo.reconcileDirty(tenant));}catch(Exception e){log.warn("Sale invalidation failed for tenant {}",tenant,e);}
  for(var tenant:repo.tenants())for(var connection:connections)for(int scan=0;scan<100;scan++){
   try{Integer processed=tx.execute(s->{var row=repo.next(tenant,connection);if(row.isEmpty())return -1;var l=row.get();if(l.owned()==null&&l.pending()==null&&repo.plan(l).isEmpty()){repo.result(l,"IDLE",null,null,60,null);return 0;}process(l);return 1;});if(Integer.valueOf(1).equals(processed))return;if(Integer.valueOf(-1).equals(processed))break;}
   catch(Exception e){log.warn("Sale price queue check failed for tenant {}",tenant,e);break;}
  }
 }
 void process(ShelfSaleRepository.Listing l){
  if(!connections.contains(l.connection())||!"ATVPDKIKX0DER".equals(l.marketplace()))return;
  try{
   var plan=repo.plan(l);
   if(plan.isEmpty()&&l.owned()==null&&l.pending()==null){repo.result(l,"IDLE",null,null,60,null);return;}
   String path="/listings/2021-08-01/items/"+encode(l.seller())+"/"+encode(l.sku())+"?marketplaceIds="+encode(l.marketplace());
   var response=amazon.get(l.tenant(),l.connection(),path+"&includedData=attributes,issues,productTypes").json();
   JsonNode offer=null;
   for(var candidate:response.path("attributes").path("purchasable_offer"))if(l.marketplace().equals(candidate.path("marketplace_id").asText())&&"USD".equals(candidate.path("currency").asText())&&"ALL".equals(candidate.path("audience").asText("ALL"))){if(offer!=null)throw new IllegalStateException("Ambiguous offer");offer=candidate;}
   if(offer==null)throw new IllegalStateException("No unambiguous USD consumer offer");
   var live=offer.path("discounted_price");boolean hasLive=live.isArray()&&!live.isEmpty();
   boolean ours=hasLive&&(same(live,l.owned())||same(live,l.pending()));
   if(hasLive&&!ours){repo.result(l,"CONFLICT",null,null,900,"Existing Amazon promotion is not owned by this feature; left unchanged.");return;}
   if(plan.isEmpty()){
    if(!hasLive){repo.result(l,"REMOVED",null,null,60,null);return;}
    patch(l,path,response,null);repo.result(l,"VERIFY_REMOVAL",l.owned(),l.pending(),15,null);return;
   }
   var p=plan.get();
   if(hasLive&&ours){
    // Finite schedules are never extended blindly; changes first clear the old managed schedule.
    var desired=discount(offer,p);
    if(price(live).compareTo(price(desired))==0&&end(live).equals(p.end())){repo.result(l,"CONFIRMED",json.writeValueAsString(live),null,60,null);return;}
    patch(l,path,response,null);repo.result(l,"VERIFY_REMOVAL",l.owned(),l.pending(),15,null);return;
   }
   var desired=discount(offer,p);
   if(l.pending()==null||!same(desired,l.pending())){
    // Persist intended ownership before sending. A timeout/crash can be reconciled by the next read.
    repo.result(l,"READY",null,json.writeValueAsString(desired),1,null);return;
   }
   patch(l,path,response,json.readTree(l.pending()));repo.result(l,"VERIFYING",l.pending(),l.pending(),15,null);
  }catch(Exception e){repo.result(l,"RETRY",l.owned(),l.pending(),Math.min(900,30*(1<<Math.min(l.attempts(),4))),"Amazon sale update unconfirmed: "+e.getClass().getSimpleName()+". Retrying safely.");log.warn("Sale request unconfirmed sku={}",l.sku());}
 }
 JsonNode discount(JsonNode offer,ShelfSaleRepository.Plan p){
  var base=offer.path("our_price");if(base.size()!=1||base.path(0).path("schedule").size()!=1)throw new IllegalStateException("Ambiguous base price");
  var schedule=base.path(0).path("schedule").path(0);
  if(schedule.has("start_at")||schedule.has("end_at"))throw new IllegalStateException("Scheduled base price requires review");
  var regular=schedule.path("value_with_tax").decimalValue();
  var sale=regular.multiply(BigDecimal.ONE.subtract(p.percent().movePointLeft(2))).setScale(2,RoundingMode.HALF_UP);
  if(sale.signum()<=0||sale.compareTo(regular)>=0)throw new IllegalStateException("Invalid discounted price");
  for(var floor:offer.path("minimum_seller_allowed_price"))for(var f:floor.path("schedule"))if(sale.compareTo(f.path("value_with_tax").decimalValue())<0)throw new IllegalStateException("Discount below Amazon price floor");
  var value=json.createArrayNode();value.addObject().putArray("schedule").addObject().put("value_with_tax",sale).put("start_at",LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toString()).put("end_at",p.end().toString());return value;
 }
 void patch(ShelfSaleRepository.Listing l,String path,JsonNode listing,JsonNode discount){
  String type=listing.path("productTypes").path(0).path("productType").asText();if(type.isBlank())throw new IllegalStateException("Product type unavailable");
  var body=json.createObjectNode().put("productType",type);
  var offer=body.putArray("patches").addObject().put("op","merge").put("path","/attributes/purchasable_offer").putArray("value").addObject();
  offer.put("marketplace_id",l.marketplace()).put("currency","USD").put("audience","ALL");
  if(discount==null)offer.putNull("discounted_price");else offer.set("discounted_price",discount);
  var result=amazon.patch(l.tenant(),l.connection(),path,json.writeValueAsString(body));
  if(!"ACCEPTED".equals(result.json().path("status").asText()))throw new IllegalStateException("Amazon rejected sale update");
 }
 boolean same(JsonNode value,String saved){
  if(saved==null)return false;
  try{var old=json.readTree(saved);return value.size()==1&&old.size()==1&&value.path(0).path("schedule").size()==1&&old.path(0).path("schedule").size()==1&&price(value).compareTo(price(old))==0&&end(value).equals(end(old))&&date(value,"start_at").equals(date(old,"start_at"));}catch(Exception e){return false;}
 }
 static BigDecimal price(JsonNode value){return value.path(0).path("schedule").path(0).path("value_with_tax").decimalValue();}
 static Instant end(JsonNode value){return date(value,"end_at");}
 static Instant date(JsonNode value,String key){String s=value.path(0).path("schedule").path(0).path(key).asText();try{return Instant.parse(s);}catch(Exception e){return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();}}
 static String encode(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8).replace("+","%20");}
}
