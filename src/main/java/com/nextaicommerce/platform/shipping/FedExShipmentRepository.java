package com.nextaicommerce.platform.shipping;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class FedExShipmentRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public FedExShipmentRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public record Shipment(UUID id,UUID carrierId,long revision,String orderId,String environment,String state,
        JsonNode rates,JsonNode packing,String tracking,String error,boolean simulated) {
        public Shipment(UUID id,UUID carrierId,long revision,String orderId,String environment,String state,JsonNode rates,JsonNode packing,String tracking,String error){this(id,carrierId,revision,orderId,environment,state,rates,packing,tracking,error,false);}
    }
    public FedExPayloads.Parcel dimensions(UUID tenant,UUID store,String key){tenant(tenant);return jdbc.query("SELECT dimensions::text FROM carrier_package_defaults WHERE tenant_id=? AND marketplace_connection_id=? AND composition_key=?",rs->rs.next()?json.readValue(rs.getString(1),FedExPayloads.Parcel.class):null,tenant,store,key);}
    public void dimensions(UUID tenant,UUID store,String key,FedExPayloads.Parcel parcel){tenant(tenant);jdbc.update("INSERT INTO carrier_package_defaults VALUES (?,?,?,?::jsonb) ON CONFLICT (tenant_id,marketplace_connection_id,composition_key) DO UPDATE SET dimensions=excluded.dimensions",tenant,store,key,json.writeValueAsString(parcel));}
    public FedExPayloads.Parcel orderPackage(UUID tenant,UUID store,String order,String key){tenant(tenant);
        if(LocalShippingFixtures.identity(order))return jdbc.query("SELECT saved_parcel::text FROM local_shipping_fixtures WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",rs->rs.next()&&rs.getString(1)!=null?json.readValue(rs.getString(1),FedExPayloads.Parcel.class):null,tenant,store,UUID.fromString(order.substring(LocalShippingFixtures.PREFIX.length())));
        if(shopify(tenant,store))return jdbc.query("SELECT parcel::text FROM shopify_order_packaging WHERE tenant_id=? AND marketplace_connection_id=? AND order_id=? AND composition_key=?",rs->rs.next()?json.readValue(rs.getString(1),FedExPayloads.Parcel.class):null,tenant,store,UUID.fromString(order),key);
        return jdbc.query("SELECT parcel::text FROM fedex_order_packaging WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND composition_key=?",rs->rs.next()?json.readValue(rs.getString(1),FedExPayloads.Parcel.class):null,tenant,store,order,key);}
    public void orderPackage(UUID tenant,UUID store,String order,String key,FedExPayloads.Parcel parcel){tenant(tenant);
        if(LocalShippingFixtures.identity(order)){int changed=jdbc.update("UPDATE local_shipping_fixtures SET saved_parcel=?::jsonb WHERE tenant_id=? AND marketplace_connection_id=? AND id=?",json.writeValueAsString(parcel),tenant,store,UUID.fromString(order.substring(LocalShippingFixtures.PREFIX.length())));if(changed!=1)throw new IllegalArgumentException("Local test order not found.");return;}
        if(shopify(tenant,store)){jdbc.update("INSERT INTO shopify_order_packaging(tenant_id,marketplace_connection_id,order_id,composition_key,parcel) VALUES (?,?,?,?,?::jsonb) ON CONFLICT(tenant_id,marketplace_connection_id,order_id) DO UPDATE SET composition_key=excluded.composition_key,parcel=excluded.parcel,updated_at=now()",tenant,store,UUID.fromString(order),key,json.writeValueAsString(parcel));return;}
        jdbc.update("INSERT INTO fedex_order_packaging(tenant_id,marketplace_connection_id,amazon_order_id,composition_key,parcel) VALUES (?,?,?,?,?::jsonb) ON CONFLICT(tenant_id,marketplace_connection_id,amazon_order_id) DO UPDATE SET composition_key=excluded.composition_key,parcel=excluded.parcel,updated_at=now()",tenant,store,order,key,json.writeValueAsString(parcel));}
    public String legacyPackaging(UUID tenant,UUID store,String signature){
        tenant(tenant);
        // Match the entire SKU/quantity composition, never the first SKU of a bundle.
        return jdbc.query("SELECT packaging FROM temporary_order_packaging_lookup WHERE tenant_id=? AND marketplace_connection_id=? AND order_sku_qty_list=? ORDER BY imported_at DESC,amazon_order_id LIMIT 1",rs->rs.next()?rs.getString(1):null,tenant,store,signature);
    }
    public UUID quote(UUID tenant,UUID store,String order,CarrierConnectionRepository.Connection c,ShippingLabelCrypto.Encrypted request,List<FedExPayloads.Rate> rates,List<CarrierLabelComposer.Line> packing,String actor) {
        tenant(tenant);UUID id=UUID.randomUUID();jdbc.update("""
          INSERT INTO carrier_shipments(id,tenant_id,marketplace_connection_id,carrier_connection_id,carrier_revision,amazon_order_id,environment,state,request_payload,request_nonce,rates,packing_lines,actor,is_simulated)
          VALUES (?,?,?,?,?,?,?,'RATED',?,?,?::jsonb,?::jsonb,?,?)
          """,id,tenant,store,c.id(),c.revision(),order,c.environment(),request.payload(),request.nonce(),json.writeValueAsString(rates),json.writeValueAsString(packing),actor,c.simulated());return id;
    }
    // Keep exact decimal quantities in the tree projection; a generic floating-point
    // tree parse can change both scale and precision before the packing safety check.
    private JsonNode readPacking(String value){return json.valueToTree(json.readValue(value,new tools.jackson.core.type.TypeReference<List<CarrierLabelComposer.Line>>(){}));}
    public List<Shipment> list(UUID tenant,UUID store,String order){tenant(tenant);return jdbc.query("SELECT * FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? ORDER BY created_at DESC LIMIT 50",(rs,n)->new Shipment(rs.getObject("id",UUID.class),rs.getObject("carrier_connection_id",UUID.class),rs.getLong("carrier_revision"),rs.getString("amazon_order_id"),rs.getString("environment"),rs.getString("state"),json.readTree(rs.getString("rates")),readPacking(rs.getString("packing_lines")),rs.getString("tracking_number"),rs.getString("error_message"),rs.getBoolean("is_simulated")),tenant,store,order);}
    public ShippingLabelCrypto.Encrypted claim(UUID tenant,UUID store,String order,UUID id,String service,long revision) {
        tenant(tenant);
        // Serialize quotes for this exact account/store/order, including sandbox test reprints.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->{},"fedex-label:"+tenant+":"+store+":"+order);
        return jdbc.query("""
          UPDATE carrier_shipments s SET state='PURCHASING',selected_service=?,updated_at=now()
          WHERE s.tenant_id=? AND s.marketplace_connection_id=? AND s.amazon_order_id=? AND s.id=? AND s.state='RATED' AND s.expires_at>now()
            AND s.carrier_revision=? AND EXISTS(SELECT 1 FROM carrier_connections c WHERE c.tenant_id=s.tenant_id AND c.id=s.carrier_connection_id AND c.revision=s.carrier_revision AND c.is_simulated=s.is_simulated AND c.deleted_at IS NULL)
            AND EXISTS(SELECT 1 FROM jsonb_array_elements(s.rates) r WHERE r->>'serviceType'=?)
            AND NOT EXISTS(SELECT 1 FROM carrier_shipments other WHERE other.tenant_id=s.tenant_id AND other.marketplace_connection_id=s.marketplace_connection_id
              AND other.amazon_order_id=s.amazon_order_id AND other.id<>s.id AND other.environment=s.environment AND other.state IN ('PURCHASING','PURCHASED','UNKNOWN'))
          RETURNING request_payload,request_nonce
          """,rs->rs.next()?new ShippingLabelCrypto.Encrypted(rs.getBytes(1),rs.getBytes(2)):null,service,tenant,store,order,id,revision,service);
    }
    public Shipment protectedShipment(UUID tenant,UUID store,String order){tenant(tenant);return jdbc.query("SELECT * FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? AND environment='SANDBOX' AND state IN ('PURCHASING','PURCHASED','UNKNOWN') ORDER BY (state<>'PURCHASED') DESC,created_at DESC LIMIT 1",(r,n)->new Shipment(r.getObject("id",UUID.class),r.getObject("carrier_connection_id",UUID.class),r.getLong("carrier_revision"),r.getString("amazon_order_id"),r.getString("environment"),r.getString("state"),json.readTree(r.getString("rates")),readPacking(r.getString("packing_lines")),r.getString("tracking_number"),r.getString("error_message"),r.getBoolean("is_simulated")),tenant,store,order).stream().findFirst().orElse(null);}
    public void purchased(UUID tenant,UUID id,String tracking,ShippingLabelCrypto.Encrypted label,JsonNode confirmation){tenant(tenant);if(jdbc.update("UPDATE carrier_shipments SET state='PURCHASED',tracking_number=?,label_payload=?,label_nonce=?,confirmation_payload=?::jsonb,confirmation_state=CASE WHEN environment='PRODUCTION' THEN 'PENDING' ELSE 'NOT_REQUIRED' END,updated_at=now() WHERE tenant_id=? AND id=? AND state='PURCHASING'",tracking,label.payload(),label.nonce(),confirmation==null?null:confirmation.toString(),tenant,id)!=1)throw new IllegalStateException("Shipment state changed; review before retrying.");}
    public void purchasedUat(UUID tenant,UUID id,String tracking,ShippingLabelCrypto.Encrypted label){purchased(tenant,id,tracking,label,null);localHandoffPending(tenant,id,true);}
    public void localHandoffPending(UUID tenant,UUID id,boolean pending){tenant(tenant);jdbc.update("UPDATE carrier_shipments SET error_message=? WHERE tenant_id=? AND id=? AND state='PURCHASED'",pending?"LOCAL_HANDOFF_PENDING":null,tenant,id);}
    public void failure(UUID tenant,UUID id,boolean uncertain){tenant(tenant);jdbc.update("UPDATE carrier_shipments SET state=?,error_message=?,updated_at=now() WHERE tenant_id=? AND id=? AND state='PURCHASING'",uncertain?"UNKNOWN":"FAILED",uncertain?"Outcome uncertain. Do not purchase again; review this shipment with FedEx.":"FedEx rejected this request. Review details before getting new rates.",tenant,id);}
    public ShippingLabelCrypto.Encrypted uatLabel(UUID tenant,UUID store,String order,UUID id){tenant(tenant);return jdbc.query("SELECT label_payload,label_nonce FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state='PURCHASED' AND environment='SANDBOX' AND NOT is_simulated AND amazon_order_id LIKE ?",rs->rs.next()?new ShippingLabelCrypto.Encrypted(rs.getBytes(1),rs.getBytes(2)):null,tenant,store,id,"shopify-uat:"+UUID.fromString(order)+":%");}
    public List<CarrierLabelComposer.Line> uatPacking(UUID tenant,UUID store,String order,UUID id){tenant(tenant);return jdbc.query("SELECT packing_lines::text FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state='PURCHASED' AND environment='SANDBOX' AND NOT is_simulated AND amazon_order_id LIKE ?",rs->rs.next()?json.readValue(rs.getString(1),new tools.jackson.core.type.TypeReference<List<CarrierLabelComposer.Line>>(){}):List.of(),tenant,store,id,"shopify-uat:"+UUID.fromString(order)+":%");}
    public ShippingLabelCrypto.Encrypted label(UUID tenant,UUID store,UUID id){tenant(tenant);return jdbc.query("SELECT label_payload,label_nonce FROM carrier_shipments WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state='PURCHASED'",rs->rs.next()?new ShippingLabelCrypto.Encrypted(rs.getBytes(1),rs.getBytes(2)):null,tenant,store,id);}
    public List<CarrierLabelComposer.Line> packing(UUID tenant,UUID store,String order){return packing(tenant,store,order,false);}
    public List<CarrierLabelComposer.Line> packing(UUID tenant,UUID store,String order,boolean labelTest){tenant(tenant);return jdbc.query("""
        SELECT coalesce(string_agg(DISTINCT product.brand,' / '),''),item.seller_sku,
          coalesce(string_agg(DISTINCT coalesce(nullif(product.description,''),catalog.display_name,product.canonical_name),' / '),item.title),CASE WHEN ? THEN item.quantity_ordered ELSE greatest(item.quantity_ordered-item.quantity_shipped,0) END,item.id
        FROM amazon_order_items item
        LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id AND mapping.marketplace_connection_id=item.marketplace_connection_id AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
        LEFT JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id
        LEFT JOIN account_catalog_items catalog ON catalog.tenant_id=component.tenant_id AND catalog.id=component.account_catalog_item_id
        LEFT JOIN global_catalog_products product ON product.id=catalog.global_product_id
        WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.amazon_order_id=? AND (CASE WHEN ? THEN item.quantity_ordered>0 ELSE item.quantity_ordered>item.quantity_shipped END)
        GROUP BY item.id,item.amazon_order_item_id,item.seller_sku,item.title,item.quantity_ordered,item.quantity_shipped ORDER BY item.amazon_order_item_id
        """,(rs,n)->{
            var allocations=jdbc.query("SELECT coalesce(item.account_sku,'Item code unavailable'),r.quantity,r.expiration_date,loc.code FROM order_inventory_reservations r JOIN account_catalog_items item ON item.tenant_id=r.tenant_id AND item.id=r.account_catalog_item_id JOIN warehouse_locations loc ON loc.tenant_id=r.tenant_id AND loc.id=r.location_id WHERE r.tenant_id=? AND r.marketplace_connection_id=? AND r.amazon_order_item_id=? AND r.status IN ('ACTIVE','SHIPPED') ORDER BY r.expiration_date NULLS LAST,loc.code,r.id",(a,k)->new CarrierLabelComposer.Allocation(a.getString(1),a.getBigDecimal(2),a.getObject(3,java.time.LocalDate.class),a.getString(4)),tenant,store,rs.getObject(5,UUID.class));
            return new CarrierLabelComposer.Line(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),allocations);
        },labelTest,tenant,store,order,labelTest);}
    private boolean shopify(UUID tenant,UUID store){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='SHOPIFY' AND status='ACTIVE')",Boolean.class,tenant,store));}
    private void tenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
}
