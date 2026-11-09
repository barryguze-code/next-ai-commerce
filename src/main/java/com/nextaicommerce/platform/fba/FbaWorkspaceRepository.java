package com.nextaicommerce.platform.fba;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
@Repository
public class FbaWorkspaceRepository {
 private final JdbcTemplate jdbc;public FbaWorkspaceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Transactional(readOnly=true) public Set<String> depletable(UUID tenant,UUID connection){
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  return Set.copyOf(jdbc.queryForList("""
   SELECT s.shipment_id FROM amazon_fba_shipments s WHERE s.tenant_id=? AND s.marketplace_connection_id=?
    AND NOT EXISTS(SELECT 1 FROM fba_shipment_depletions d WHERE d.tenant_id=s.tenant_id AND d.marketplace_connection_id=s.marketplace_connection_id AND d.shipment_id=s.shipment_id)
    AND (SELECT count(*) FROM warehouses w WHERE w.tenant_id=s.tenant_id AND w.status='ACTIVE' AND upper(w.country_code)=upper(s.raw_payload#>>'{ShipFromAddress,CountryCode}') AND nullif(upper(replace(btrim(w.postal_code),' ','')),'')=upper(replace(btrim(s.raw_payload#>>'{ShipFromAddress,PostalCode}'),' ','')))=1
   """,String.class,tenant,connection));
 }
 public record DepletionState(boolean adjusted,boolean depleted,boolean needsReview,String reason){}
 @Transactional(readOnly=true) public Map<String,DepletionState> states(UUID tenant,UUID connection){
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());var result=new HashMap<String,DepletionState>();
  jdbc.query("""
   SELECT s.shipment_id,EXISTS(SELECT 1 FROM fba_shipment_depletion_overrides o WHERE o.tenant_id=s.tenant_id AND o.marketplace_connection_id=s.marketplace_connection_id AND o.shipment_id=s.shipment_id) adjusted,
    d.id IS NOT NULL depleted,a.status='NEEDS_REVIEW' needs_review,a.reason
   FROM amazon_fba_shipments s LEFT JOIN fba_shipment_depletions d ON d.tenant_id=s.tenant_id AND d.marketplace_connection_id=s.marketplace_connection_id AND d.shipment_id=s.shipment_id
   LEFT JOIN fba_shipment_depletion_attempts a ON a.tenant_id=s.tenant_id AND a.marketplace_connection_id=s.marketplace_connection_id AND a.shipment_id=s.shipment_id
   WHERE s.tenant_id=? AND s.marketplace_connection_id=?
   """,r->{result.put(r.getString(1),new DepletionState(r.getBoolean(2),r.getBoolean(3),r.getBoolean(4),r.getString(5)));},tenant,connection);return result;
 }
 public record Tab(String value,String label,long count){}
 @Transactional(readOnly=true) public List<Tab> tabs(UUID tenant,UUID connection,String email){
  jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
  var rows=jdbc.query("""
   WITH facts AS(SELECT s.shipment_status,
    EXISTS(SELECT 1 FROM fba_shipment_user_preferences p JOIN app_users u ON u.id=p.user_id WHERE p.tenant_id=s.tenant_id AND p.marketplace_connection_id=s.marketplace_connection_id AND p.shipment_id=s.shipment_id AND lower(u.email)=lower(?)) removed,
    jsonb_exists(s.raw_payload,'itemsCheckedAt') AND EXISTS(SELECT 1 FROM amazon_fba_shipment_items i WHERE i.tenant_id=s.tenant_id AND i.marketplace_connection_id=s.marketplace_connection_id AND i.shipment_id=s.shipment_id GROUP BY i.shipment_id HAVING sum(quantity_shipped)<>sum(quantity_received)) difference
    FROM amazon_fba_shipments s WHERE s.tenant_id=? AND s.marketplace_connection_id=?)
   SELECT coalesce(shipment_status,'UNKNOWN') status,count(*) FILTER(WHERE NOT removed) total,count(*) FILTER(WHERE NOT removed AND difference) differences,count(*) FILTER(WHERE removed) removed FROM facts GROUP BY shipment_status
   """,(r,n)->new Object[]{r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4)},email,tenant,connection);
  var result=new ArrayList<Tab>();long total=0,difference=0,removed=0;for(var r:rows){total+=(Long)r[1];difference+=(Long)r[2];removed+=(Long)r[3];}result.add(new Tab("","All",total));
  rows.stream().sorted(java.util.Comparator.comparing(r->(String)r[0])).forEach(r->{if((Long)r[1]>0)result.add(new Tab((String)r[0],((String)r[0]).toLowerCase(Locale.ROOT).replace('_',' '),(Long)r[1]));});
  if(difference>0)result.add(new Tab("DIFFERENCE","Shipped ≠ received",difference));if(removed>0)result.add(new Tab("REMOVED","Removed",removed));return result;
 }
}
