package com.nextaicommerce.platform.shipping;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class BuyShippingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public BuyShippingRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    public record StoreSettings(String marketplaceId,String mode,boolean printPackingSlip){}
    public record OrderInfo(String amazonOrderId,String status,String fulfillmentChannel,String marketplaceId,
            Instant earliestShip,Instant latestShip,Instant earliestDelivery,Instant latestDelivery){}
    public record OrderItem(String orderItemId,String sellerSku,String asin,String title,int quantityOrdered,int quantityShipped){
        public int remaining(){return Math.max(0,quantityOrdered-quantityShipped);}
    }
    public record Address(UUID id,String label,String contactName,String companyName,String line1,String line2,
            String line3,String city,String state,String postalCode,String countryCode,String phone,String email,boolean isDefault){}
    public record PackageProfile(UUID id,String name,String containerCode,BigDecimal length,BigDecimal width,
            BigDecimal height,String dimensionUnit,BigDecimal weight,String weightUnit,String preferredCarrier,String temperatureClass){}
    public record ShipmentView(UUID id,int packageSequence,String state,String carrierName,String serviceName,
            BigDecimal price,String currency,String trackingId,Instant purchasedAt,String lastError,boolean hasLabel){}
    public record ShipmentCommand(UUID id,UUID tenantId,UUID connectionId,String marketplaceId,String amazonOrderId,
            String state,JsonNode requestDetails,boolean packingSlip,String selectedServiceId,String selectedOfferId,
            String carrierName,String serviceName,BigDecimal rate,String currency,String amazonShipmentId){}
    public record PackingLine(String brand,String sellerSku,String title,int quantity,String location,java.time.LocalDate expiration){}
    public record PackingContext(String packageName,String containerCode,String temperatureClass,BigDecimal customerShipping,
            String currency,boolean extraIce,java.time.LocalDate handoffDate,String operationNote){}
    public record Offer(UUID id,String serviceId,String offerId,String carrierName,String serviceName,
            BigDecimal amount,String currency,Instant shipDate,Instant earliestDelivery,Instant latestDelivery,
            boolean cheapest,boolean fastest,boolean requiresSellerInput,List<String> labelFormats,JsonNode payload){}
    public record Artifact(byte[] encryptedPayload,byte[] nonce,String mimeType){}

    @Transactional(readOnly=true)
    public StoreSettings settings(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        return jdbc.query("SELECT marketplace_identifier,buy_shipping_mode,print_packing_slip FROM marketplace_connections WHERE tenant_id=? AND id=? AND channel='AMAZON'",
            rs->rs.next()?new StoreSettings(rs.getString(1),rs.getString(2),rs.getBoolean(3)):null,tenantId,connectionId);
    }

    @Transactional
    public void setMode(UUID tenantId,UUID connectionId,String mode,boolean printPackingSlip){
        setTenant(tenantId);
        if(!List.of("DISABLED","RATES_ONLY","PURCHASE_ENABLED").contains(mode))throw new IllegalArgumentException("Choose a valid Buy Shipping mode.");
        int changed=jdbc.update("UPDATE marketplace_connections SET buy_shipping_mode=?,print_packing_slip=?,updated_at=now() WHERE tenant_id=? AND id=? AND channel='AMAZON'",
            mode,printPackingSlip,tenantId,connectionId);
        if(changed!=1)throw new IllegalArgumentException("Amazon store was not found.");
    }

    @Transactional(readOnly=true)
    public OrderInfo order(UUID tenantId,UUID connectionId,String orderId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT amazon_order_id,order_status,fulfillment_channel,marketplace_id,earliest_ship_date,latest_ship_date,
                   earliest_delivery_date,latest_delivery_date
            FROM amazon_orders WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
            """,rs->rs.next()?new OrderInfo(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                instant(rs.getTimestamp(5)),instant(rs.getTimestamp(6)),instant(rs.getTimestamp(7)),instant(rs.getTimestamp(8))):null,
            tenantId,connectionId,orderId);
    }

    @Transactional(readOnly=true)
    public List<OrderItem> items(UUID tenantId,UUID connectionId,String orderId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT amazon_order_item_id,seller_sku,asin,title,quantity_ordered,quantity_shipped
            FROM amazon_order_items WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
            ORDER BY created_at,amazon_order_item_id
            """,(rs,row)->new OrderItem(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getInt(6)),
            tenantId,connectionId,orderId);
    }

    @Transactional(readOnly=true)
    public java.util.Map<String,Integer> committedQuantities(UUID tenantId,UUID connectionId,String orderId){
        setTenant(tenantId);java.util.Map<String,Integer> result=new java.util.HashMap<>();
        jdbc.query("""
            SELECT shipped.amazon_order_item_id,
                   greatest(sum(shipped.quantity)-max(order_item.quantity_shipped),0)::integer
            FROM buy_shipping_shipment_items shipped
            JOIN buy_shipping_shipments shipment ON shipment.tenant_id=shipped.tenant_id AND shipment.id=shipped.shipment_id
            JOIN amazon_order_items order_item ON order_item.tenant_id=shipped.tenant_id
              AND order_item.marketplace_connection_id=shipped.marketplace_connection_id
              AND order_item.amazon_order_item_id=shipped.amazon_order_item_id
            WHERE shipped.tenant_id=? AND shipped.marketplace_connection_id=? AND order_item.amazon_order_id=?
              AND shipment.state IN ('PURCHASE_QUEUED','PURCHASE_IN_PROGRESS','PURCHASED','PURCHASE_UNKNOWN')
            GROUP BY shipped.amazon_order_item_id
            """,rs->{result.put(rs.getString(1),rs.getInt(2));},tenantId,connectionId,orderId);
        return java.util.Map.copyOf(result);
    }

    @Transactional(readOnly=true)
    public List<Address> addresses(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT id,label,contact_name,company_name,address_line_1,address_line_2,address_line_3,city,
                   state_or_province_code,postal_code,country_code,phone,email,is_default
            FROM merchant_ship_from_addresses
            WHERE tenant_id=? AND marketplace_connection_id=? AND status='ACTIVE'
            ORDER BY is_default DESC,label
            """,(rs,row)->new Address(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getString(11),rs.getString(12),rs.getString(13),rs.getBoolean(14)),tenantId,connectionId);
    }

    @Transactional
    public Address saveAddress(UUID tenantId,UUID connectionId,Address input){
        setTenant(tenantId);
        if(input.isDefault())jdbc.update("UPDATE merchant_ship_from_addresses SET is_default=false WHERE tenant_id=? AND marketplace_connection_id=?",tenantId,connectionId);
        UUID id=input.id()==null?UUID.randomUUID():input.id();
        Object[] values={required(input.label(),"Address name"),required(input.contactName(),"Contact name"),blank(input.companyName()),
            required(input.line1(),"Address"),blank(input.line2()),blank(input.line3()),required(input.city(),"City"),blank(input.state()),
            required(input.postalCode(),"Postal code"),required(input.countryCode(),"Country").toUpperCase(),required(input.phone(),"Phone"),
            blank(input.email()),input.isDefault()};
        if(input.id()==null)jdbc.update("""
            INSERT INTO merchant_ship_from_addresses(id,tenant_id,marketplace_connection_id,label,contact_name,company_name,
                address_line_1,address_line_2,address_line_3,city,state_or_province_code,postal_code,country_code,phone,email,is_default)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,connectionId,values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9],values[10],values[11],values[12]);
        else{
            int changed=jdbc.update("""
                UPDATE merchant_ship_from_addresses SET label=?,contact_name=?,company_name=?,address_line_1=?,address_line_2=?,
                    address_line_3=?,city=?,state_or_province_code=?,postal_code=?,country_code=?,phone=?,email=?,is_default=?,status='ACTIVE'
                WHERE tenant_id=? AND marketplace_connection_id=? AND id=?
                """,values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9],values[10],values[11],values[12],tenantId,connectionId,id);
            if(changed!=1)throw new IllegalArgumentException("Ship-from address was not found in this Amazon store.");
        }
        return addresses(tenantId,connectionId).stream().filter(a->a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly=true)
    public List<PackageProfile> profiles(UUID tenantId,UUID connectionId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT id,name,container_code,length,width,height,dimension_unit,weight,weight_unit,preferred_carrier,temperature_class
            FROM shipping_package_profiles WHERE tenant_id=? AND marketplace_connection_id=? AND status='ACTIVE' ORDER BY name
            """,(rs,row)->new PackageProfile(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getString(7),rs.getBigDecimal(8),rs.getString(9),rs.getString(10),rs.getString(11)),tenantId,connectionId);
    }

    @Transactional
    public UUID saveProfile(UUID tenantId,UUID connectionId,PackageProfile profile,List<String> sellerSkus){
        setTenant(tenantId);UUID id=profile.id()==null?UUID.randomUUID():profile.id();
        Object[] values={required(profile.name(),"Package name"),blank(profile.containerCode()),profile.length(),profile.width(),profile.height(),
            profile.dimensionUnit(),profile.weight(),profile.weightUnit(),blank(profile.preferredCarrier()),temperature(profile.temperatureClass())};
        if(profile.id()==null)jdbc.update("""
            INSERT INTO shipping_package_profiles(id,tenant_id,marketplace_connection_id,name,container_code,length,width,height,
                dimension_unit,weight,weight_unit,preferred_carrier,temperature_class) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,tenantId,connectionId,values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9]);
        else{
            int changed=jdbc.update("""
                UPDATE shipping_package_profiles SET name=?,container_code=?,length=?,width=?,height=?,dimension_unit=?,weight=?,
                    weight_unit=?,preferred_carrier=?,temperature_class=?,status='ACTIVE' WHERE tenant_id=? AND marketplace_connection_id=? AND id=?
                """,values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7],values[8],values[9],tenantId,connectionId,id);
            if(changed!=1)throw new IllegalArgumentException("Saved package was not found in this Amazon store.");
        }
        for(String sku:sellerSkus)if(sku!=null&&!sku.isBlank())jdbc.update("""
            INSERT INTO marketplace_sku_package_defaults(tenant_id,marketplace_connection_id,seller_sku,package_profile_id)
            VALUES(?,?,?,?) ON CONFLICT(tenant_id,marketplace_connection_id,seller_sku)
            DO UPDATE SET package_profile_id=EXCLUDED.package_profile_id,updated_at=now()
            """,tenantId,connectionId,sku,id);
        return id;
    }

    @Transactional(readOnly=true)
    public PackageProfile defaultProfile(UUID tenantId,UUID connectionId,String orderId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT profile.id,profile.name,profile.container_code,profile.length,profile.width,profile.height,
                   profile.dimension_unit,profile.weight,profile.weight_unit,profile.preferred_carrier,profile.temperature_class
            FROM amazon_order_items item
            JOIN marketplace_sku_package_defaults defaults ON defaults.tenant_id=item.tenant_id
              AND defaults.marketplace_connection_id=item.marketplace_connection_id AND defaults.seller_sku=item.seller_sku
            JOIN shipping_package_profiles profile ON profile.tenant_id=defaults.tenant_id AND profile.id=defaults.package_profile_id
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.amazon_order_id=? AND profile.status='ACTIVE'
            ORDER BY item.created_at LIMIT 1
            """,rs->rs.next()?new PackageProfile(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getString(7),rs.getBigDecimal(8),rs.getString(9),rs.getString(10),rs.getString(11)):null,
            tenantId,connectionId,orderId);
    }

    @Transactional(readOnly=true)
    public List<ShipmentView> shipments(UUID tenantId,UUID connectionId,String orderId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT shipment.id,package_sequence,state,carrier_name,shipping_service_name,adjusted_rate_amount,currency,
                   tracking_id,purchased_at,last_error_message,
                   EXISTS(SELECT 1 FROM shipping_label_artifacts artifact WHERE artifact.tenant_id=shipment.tenant_id
                     AND artifact.shipment_id=shipment.id AND artifact.artifact_type='COMPOSED_PRINT_FILE')
            FROM buy_shipping_shipments shipment
            WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=? ORDER BY created_at DESC
            """,(rs,row)->new ShipmentView(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getBigDecimal(6),rs.getString(7),rs.getString(8),instant(rs.getTimestamp(9)),rs.getString(10),rs.getBoolean(11)),
            tenantId,connectionId,orderId);
    }

    @Transactional
    public UUID createRatedShipment(UUID tenantId,UUID connectionId,String orderId,UUID addressId,UUID profileId,
            int sequence,String fingerprint,JsonNode requestDetails,JsonNode packageSnapshot,boolean packingSlip,String actor){
        setTenant(tenantId);UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO buy_shipping_shipments(id,tenant_id,marketplace_connection_id,amazon_order_id,ship_from_address_id,
                package_profile_id,package_sequence,request_fingerprint,request_details,package_snapshot,packing_slip_enabled,created_by_email)
            VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)
            """,id,tenantId,connectionId,orderId,addressId,profileId,sequence,fingerprint,requestDetails.toString(),packageSnapshot.toString(),packingSlip,actor);
        audit(tenantId,id,"RATES_REQUESTED",actor,null,json.createObjectNode().put("packageSequence",sequence));
        return id;
    }

    @Transactional
    public void addShipmentItem(UUID tenantId,UUID shipmentId,UUID connectionId,String itemId,int quantity,JsonNode snapshot){
        setTenant(tenantId);
        jdbc.update("""
            INSERT INTO buy_shipping_shipment_items(tenant_id,shipment_id,marketplace_connection_id,amazon_order_item_id,
                quantity,inventory_location_id,inventory_expiration_date,display_snapshot)
            SELECT ?,?,?,?,?,reservation.location_id,reservation.expiration_date,CAST(? AS jsonb)
            FROM (SELECT 1) seed
            LEFT JOIN LATERAL(SELECT active.location_id,active.expiration_date
                FROM order_inventory_reservations active
                JOIN amazon_order_items order_item ON order_item.tenant_id=active.tenant_id AND order_item.id=active.amazon_order_item_id
                WHERE active.tenant_id=? AND active.marketplace_connection_id=? AND order_item.amazon_order_item_id=? AND active.status='ACTIVE'
                ORDER BY active.expiration_date NULLS LAST,active.created_at LIMIT 1) reservation ON true
            """,tenantId,shipmentId,connectionId,itemId,quantity,snapshot.toString(),tenantId,connectionId,itemId);
    }

    @Transactional(readOnly=true)
    public List<PackingLine> packingLines(UUID tenantId,UUID shipmentId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT coalesce(max(product.brand),''),item.seller_sku,item.title,shipped.quantity,
                   coalesce(location.code,'Unassigned'),shipped.inventory_expiration_date
            FROM buy_shipping_shipment_items shipped
            JOIN amazon_order_items item ON item.tenant_id=shipped.tenant_id
              AND item.marketplace_connection_id=shipped.marketplace_connection_id
              AND item.amazon_order_item_id=shipped.amazon_order_item_id
            LEFT JOIN warehouse_locations location ON location.tenant_id=shipped.tenant_id AND location.id=shipped.inventory_location_id
            LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
              AND mapping.marketplace_connection_id=item.marketplace_connection_id AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
            LEFT JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id AND component.marketplace_sku_mapping_id=mapping.id
            LEFT JOIN account_catalog_items catalog ON catalog.tenant_id=component.tenant_id AND catalog.id=component.account_catalog_item_id
            LEFT JOIN global_catalog_products product ON product.id=catalog.global_product_id
            WHERE shipped.tenant_id=? AND shipped.shipment_id=?
            GROUP BY item.seller_sku,item.title,shipped.quantity,location.code,shipped.inventory_expiration_date,item.created_at
            ORDER BY item.created_at,item.seller_sku
            """,(rs,row)->new PackingLine(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getObject(6,java.time.LocalDate.class)),
            tenantId,shipmentId);
    }

    @Transactional(readOnly=true)
    public PackingContext packingContext(UUID tenantId,UUID shipmentId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT coalesce(shipment.package_snapshot->>'name','Package'),coalesce(shipment.package_snapshot->>'containerCode',''),
                   coalesce(shipment.package_snapshot->>'temperatureClass','AMBIENT'),
                   coalesce((SELECT sum(greatest(coalesce(item.shipping_price,0)-coalesce(item.shipping_discount,0),0))
                     FROM buy_shipping_shipment_items packed JOIN amazon_order_items item ON item.tenant_id=packed.tenant_id
                       AND item.marketplace_connection_id=packed.marketplace_connection_id
                       AND item.amazon_order_item_id=packed.amazon_order_item_id
                     WHERE packed.tenant_id=shipment.tenant_id AND packed.shipment_id=shipment.id),0),
                   coalesce(shipment.currency,'USD'),shipment.extra_ice,shipment.planned_handoff_date,
                   coalesce(shipment.service_decision->>'operationNote','')
            FROM buy_shipping_shipments shipment WHERE shipment.tenant_id=? AND shipment.id=?
            """,rs->rs.next()?new PackingContext(rs.getString(1),rs.getString(2),rs.getString(3),rs.getBigDecimal(4),rs.getString(5),
                rs.getBoolean(6),rs.getObject(7,java.time.LocalDate.class),rs.getString(8)):
                new PackingContext("Package","","AMBIENT",BigDecimal.ZERO,"USD",false,null,""),tenantId,shipmentId);
    }

    @Transactional
    public void saveOffers(UUID tenantId,UUID shipmentId,List<Offer> offers,JsonNode raw,String requestId){
        setTenant(tenantId);Instant expires=Instant.now().plusSeconds(600);
        jdbc.update("DELETE FROM buy_shipping_rate_offers WHERE tenant_id=? AND shipment_id=?",tenantId,shipmentId);
        for(Offer offer:offers)jdbc.update("""
            INSERT INTO buy_shipping_rate_offers(id,tenant_id,shipment_id,service_id,offer_id,carrier_name,service_name,
                base_amount,adjusted_amount,currency,ship_date,earliest_delivery,latest_delivery,is_cheapest,is_fastest,
                requires_seller_input,available_label_formats,payload,expires_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
            """,offer.id(),tenantId,shipmentId,offer.serviceId(),blank(offer.offerId()),offer.carrierName(),offer.serviceName(),
            offer.amount(),offer.amount(),offer.currency(),timestamp(offer.shipDate()),timestamp(offer.earliestDelivery()),timestamp(offer.latestDelivery()),
            offer.cheapest(),offer.fastest(),offer.requiresSellerInput(),json.valueToTree(offer.labelFormats()).toString(),offer.payload().toString(),timestamp(expires));
        jdbc.update("UPDATE buy_shipping_shipments SET raw_rate_response=CAST(? AS jsonb),rating_expires_at=? WHERE tenant_id=? AND id=?",
            raw.toString(),timestamp(expires),tenantId,shipmentId);
        audit(tenantId,shipmentId,"RATES_RECEIVED",null,requestId,json.createObjectNode().put("offers",offers.size()));
    }

    @Transactional(readOnly=true)
    public List<Offer> offers(UUID tenantId,UUID shipmentId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT id,service_id,offer_id,carrier_name,service_name,adjusted_amount,currency,ship_date,
                   earliest_delivery,latest_delivery,is_cheapest,is_fastest,requires_seller_input,available_label_formats,payload
            FROM buy_shipping_rate_offers WHERE tenant_id=? AND shipment_id=? AND expires_at>now()
            ORDER BY is_cheapest DESC,is_fastest DESC,adjusted_amount,latest_delivery NULLS LAST
            """,(rs,row)->new Offer(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getBigDecimal(6),rs.getString(7),instant(rs.getTimestamp(8)),instant(rs.getTimestamp(9)),instant(rs.getTimestamp(10)),
                rs.getBoolean(11),rs.getBoolean(12),rs.getBoolean(13),stringList(rs.getString(14)),readJson(rs.getString(15))),tenantId,shipmentId);
    }

    @Transactional
    public void queuePurchase(UUID tenantId,UUID connectionId,String orderId,UUID shipmentId,UUID offerId,String actor){
        setTenant(tenantId);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",rs->{},connectionId+":"+orderId);
        Integer over=jdbc.queryForObject("""
            SELECT count(*) FROM buy_shipping_shipment_items target
            JOIN amazon_order_items order_item ON order_item.tenant_id=target.tenant_id
              AND order_item.marketplace_connection_id=target.marketplace_connection_id
              AND order_item.amazon_order_item_id=target.amazon_order_item_id
            WHERE target.tenant_id=? AND target.marketplace_connection_id=? AND target.shipment_id=?
              AND EXISTS(SELECT 1 FROM buy_shipping_shipments target_shipment WHERE target_shipment.tenant_id=target.tenant_id
                AND target_shipment.id=target.shipment_id AND target_shipment.amazon_order_id=?)
              AND target.quantity+coalesce((SELECT greatest(sum(other.quantity)-order_item.quantity_shipped,0) FROM buy_shipping_shipment_items other
                JOIN buy_shipping_shipments committed ON committed.tenant_id=other.tenant_id AND committed.id=other.shipment_id
                WHERE other.tenant_id=target.tenant_id AND other.marketplace_connection_id=target.marketplace_connection_id
                  AND other.amazon_order_item_id=target.amazon_order_item_id AND other.shipment_id<>target.shipment_id
                  AND committed.state IN ('PURCHASE_QUEUED','PURCHASE_IN_PROGRESS','PURCHASED','PURCHASE_UNKNOWN')),0)
                >greatest(order_item.quantity_ordered-order_item.quantity_shipped,0)
            """,Integer.class,tenantId,connectionId,shipmentId,orderId);
        if(over!=null&&over>0)throw new IllegalStateException("Another label already covers some of this package quantity. Refresh the order before purchasing.");
        Integer changed=jdbc.query("""
            UPDATE buy_shipping_shipments shipment SET state='PURCHASE_QUEUED',shipping_service_id=offer.service_id,
                shipping_service_offer_id=offer.offer_id,carrier_name=offer.carrier_name,shipping_service_name=offer.service_name,
                adjusted_rate_amount=offer.adjusted_amount,currency=offer.currency,last_error_code=NULL,last_error_message=NULL
            FROM buy_shipping_rate_offers offer,marketplace_connections connection
            WHERE shipment.tenant_id=? AND shipment.marketplace_connection_id=? AND shipment.amazon_order_id=? AND shipment.id=? AND shipment.state='RATED'
              AND offer.tenant_id=shipment.tenant_id AND offer.shipment_id=shipment.id AND offer.id=? AND offer.expires_at>now()
              AND offer.requires_seller_input=false
              AND jsonb_exists(offer.available_label_formats,'PDF')
              AND connection.tenant_id=shipment.tenant_id AND connection.id=shipment.marketplace_connection_id
              AND connection.buy_shipping_mode='PURCHASE_ENABLED'
            RETURNING 1
            """,rs->rs.next()?rs.getInt(1):0,tenantId,connectionId,orderId,shipmentId,offerId);
        if(changed==null||changed!=1)throw new IllegalStateException("This rate expired, was already used, or label purchasing is still in rates-only mode.");
        audit(tenantId,shipmentId,"PURCHASE_QUEUED",actor,null,json.createObjectNode().put("offerId",offerId.toString()));
    }

    @Transactional
    public ShipmentCommand claim(UUID tenantId,String queuedState,String claimedState){
        setTenant(tenantId);
        return jdbc.query("""
            UPDATE buy_shipping_shipments SET state=?,purchase_started_at=CASE WHEN ?='PURCHASE_IN_PROGRESS' THEN now() ELSE purchase_started_at END
            WHERE (tenant_id,id)=(SELECT tenant_id,id FROM buy_shipping_shipments WHERE tenant_id=? AND state=? ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id,tenant_id,marketplace_connection_id,(SELECT marketplace_identifier FROM marketplace_connections c
                WHERE c.tenant_id=buy_shipping_shipments.tenant_id AND c.id=marketplace_connection_id),amazon_order_id,state,
                request_details,packing_slip_enabled,shipping_service_id,shipping_service_offer_id,carrier_name,
                shipping_service_name,adjusted_rate_amount,currency,amazon_shipment_id
            """,rs->rs.next()?command(rs):null,claimedState,claimedState,tenantId,queuedState);
    }

    @Transactional
    public ShipmentCommand claimPendingRefund(UUID tenantId){
        setTenant(tenantId);
        return jdbc.query("""
            UPDATE buy_shipping_shipments SET updated_at=now()
            WHERE (tenant_id,id)=(SELECT tenant_id,id FROM buy_shipping_shipments
              WHERE tenant_id=? AND state='REFUND_PENDING' AND updated_at<now()-interval '15 seconds'
              ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id,tenant_id,marketplace_connection_id,(SELECT marketplace_identifier FROM marketplace_connections c
                WHERE c.tenant_id=buy_shipping_shipments.tenant_id AND c.id=marketplace_connection_id),amazon_order_id,state,
                request_details,packing_slip_enabled,shipping_service_id,shipping_service_offer_id,carrier_name,
                shipping_service_name,adjusted_rate_amount,currency,amazon_shipment_id
            """,rs->rs.next()?command(rs):null,tenantId);
    }

    @Transactional
    public ShipmentCommand claimRecoverablePurchase(UUID tenantId){
        setTenant(tenantId);
        return jdbc.query("""
            UPDATE buy_shipping_shipments SET updated_at=now(),recovery_attempts=recovery_attempts+1
            WHERE (tenant_id,id)=(SELECT tenant_id,id FROM buy_shipping_shipments
              WHERE tenant_id=? AND state='PURCHASE_UNKNOWN' AND amazon_shipment_id IS NOT NULL AND recovery_attempts<5
                AND updated_at<now()-interval '15 seconds'
              ORDER BY updated_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id,tenant_id,marketplace_connection_id,(SELECT marketplace_identifier FROM marketplace_connections c
                WHERE c.tenant_id=buy_shipping_shipments.tenant_id AND c.id=marketplace_connection_id),amazon_order_id,state,
                request_details,packing_slip_enabled,shipping_service_id,shipping_service_offer_id,carrier_name,
                shipping_service_name,adjusted_rate_amount,currency,amazon_shipment_id
            """,rs->rs.next()?command(rs):null,tenantId);
    }

    @Transactional
    public void purchaseIdentityUnknown(UUID tenantId,UUID shipmentId,AmazonMerchantFulfillmentClient.PurchaseMetadata metadata,String message){
        setTenant(tenantId);
        jdbc.update("""
            UPDATE buy_shipping_shipments SET state='PURCHASE_UNKNOWN',amazon_shipment_id=?,tracking_id=?,shipping_service_id=?,
                carrier_name=?,shipping_service_name=?,adjusted_rate_amount=?,currency=?,ship_date=?,earliest_delivery_date=?,
                latest_delivery_date=?,raw_purchase_response=CAST(? AS jsonb),last_error_code='LABEL_ARTIFACT',last_error_message=?
            WHERE tenant_id=? AND id=?
            """,metadata.shipmentId(),metadata.trackingId(),metadata.serviceId(),metadata.carrierName(),metadata.serviceName(),metadata.price(),
            metadata.currency(),timestamp(metadata.shipDate()),timestamp(metadata.earliestDelivery()),timestamp(metadata.latestDelivery()),
            metadata.response().toString(),limit(message,500),tenantId,shipmentId);
        audit(tenantId,shipmentId,"PURCHASE_UNKNOWN",null,metadata.requestId(),json.createObjectNode().put("amazonShipmentId",metadata.shipmentId()));
        updateBatchItem(tenantId,shipmentId,"PURCHASE_UNKNOWN",message);
    }

    @Transactional
    public void purchased(UUID tenantId,UUID shipmentId,String amazonShipmentId,String trackingId,BigDecimal price,String currency,
            String serviceId,String carrier,String service,Instant shipDate,Instant earliestDelivery,Instant latestDelivery,
            JsonNode raw,String requestId,ShippingLabelCrypto.Encrypted carrierLabel,ShippingLabelCrypto.Encrypted composedLabel,int pageCount){
        setTenant(tenantId);
        jdbc.update("""
            UPDATE buy_shipping_shipments SET state='PURCHASED',amazon_shipment_id=?,tracking_id=?,adjusted_rate_amount=?,currency=?,
                shipping_service_id=?,carrier_name=?,shipping_service_name=?,ship_date=?,earliest_delivery_date=?,latest_delivery_date=?,
                raw_purchase_response=CAST(? AS jsonb),purchased_at=now(),last_error_code=NULL,last_error_message=NULL
            WHERE tenant_id=? AND id=?
            """,amazonShipmentId,trackingId,price,currency,serviceId,carrier,service,timestamp(shipDate),timestamp(earliestDelivery),
            timestamp(latestDelivery),raw.toString(),tenantId,shipmentId);
        saveArtifact(tenantId,shipmentId,"CARRIER_LABEL",carrierLabel,"application/pdf",1);
        saveArtifact(tenantId,shipmentId,"COMPOSED_PRINT_FILE",composedLabel,"application/pdf",pageCount);
        allocateCost(tenantId,shipmentId,price,currency);
        audit(tenantId,shipmentId,"PURCHASED",null,requestId,json.createObjectNode().put("trackingId",trackingId==null?"":trackingId));
        updateBatchItem(tenantId,shipmentId,"PURCHASED",null);
    }

    @Transactional
    public void failure(UUID tenantId,UUID shipmentId,String state,String code,String message){
        setTenant(tenantId);
        jdbc.update("UPDATE buy_shipping_shipments SET state=?,last_error_code=?,last_error_message=? WHERE tenant_id=? AND id=?",
            state,code,limit(message,500),tenantId,shipmentId);
        audit(tenantId,shipmentId,state,null,null,json.createObjectNode().put("message",limit(message,500)));
        if(List.of("FAILED","PURCHASE_UNKNOWN").contains(state))updateBatchItem(tenantId,shipmentId,state,message);
    }

    @Transactional
    public void markAbandonedPurchasesUnknown(UUID tenantId){
        setTenant(tenantId);
        jdbc.update("""
            UPDATE buy_shipping_shipments SET state='PURCHASE_UNKNOWN',last_error_code='INTERRUPTED',
                last_error_message='The application stopped while Amazon was processing this purchase. Check Amazon before buying again.'
            WHERE tenant_id=? AND state='PURCHASE_IN_PROGRESS' AND purchase_started_at<now()-interval '5 minutes'
            """,tenantId);
        List<UUID> interrupted=jdbc.query("""
            SELECT item.shipment_id FROM buy_shipping_batch_orders item
            JOIN buy_shipping_shipments shipment ON shipment.tenant_id=item.tenant_id AND shipment.id=item.shipment_id
            WHERE item.tenant_id=? AND item.state='PURCHASE_QUEUED' AND shipment.state='PURCHASE_UNKNOWN'
            """,(rs,row)->rs.getObject(1,UUID.class),tenantId);
        for(UUID shipmentId:interrupted)updateBatchItem(tenantId,shipmentId,"PURCHASE_UNKNOWN",
            "The application stopped while Amazon was processing this purchase. Check Amazon before buying again.");
    }

    @Transactional
    public void queueRefund(UUID tenantId,UUID connectionId,UUID shipmentId,String actor){
        setTenant(tenantId);
        int changed=jdbc.update("""
            UPDATE buy_shipping_shipments SET state='REFUND_QUEUED',refund_requested_at=now()
            WHERE tenant_id=? AND marketplace_connection_id=? AND id=? AND state='PURCHASED'
            """,tenantId,connectionId,shipmentId);
        if(changed!=1)throw new IllegalStateException("Only a purchased, unrefunded label can be refunded.");
        audit(tenantId,shipmentId,"REFUND_QUEUED",actor,null,json.createObjectNode());
    }

    @Transactional
    public void refunded(UUID tenantId,UUID shipmentId,String state,JsonNode detail,String requestId){
        setTenant(tenantId);
        if(!List.of("REFUND_PENDING","REFUND_REJECTED","REFUND_APPLIED").contains(state))state="REFUND_PENDING";
        boolean applied="REFUND_APPLIED".equals(state);
        jdbc.update("UPDATE buy_shipping_shipments SET state=?,refunded_at=CASE WHEN ? THEN now() ELSE NULL END WHERE tenant_id=? AND id=?",
            state,applied,tenantId,shipmentId);
        audit(tenantId,shipmentId,state,null,requestId,detail);
    }

    @Transactional(readOnly=true)
    public Artifact artifact(UUID tenantId,UUID connectionId,UUID shipmentId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT artifact.encrypted_payload,artifact.encryption_nonce,artifact.mime_type
            FROM shipping_label_artifacts artifact JOIN buy_shipping_shipments shipment
              ON shipment.tenant_id=artifact.tenant_id AND shipment.id=artifact.shipment_id
            WHERE artifact.tenant_id=? AND shipment.marketplace_connection_id=? AND artifact.shipment_id=?
              AND artifact.artifact_type='COMPOSED_PRINT_FILE'
            """,rs->rs.next()?new Artifact(rs.getBytes(1),rs.getBytes(2),rs.getString(3)):null,tenantId,connectionId,shipmentId);
    }

    private void saveArtifact(UUID tenantId,UUID shipmentId,String type,ShippingLabelCrypto.Encrypted encrypted,String mime,int pages){
        jdbc.update("""
            INSERT INTO shipping_label_artifacts(tenant_id,shipment_id,artifact_type,encrypted_payload,encryption_nonce,mime_type,page_count)
            VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant_id,shipment_id,artifact_type) DO UPDATE
            SET encrypted_payload=EXCLUDED.encrypted_payload,encryption_nonce=EXCLUDED.encryption_nonce,mime_type=EXCLUDED.mime_type,page_count=EXCLUDED.page_count
            """,tenantId,shipmentId,type,encrypted.payload(),encrypted.nonce(),mime,pages);
    }

    private void allocateCost(UUID tenantId,UUID shipmentId,BigDecimal price,String currency){
        List<Object[]> items=jdbc.query("SELECT amazon_order_item_id,quantity FROM buy_shipping_shipment_items WHERE tenant_id=? AND shipment_id=? ORDER BY amazon_order_item_id",
            (rs,row)->new Object[]{rs.getString(1),rs.getInt(2)},tenantId,shipmentId);
        int total=items.stream().mapToInt(row->(int)row[1]).sum();if(total<=0)return;
        BigDecimal allocated=BigDecimal.ZERO;
        for(int i=0;i<items.size();i++){
            BigDecimal amount=i==items.size()-1?price.subtract(allocated):price.multiply(BigDecimal.valueOf((int)items.get(i)[1]))
                .divide(BigDecimal.valueOf(total),4,java.math.RoundingMode.HALF_UP);
            allocated=allocated.add(amount);
            jdbc.update("INSERT INTO buy_shipping_cost_allocations(tenant_id,shipment_id,amazon_order_item_id,allocated_postage,currency) VALUES(?,?,?,?,?)",
                tenantId,shipmentId,items.get(i)[0],amount,currency);
        }
    }

    private void audit(UUID tenantId,UUID shipmentId,String event,String actor,String requestId,JsonNode detail){
        jdbc.update("INSERT INTO buy_shipping_audit_events(tenant_id,shipment_id,event_type,actor_email,amazon_request_id,detail) VALUES(?,?,?,?,?,CAST(? AS jsonb))",
            tenantId,shipmentId,event,actor,requestId,detail.toString());
    }
    private void updateBatchItem(UUID tenantId,UUID shipmentId,String state,String error){
        int changed=jdbc.update("UPDATE buy_shipping_batch_orders SET state=?,last_error=? WHERE tenant_id=? AND shipment_id=?",
            state,blank(error),tenantId,shipmentId);if(changed==0)return;
        UUID batchId=jdbc.queryForObject("SELECT batch_id FROM buy_shipping_batch_orders WHERE tenant_id=? AND shipment_id=?",UUID.class,tenantId,shipmentId);
        jdbc.update("""
            UPDATE buy_shipping_batches batch SET state=CASE
              WHEN totals.purchased=totals.total AND totals.total>0 THEN 'READY'
              WHEN totals.purchased>0 AND totals.attention>0 AND totals.active=0 THEN 'PARTIAL'
              WHEN totals.attention=totals.total THEN 'FAILED'
              ELSE 'PROCESSING' END,
              completed_at=CASE WHEN totals.active=0 THEN now() ELSE NULL END
            FROM (SELECT count(*) total,count(*) FILTER (WHERE state='PURCHASED') purchased,
                count(*) FILTER (WHERE state IN ('NEEDS_ATTENTION','FAILED','PURCHASE_UNKNOWN')) attention,
                count(*) FILTER (WHERE state IN ('PENDING','RATING','RATED','PURCHASE_QUEUED')) active
                FROM buy_shipping_batch_orders WHERE tenant_id=? AND batch_id=?) totals
            WHERE batch.tenant_id=? AND batch.id=?
            """,tenantId,batchId,tenantId,batchId);
    }
    private ShipmentCommand command(java.sql.ResultSet rs)throws java.sql.SQLException{return new ShipmentCommand(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
        rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),readJson(rs.getString(7)),rs.getBoolean(8),
        rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getBigDecimal(13),rs.getString(14),rs.getString(15));}
    private JsonNode readJson(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException("Stored shipping data is invalid.",ex);}}
    private List<String> stringList(String value){JsonNode array=readJson(value);java.util.ArrayList<String> result=new java.util.ArrayList<>();if(array.isArray())for(JsonNode item:array)result.add(item.asText());return List.copyOf(result);}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private static void setTenant(JdbcTemplate jdbc,UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
    private void setTenant(UUID tenantId){setTenant(jdbc,tenantId);}
    private static String required(String value,String label){if(value==null||value.isBlank())throw new IllegalArgumentException(label+" is required.");return value.trim();}
    private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String temperature(String value){String normalized=value==null?"AMBIENT":value.trim().toUpperCase();
        if(!List.of("AMBIENT","REFRIGERATED","FROZEN").contains(normalized))throw new IllegalArgumentException("Choose ambient, refrigerated, or frozen.");return normalized;}
    private static String limit(String value,int max){String safe=value==null?"Unexpected error.":value;return safe.length()<=max?safe:safe.substring(0,max);}
}
