package com.nextaicommerce.platform.profit;

import com.nextaicommerce.platform.sync.AmazonSpApiClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

/** Explicit, audited base-price changes. Never retries an uncertain Amazon write. */
@Service
public class ProfitPricePublisher {
 private final JdbcTemplate jdbc; private final TransactionTemplate tx;
 private final AmazonSpApiClient amazon; private final ObjectMapper json;
 private final boolean enabled; private final Set<UUID> allowed;
 public ProfitPricePublisher(JdbcTemplate jdbc,TransactionTemplate tx,AmazonSpApiClient amazon,ObjectMapper json,
   Environment environment,@Value("${app.local-development:false}") boolean local,
   @Value("${app.amazon.write-enabled:false}") boolean writes,
   @Value("${app.amazon.profit-price-enabled:false}") boolean feature,
   @Value("${app.amazon.profit-price-connections:}") String connections){
  this.jdbc=jdbc;this.tx=tx;this.amazon=amazon;this.json=json;
  enabled=!local&&writes&&feature&&environment.matchesProfiles("prod & !local");
  allowed=Arrays.stream(connections.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
 }
 public boolean available(UUID connection){return enabled&&allowed.contains(connection);}
 public record Confirmation(UUID id,String sku,BigDecimal oldPrice,BigDecimal newPrice,LocalDate saleStart,LocalDate saleEnd){
  public Confirmation(UUID id,String sku,BigDecimal oldPrice,BigDecimal newPrice){this(id,sku,oldPrice,newPrice,null,null);}
 }
 private record Listing(String seller,String sku,String marketplace){}
 private void guard(UUID connection){if(!available(connection))throw new IllegalArgumentException("Amazon price publishing is disabled here. Preview and cost editing remain available.");}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 private Listing listing(UUID tenant,UUID connection,String sku){return tx.execute(s->{scope(tenant);
  var rows=jdbc.query("""
   SELECT mc.seller_identifier,l.seller_sku,l.marketplace_id FROM amazon_listings l
   JOIN marketplace_connections mc ON mc.tenant_id=l.tenant_id AND mc.id=l.marketplace_connection_id
   WHERE l.tenant_id=? AND l.marketplace_connection_id=? AND l.seller_sku=?
    AND mc.channel='AMAZON' AND mc.status='ACTIVE' AND l.marketplace_id='ATVPDKIKX0DER'
    AND upper(coalesce(l.fulfillment_channel,'MFN')) NOT IN ('AFN','AMAZON','FBA')
   """,(r,n)->new Listing(r.getString(1),r.getString(2),r.getString(3)),tenant,connection,sku);
  if(rows.size()!=1)throw new IllegalArgumentException("Choose an active US FBM listing in this account.");return rows.getFirst();
 });}
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
 private static String path(Listing l){return "/listings/2021-08-01/items/"+encode(l.seller())+"/"+encode(l.sku())+"?marketplaceIds="+encode(l.marketplace());}
 private JsonNode live(UUID tenant,UUID connection,Listing l){return amazon.get(tenant,connection,path(l)+"&includedData=attributes,productTypes").json();}
 static JsonNode offer(JsonNode listing){
  JsonNode result=null;
  for(var candidate:listing.path("attributes").path("purchasable_offer"))
   if("ATVPDKIKX0DER".equals(candidate.path("marketplace_id").asText())&&"USD".equals(candidate.path("currency").asText())&&"ALL".equals(candidate.path("audience").asText("ALL"))){
    if(result!=null)throw new IllegalArgumentException("Multiple Amazon offers require review in Seller Central.");result=candidate;
   }
  if(result==null)throw new IllegalArgumentException("Amazon did not return a USD consumer offer.");
  for(String field:List.of("discounted_price","automated_pricing_merchandising_rule_plan"))
   if(result.hasNonNull(field)&&!result.path(field).isEmpty())throw new IllegalArgumentException("This listing has a sale or automated pricing. Review its price in Seller Central.");
  var price=result.path("our_price");var schedules=price.path(0).path("schedule");var schedule=schedules.path(0);
  if(price.size()!=1||schedules.size()!=1||schedule.has("start_at")||schedule.has("end_at")||!schedule.path("value_with_tax").isNumber())
   throw new IllegalArgumentException("Scheduled or ambiguous Amazon prices require review in Seller Central.");
  return result;
 }
 private static BigDecimal price(JsonNode offer){return offer.path("our_price").path(0).path("schedule").path(0).path("value_with_tax").decimalValue();}
 static void validate(BigDecimal amount,JsonNode offer){
  if(amount==null||amount.signum()<=0||amount.scale()>2||amount.compareTo(new BigDecimal("100000"))>0)throw new IllegalArgumentException("Enter a positive USD price with at most two decimal places.");
  for(String field:List.of("minimum_seller_allowed_price","maximum_seller_allowed_price"))for(var bound:offer.path(field))for(var schedule:bound.path("schedule")){
   if(!schedule.path("value_with_tax").isNumber())throw new IllegalArgumentException("Amazon price limits need review.");
   int comparison=amount.compareTo(schedule.path("value_with_tax").decimalValue());
   if(field.startsWith("minimum")?comparison<0:comparison>0)throw new IllegalArgumentException("The price is outside Amazon's configured price limits.");
  }
 }
 public Confirmation prepare(UUID tenant,UUID connection,String sku,BigDecimal amount,String actor){
  return prepare(tenant,connection,sku,amount,actor,null,null);
 }
 public Confirmation prepare(UUID tenant,UUID connection,String sku,BigDecimal amount,String actor,LocalDate start,LocalDate end){
  guard(connection);var listing=listing(tenant,connection,sku);var offer=offer(live(tenant,connection,listing));validate(amount,offer);
  var old=price(offer);if(old.compareTo(amount)==0)throw new IllegalArgumentException("Amazon already has this price.");
  validateSale(amount,old,start,end);
  UUID id=UUID.randomUUID();tx.executeWithoutResult(s->{scope(tenant);jdbc.update("INSERT INTO profit_price_confirmations(id,tenant_id,connection_id,seller_sku,old_price,new_price,requested_by,sale_start,sale_end) VALUES (?,?,?,?,?,?,?,?,?)",id,tenant,connection,sku,old,amount,actor,start,end);});
  return new Confirmation(id,sku,old,amount,start,end);
 }
 static void validateSale(BigDecimal amount,BigDecimal base,LocalDate start,LocalDate end){
  if(start==null&&end==null)return;
  if(start==null||end==null||end.isBefore(start)||start.isBefore(LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")))||amount.compareTo(base)>=0)
   throw new IllegalArgumentException("Choose a sale price below the current price and valid start/end dates, starting today or later (Pacific time).");
 }
 public String submit(UUID tenant,UUID connection,UUID id,String actor){
  guard(connection);
  Confirmation confirmed=tx.execute(s->{scope(tenant);var rows=jdbc.query("""
   UPDATE profit_price_confirmations SET status='SENDING',updated_at=now()
   WHERE tenant_id=? AND connection_id=? AND id=? AND requested_by=? AND status='PREVIEW'
     AND created_at>now()-interval '5 minutes' RETURNING id,seller_sku,old_price,new_price,sale_start,sale_end
   """,(r,n)->new Confirmation(r.getObject(1,UUID.class),r.getString(2),r.getBigDecimal(3),r.getBigDecimal(4),r.getObject(5,LocalDate.class),r.getObject(6,LocalDate.class)),tenant,connection,id,actor);
   if(rows.isEmpty())throw new IllegalArgumentException("Confirmation expired or already submitted. Check Amazon before trying again.");return rows.getFirst();});
  boolean sending=false;
  try{
   var listing=listing(tenant,connection,confirmed.sku());var live=live(tenant,connection,listing);var offer=offer(live);validate(confirmed.newPrice(),offer);
   if(price(offer).compareTo(confirmed.oldPrice())!=0)throw new IllegalArgumentException("Amazon's price changed since your preview. Review it again.");
   validateSale(confirmed.newPrice(),confirmed.oldPrice(),confirmed.saleStart(),confirmed.saleEnd());
   String body=patch(json,live,confirmed.newPrice(),confirmed.saleStart(),confirmed.saleEnd());sending=true;
   var result=amazon.patch(tenant,connection,path(listing),body);
   if(!"ACCEPTED".equals(result.json().path("status").asText())){status(tenant,id,"UNCONFIRMED");return "Amazon did not accept the update. Check Seller Central before trying again.";}
   status(tenant,id,"ACCEPTED");
   return "Amazon accepted the price submission; processing is not yet confirmed. Historical orders are unchanged. The next listing sync will refresh the displayed price.";
  }catch(Exception e){status(tenant,id,sending?"UNCONFIRMED":"CONFLICT");if(sending)return "Amazon's response was not confirmed. No automatic retry was made. Check Seller Central before submitting again.";
   throw new IllegalArgumentException(e instanceof IllegalArgumentException?e.getMessage():"Could not verify the live Amazon listing. No price was sent.");}
 }
 static String patch(ObjectMapper json,JsonNode listing,BigDecimal amount){
  return patch(json,listing,amount,null,null);
 }
 static String patch(ObjectMapper json,JsonNode listing,BigDecimal amount,LocalDate start,LocalDate end){
  String type=listing.path("productTypes").path(0).path("productType").asText();if(type.isBlank())throw new IllegalArgumentException("Amazon product type is unavailable.");
  var body=json.createObjectNode().put("productType",type);
  var offer=body.putArray("patches").addObject().put("op","merge").put("path","/attributes/purchasable_offer").putArray("value").addObject();
  offer.put("marketplace_id","ATVPDKIKX0DER").put("currency","USD").put("audience","ALL");
  var schedule=offer.putArray(start==null?"our_price":"discounted_price").addObject().putArray("schedule").addObject().put("value_with_tax",amount);
  if(start!=null)schedule.put("start_at",start.toString()).put("end_at",end.toString());
  return json.writeValueAsString(body);
 }
 private void status(UUID tenant,UUID id,String value){tx.executeWithoutResult(s->{scope(tenant);jdbc.update("UPDATE profit_price_confirmations SET status=?,updated_at=now() WHERE tenant_id=? AND id=?",value,tenant,id);});}
}
