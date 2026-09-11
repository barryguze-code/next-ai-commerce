package com.nextaicommerce.platform.marketplace;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MarketplaceSkuAutoMapper {
    private static final Logger log=LoggerFactory.getLogger(MarketplaceSkuAutoMapper.class);
    private static final Pattern PACK_SUFFIX=Pattern.compile("(?i)(?:[-_\\s]?)(?:(\\d+)\\s*X\\s*EA|EA\\s*X\\s*(\\d+))$");
    private static final Pattern BUNDLE_COMPONENT=Pattern.compile("(?i)(?:^|[-_])(\\d+)\\s*X\\s*(\\d+)(?=$|[-_])");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public MarketplaceSkuAutoMapper(JdbcTemplate jdbc,TransactionTemplate transactions){
        this.jdbc=jdbc;this.transactions=transactions;
    }

    /** Reconciles listings that were already present before vendor catalogue codes were added. */
    @Scheduled(initialDelayString="${app.marketplace.sku-mapping-initial-delay-ms:15000}",
        fixedDelayString="${app.marketplace.sku-mapping-delay-ms:21600000}")
    public void reconcileExistingListings(){
        List<UUID> tenants=jdbc.query("SELECT id FROM tenants WHERE status='ACTIVE' ORDER BY id",
            (rs,row)->rs.getObject(1,UUID.class));
        int mapped=0;
        for(UUID tenantId:tenants){
            try{
                mapped+=mapTenant(tenantId).mapped();
            }catch(RuntimeException ex){
                log.warn("Marketplace SKU reconciliation delayed tenantId={}: {}",tenantId,ex.getMessage());
            }
        }
        if(mapped>0)log.info("Existing marketplace SKU reconciliation completed mapped={}",mapped);
    }

    public MappingResult mapTenant(UUID tenantId){
        MappingResult result=transactions.execute(status->{setTenant(tenantId);
            List<UUID> connections=jdbc.query("SELECT id FROM marketplace_connections WHERE tenant_id=? AND channel='AMAZON' AND status='ACTIVE'",
                (rs,row)->rs.getObject(1,UUID.class),tenantId);int mapped=0;
            for(UUID connectionId:connections)mapped+=mapConnection(tenantId,connectionId);
            Integer remaining=jdbc.queryForObject("""
                SELECT count(*) FROM amazon_listings listing WHERE listing.tenant_id=?
                  AND listing.platform_status<>'DELETED' AND NOT EXISTS(SELECT 1 FROM marketplace_sku_mappings mapping
                    WHERE mapping.tenant_id=listing.tenant_id AND mapping.marketplace_connection_id=listing.marketplace_connection_id
                      AND mapping.marketplace_sku=listing.seller_sku AND mapping.status='ACTIVE')
                """,Integer.class,tenantId);return new MappingResult(mapped,remaining==null?0:remaining);});
        return result==null?new MappingResult(0,0):result;
    }

    @Transactional
    public int mapConnection(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        int repaired=repairMissingComponents(tenantId,connectionId);
        List<IdentityCode> identityCodes=jdbc.query("""
            SELECT item.id,item.account_sku
            FROM account_catalog_items item
            WHERE item.tenant_id=? AND item.status='ACTIVE' AND item.account_sku IS NOT NULL AND trim(item.account_sku)<>''
            UNION ALL
            SELECT offer.account_catalog_item_id,offer.vendor_item_code
            FROM vendor_catalog_offers offer
            JOIN account_catalog_items item ON item.tenant_id=offer.tenant_id AND item.id=offer.account_catalog_item_id
            WHERE offer.tenant_id=? AND item.status='ACTIVE' AND offer.effective_to IS NULL
              AND offer.vendor_item_code IS NOT NULL AND trim(offer.vendor_item_code)<>''
            """,(rs,row)->new IdentityCode(rs.getObject(1,UUID.class),rs.getString(2)),tenantId,tenantId);
        Map<String,Set<UUID>> itemsByCode=new HashMap<>();
        for(IdentityCode identity:identityCodes){
            String normalized=normalizeCode(identity.code());
            if(!normalized.isBlank())itemsByCode.computeIfAbsent(normalized,ignored->new HashSet<>()).add(identity.itemId());
        }
        List<Listing> listings=jdbc.query("""
            WITH sku_source AS (
              SELECT listing.seller_sku,listing.asin
              FROM amazon_listings listing
              WHERE listing.tenant_id=? AND listing.marketplace_connection_id=?
                AND listing.platform_status<>'DELETED'
              UNION ALL
              SELECT item.seller_sku,item.asin
              FROM amazon_order_items item
              JOIN amazon_orders orders ON orders.tenant_id=item.tenant_id
                AND orders.marketplace_connection_id=item.marketplace_connection_id
                AND orders.amazon_order_id=item.amazon_order_id
              WHERE item.tenant_id=? AND item.marketplace_connection_id=?
                AND orders.operational_scope='LIVE'
                AND regexp_replace(upper(coalesce(orders.order_status,'')),'[^A-Z]','','g')
                    IN ('PENDING','UNSHIPPED')
                AND upper(coalesce(orders.fulfillment_channel,'')) NOT IN ('AFN','AMAZON')
            )
            SELECT source.seller_sku,max(source.asin)
            FROM sku_source source
            WHERE source.seller_sku IS NOT NULL AND trim(source.seller_sku)<>''
              AND NOT EXISTS (SELECT 1 FROM marketplace_sku_mappings mapping
                WHERE mapping.tenant_id=? AND mapping.marketplace_connection_id=?
                  AND mapping.marketplace_sku=source.seller_sku AND mapping.status='ACTIVE')
            GROUP BY source.seller_sku
            ORDER BY source.seller_sku
            """,(rs,row)->new Listing(rs.getString(1),rs.getString(2)),
            tenantId,connectionId,tenantId,connectionId,tenantId,connectionId);
        List<MappingCandidate> mappingCandidates=new ArrayList<>();int unmatched=0,ambiguous=0;
        for(Listing listing:listings){
            ParsedSku parsed=parse(listing.sku());
            if(!parsed.bundleComponents().isEmpty()){
                if(!parsed.validBundleTotal()){unmatched++;continue;}
                Map<UUID,BigDecimal> components=new LinkedHashMap<>();boolean valid=true;
                for(ParsedComponent component:parsed.bundleComponents()){
                    Set<UUID> candidates=itemsByCode.get(component.itemCode());
                    if(candidates==null||candidates.isEmpty()){unmatched++;valid=false;break;}
                    if(candidates.size()!=1){ambiguous++;valid=false;break;}
                    components.merge(candidates.iterator().next(),BigDecimal.valueOf(component.quantity()),BigDecimal::add);
                }
                if(valid&&components.size()>1){
                    List<MappingComponent> resolved=components.entrySet().stream()
                        .map(entry->new MappingComponent(entry.getKey(),entry.getValue())).toList();
                    mappingCandidates.add(new MappingCandidate(resolved.getFirst().itemId(),listing.sku(),listing.asin(),
                        BigDecimal.valueOf(parsed.quantityPerMarketplaceUnit()),resolved));
                    continue;
                }
                if(!valid||parsed.explicitBundle()){continue;}
                // An implicit candidate whose identifiers all resolve to one product is a normal pack,
                // so let the established single-product rule handle it below.
            }
            Set<UUID> matches=new HashSet<>();
            for(String token:parsed.itemCodeTokens()){
                Set<UUID> candidates=itemsByCode.get(token);
                if(candidates!=null)matches.addAll(candidates);
            }
            if(matches.isEmpty()){unmatched++;continue;}
            if(matches.size()!=1){ambiguous++;continue;}
            UUID itemId=matches.iterator().next();
            mappingCandidates.add(new MappingCandidate(itemId,listing.sku(),listing.asin(),
                BigDecimal.valueOf(parsed.quantityPerMarketplaceUnit()),
                List.of(new MappingComponent(itemId,BigDecimal.valueOf(parsed.quantityPerMarketplaceUnit())))));
        }
        int mapped=writeMappings(tenantId,connectionId,mappingCandidates);
        if(mapped>0)log.info("Marketplace SKU auto-mapping completed tenantId={} connectionId={} mapped={}",tenantId,connectionId,mapped);
        if(unmatched>0||ambiguous>0)log.info("Marketplace SKU auto-mapping review tenantId={} connectionId={} unmatched={} ambiguous={}",tenantId,connectionId,unmatched,ambiguous);
        return mapped+repaired;
    }

    /**
     * Older and interrupted writes can leave an active single-product mapping without its
     * component row. All catalogue and inventory consumers use components as the canonical
     * relationship, so heal those records before deciding that an active SKU is already done.
     */
    private int repairMissingComponents(UUID tenantId,UUID connectionId){
        int repaired=jdbc.update("""
            INSERT INTO marketplace_sku_mapping_components
                (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order)
            SELECT mapping.tenant_id,mapping.id,mapping.account_catalog_item_id,
                   greatest(mapping.quantity_per_marketplace_unit,1),0
            FROM marketplace_sku_mappings mapping
            WHERE mapping.tenant_id=? AND mapping.marketplace_connection_id=?
              AND mapping.status='ACTIVE' AND mapping.account_catalog_item_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM marketplace_sku_mapping_components component
                WHERE component.tenant_id=mapping.tenant_id
                  AND component.marketplace_sku_mapping_id=mapping.id)
            ON CONFLICT (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id) DO NOTHING
            """,tenantId,connectionId);
        if(repaired>0)log.info("Marketplace SKU component repair completed tenantId={} connectionId={} repaired={}",
            tenantId,connectionId,repaired);
        return repaired;
    }

    /** Writes hundreds of mappings in the same database round trips previously used for one mapping. */
    private int writeMappings(UUID tenantId,UUID connectionId,List<MappingCandidate> candidates){
        int mapped=0;
        for(int start=0;start<candidates.size();start+=500){
            List<MappingCandidate> chunk=candidates.subList(start,Math.min(start+500,candidates.size()));
            StringBuilder values=new StringBuilder();
            List<Object> arguments=new ArrayList<>(chunk.size()*4+2);
            arguments.add(tenantId);arguments.add(connectionId);
            for(int index=0;index<chunk.size();index++){
                if(index>0)values.append(',');
                values.append("(?::uuid,?,?,?::numeric)");
                MappingCandidate candidate=chunk.get(index);
                arguments.add(candidate.itemId());arguments.add(candidate.sku());
                arguments.add(candidate.asin());arguments.add(candidate.quantity());
            }
            List<WrittenMapping> written=jdbc.query("""
                INSERT INTO marketplace_sku_mappings
                    (tenant_id,marketplace_connection_id,account_catalog_item_id,marketplace_sku,asin,
                     quantity_per_marketplace_unit,status,mapping_source,auto_map_blocked)
                SELECT ?,?,candidate.item_id,candidate.sku,candidate.asin,candidate.quantity,'ACTIVE','AUTO',false
                FROM (VALUES %s) candidate(item_id,sku,asin,quantity)
                ON CONFLICT (tenant_id,marketplace_connection_id,marketplace_sku) DO UPDATE SET
                    account_catalog_item_id=EXCLUDED.account_catalog_item_id,asin=EXCLUDED.asin,
                    quantity_per_marketplace_unit=EXCLUDED.quantity_per_marketplace_unit,status='ACTIVE',
                    mapping_source='AUTO',updated_at=now()
                WHERE marketplace_sku_mappings.status='INACTIVE'
                  AND marketplace_sku_mappings.auto_map_blocked=false
                RETURNING id,marketplace_sku
                """.formatted(values),(rs,row)->new WrittenMapping(rs.getObject(1,UUID.class),rs.getString(2)),arguments.toArray());
            if(written.isEmpty())continue;
            String placeholders=String.join(",",java.util.Collections.nCopies(written.size(),"?"));
            List<Object> deleteArguments=new ArrayList<>(written.size()+1);
            deleteArguments.add(tenantId);
            written.forEach(mapping->deleteArguments.add(mapping.mappingId()));
            jdbc.update("DELETE FROM marketplace_sku_mapping_components WHERE tenant_id=? AND marketplace_sku_mapping_id IN ("+
                placeholders+")",deleteArguments.toArray());
            Map<String,MappingCandidate> candidatesBySku=new HashMap<>();chunk.forEach(candidate->candidatesBySku.put(candidate.sku(),candidate));
            List<MappingComponentWrite> componentWrites=new ArrayList<>();
            written.forEach(mapping->{MappingCandidate candidate=candidatesBySku.get(mapping.sku());
                for(int index=0;index<candidate.components().size();index++){
                    MappingComponent component=candidate.components().get(index);
                    componentWrites.add(new MappingComponentWrite(mapping.mappingId(),component.itemId(),component.quantity(),index));
                }});
            jdbc.batchUpdate("""
                INSERT INTO marketplace_sku_mapping_components
                    (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id,quantity,sort_order)
                VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id,marketplace_sku_mapping_id,account_catalog_item_id) DO UPDATE SET
                    quantity=EXCLUDED.quantity,sort_order=EXCLUDED.sort_order,updated_at=now()
                """,componentWrites,1000,(statement,component)->{
                    statement.setObject(1,tenantId);statement.setObject(2,component.mappingId());
                    statement.setObject(3,component.itemId());statement.setBigDecimal(4,component.quantity());statement.setInt(5,component.sortOrder());
                });
            mapped+=written.size();
        }
        return mapped;
    }

    static ParsedSku parse(String sellerSku){
        String sku=sellerSku==null?"":sellerSku.trim();
        var matcher=PACK_SUFFIX.matcher(sku);int quantity=1;String base=sku;
        if(matcher.find()){
            quantity=Integer.parseInt(matcher.group(1)!=null?matcher.group(1):matcher.group(2));
            base=sku.substring(0,matcher.start()).replaceFirst("[-_\\s]+$","");
        }
        List<String> tokens=new ArrayList<>();List<ParsedComponent> components=new ArrayList<>();
        var componentMatcher=BUNDLE_COMPONENT.matcher(base);
        while(componentMatcher.find()){
            int componentQuantity=Integer.parseInt(componentMatcher.group(1));String componentCode=normalizeCode(componentMatcher.group(2));
            if(componentQuantity>0&&!componentCode.isBlank()){components.add(new ParsedComponent(componentCode,componentQuantity));tokens.add(componentCode);}
        }
        for(String token:base.split("[^A-Za-z0-9]+")){
            String normalized=normalizeCode(token);
            if(!normalized.isBlank())tokens.add(normalized);
        }
        boolean explicitBundle=!components.isEmpty();
        if(!explicitBundle&&quantity>1){
            var implicitCodes=new java.util.LinkedHashSet<String>();
            for(String token:tokens)if(token.matches("\\d+"))implicitCodes.add(token);
            if(implicitCodes.size()>1)
                implicitCodes.forEach(code->components.add(new ParsedComponent(code,1)));
        }
        int componentTotal=components.stream().mapToInt(ParsedComponent::quantity).sum();
        return new ParsedSku(Math.max(1,quantity),List.copyOf(tokens),List.copyOf(components),
            components.isEmpty()||componentTotal==Math.max(1,quantity),explicitBundle);
    }

    private static String normalizeCode(String value){
        String normalized=value==null?"":value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");
        return normalized.matches("\\d+")?normalized.replaceFirst("^0+(?!$)",""):normalized;
    }
    private void setTenant(UUID tenantId){
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());
    }
    record ParsedSku(int quantityPerMarketplaceUnit,List<String> itemCodeTokens,List<ParsedComponent> bundleComponents,
            boolean validBundleTotal,boolean explicitBundle){}
    record ParsedComponent(String itemCode,int quantity){}
    public record MappingResult(int mapped,int remaining){}
    private record IdentityCode(UUID itemId,String code){}
    private record Listing(String sku,String asin){}
    private record MappingComponent(UUID itemId,BigDecimal quantity){}
    private record MappingCandidate(UUID itemId,String sku,String asin,BigDecimal quantity,List<MappingComponent> components){}
    private record WrittenMapping(UUID mappingId,String sku){}
    private record MappingComponentWrite(UUID mappingId,UUID itemId,BigDecimal quantity,int sortOrder){}
}
