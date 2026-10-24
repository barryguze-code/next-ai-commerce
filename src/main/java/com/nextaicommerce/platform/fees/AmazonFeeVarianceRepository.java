package com.nextaicommerce.platform.fees;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AmazonFeeVarianceRepository {
    private final JdbcTemplate jdbc;private final AmazonFeeEvidence evidence;
    private final boolean visible,approvals;
    private com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider relationships;
    @org.springframework.beans.factory.annotation.Autowired
    void configureRelationships(com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider value){relationships=value;}
    private static final String APPROVED_AMOUNT="""
      (SELECT a.amount FROM amazon_fee_approvals a WHERE a.tenant_id=f.tenant_id AND a.marketplace_connection_id=f.marketplace_connection_id
       AND a.marketplace_id=f.marketplace_id AND a.seller_sku=f.seller_sku AND a.currency=f.currency AND a.fee_type=f.fee_type
       AND a.unit_price=f.unit_price AND a.shipping=f.shipping ORDER BY a.effective_from DESC,a.id DESC LIMIT 1)
      """;
    private static List<Map<String,Object>> wireMoney(List<Map<String,Object>> rows){
        for(var row:rows){
            int qty=row.get("quantity") instanceof Number n?n.intValue():0;
            BigDecimal unit=AmazonFeeEvidence.perUnit((BigDecimal)row.get("charge_expense"),qty);
            row.put("unit_actual",unit==null?null:unit.toPlainString());
            row.put("already_approved",unit!=null&&row.get("approved_amount") instanceof BigDecimal a&&unit.compareTo(a)==0);
            for(String key:List.of("expected_amount","actual_amount","difference","unit_price","shipping","approved_amount","unit_expected","charge_expense"))
                if(row.get(key) instanceof BigDecimal value)row.put(key,value.toPlainString());
        }return rows;
    }

    public AmazonFeeVarianceRepository(JdbcTemplate jdbc,AmazonFeeEvidence evidence,
            @Value("${app.amazon.fee-variance-ui-enabled:false}") boolean visible,
            @Value("${app.amazon.fee-approvals-enabled:false}") boolean approvals){this.jdbc=jdbc;this.evidence=evidence;this.visible=visible;this.approvals=approvals;}
    public boolean visible(){return visible;}
    public boolean prospectiveEnabled(){return evidence.enabled();}
    public boolean approvalsEnabled(){return visible&&approvals&&evidence.enabled();}
    private void scope(UUID tenant,UUID store){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        if(jdbc.queryForObject("SELECT count(*) FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='AMAZON'",Integer.class,tenant,store)!=1)throw new IllegalArgumentException("Choose an Amazon store");}
    @Transactional(readOnly=true)
    public Map<String,Object> context(UUID tenant,UUID store){scope(tenant,store);return jdbc.queryForMap("SELECT display_name,marketplace_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",tenant,store);}
    /** Historical differences stay identifiable even when a later revision supersedes them. */
    @Transactional(readOnly=true)
    public List<Map<String,Object>> history(UUID tenant,UUID store,String order,String sku){return historyPage(tenant,store,order,sku,0,null).rows();}
    public record HistoryPage(List<Map<String,Object>> rows,boolean hasMore,int page,long snapshot){}
    @Transactional(readOnly=true)
    public HistoryPage historyPage(UUID tenant,UUID store,String order,String sku,int page,Long requestedSnapshot){
        if(!visible)return new HistoryPage(List.of(),false,0,0);scope(tenant,store);
        if(page<0||page>10000)throw new IllegalArgumentException("Invalid evidence page");
        Long latest=jdbc.queryForObject("SELECT coalesce(max(revision_seq),0) FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=?",Long.class,tenant,store);
        long snapshot=requestedSnapshot==null?latest:Math.max(0,Math.min(requestedSnapshot,latest));
        var rows=jdbc.queryForList("""
            SELECT f.id,f.order_id,f.order_item_id,f.seller_sku,f.fee_type,f.currency,f.quantity,f.unit_price,f.shipping,
             f.charge_id,f.expected_amount,f.actual_amount,f.difference,f.state,f.reason,f.boundary_at,f.observed_at,
             c.posted_at charge_posted_at,c.observed_at charge_received_at,c.expense_amount charge_expense,
             coalesce((SELECT a.amount FROM amazon_fee_approvals a WHERE a.tenant_id=f.tenant_id AND a.marketplace_connection_id=f.marketplace_connection_id AND a.marketplace_id=f.marketplace_id AND a.id=f.approval_id),
               (SELECT q.amount FROM amazon_fee_observations q WHERE q.tenant_id=f.tenant_id AND q.marketplace_connection_id=f.marketplace_connection_id AND q.marketplace_id=f.marketplace_id AND q.id=f.observation_id)) unit_expected,
            """+APPROVED_AMOUNT+"""
             approved_amount,
             EXISTS(SELECT 1 FROM amazon_fee_observations q JOIN amazon_listings l ON l.tenant_id=q.tenant_id
              AND l.marketplace_connection_id=q.marketplace_connection_id AND l.marketplace_id=q.marketplace_id AND l.seller_sku=q.seller_sku
              WHERE q.tenant_id=f.tenant_id AND q.marketplace_connection_id=f.marketplace_connection_id AND q.seller_sku=f.seller_sku
               AND q.marketplace_id=f.marketplace_id AND q.currency=f.currency AND q.unit_price=f.unit_price AND q.shipping=f.shipping AND q.fee_type=f.fee_type
               AND q.basis->>'_platformShippingApproved'='true' AND q.observed_at>=clock_timestamp()-interval '24 hours'
               AND l.updated_at<=q.observed_at AND l.price=f.unit_price AND l.currency=f.currency) shipping_verified,
             (SELECT a.id FROM amazon_fee_approvals a WHERE a.tenant_id=f.tenant_id AND a.marketplace_connection_id=f.marketplace_connection_id
                AND a.marketplace_id=f.marketplace_id AND a.seller_sku=f.seller_sku AND a.fee_type=f.fee_type AND a.currency=f.currency
                AND a.unit_price=f.unit_price AND a.shipping=f.shipping ORDER BY a.effective_from DESC,a.id DESC LIMIT 1) current_approval,
             f.id=latest.id current_revision,
             (f.charge_id=latest.charge_id AND f.expected_amount IS NOT DISTINCT FROM latest.expected_amount
               AND f.actual_amount IS NOT DISTINCT FROM latest.actual_amount) same_economic_as_current
            FROM amazon_fee_comparisons f LEFT JOIN amazon_order_fee_charge_observations c
             ON c.tenant_id=f.tenant_id AND c.marketplace_connection_id=f.marketplace_connection_id AND c.id=f.charge_id
            LEFT JOIN LATERAL(SELECT n.id,n.charge_id,n.expected_amount,n.actual_amount FROM amazon_fee_comparisons n
              WHERE n.tenant_id=f.tenant_id AND n.marketplace_connection_id=f.marketplace_connection_id AND n.marketplace_id=f.marketplace_id
                AND n.order_item_id=f.order_item_id AND n.fee_type=f.fee_type ORDER BY n.revision_seq DESC LIMIT 1) latest ON true
            WHERE f.tenant_id=? AND f.marketplace_connection_id=? AND (?='' OR f.order_id=?) AND (?='' OR f.seller_sku=?) AND f.revision_seq<=?
            ORDER BY f.purchase_at DESC,f.order_item_id,f.fee_type,f.revision_seq DESC LIMIT 101 OFFSET ?
            """,tenant,store,order,order,sku,sku,snapshot,page*100);
        return new HistoryPage(List.copyOf(wireMoney(rows.subList(0,Math.min(100,rows.size())))),rows.size()>100,page,snapshot);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> previews(UUID tenant,UUID store,String kind,List<String> keys){
        if(!visible)return List.of();scope(tenant,store);
        if(!Set.of("ORDER","SKU").contains(kind)||keys.isEmpty()||keys.size()>200||keys.stream().anyMatch(k->k==null||k.length()>240))throw new IllegalArgumentException("Choose visible records");
        String column="ORDER".equals(kind)?"order_id":"seller_sku";
        var args=new ArrayList<Object>();args.add(tenant);args.add(store);args.addAll(keys);
        if("SKU".equals(kind))return jdbc.queryForList("SELECT seller_sku,count(DISTINCT order_id) affected_orders FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=? AND state IN ('HIGHER','LOWER') AND seller_sku IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+") GROUP BY seller_sku",args.toArray());
        return wireMoney(jdbc.queryForList("SELECT DISTINCT ON(f.order_item_id,f.fee_type) f.order_id,f.seller_sku,f.fee_type,f.state,f.reason,f.difference,f.currency,f.quantity,"+APPROVED_AMOUNT+" approved_amount FROM amazon_fee_comparisons f WHERE f.tenant_id=? AND f.marketplace_connection_id=? AND f."+column+" IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+") ORDER BY f.order_item_id,f.fee_type,f.revision_seq DESC",args.toArray()));
    }
    @Transactional(readOnly=true)
    public long count(UUID tenant,UUID store,boolean skus){if(!visible)return 0;scope(tenant,store);
        return jdbc.queryForObject("SELECT count(DISTINCT "+(skus?"seller_sku":"order_id")+") FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=? AND state IN ('HIGHER','LOWER')",Long.class,tenant,store);}
    public record Choice(UUID comparison,UUID expectedApproval){}
    @Transactional
    public List<UUID> approveAll(UUID tenant,UUID store,List<Choice> choices,UUID request,String actor){
        if(!approvalsEnabled())throw new IllegalArgumentException("Fee approval is not enabled");
        if(choices==null||choices.isEmpty()||choices.size()>2)throw new IllegalArgumentException("Choose one or two fee types");
        scope(tenant,store);Set<String> types=new HashSet<>();List<Object> identity=null;
        for(var choice:choices){
            if(choice==null||choice.comparison()==null)throw new IllegalArgumentException("Choose exact order fee evidence");
            var found=jdbc.queryForList("SELECT order_item_id,order_id,seller_sku,marketplace_id,currency,unit_price,shipping,quantity,fee_type FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,choice.comparison());
            if(found.isEmpty())throw new IllegalArgumentException("Fee evidence unavailable");var row=found.getFirst();
            var current=Arrays.<Object>asList(row.get("order_item_id"),row.get("order_id"),row.get("seller_sku"),row.get("marketplace_id"),row.get("currency"),row.get("unit_price"),row.get("shipping"),row.get("quantity"));
            if(current.contains(null)||(identity!=null&&!identity.equals(current))||!types.add(row.get("fee_type").toString()))throw new IllegalArgumentException("Choose exact order-item fee evidence");identity=current;
        }
        List<UUID> result=new ArrayList<>();for(var choice:choices)result.add(approve(tenant,store,choice.comparison(),request,choice.expectedApproval(),actor));return List.copyOf(result);
    }
    @Transactional
    public UUID approve(UUID tenant,UUID store,UUID comparison,UUID request,UUID expectedApproval,String actor){
        if(!approvalsEnabled())throw new IllegalArgumentException("Fee approval is not enabled");scope(tenant,store);
        if(comparison==null||request==null||actor==null||actor.isBlank())throw new IllegalArgumentException("Choose current fee evidence");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+":"+store+":fee-approval");
        var rows=jdbc.queryForList("SELECT * FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,comparison);
        if(rows.isEmpty())throw new IllegalArgumentException("Fee evidence unavailable");var old=rows.getFirst();
        var replay=jdbc.queryForList("SELECT id,source_charge_id,comparison_id,actor FROM amazon_fee_approvals WHERE tenant_id=? AND marketplace_connection_id=? AND request_key=? AND fee_type=?",tenant,store,request,old.get("fee_type"));
        if(!replay.isEmpty()){
            var prior=replay.getFirst();if(!Objects.equals(prior.get("comparison_id"),comparison)||!Objects.equals(prior.get("source_charge_id"),old.get("charge_id"))||!Objects.equals(prior.get("actor"),actor))throw new IllegalArgumentException("Approval request was used for different evidence");
            return (UUID)prior.get("id");
        }
        if(relationships==null)throw new IllegalArgumentException("Relationship classification unavailable");
        if(relationships!=null){
            var scope=new com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope(tenant,store,(String)old.get("marketplace_id"));
            var classification=relationships.classify(scope,List.of((String)old.get("seller_sku"))).get(old.get("seller_sku"));
            if(classification!=null&&Set.of(com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.State.PARENT,com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.State.CONFLICT).contains(classification.state()))throw new IllegalArgumentException("Parent or conflicting listing is not eligible for prospective fees");
        }
        evidence.compareOrder(tenant,store,(String)old.get("marketplace_id"),(String)old.get("order_id"));
        var latest=jdbc.queryForMap("SELECT * FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=? AND order_item_id=? AND fee_type=? ORDER BY revision_seq DESC LIMIT 1",tenant,store,old.get("order_item_id"),old.get("fee_type"));
        if(!comparison.equals(latest.get("id"))||!Set.of("HIGHER","LOWER").contains(latest.get("state")))throw new IllegalArgumentException("Fee evidence changed. Compare again.");
        var charge=jdbc.queryForMap("SELECT * FROM amazon_order_fee_charge_observations WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",tenant,store,old.get("charge_id"));
        BigDecimal unit=AmazonFeeEvidence.perUnit((BigDecimal)charge.get("expense_amount"),((Number)old.get("quantity")).intValue());
        if(unit==null||!"SUPPORTED".equals(charge.get("state")))throw new IllegalArgumentException("Actual unit basis unavailable");
        // Prospective acceptance must also match the current listing, never a historical price shortcut.
        if(jdbc.queryForObject("SELECT count(*) FROM amazon_listings WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? AND price=? AND currency=? AND upper(listing_status)='ACTIVE' AND upper(fulfillment_channel) IN ('AFN','FBA','AMAZON','AMAZON_NA','AMAZON_EU','AMAZON_JP')",Integer.class,tenant,store,old.get("marketplace_id"),old.get("seller_sku"),old.get("unit_price"),old.get("currency"))!=1)throw new IllegalArgumentException("Current SKU basis differs. Compare again.");
        if(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM amazon_fee_comparisons f WHERE f.tenant_id=? AND f.marketplace_connection_id=?
             AND f.marketplace_id=? AND f.seller_sku=? AND f.currency=? AND f.fee_type=? AND f.unit_price=? AND f.shipping=?
             AND f.actual_amount IS NOT NULL AND f.actual_amount/f.quantity<>? AND f.purchase_at>=?
             AND NOT EXISTS(SELECT 1 FROM amazon_fee_comparisons n WHERE n.tenant_id=f.tenant_id AND n.marketplace_connection_id=f.marketplace_connection_id
               AND n.order_item_id=f.order_item_id AND n.fee_type=f.fee_type AND n.revision_seq>f.revision_seq))
            """,Boolean.class,tenant,store,old.get("marketplace_id"),old.get("seller_sku"),old.get("currency"),old.get("fee_type"),old.get("unit_price"),old.get("shipping"),unit,old.get("boundary_at")))throw new IllegalArgumentException("Conflicting actual fees need investigation");
        var previous=jdbc.queryForList("SELECT id FROM amazon_fee_approvals WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND seller_sku=? AND currency=? AND fee_type=? AND unit_price=? AND shipping=? ORDER BY effective_from DESC,id DESC LIMIT 1",UUID.class,tenant,store,old.get("marketplace_id"),old.get("seller_sku"),old.get("currency"),old.get("fee_type"),old.get("unit_price"),old.get("shipping"));
        UUID prior=previous.isEmpty()?null:previous.getFirst();
        if(!Objects.equals(prior,expectedApproval))throw new IllegalArgumentException("Approved basis changed. Compare again.");
        if(!jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM amazon_fee_observations q JOIN amazon_listings l ON l.tenant_id=q.tenant_id
              AND l.marketplace_connection_id=q.marketplace_connection_id AND l.marketplace_id=q.marketplace_id AND l.seller_sku=q.seller_sku
              WHERE q.tenant_id=? AND q.marketplace_connection_id=? AND q.marketplace_id=? AND q.seller_sku=? AND q.currency=? AND q.fee_type=? AND q.unit_price=? AND q.shipping=?
                AND q.basis->>'_platformShippingApproved'='true' AND q.observed_at>=clock_timestamp()-interval '24 hours'
                AND l.updated_at<=q.observed_at)
            """,Boolean.class,tenant,store,old.get("marketplace_id"),old.get("seller_sku"),old.get("currency"),old.get("fee_type"),old.get("unit_price"),old.get("shipping")))throw new IllegalArgumentException("Current shipping basis requires verification");
        jdbc.update("""
            INSERT INTO amazon_fee_approvals(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,fulfillment,
             fee_type,unit_price,shipping,amount,source_charge_id,comparison_id,actor,request_key,previous_id)
            VALUES(?,?,?,?,?,'FBA',?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """,tenant,store,old.get("marketplace_id"),old.get("seller_sku"),old.get("currency"),old.get("fee_type"),old.get("unit_price"),old.get("shipping"),unit,old.get("charge_id"),comparison,actor,request,previous.isEmpty()?null:previous.getFirst());
        return jdbc.queryForObject("SELECT id FROM amazon_fee_approvals WHERE tenant_id=? AND marketplace_connection_id=? AND request_key=? AND fee_type=?",UUID.class,tenant,store,request,old.get("fee_type"));
    }
    @Transactional(readOnly=true)
    public BigDecimal applicableSku(UUID tenant,UUID store,String marketplace,String fulfillment,String sku,String currency,String type,BigDecimal price,BigDecimal shipping){
        if(!evidence.enabled()||!"FBA".equals(fulfillment))return null;scope(tenant,store);
        var rows=jdbc.queryForList("SELECT amount FROM amazon_fee_approvals WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND fulfillment=? AND seller_sku=? AND currency=? AND fee_type=? AND unit_price=? AND shipping=? AND effective_from<=clock_timestamp() ORDER BY effective_from DESC,id DESC LIMIT 1",BigDecimal.class,tenant,store,marketplace,fulfillment,sku,currency,type,price,shipping);
        return rows.isEmpty()?null:rows.getFirst();
    }
}
