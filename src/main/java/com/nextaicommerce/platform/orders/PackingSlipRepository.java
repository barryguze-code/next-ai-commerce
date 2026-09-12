package com.nextaicommerce.platform.orders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, internal pick-label view. It does not create or change an Amazon shipment. */
@Repository
public class PackingSlipRepository {
    private final JdbcTemplate jdbc;
    public PackingSlipRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public record Slip(String orderId,String sellerCentralUrl,String packageName,boolean packageMatched,List<Line> lines){}
    public record Line(String sku,String title,int quantity,LocalDate expirationDate,String location){}

    @Transactional(readOnly=true)
    public Slip find(UUID tenantId,UUID connectionId,String orderId){
        setTenant(tenantId);
        var order=jdbc.query("""
            SELECT order_row.amazon_order_id,connection.marketplace_identifier
            FROM amazon_orders order_row JOIN marketplace_connections connection
              ON connection.tenant_id=order_row.tenant_id AND connection.id=order_row.marketplace_connection_id
            WHERE order_row.tenant_id=? AND order_row.marketplace_connection_id=? AND order_row.amazon_order_id=?
            """,(rs,row)->new Object[]{rs.getString(1),rs.getString(2)},tenantId,connectionId,orderId);
        if(order.isEmpty())return null;
        String packageName=jdbc.query("""
            SELECT packaging FROM temporary_order_packaging_lookup
            WHERE tenant_id=? AND marketplace_connection_id=? AND amazon_order_id=?
            """,rs->rs.next()?rs.getString(1):null,tenantId,connectionId,orderId);
        var lines=jdbc.query("""
            SELECT coalesce(nullif(catalog.account_sku,''),nullif(item.seller_sku,''),'Unmapped SKU') sku,
                   coalesce(nullif(catalog.display_name,''),nullif(product.canonical_name,''),nullif(item.title,''),'Unnamed item') title,
                   sum(item.quantity_ordered*coalesce(component.quantity,1)) quantity,
                   reserved.expiration_date,
                   coalesce(min(default_location.code),'—') location
            FROM amazon_order_items item
            LEFT JOIN marketplace_sku_mappings mapping ON mapping.tenant_id=item.tenant_id
              AND mapping.marketplace_connection_id=item.marketplace_connection_id
              AND mapping.marketplace_sku=item.seller_sku AND mapping.status='ACTIVE'
            LEFT JOIN marketplace_sku_mapping_components component ON component.tenant_id=mapping.tenant_id
              AND component.marketplace_sku_mapping_id=mapping.id
            LEFT JOIN account_catalog_items catalog ON catalog.tenant_id=component.tenant_id AND catalog.id=component.account_catalog_item_id
            LEFT JOIN global_catalog_products product ON product.id=catalog.global_product_id
            LEFT JOIN account_catalog_item_locations default_link ON default_link.tenant_id=catalog.tenant_id
              AND default_link.account_catalog_item_id=catalog.id AND default_link.is_default
            LEFT JOIN warehouse_locations default_location ON default_location.tenant_id=default_link.tenant_id AND default_location.id=default_link.location_id
            LEFT JOIN LATERAL (SELECT min(reservation.expiration_date) expiration_date
              FROM order_inventory_reservations reservation
              WHERE reservation.tenant_id=item.tenant_id AND reservation.amazon_order_item_id=item.id
                AND (component.account_catalog_item_id IS NULL OR reservation.account_catalog_item_id=component.account_catalog_item_id)
                AND reservation.status='ACTIVE') reserved ON true
            WHERE item.tenant_id=? AND item.marketplace_connection_id=? AND item.amazon_order_id=? AND item.quantity_ordered>0
            GROUP BY coalesce(nullif(catalog.account_sku,''),nullif(item.seller_sku,''),'Unmapped SKU'),
                coalesce(nullif(catalog.display_name,''),nullif(product.canonical_name,''),nullif(item.title,''),'Unnamed item'),reserved.expiration_date
            ORDER BY 1
            """,(rs,row)->new Line(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getObject(4,LocalDate.class),rs.getString(5)),tenantId,connectionId,orderId);
        String marketplace=(String)order.getFirst()[1];
        return new Slip(orderId,sellerCentralUrl((String)order.getFirst()[0],marketplace),
            packageName==null||packageName.isBlank()?"Package to confirm":packageName,
            packageName!=null&&!packageName.isBlank(),lines);
    }

    static String sellerCentralUrl(String orderId,String marketplace){
        String domain=switch(marketplace){case "A2EUQ1WTGCTBG2"->"sellercentral.amazon.ca";case "A1F83G8C2ARO7P"->"sellercentral.amazon.co.uk";default->"sellercentral.amazon.com";};
        return "https://"+domain+"/orders-v3/order/"+orderId;
    }
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
}
