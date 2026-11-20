package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Called inside the tenant-scoped profit transaction. Evidence only; never inferred from packing lists. */
public final class InvoiceCostEvidence {
    private InvoiceCostEvidence() {}
    public record Invoice(UUID documentId,String number,LocalDate date,BigDecimal cost,String currency,UUID vendorId,String vendorName) {}
    public static Map<UUID,Invoice> latest(JdbcTemplate jdbc,UUID tenant,Collection<UUID> items){
        if(items.isEmpty())return Map.of();
        var result=new HashMap<UUID,Invoice>();
        new NamedParameterJdbcTemplate(jdbc).query("""
            SELECT DISTINCT ON (i.account_catalog_item_id) i.account_catalog_item_id,
              d.id,d.document_number,d.document_date,i.unit_cost,i.currency,p.vendor_id,v.name
            FROM purchase_order_items i
            JOIN purchase_orders p ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
            JOIN receiving_documents d ON d.tenant_id=p.tenant_id AND d.id=p.receiving_document_id
            JOIN receiving_document_lines l ON l.tenant_id=i.tenant_id AND l.id=i.receiving_line_id
            JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
            JOIN vendors v ON v.tenant_id=p.tenant_id AND v.id=p.vendor_id
            WHERE i.tenant_id=:tenant AND i.account_catalog_item_id IN (:items)
              AND d.document_type='INVOICE' AND d.removed_at IS NULL AND s.status<>'CANCELLED'
              AND l.invoice_unit_cost>0 AND i.unit_cost>0 AND i.currency ~ '^[A-Z]{3}$'
              AND EXISTS(SELECT 1 FROM receiving_line_receipts r WHERE r.tenant_id=i.tenant_id
                AND r.purchase_order_item_id=i.id AND r.voided_at IS NULL
                AND r.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED'))
            ORDER BY i.account_catalog_item_id,d.document_date DESC NULLS LAST,d.created_at DESC,i.id
            """,Map.of("tenant",tenant,"items",items),rs->{result.put(rs.getObject(1,UUID.class),new Invoice(
                rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,LocalDate.class),rs.getBigDecimal(5),
                rs.getString(6),rs.getObject(7,UUID.class),rs.getString(8)));});
        return result;
    }
}
