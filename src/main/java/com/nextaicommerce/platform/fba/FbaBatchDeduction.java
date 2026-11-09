package com.nextaicommerce.platform.fba;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.nextaicommerce.platform.web.MemberAccessService;
/** One bounded, atomic owner-approved selection; never a background inventory action. */
@Service
public class FbaBatchDeduction {
 private final FbaDepletionRepository stock;private final JdbcTemplate jdbc;private final MemberAccessService access;
 private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
 public FbaBatchDeduction(FbaDepletionRepository stock,JdbcTemplate jdbc,MemberAccessService access){this.stock=stock;this.jdbc=jdbc;this.access=access;}
 public record Plan(FbaDepletionRepository.Preview shipment,List<FbaDepletionRepository.Allocation> allocations){}
 public record Review(List<Plan> plans,List<String> blockers,String revision){}
 public record Choice(String shipment,List<FbaDepletionRepository.Allocation> allocations){}
 private List<String> members(List<String> ids){if(ids==null||ids.isEmpty()||ids.size()>25||ids.stream().anyMatch(v->v==null||v.isBlank()||v.length()>80)||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Select 1–25 distinct shipments.");return ids.stream().sorted().toList();}
 private String hash(Object value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private void scope(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 @Transactional(readOnly=true) public Review preview(UUID tenant,UUID connection,List<String> ids){scope(tenant);var plans=new ArrayList<Plan>();var blockers=new ArrayList<String>();var remaining=new HashMap<UUID,BigDecimal>();
  for(String id:members(ids)){var p=stock.preview(tenant,connection,id);for(String reason:p.blockers())blockers.add(id+": "+reason);if(p.depleted())blockers.add(id+": Stock already deducted.");
   var lots=new HashMap<UUID,FbaDepletionRepository.Stock>();p.stock().forEach(l->{lots.put(l.layer(),l);remaining.putIfAbsent(l.layer(),l.available());});var required=new LinkedHashMap<UUID,BigDecimal>();
   for(var a:p.allocations()){var lot=lots.get(a.layer());if(lot!=null)required.merge(lot.item(),a.quantity(),BigDecimal::add);}
   var allocations=new ArrayList<FbaDepletionRepository.Allocation>();for(var item:required.entrySet()){BigDecimal left=item.getValue();for(var lot:p.stock()){if(!lot.item().equals(item.getKey())||left.signum()<=0)continue;var take=remaining.get(lot.layer()).min(left);if(take.signum()>0){allocations.add(new FbaDepletionRepository.Allocation(lot.layer(),take));remaining.put(lot.layer(),remaining.get(lot.layer()).subtract(take));left=left.subtract(take);}}if(left.signum()>0)blockers.add(id+": Not enough eligible stock for the complete selection.");}
   plans.add(new Plan(p,allocations));
  }return new Review(plans,List.copyOf(new LinkedHashSet<>(blockers)),hash(plans));
 }
 @Transactional public List<UUID> confirm(UUID tenant,UUID connection,String email,UUID request,String revision,List<Choice> choices){
  if(request==null||revision==null||choices==null)throw new IllegalArgumentException("Review the selection first.");var ids=members(choices.stream().map(Choice::shipment).toList());scope(tenant);
  var member=access.current(tenant,email);if(!access.activePlatformAdministrator(email)&&(member==null||!Set.of("OWNER","ADMIN","OPERATOR").contains(member.role())||member.restricted()&&!member.stores().contains(connection)))throw new org.springframework.security.access.AccessDeniedException("Account or store access changed.");
  jdbc.execute("SET LOCAL lock_timeout='5s'");jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,tenant.toString());
  var normalized=choices.stream().sorted(Comparator.comparing(Choice::shipment)).map(c->new Choice(c.shipment(),canonical(c.allocations()))).toList();String signature=hash(List.of(connection,revision,normalized));
  var prior=jdbc.query("SELECT id,shipment_id,approval::text FROM fba_shipment_depletions WHERE tenant_id=? AND approval->'batch'->>'id'=? ORDER BY shipment_id",(r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getString(3)},tenant,request.toString());
  if(!prior.isEmpty()){if(prior.size()!=ids.size()||!prior.stream().map(v->(String)v[1]).sorted().toList().equals(ids)||prior.stream().anyMatch(v->!json.readTree((String)v[2]).path("batch").path("signature").asText().equals(signature)))throw new IllegalArgumentException("Batch request was already used with a different selection or allocation.");return prior.stream().sorted(Comparator.comparing(v->(String)v[1])).map(v->(UUID)v[0]).toList();}
  for(String id:ids)stock.lockShipment(tenant,connection,id);
  var review=preview(tenant,connection,ids);if(!review.blockers().isEmpty())throw new IllegalArgumentException(String.join(" ",review.blockers()));if(!review.revision().equals(revision))throw new IllegalArgumentException("Selection, stock or mapping changed. Review again.");
  var result=new ArrayList<UUID>();Map<String,Object> batch=Map.of("id",request.toString(),"signature",signature,"members",ids);
  for(var c:normalized){var current=stock.preview(tenant,connection,c.shipment());UUID key=UUID.nameUUIDFromBytes((request+"|"+c.shipment()).getBytes(StandardCharsets.UTF_8));result.add(stock.deplete(tenant,connection,c.shipment(),email,key,current.revision(),c.allocations(),batch));}
  return result;
 }
 private List<FbaDepletionRepository.Allocation> canonical(List<FbaDepletionRepository.Allocation> values){if(values==null||values.isEmpty()||values.size()>2000)throw new IllegalArgumentException("Choose stock allocations.");var ids=new HashSet<UUID>();return values.stream().map(a->{if(a.layer()==null||a.quantity()==null||a.quantity().signum()<=0||a.quantity().stripTrailingZeros().scale()>0||!ids.add(a.layer()))throw new IllegalArgumentException("Choose unique positive whole-unit allocations.");return new FbaDepletionRepository.Allocation(a.layer(),a.quantity().stripTrailingZeros());}).sorted(Comparator.comparing(a->a.layer().toString())).toList();}
}
