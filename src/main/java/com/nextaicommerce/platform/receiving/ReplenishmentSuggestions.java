package com.nextaicommerce.platform.receiving;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant-isolated, immutable last-good snapshots. A page request never aggregates order history. */
@Service
public class ReplenishmentSuggestions {
 static final long REFRESH_SECONDS=6*60*60;
 public record Snapshot(List<Map<String,Object>> items,Instant generatedAt) {}
 private final JdbcTemplate jdbc; private final TransactionTemplate tx;
 private final ReplenishmentDataLoader loader; private final ObjectMapper json;
 private final Map<UUID,Snapshot> snapshots=new ConcurrentHashMap<>();
 private final Map<UUID,Long> revisions=new ConcurrentHashMap<>(),builtRevisions=new ConcurrentHashMap<>();
 private final Set<UUID> building=ConcurrentHashMap.newKeySet();
 private final Map<UUID,String> errors=new ConcurrentHashMap<>();
 private final java.util.concurrent.atomic.AtomicBoolean refreshQueued=new java.util.concurrent.atomic.AtomicBoolean();
 private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"replenishment-draft");thread.setDaemon(true);return thread;});
 public ReplenishmentSuggestions(JdbcTemplate jdbc,TransactionTemplate tx,ReplenishmentDataLoader loader,ObjectMapper json){this.jdbc=jdbc;this.tx=tx;this.loader=loader;this.json=json;}
 private void context(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
 public Snapshot snapshot(UUID tenant){return snapshots.get(tenant);}
 public boolean pending(UUID tenant){var previous=snapshot(tenant);return building.contains(tenant)||previous==null||!previous.generatedAt().isAfter(Instant.now().minusSeconds(REFRESH_SECONDS))||!Objects.equals(revisions.getOrDefault(tenant,0L),builtRevisions.getOrDefault(tenant,0L));}
 public String error(UUID tenant){return errors.get(tenant);}
 public synchronized void requestRefresh(UUID tenant){if(!pending(tenant))revisions.merge(tenant,1L,Long::sum);}
 public List<Map<String,Object>> vendors(UUID tenant){return tx.execute(s->{context(tenant);return jdbc.queryForList("SELECT id,name,coalesce(vendor_code,'—') code,coalesce(distribution_center,'') dc FROM vendors WHERE tenant_id=? AND status='ACTIVE' ORDER BY name,id",tenant);});}
 public Map<String,Object> configuration(UUID tenant){return tx.execute(s->{context(tenant);var values=jdbc.queryForList("SELECT configuration::text FROM replenishment_settings WHERE tenant_id=?",String.class,tenant);if(values.isEmpty())return new LinkedHashMap<>(Map.of("low",7,"target",14,"overstock",35,"vendors",Map.of()));try{return json.readValue(values.get(0),new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Cannot read replenishment settings",e);}});}
 static int integer(Map<String,Object> m,String key,int fallback){Object value=m.get(key);if(value==null||value.toString().isBlank())return fallback;try{return Integer.parseInt(value.toString());}catch(Exception e){throw new IllegalArgumentException("Enter whole days for "+key);}}
 @SuppressWarnings("unchecked")
 public static ReplenishmentPlanning.Policy policy(Map<String,Object> config,UUID vendor,String name){
  var all=(Map<String,Object>)config.getOrDefault("vendors",Map.of());
  var override=(Map<String,Object>)all.getOrDefault(vendor==null?"":vendor.toString(),Map.of());
  String upper=name.toUpperCase(Locale.ROOT);int defaultLead=upper.contains("KEHE")?2:upper.contains("OUTER AISLE")?12:0;
  return new ReplenishmentPlanning.Policy(integer(override,"lead",defaultLead),integer(override,"low",integer(config,"low",7)),integer(override,"target",integer(config,"target",14)),integer(override,"overstock",integer(config,"overstock",35)));
 }
 public void save(UUID tenant,String actor,int low,int target,int overstock,Map<String,String> form){
  new ReplenishmentPlanning.Policy(0,low,target,overstock);
  var overrides=new LinkedHashMap<String,Object>();
  for(var vendor:vendors(tenant)){
   String id=vendor.get("id").toString();var entry=new LinkedHashMap<String,Object>();
   for(String key:List.of("lead","low","target","overstock")){String value=form.get(id+"_"+key);if(value!=null&&!value.isBlank())entry.put(key,value.trim());}
   overrides.put(id,entry);
  }
  Map<String,Object> config=Map.of("low",low,"target",target,"overstock",overstock,"vendors",overrides);
  for(var vendor:vendors(tenant))policy(config,(UUID)vendor.get("id"),vendor.get("name").toString());
  tx.executeWithoutResult(s->{context(tenant);jdbc.update("INSERT INTO replenishment_settings(tenant_id,configuration,updated_by) VALUES (?,?::jsonb,?) ON CONFLICT(tenant_id) DO UPDATE SET configuration=excluded.configuration,updated_at=now(),updated_by=excluded.updated_by",tenant,json.writeValueAsString(config),actor);});
  revisions.merge(tenant,1L,Long::sum);
 }
 @Scheduled(initialDelay=10000,fixedDelay=60000)
 public void queueRefresh(){
  if(!refreshQueued.compareAndSet(false,true))return;
  try{worker.execute(()->{try{refresh();}catch(Exception e){LoggerFactory.getLogger(getClass()).error("Replenishment refresh failed; retrying on the next schedule.",e);}finally{refreshQueued.set(false);}});}
  catch(java.util.concurrent.RejectedExecutionException e){refreshQueued.set(false);}
 }
 @jakarta.annotation.PreDestroy
 public void stopWorker(){worker.shutdownNow();}
 /** Runs on its own bounded worker so forecasts never occupy the shared scheduler. */
 public void refresh(){
  for(UUID tenant:jdbc.queryForList("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",UUID.class)){
   Snapshot previous=snapshots.get(tenant);long revision=revisions.getOrDefault(tenant,0L);
   if(previous!=null&&!pending(tenant))continue;
   if(!building.add(tenant))continue;
   try{
    Map<String,Object> config=configuration(tenant);
    var reads=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
    reads.setReadOnly(true);reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var rows=reads.execute(s->{context(tenant);var all=new ArrayList<Map<String,Object>>();for(int offset=0;;offset+=25){var batch=loader.load(tenant,offset);all.addAll(batch);if(batch.size()<25)break;}ReplenishmentAvailability.enrich(jdbc,tenant,all,Instant.now());return all;});
    for(var row:rows){
     var policy=policy(config,(UUID)row.get("vendor_id"),row.get("vendor").toString());
     double minimum=2;
     for(var sku:(List<Map<String,Object>>)row.get("skus"))if(((Number)sku.get("eaches")).doubleValue()>0)minimum=Math.max(minimum,2*((Number)sku.get("quantity")).doubleValue());
     var estimate=ReplenishmentPlanning.estimate(((Number)row.get("available")).doubleValue(),((Number)row.get("demand")).doubleValue(),((Number)row.get("pack")).doubleValue(),policy,minimum);
     double cover=((Number)row.get("available")).doubleValue()/estimate.daily();
     row.put("cover",cover);row.put("low_days",policy.low());row.put("target_days",policy.target());row.put("overstock_days",policy.overstock());
     row.put("bar_percent",Math.min(100,Math.max(0,cover/(policy.overstock()*1.25)*100)));row.put("minimum_each",minimum);
     row.put("lead",policy.lead());row.put("cases",estimate.cases());row.put("status",estimate.status());
     row.put("suggested_each",estimate.cases()*((Number)row.get("pack")).doubleValue());
    }
    snapshots.put(tenant,new Snapshot(List.copyOf(rows),Instant.now()));builtRevisions.put(tenant,revision);errors.remove(tenant);
   }catch(Exception e){errors.put(tenant,"Forecast preparation failed. Your previous forecast is preserved; another attempt will run shortly.");LoggerFactory.getLogger(getClass()).error("Replenishment generation failed for tenant {}. Keeping last good snapshot.",tenant,e);}
   finally{building.remove(tenant);}
  }
 }
}
