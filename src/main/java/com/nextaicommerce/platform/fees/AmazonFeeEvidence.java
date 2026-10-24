package com.nextaicommerce.platform.fees;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** All local publication participates in the caller's import transaction. No API calls or scheduler. */
@Service
public class AmazonFeeEvidence {
    // Versioned derivation contract: upgrades require explicit bounded reprojection, never source rewrites.
    static final String DERIVATION_REVISION="fee-comparison-v2";
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final boolean enabled;
    public AmazonFeeEvidence(JdbcTemplate jdbc, ObjectMapper json,
                             @Value("${app.amazon.fee-evidence-enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.json=json; this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    private void scope(UUID tenant, UUID connection, String market) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        if (jdbc.queryForObject("SELECT count(*) FROM marketplace_connections WHERE tenant_id=? AND id=? AND marketplace_identifier=? AND channel='AMAZON'",
                Integer.class,tenant,connection,market)!=1) throw new IllegalArgumentException("Store unavailable");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"fee-evidence:"+tenant+":"+connection);
    }
    @Transactional
    public void quote(UUID tenant, UUID connection, String market, String sku, UUID source, String key,
                      JsonNode basis, BigDecimal referral, BigDecimal fba, Instant observed) {
        if (!enabled) return;
        scope(tenant,connection,market);
        var money=basis.path("PriceToEstimateFees"); var listing=money.path("ListingPrice"); var shipping=money.path("Shipping");
        String currency=listing.path("CurrencyCode").asText();
        String seller=jdbc.queryForObject("SELECT seller_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,connection);
        if (!basis.path("IsAmazonFulfilled").asBoolean(false) || !market.equals(basis.path("MarketplaceId").asText())
                || !Objects.equals(seller,basis.path("_platformSellerId").asText()) || !listing.path("Amount").isNumber()
                || !shipping.path("Amount").isNumber() || !currency.matches("[A-Z]{3}") || !currency.equals(shipping.path("CurrencyCode").asText())
                || observed==null || observed.isAfter(Instant.now().plusSeconds(1))) throw new IllegalArgumentException("Quote basis unavailable");
        for (String type:List.of("REFERRAL","FBA")) {
            BigDecimal amount="REFERRAL".equals(type)?referral:fba;
            if (amount==null || amount.signum()<0) continue;
            var previous=jdbc.queryForList("""
                SELECT amount,boundary_at FROM amazon_fee_observations WHERE tenant_id=? AND marketplace_connection_id=?
                AND marketplace_id=? AND seller_sku=? AND currency=? AND fee_type=? AND unit_price=? AND shipping=?
                AND observed_at<=? ORDER BY observed_at DESC,id DESC LIMIT 1
                """,tenant,connection,market,sku,currency,type,listing.path("Amount").decimalValue(),shipping.path("Amount").decimalValue(),Timestamp.from(observed));
            Instant boundary=previous.isEmpty() || amount.compareTo((BigDecimal)previous.getFirst().get("amount"))!=0
                    ? observed : ((Timestamp)previous.getFirst().get("boundary_at")).toInstant();
            jdbc.update("""
                INSERT INTO amazon_fee_observations(tenant_id,marketplace_connection_id,marketplace_id,seller_sku,currency,
                  fulfillment,fee_type,unit_price,shipping,amount,observed_at,boundary_at,source_document_id,evidence_key,basis)
                VALUES(?,?,?,?,?,'FBA',?,?,?,?,?,?,?,?,CAST(? AS jsonb)) ON CONFLICT DO NOTHING
                """,tenant,connection,market,sku,currency,type,listing.path("Amount").decimalValue(),shipping.path("Amount").decimalValue(),amount,
                    Timestamp.from(observed),Timestamp.from(boundary),source,key,basis.toString());
        }
    }
    @Transactional
    public void finances(UUID tenant, UUID connection, String market, UUID source, JsonNode root) {
        if (!enabled) return;
        scope(tenant,connection,market);
        String seller=jdbc.queryForObject("SELECT seller_identifier FROM marketplace_connections WHERE tenant_id=? AND id=?",String.class,tenant,connection);
        Set<String> orders=new LinkedHashSet<>();
        for (var transaction:root.path("payload").path("transactions")) {
            // Serialize revisions for one seller transaction; observation time is receipt order, not Amazon effectivity.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,
                    tenant+":"+connection+":"+transaction.path("transactionId").asText("unidentified"));
            for(String priorOrder:jdbc.queryForList("SELECT DISTINCT order_id FROM amazon_order_fee_charge_observations WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND transaction_key=? AND order_id IS NOT NULL",String.class,tenant,connection,market,transaction.path("transactionId").asText("")))orders.add(priorOrder);
            Timestamp received=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class);
            String revision=digest(transaction.toString());
            for(var c:new AmazonOrderFeeParser().parse(transaction,market,seller)) {
                String key=c.transaction().isBlank()?"unidentified:"+revision:c.transaction();
                jdbc.update("""
                    INSERT INTO amazon_order_fee_charge_observations(tenant_id,marketplace_connection_id,marketplace_id,
                      transaction_key,revision,component_path,order_id,seller_sku,fee_type,currency,signed_amount,expense_amount,
                      quantity,fulfillment,posted_at,state,reason,source_document_id,raw_evidence,observed_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT DO NOTHING
                    """,tenant,connection,market,key,revision,c.path(),c.order(),c.sku(),c.type(),c.currency(),c.signed(),c.expense(),
                        c.quantity(),c.fulfillment(),c.posted()==null?null:Timestamp.from(c.posted()),c.state(),c.reason(),source,transaction.toString(),received);
                if(c.order()!=null) orders.add(c.order());
            }
        }
        for(String order:orders) { bindOrder(tenant,connection,market,order); compareOrder(tenant,connection,market,order); }
    }
    @Transactional
    public void bindOrder(UUID tenant, UUID connection, String market, String order) {
        if (!enabled) return;
        scope(tenant,connection,market);
        var items=jdbc.queryForList("""
            SELECT i.*,o.purchase_date,o.currency order_currency,o.fulfillment_channel FROM amazon_order_items i
            JOIN amazon_orders o ON o.tenant_id=i.tenant_id AND o.marketplace_connection_id=i.marketplace_connection_id
              AND o.amazon_order_id=i.amazon_order_id
            WHERE i.tenant_id=? AND i.marketplace_connection_id=? AND i.amazon_order_id=? AND o.marketplace_id=?
            """,tenant,connection,order,market);
        for(var item:items) {
            if(item.get("purchase_date")==null || item.get("seller_sku")==null || item.get("currency")==null || ((Number)item.get("quantity_ordered")).intValue()<1) continue;
            int qty=((Number)item.get("quantity_ordered")).intValue();
            BigDecimal price=perUnit((BigDecimal)item.get("item_price"),qty),shipping=perUnit((BigDecimal)item.get("shipping_price"),qty);
            Timestamp purchase=(Timestamp)item.get("purchase_date");String currency=(String)item.get("currency"),sku=(String)item.get("seller_sku");
            boolean fba=Set.of("AFN","FBA","AMAZON").contains(Objects.toString(item.get("fulfillment_channel"),""));
            for(String type:List.of("REFERRAL","FBA")) {
                Map<String,Object> basis=null; boolean approved=false;
                if(fba && price!=null && price.signum()>0 && shipping!=null && currency.equals(item.get("order_currency"))) {
                    var approvals=jdbc.queryForList("""
                        SELECT id,amount,effective_from boundary_at FROM amazon_fee_approvals WHERE tenant_id=? AND marketplace_connection_id=?
                        AND marketplace_id=? AND seller_sku=? AND currency=? AND fee_type=? AND unit_price=? AND shipping=? AND effective_from<?
                        ORDER BY effective_from DESC,id DESC LIMIT 1
                        """,tenant,connection,market,sku,currency,type,price,shipping,purchase);
                    if(!approvals.isEmpty()){basis=approvals.getFirst();approved=true;}
                    else {
                        var quotes=jdbc.queryForList("""
                            SELECT id,amount,boundary_at FROM amazon_fee_observations WHERE tenant_id=? AND marketplace_connection_id=?
                            AND marketplace_id=? AND seller_sku=? AND currency=? AND fee_type=? AND unit_price=? AND shipping=?
                            AND observed_at<? AND observed_at>=? ORDER BY observed_at DESC,id DESC LIMIT 1
                            """,tenant,connection,market,sku,currency,type,price,shipping,purchase,Timestamp.from(purchase.toInstant().minus(Duration.ofHours(24))));
                        if(!quotes.isEmpty())basis=quotes.getFirst();
                    }
                }
                jdbc.update("""
                    INSERT INTO amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,order_item_id,order_id,
                      seller_sku,fee_type,currency,quantity,unit_price,shipping,purchase_at,boundary_at,observation_id,approval_id,
                      expected_amount,state,reason,revision_key)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'BASIS') ON CONFLICT DO NOTHING
                    """,tenant,connection,market,item.get("id"),order,sku,type,currency,qty,price,shipping,purchase,
                        basis==null?null:basis.get("boundary_at"),basis==null||approved?null:basis.get("id"),basis!=null&&approved?basis.get("id"):null,
                        basis==null?null:((BigDecimal)basis.get("amount")).multiply(BigDecimal.valueOf(qty)),
                        basis==null?"NOT_COMPARABLE":"AWAITING",basis==null?"Basis unavailable":"Awaiting charges");
            }
        }
        compareOrder(tenant,connection,market,order);
    }
    @Transactional
    public void compareOrder(UUID tenant,UUID connection,String market,String order) {
        if(!enabled)return;
        scope(tenant,connection,market);
        for(var b:jdbc.queryForList("SELECT * FROM amazon_fee_comparisons WHERE tenant_id=? AND marketplace_connection_id=? AND marketplace_id=? AND order_id=? AND revision_key='BASIS' AND expected_amount IS NOT NULL",tenant,connection,market,order)) {
            var charges=jdbc.queryForList("""
                SELECT c.* FROM amazon_order_fee_charge_observations c WHERE c.tenant_id=? AND c.marketplace_connection_id=?
                  AND c.marketplace_id=? AND c.order_id=? AND c.seller_sku=? AND c.fee_type=?
                  AND NOT EXISTS(SELECT 1 FROM amazon_order_fee_charge_observations newer
                    WHERE newer.tenant_id=c.tenant_id AND newer.marketplace_connection_id=c.marketplace_connection_id
                     AND newer.marketplace_id=c.marketplace_id AND newer.transaction_key=c.transaction_key
                     AND newer.observation_seq>c.observation_seq AND newer.revision<>c.revision)
                ORDER BY c.observation_seq
                """,tenant,connection,market,order,b.get("seller_sku"),b.get("fee_type"));
            boolean unresolved=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM amazon_order_fee_charge_observations c WHERE c.tenant_id=? AND c.marketplace_connection_id=?
                 AND c.marketplace_id=? AND c.order_id=? AND (c.seller_sku IS NULL OR c.seller_sku=?) AND c.state<>'SUPPORTED'
                 AND NOT EXISTS(SELECT 1 FROM amazon_order_fee_charge_observations n WHERE n.tenant_id=c.tenant_id
                   AND n.marketplace_connection_id=c.marketplace_connection_id AND n.marketplace_id=c.marketplace_id
                   AND n.transaction_key=c.transaction_key AND n.observation_seq>c.observation_seq AND n.revision<>c.revision))
                """,Boolean.class,tenant,connection,market,order,b.get("seller_sku"));
            var scopeIds=jdbc.queryForList("""
                SELECT c.id FROM amazon_order_fee_charge_observations c WHERE c.tenant_id=? AND c.marketplace_connection_id=?
                 AND c.marketplace_id=? AND c.order_id=? AND (c.seller_sku IS NULL OR c.seller_sku=?)
                 AND NOT EXISTS(SELECT 1 FROM amazon_order_fee_charge_observations n WHERE n.tenant_id=c.tenant_id
                  AND n.marketplace_connection_id=c.marketplace_connection_id AND n.marketplace_id=c.marketplace_id
                  AND n.transaction_key=c.transaction_key AND n.observation_seq>c.observation_seq AND n.revision<>c.revision)
                 ORDER BY c.observation_seq
                """,UUID.class,tenant,connection,market,order,b.get("seller_sku"));
            boolean identityChanged=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM amazon_order_fee_charge_observations c
                 JOIN LATERAL(SELECT n.revision FROM amazon_order_fee_charge_observations n
                   WHERE n.tenant_id=c.tenant_id AND n.marketplace_connection_id=c.marketplace_connection_id
                     AND n.marketplace_id=c.marketplace_id AND n.transaction_key=c.transaction_key
                   ORDER BY n.observation_seq DESC LIMIT 1) latest ON true
                 WHERE c.tenant_id=? AND c.marketplace_connection_id=? AND c.marketplace_id=? AND c.order_id=?
                  AND c.seller_sku=? AND NOT EXISTS(SELECT 1 FROM amazon_order_fee_charge_observations member
                    WHERE member.tenant_id=c.tenant_id AND member.marketplace_connection_id=c.marketplace_connection_id
                      AND member.marketplace_id=c.marketplace_id AND member.transaction_key=c.transaction_key AND member.revision=latest.revision
                      AND member.order_id=c.order_id AND member.seller_sku=c.seller_sku))
                """,Boolean.class,tenant,connection,market,order,b.get("seller_sku"));
            if(charges.isEmpty()&&scopeIds.isEmpty()&&!identityChanged)continue;
            boolean unique=jdbc.queryForObject("SELECT count(*) FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND seller_sku=?",Integer.class,tenant,connection,order,b.get("seller_sku"))==1;
            var c=charges.isEmpty()?Map.<String,Object>of():charges.getFirst();boolean matched=unique&&charges.size()==1&&"SUPPORTED".equals(c.get("state"))
                    &&Objects.equals(c.get("quantity"),b.get("quantity"))&&Objects.equals(c.get("currency"),b.get("currency"));
            // Snapshot corrections and partial shipments cannot silently reuse an old quantity/price basis.
            var current=jdbc.queryForMap("""
                SELECT i.*,o.marketplace_id current_market,o.purchase_date current_purchase,o.currency order_currency,o.fulfillment_channel
                FROM amazon_order_items i JOIN amazon_orders o ON o.tenant_id=i.tenant_id
                  AND o.marketplace_connection_id=i.marketplace_connection_id AND o.amazon_order_id=i.amazon_order_id
                WHERE i.tenant_id=? AND i.marketplace_connection_id=? AND i.id=?
                """,tenant,connection,b.get("order_item_id"));
            matched=matched&&!unresolved&&!identityChanged&&Objects.equals(current.get("seller_sku"),b.get("seller_sku"))
                &&Objects.equals(current.get("currency"),b.get("currency"))&&Objects.equals(current.get("order_currency"),b.get("currency"))
                &&Objects.equals(current.get("current_market"),market)&&Objects.equals(current.get("current_purchase"),b.get("purchase_at"))
                &&Set.of("AFN","FBA","AMAZON").contains(Objects.toString(current.get("fulfillment_channel"),""));
            matched=matched&&Objects.equals(current.get("quantity_ordered"),b.get("quantity"))
                    &&equal(perUnit((BigDecimal)current.get("item_price"),((Number)b.get("quantity")).intValue()),(BigDecimal)b.get("unit_price"))
                    &&equal(perUnit((BigDecimal)current.get("shipping_price"),((Number)b.get("quantity")).intValue()),(BigDecimal)b.get("shipping"));
            matched=matched&&c.get("posted_at")!=null&&((Timestamp)c.get("posted_at")).compareTo((Timestamp)b.get("purchase_at"))>=0
                &&((Timestamp)c.get("posted_at")).compareTo((Timestamp)c.get("observed_at"))<=0
                &&equal((BigDecimal)current.get("item_tax"),BigDecimal.ZERO)&&equal((BigDecimal)current.get("shipping_tax"),BigDecimal.ZERO)
                &&equal((BigDecimal)current.get("promotion_discount"),BigDecimal.ZERO)
                &&(equal((BigDecimal)current.get("shipping_price"),BigDecimal.ZERO)||equal((BigDecimal)current.get("shipping_discount"),BigDecimal.ZERO));
            var result=FeeComparison.compare((String)b.get("currency"),((Timestamp)b.get("purchase_at")).toInstant(),
                    ((Timestamp)b.get("boundary_at")).toInstant(),(BigDecimal)b.get("expected_amount"),matched?(BigDecimal)c.get("expense_amount"):null,matched);
            String state=matched?result.state():charges.isEmpty()&&!unresolved&&!identityChanged?"AWAITING":"NOT_COMPARABLE",reason=matched?result.reason():state.equals("AWAITING")?"Awaiting complete charges":"Charge identity, quantity or revision unresolved";
            Timestamp updated=(Timestamp)current.get("updated_at");
            String revision=digest(DERIVATION_REVISION+":"+scopeIds.stream().map(UUID::toString).sorted().toList()+":"
                    +charges.stream().map(x->x.get("id").toString()).sorted().toList()+":"+state+":"+(updated==null?"unknown":updated.toInstant()));
            jdbc.update("""
                INSERT INTO amazon_fee_comparisons(tenant_id,marketplace_connection_id,marketplace_id,order_item_id,order_id,
                  seller_sku,fee_type,currency,quantity,unit_price,shipping,purchase_at,boundary_at,observation_id,approval_id,
                  expected_amount,actual_amount,difference,state,reason,charge_id,previous_id,revision_key)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """,tenant,connection,market,b.get("order_item_id"),order,b.get("seller_sku"),b.get("fee_type"),b.get("currency"),b.get("quantity"),b.get("unit_price"),b.get("shipping"),
                    b.get("purchase_at"),b.get("boundary_at"),b.get("observation_id"),b.get("approval_id"),matched?result.expected():b.get("expected_amount"),
                    result.actual(),result.difference(),state,reason,matched?c.get("id"):null,b.get("id"),revision);
        }
    }
    static BigDecimal perUnit(BigDecimal total,int quantity){if(total==null||quantity<1)return null;try{return total.divide(BigDecimal.valueOf(quantity),4,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){return null;}}
    static boolean equal(BigDecimal a,BigDecimal b){return a!=null&&b!=null&&a.compareTo(b)==0;}
    public static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
