package com.nextaicommerce.platform.receiving;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Account-scoped purchasing drafts. Submission records internal POs only. */
@Repository
public class ReplenishmentPurchasePlans {
 public record InputLine(UUID id,long each){}
 public record Command(UUID id,long revision,String name,List<InputLine> lines,String termsRevision){}
 public record Line(UUID id,String name,String code,String upc,UUID vendorId,String vendor,String dc,BigDecimal pack,long each,BigDecimal cost,String currency){}
 public record Plan(UUID id,String name,String status,long revision,Instant createdAt,List<Line> lines,List<Map<String,Object>> pos){}
 private final JdbcTemplate jdbc;
 public ReplenishmentPurchasePlans(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 @Transactional(readOnly=true)
 public List<Map<String,Object>> list(UUID tenant,String status){scope(tenant);return jdbc.queryForList("""
  SELECT p.id,p.name,p.status,p.revision,p.created_at,(SELECT count(*) FROM replenishment_purchase_plan_lines l WHERE l.tenant_id=p.tenant_id AND l.plan_id=p.id) items,
  (SELECT count(DISTINCT vendor_id) FROM replenishment_purchase_plan_lines l WHERE l.tenant_id=p.tenant_id AND l.plan_id=p.id) vendors
  FROM replenishment_purchase_plans p WHERE p.tenant_id=? AND p.status=? AND p.request_hash<>'DELETED' ORDER BY p.updated_at DESC,p.id LIMIT 100
  """,tenant,"SUBMITTED".equals(status)?"SUBMITTED":"DRAFT");}
 @Transactional(readOnly=true)
 public Plan get(UUID tenant,UUID id){scope(tenant);return read(tenant,id);}
 public record DeleteDraft(long revision){}
 @Transactional
 public void deleteDraft(UUID tenant,UUID id,long expectedRevision){
  scope(tenant);
  var rows=jdbc.queryForList("SELECT status,revision FROM replenishment_purchase_plans WHERE tenant_id=? AND id=? AND request_hash<>'DELETED' FOR UPDATE",tenant,id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Draft not found");
  var row=rows.getFirst();
  if(!"DRAFT".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Submitted POs cannot be deleted");
  if(((Number)row.get("revision")).longValue()!=expectedRevision)throw new ResponseStatusException(HttpStatus.CONFLICT,"This draft changed. Reopen Saved drafts before deleting.");
  jdbc.update("DELETE FROM replenishment_purchase_plan_lines WHERE tenant_id=? AND plan_id=?",tenant,id);
  // Retain the idempotency identity so a delayed create retry cannot resurrect a deleted draft.
  jdbc.update("UPDATE replenishment_purchase_plans SET request_hash='DELETED',revision=revision+1,updated_at=now() WHERE tenant_id=? AND id=?",tenant,id);
 }
 public record Quote(List<Line> lines,String revision){}
 @Transactional(readOnly=true)
 public Quote quote(UUID tenant,List<InputLine> input){validate(new Command(UUID.randomUUID(),0,"Quote",input,null));scope(tenant);var lines=resolve(tenant,input);return new Quote(lines,termsHash(lines));}
 private Plan read(UUID tenant,UUID id){
  var heads=jdbc.queryForList("SELECT * FROM replenishment_purchase_plans WHERE tenant_id=? AND id=? AND request_hash<>'DELETED'",tenant,id);
  if(heads.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Purchase plan not found");var h=heads.getFirst();
  var lines=jdbc.query("SELECT * FROM replenishment_purchase_plan_lines WHERE tenant_id=? AND plan_id=? ORDER BY vendor_name,name,item_id",(r,n)->new Line(r.getObject("item_id",UUID.class),r.getString("name"),r.getString("item_code"),r.getString("barcode"),r.getObject("vendor_id",UUID.class),r.getString("vendor_name"),r.getString("dc"),r.getBigDecimal("units_per_case"),r.getLong("quantity"),r.getBigDecimal("unit_cost"),r.getString("currency")),tenant,id);
  return new Plan(id,(String)h.get("name"),(String)h.get("status"),((Number)h.get("revision")).longValue(),((java.sql.Timestamp)h.get("created_at")).toInstant(),lines,jdbc.queryForList("SELECT po_number,currency,total FROM replenishment_vendor_pos WHERE tenant_id=? AND plan_id=? ORDER BY po_number",tenant,id));
 }
 @Transactional
 public Plan save(UUID tenant,String email,Command command,boolean submit){
  validate(command);scope(tenant);
  UUID actor=jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?) AND status='ACTIVE'",UUID.class,email);
  String hash=hash(command,submit);
  // The caller's UUID makes lost-response retries safe, including the first insert.
  jdbc.update("INSERT INTO replenishment_purchase_plans(id,tenant_id,name,request_hash,created_by,updated_by) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",command.id(),tenant,command.name().trim(),hash,actor,actor);
  var heads=jdbc.queryForList("SELECT * FROM replenishment_purchase_plans WHERE tenant_id=? AND id=? FOR UPDATE",tenant,command.id());
  if(heads.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"Choose a new purchase plan ID");var h=heads.getFirst();
  if("DELETED".equals(h.get("request_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"This draft was deleted. Start a new PO.");
  long revision=((Number)h.get("revision")).longValue();
  boolean hasLines=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM replenishment_purchase_plan_lines WHERE tenant_id=? AND plan_id=?)",Boolean.class,tenant,command.id()));
  if(hasLines&&hash.equals(h.get("request_hash")))return read(tenant,command.id());
  if("SUBMITTED".equals(h.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"This PO is already submitted");
  if((hasLines?revision:0)!=command.revision())throw new ResponseStatusException(HttpStatus.CONFLICT,"This draft changed. Reopen the saved draft before editing.");
  List<Line> lines=resolve(tenant,command.lines());
  if(submit){
   if(!termsHash(lines).equals(command.termsRevision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Buying terms changed. Review the refreshed basket before submitting.");
   if(lines.stream().anyMatch(l->l.vendorId()==null||l.cost()==null))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a vendor and catalogue cost for every item before submitting.");
   for(UUID vendor:lines.stream().map(Line::vendorId).distinct().toList())if(lines.stream().filter(l->vendor.equals(l.vendorId())).map(Line::currency).distinct().count()!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"One vendor PO cannot contain multiple currencies.");
  }
  jdbc.update("DELETE FROM replenishment_purchase_plan_lines WHERE tenant_id=? AND plan_id=?",tenant,command.id());
  for(var l:lines)jdbc.update("INSERT INTO replenishment_purchase_plan_lines(tenant_id,plan_id,item_id,vendor_id,name,item_code,barcode,vendor_name,dc,units_per_case,quantity,unit_cost,currency) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",tenant,command.id(),l.id(),l.vendorId(),l.name(),l.code(),l.upc(),l.vendor(),l.dc(),l.pack(),l.each(),l.cost(),l.currency());
  if(submit)for(UUID vendor:lines.stream().map(Line::vendorId).distinct().toList()){
   var group=lines.stream().filter(l->vendor.equals(l.vendorId())).toList();
   BigDecimal total=group.stream().map(l->l.cost().multiply(BigDecimal.valueOf(l.each()))).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);
   UUID po=UUID.randomUUID();jdbc.update("INSERT INTO replenishment_vendor_pos(id,tenant_id,plan_id,vendor_id,po_number,currency,total,created_by) VALUES (?,?,?,?,?,?,?,?)",po,tenant,command.id(),vendor,"PO-"+po.toString().replace("-","").toUpperCase(Locale.ROOT),group.getFirst().currency(),total,actor);
  }
  jdbc.update("UPDATE replenishment_purchase_plans SET name=?,status=?,revision=?,request_hash=?,updated_by=?,updated_at=now(),submitted_at=CASE WHEN ? THEN now() ELSE NULL END WHERE tenant_id=? AND id=?",command.name().trim(),submit?"SUBMITTED":"DRAFT",hasLines?revision+1:1,hash,actor,submit,tenant,command.id());
  return read(tenant,command.id());
 }
 static void validate(Command c){
  if(c==null||c.id()==null||c.revision()<0||c.name()==null||c.name().isBlank()||c.name().trim().length()>160||c.lines()==null||c.lines().isEmpty()||c.lines().size()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter a plan name and 1–500 items.");
  Set<UUID> ids=new HashSet<>();for(var l:c.lines())if(l==null||l.id()==null||!ids.add(l.id())||l.each()<1||l.each()>100000000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Enter unique items with whole positive quantities.");
 }
 private List<Line> resolve(UUID tenant,List<InputLine> input){
  String placeholders=String.join(",",Collections.nCopies(input.size(),"?"));var args=new ArrayList<Object>();args.add(tenant);input.forEach(l->args.add(l.id()));
  // One bounded catalogue query; never trust browser costs, vendor identity or account scope.
  var rows=jdbc.query("""
   SELECT item.id,coalesce(item.display_name,p.canonical_name) name,coalesce(o.vendor_item_code,item.account_sku,'') code,
    coalesce((SELECT identifier_value FROM global_product_identifiers WHERE global_product_id=p.id AND identifier_type IN ('UPC','EAN','GTIN') ORDER BY is_primary DESC,identifier_type,identifier_value LIMIT 1),'') barcode,
    o.vendor_id,coalesce(o.vendor_name,'Choose vendor') vendor,coalesce(o.dc,'') dc,
    coalesce(item.replenishment_units_per_case,o.units_per_case,p.units_per_case,1) pack,o.unit_cost,coalesce(o.currency,'USD') currency
   FROM account_catalog_items item JOIN global_catalog_products p ON p.id=item.global_product_id
   LEFT JOIN LATERAL (SELECT v.id vendor_id,v.name vendor_name,v.distribution_center dc,vco.vendor_item_code,
     round(vco.list_cost*(1-vco.discount_rate/100),4) unit_cost,vco.currency,pv.units_per_case
     FROM vendor_catalog_offers vco JOIN vendors v ON v.tenant_id=vco.tenant_id AND v.id=vco.vendor_id AND v.status='ACTIVE'
     LEFT JOIN global_product_packaging_versions pv ON pv.id=vco.packaging_version_id
     WHERE vco.tenant_id=item.tenant_id AND vco.account_catalog_item_id=item.id AND vco.effective_from<=current_date AND (vco.effective_to IS NULL OR vco.effective_to>=current_date)
     ORDER BY vco.is_default DESC,vco.updated_at DESC,vco.id LIMIT 1) o ON true
   WHERE item.tenant_id=? AND item.status='ACTIVE' AND item.id IN (
   """+placeholders+")",(r,n)->new Line(r.getObject("id",UUID.class),r.getString("name"),r.getString("code"),r.getString("barcode"),r.getObject("vendor_id",UUID.class),r.getString("vendor"),r.getString("dc"),r.getBigDecimal("pack"),0,r.getBigDecimal("unit_cost"),r.getString("currency")),args.toArray());
  if(rows.size()!=input.size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"An item is inactive or unavailable in this account. Your basket is unchanged.");
  Map<UUID,Long> quantities=new HashMap<>();input.forEach(l->quantities.put(l.id(),l.each()));
  return rows.stream().map(l->new Line(l.id(),l.name(),l.code(),l.upc(),l.vendorId(),l.vendor(),l.dc(),l.pack(),quantities.get(l.id()),l.cost(),l.currency())).toList();
 }
 private static String termsHash(List<Line> lines){
  String value=lines.stream().sorted(Comparator.comparing(l->l.id().toString())).map(l->l.id()+":"+l.vendorId()+":"+l.pack().stripTrailingZeros().toPlainString()+":"+l.each()+":"+(l.cost()==null?"?":l.cost().stripTrailingZeros().toPlainString())+":"+l.currency()).reduce("",(a,b)->a+"|"+b);
  return digest(value);
 }
 private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private static String hash(Command c,boolean submit){try{
  StringBuilder value=new StringBuilder(c.name().trim()).append('|').append(submit).append('|').append(c.termsRevision());
  c.lines().stream().sorted(Comparator.comparing(l->l.id().toString())).forEach(l->value.append('|').append(l.id()).append(':').append(l.each()));
  return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
 }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
