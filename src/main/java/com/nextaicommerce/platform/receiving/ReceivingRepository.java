package com.nextaicommerce.platform.receiving;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import com.nextaicommerce.platform.catalog.CatalogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReceivingRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    public ReceivingRepository(JdbcTemplate jdbc, CatalogRepository catalog) { this.jdbc = jdbc; this.catalog = catalog; }

    public record SessionView(UUID id, String reference, String status, String currency,
            BigDecimal freight, BigDecimal duty, BigDecimal other, BigDecimal invoiceTotal,
            LocalDate firstInvoiceDate, LocalDate lastInvoiceDate, int documents, int lines) {
        public SessionView(UUID id, String reference, String status, String currency,
                BigDecimal freight, BigDecimal duty, BigDecimal other, int documents, int lines) {
            this(id,reference,status,currency,freight,duty,other,BigDecimal.ZERO,null,null,documents,lines);
        }
        public String freightDisplay(){return displayMoney(freight);}
        public String dutyDisplay(){return displayMoney(duty);}
        public String otherDisplay(){return displayMoney(other);}
        public String invoiceTotalDisplay(){return displayMoney(invoiceTotal);}
        public String invoiceDateDisplay(){
            if(firstInvoiceDate==null)return "Date unavailable";
            var formatter=DateTimeFormatter.ofPattern("MM/dd/yy");
            if(lastInvoiceDate==null||firstInvoiceDate.equals(lastInvoiceDate))return firstInvoiceDate.format(formatter);
            return firstInvoiceDate.format(formatter)+" – "+lastInvoiceDate.format(formatter);
        }
        public String sharedCostsDisplay(){return displayMoney(freight.add(duty).add(other));}
    }
    public record DocumentView(UUID id, UUID sessionId, String vendorName, String type,
            String filename, String documentNumber, LocalDate documentDate, String status,
            int lines, BigDecimal total, String poNumber) {}
    public record ReceiveLineView(UUID id, UUID documentId, String productName, String brand, String imageUrl,
            String vendorItemCode, String identifier, BigDecimal orderedQuantity, BigDecimal unitsPerCase, BigDecimal expectedEach,
            BigDecimal receivedEach, BigDecimal discrepancyQuantity, BigDecimal remainingEach,
            BigDecimal unitCost, String currency, boolean expirationRequired, String status,
            String discrepancyStatus, String matchSource, int receiptCount,BigDecimal defaultCost,
            BigDecimal depositFee,BigDecimal otherFee,BigDecimal casePrice,
            UUID defaultLocationId,String defaultLocationCode,String defaultLocationName) {
        public ReceiveLineView(UUID id,UUID documentId,String productName,String brand,String imageUrl,
                String vendorItemCode,String identifier,BigDecimal orderedQuantity,BigDecimal unitsPerCase,
                BigDecimal expectedEach,BigDecimal receivedEach,BigDecimal discrepancyQuantity,BigDecimal remainingEach,
                BigDecimal unitCost,String currency,boolean expirationRequired,String status,String discrepancyStatus,
                String matchSource,int receiptCount){
            this(id,documentId,productName,brand,imageUrl,vendorItemCode,identifier,orderedQuantity,unitsPerCase,
                expectedEach,receivedEach,discrepancyQuantity,remainingEach,unitCost,currency,expirationRequired,status,
                discrepancyStatus,matchSource,receiptCount,null,BigDecimal.ZERO,BigDecimal.ZERO,null,null,null,null);
        }
        public BigDecimal suggestedCases(){return unitsPerCase.signum()==0?BigDecimal.ZERO:remainingEach.divideToIntegralValue(unitsPerCase);}
        public BigDecimal suggestedEaches(){return unitsPerCase.signum()==0?remainingEach:remainingEach.remainder(unitsPerCase);}
        public String expectedUnits(){return wholeUnits(expectedEach);}
        public String receivedUnits(){return wholeUnits(receivedEach);}
        public String remainingUnits(){return wholeUnits(remainingEach);}
        public String casesInput(){return wholeUnits(suggestedCases());}
        public String eachesInput(){return wholeUnits(suggestedEaches());}
        public String unitsPerCaseInput(){return wholeUnits(unitsPerCase);}
        public int nextExpirationBatch(){return receiptCount+1;}
        public String unitCostDisplay(){return money(unitCost);}
        public String defaultCostDisplay(){return money(defaultCost);}
        public String depositFeeInput(){return money(depositFee);}
        public String otherFeeInput(){return money(otherFee);}
        public String casePriceDisplay(){return money(casePrice);}
        public boolean costDiffers(){return defaultCost!=null&&unitCost.subtract(defaultCost).abs().compareTo(new BigDecimal("0.005"))>=0;}
        private static String money(BigDecimal value){return value==null?"0.00":value.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
        public String receivingState(){
            if("OVER_SHIPPED".equals(discrepancyStatus)||receivedEach.compareTo(expectedEach)>0)return "OVER";
            if(!"NONE".equals(discrepancyStatus))return "DISCREPANCY";
            if(receivedEach.compareTo(expectedEach)==0&&expectedEach.signum()>0)return "COMPLETE";
            if(receivedEach.signum()>0)return "PARTIAL";
            return "PENDING";
        }
    }
    public record CreditView(String poNumber, String vendorName, String productName, String vendorItemCode,
            String reason, BigDecimal quantity, BigDecimal unitCost, String currency, String status, String notes) {
        public String expectedCreditDisplay(){return displayMoney(quantity.multiply(unitCost));}
    }
    public record ReceiveProgress(BigDecimal receivedEach,BigDecimal remainingEach,int completedLines,
            int totalLines,String sessionStatus){}

    @Transactional(readOnly=true)
    public List<SessionView> sessions(UUID tenantId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT s.id,s.reference,s.status,s.currency,s.freight_amount,s.duty_import_amount,s.other_shared_cost,
                   coalesce((SELECT sum(po.merchandise_total) FROM purchase_orders po
                             WHERE po.tenant_id=s.tenant_id AND po.receiving_session_id=s.id),0) invoice_total,
                   min(d.document_date) invoice_date_from,max(d.document_date) invoice_date_to,
                   count(DISTINCT d.id) documents,count(l.id) lines
            FROM receiving_sessions s
            LEFT JOIN receiving_documents d ON d.tenant_id=s.tenant_id AND d.receiving_session_id=s.id
            LEFT JOIN receiving_document_lines l ON l.tenant_id=d.tenant_id AND l.receiving_document_id=d.id
            WHERE s.tenant_id=? GROUP BY s.id ORDER BY s.created_at DESC
            """, (rs,row)->new SessionView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),
                rs.getObject(9,LocalDate.class),rs.getObject(10,LocalDate.class),rs.getInt(11),rs.getInt(12)), tenantId);
    }

    @Transactional(readOnly=true)
    public SessionView session(UUID tenantId, UUID sessionId) {
        setTenant(tenantId);
        SessionView session=jdbc.query("""
            SELECT s.id,s.reference,s.status,s.currency,s.freight_amount,s.duty_import_amount,s.other_shared_cost,
                   coalesce((SELECT sum(po.merchandise_total) FROM purchase_orders po
                             WHERE po.tenant_id=s.tenant_id AND po.receiving_session_id=s.id),0),
                   min(d.document_date),max(d.document_date),count(DISTINCT d.id),count(l.id)
            FROM receiving_sessions s
            LEFT JOIN receiving_documents d ON d.tenant_id=s.tenant_id AND d.receiving_session_id=s.id
            LEFT JOIN receiving_document_lines l ON l.tenant_id=d.tenant_id AND l.receiving_document_id=d.id
            WHERE s.tenant_id=? AND s.id=? GROUP BY s.id
            """,rs->rs.next()?new SessionView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getBigDecimal(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),
                rs.getObject(9,LocalDate.class),rs.getObject(10,LocalDate.class),rs.getInt(11),rs.getInt(12)):null,
            tenantId,sessionId);
        if(session==null)throw new IllegalArgumentException("Receiving session not found.");
        return session;
    }

    @Transactional(readOnly=true)
    public List<DocumentView> documents(UUID tenantId, UUID sessionId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT d.id,d.receiving_session_id,v.name,d.document_type,d.original_filename,d.document_number,
                   d.document_date,d.status,count(l.id),coalesce(sum(l.ordered_quantity*l.invoice_unit_cost),0),po.po_number
            FROM receiving_documents d JOIN vendors v ON v.tenant_id=d.tenant_id AND v.id=d.vendor_id
            LEFT JOIN receiving_document_lines l ON l.tenant_id=d.tenant_id AND l.receiving_document_id=d.id
            LEFT JOIN purchase_orders po ON po.tenant_id=d.tenant_id AND po.receiving_document_id=d.id
            WHERE d.tenant_id=? AND d.receiving_session_id=?
            GROUP BY d.id,v.name,po.po_number ORDER BY d.created_at
            """, (rs,row)->new DocumentView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,LocalDate.class),rs.getString(8),
                rs.getInt(9),rs.getBigDecimal(10),rs.getString(11)),
                tenantId,sessionId);
    }

    @Transactional(readOnly=true)
    public List<ReceiveLineView> lines(UUID tenantId, UUID sessionId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT item.id,po.receiving_document_id,item.description,product.brand,
                   coalesce(listing_image.image_url,''),
                   item.vendor_item_code,item.source_identifier,
                   item.ordered_quantity,item.units_per_case,
                   item.ordered_quantity * CASE WHEN item.invoice_unit='CASE' THEN item.units_per_case ELSE 1 END expected_each,
                   coalesce(receipts.received_each,0),item.discrepancy_quantity,
                   greatest(item.ordered_quantity * CASE WHEN item.invoice_unit='CASE' THEN item.units_per_case ELSE 1 END
                     - coalesce(receipts.received_each,0)-item.discrepancy_quantity,0) remaining_each,
                   item.unit_cost,item.currency,product.requires_expiration_date,item.status,
                   item.discrepancy_status,item.match_source,coalesce(receipts.receipt_count,0),
                   default_offer.list_cost*(1-default_offer.discount_rate/100),
                   item.deposit_fee_per_unit,item.other_fee_per_unit,
                   CASE WHEN source_line.raw_data->>'PriceBasis'='CASE' THEN source_line.invoice_unit_cost END,
                   default_location.id,default_location.code,default_location.name
            FROM purchase_order_items item
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            JOIN receiving_document_lines source_line ON source_line.tenant_id=item.tenant_id AND source_line.id=item.receiving_line_id
            LEFT JOIN account_catalog_items account_item ON account_item.tenant_id=item.tenant_id AND account_item.id=item.account_catalog_item_id
            LEFT JOIN global_catalog_products product ON product.id=account_item.global_product_id
            LEFT JOIN purchase_orders purchase_order ON purchase_order.tenant_id=item.tenant_id AND purchase_order.id=item.purchase_order_id
            LEFT JOIN vendor_catalog_offers default_offer ON default_offer.tenant_id=item.tenant_id
                AND default_offer.vendor_id=purchase_order.vendor_id AND default_offer.account_catalog_item_id=item.account_catalog_item_id
                AND default_offer.effective_to IS NULL AND default_offer.is_default
            LEFT JOIN LATERAL (
                SELECT candidate.image_url FROM (
                    SELECT listing.image_url,listing.last_seen_at
                    FROM amazon_listings listing
                    WHERE listing.tenant_id=item.tenant_id AND listing.image_url IS NOT NULL
                      AND item.source_identifier IS NOT NULL
                      AND upper(listing.product_id)=upper(item.source_identifier)
                    UNION ALL
                    SELECT listing.image_url,listing.last_seen_at
                    FROM amazon_listings listing
                    WHERE listing.tenant_id=item.tenant_id AND listing.image_url IS NOT NULL
                      AND account_item.account_sku IS NOT NULL
                      AND upper(listing.seller_sku)=upper(account_item.account_sku)
                    UNION ALL
                    SELECT listing.image_url,listing.last_seen_at
                    FROM global_product_identifiers identifier
                    JOIN amazon_listings listing ON listing.tenant_id=item.tenant_id
                      AND listing.image_url IS NOT NULL
                      AND upper(listing.product_id)=upper(identifier.identifier_value)
                    WHERE identifier.global_product_id=product.id
                    UNION ALL
                    SELECT listing.image_url,listing.last_seen_at
                    FROM marketplace_sku_mappings mapping
                    JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id
                      AND listing.marketplace_connection_id=mapping.marketplace_connection_id
                      AND upper(listing.seller_sku)=upper(mapping.marketplace_sku)
                      AND listing.image_url IS NOT NULL
                    WHERE mapping.tenant_id=item.tenant_id AND mapping.account_catalog_item_id=account_item.id
                      AND mapping.status='ACTIVE'
                    UNION ALL
                    SELECT listing.image_url,listing.last_seen_at
                    FROM marketplace_sku_mappings mapping
                    JOIN amazon_listings listing ON listing.tenant_id=mapping.tenant_id
                      AND listing.marketplace_connection_id=mapping.marketplace_connection_id
                      AND listing.asin=mapping.asin
                      AND listing.image_url IS NOT NULL
                    WHERE mapping.tenant_id=item.tenant_id AND mapping.account_catalog_item_id=account_item.id
                      AND mapping.status='ACTIVE' AND mapping.asin IS NOT NULL
                ) candidate ORDER BY candidate.last_seen_at DESC LIMIT 1
            ) listing_image ON true
            LEFT JOIN LATERAL (SELECT sum(total_each_quantity) received_each,count(*) receipt_count
                FROM receiving_line_receipts receipt WHERE receipt.voided_at IS NULL AND receipt.tenant_id=item.tenant_id AND receipt.purchase_order_item_id=item.id) receipts ON true
            LEFT JOIN LATERAL (
                SELECT location.id,location.code,location.name
                FROM account_catalog_item_locations assignment
                JOIN warehouse_locations location ON location.tenant_id=assignment.tenant_id AND location.id=assignment.location_id
                WHERE assignment.tenant_id=item.tenant_id AND assignment.account_catalog_item_id=item.account_catalog_item_id
                  AND assignment.is_default AND location.status='ACTIVE' LIMIT 1
            ) default_location ON true
            WHERE item.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) ORDER BY po.created_at,item.created_at
            """,(rs,row)->new ReceiveLineView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getBigDecimal(8),
                rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getBigDecimal(13),
                rs.getBigDecimal(14),rs.getString(15),rs.getBoolean(16),rs.getString(17),rs.getString(18),
                rs.getString(19),rs.getInt(20),rs.getBigDecimal(21),rs.getBigDecimal(22),rs.getBigDecimal(23),rs.getBigDecimal(24),
                rs.getObject(25,UUID.class),rs.getString(26),rs.getString(27)),tenantId,sessionId);
    }

    @Transactional(readOnly=true)
    public List<CreditView> credits(UUID tenantId, UUID sessionId) {
        setTenant(tenantId);
        return jdbc.query("""
            SELECT po.po_number,v.name,item.description,item.vendor_item_code,credit.reason,credit.quantity,
                   credit.unit_cost,credit.currency,credit.status,credit.notes
            FROM vendor_credit_requests credit
            JOIN purchase_order_items item ON item.tenant_id=credit.tenant_id AND item.id=credit.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            JOIN vendors v ON v.tenant_id=credit.tenant_id AND v.id=credit.vendor_id
            WHERE credit.tenant_id=? AND credit.receiving_session_id=? ORDER BY credit.created_at
            """,(rs,row)->new CreditView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getString(8),rs.getString(9),rs.getString(10)),
                tenantId,sessionId);
    }

    @Transactional(readOnly=true)
    public List<LocalDate> recentExpirationDates(UUID tenantId,UUID sessionId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT receipt.expiration_date
            FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND receipt.expiration_date IS NOT NULL
            GROUP BY receipt.expiration_date
            ORDER BY bool_or(po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL)) DESC,max(receipt.received_at) DESC
            LIMIT 5
            """,(rs,row)->rs.getObject(1,LocalDate.class),tenantId,sessionId);
    }

    @Transactional
    public UUID createSession(UUID tenantId, String actorEmail, String reference, String currency,
            BigDecimal freight, BigDecimal duty, BigDecimal other, String allocation) {
        setTenant(tenantId);
        return jdbc.queryForObject("""
            INSERT INTO receiving_sessions (tenant_id,reference,currency,freight_amount,duty_import_amount,
                other_shared_cost,allocation_method,created_by)
            VALUES (?,?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?))) RETURNING id
            """,UUID.class,tenantId,blank(reference),currency(currency),zero(freight),zero(duty),zero(other),
            List.of("VALUE","QUANTITY","WEIGHT","MANUAL").contains(allocation)?allocation:"VALUE",actorEmail);
    }

    @Transactional
    public void discardEmptySession(UUID tenantId, UUID sessionId) {
        setTenant(tenantId);
        jdbc.update("""
            DELETE FROM receiving_sessions session
            WHERE session.tenant_id=? AND session.id=?
              AND session.status IN ('DRAFT','MATCHING')
              AND NOT EXISTS (SELECT 1 FROM receiving_documents document
                              WHERE document.tenant_id=session.tenant_id
                                AND document.receiving_session_id=session.id)
            """,tenantId,sessionId);
    }

    @Transactional
    public UUID addDocument(UUID tenantId, UUID sessionId, UUID vendorId, String type, String filename,
            String hash, String currency, List<Map<String,String>> rows, String actorEmail) {
        setTenant(tenantId);
        lockInventory(tenantId);
        String documentType="PACKING_LIST".equals(type)?"PACKING_LIST":"INVOICE";
        Boolean validVendor=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vendors WHERE tenant_id=? AND id=? AND status='ACTIVE')",
            Boolean.class,tenantId,vendorId);
        if(!Boolean.TRUE.equals(validVendor))throw new IllegalArgumentException("Choose an active vendor before uploading invoices or packing lists.");
        String documentNumber=first(rows,"invoicenumber","invoiceno","invoice","documentnumber","packinglistnumber");
        LocalDate documentDate=parseDate(first(rows,"invoicedate","documentdate","date"));
        String sessionStatus=jdbc.query("SELECT status FROM receiving_sessions WHERE tenant_id=? AND id=? FOR UPDATE",
            rs->rs.next()?rs.getString(1):null,tenantId,sessionId);
        if(sessionStatus==null)throw new IllegalArgumentException("Receiving session not found.");
        if("POSTED".equals(sessionStatus)||"CANCELLED".equals(sessionStatus))
            throw new IllegalArgumentException("Posted or cancelled receiving sessions cannot accept another document.");
        Boolean duplicateFile=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM receiving_documents WHERE tenant_id=? AND vendor_id=? AND file_sha256=? AND removed_at IS NULL)",
            Boolean.class,tenantId,vendorId,hash);
        if(Boolean.TRUE.equals(duplicateFile))throw new IllegalArgumentException("This vendor document file was already uploaded.");
        if(!documentNumber.isBlank()) {
            Boolean duplicate=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM receiving_documents WHERE tenant_id=? AND vendor_id=? AND document_number=? AND removed_at IS NULL)",
                Boolean.class,tenantId,vendorId,documentNumber);
            if(Boolean.TRUE.equals(duplicate))throw new IllegalArgumentException("A receiving source with this vendor reference already exists: "+documentNumber+". Open the existing document; do not count its invoice and packing list twice.");
        }
        String cleanCurrency=currency(currency);
        UUID documentId=jdbc.queryForObject("""
            INSERT INTO receiving_documents (tenant_id,receiving_session_id,vendor_id,document_type,
                document_number,document_date,original_filename,file_sha256,currency,status)
            VALUES (?,?,?,?,?,?,?,?,?,'EXTRACTED') RETURNING id
            """,UUID.class,tenantId,sessionId,vendorId,documentType,blank(documentNumber),documentDate,filename,hash,cleanCurrency);
        if(!documentNumber.isBlank()) jdbc.update("UPDATE receiving_sessions SET reference=coalesce(reference,?) WHERE tenant_id=? AND id=?",
            documentType.equals("INVOICE")?"Invoice "+documentNumber:documentNumber,tenantId,sessionId);
        jdbc.update("UPDATE receiving_documents SET unshipped_rows=?::jsonb WHERE tenant_id=? AND id=?",
            new tools.jackson.databind.ObjectMapper().writeValueAsString(unshippedRows(rows)),tenantId,documentId);
        BigDecimal total=BigDecimal.ZERO; int rowNumber=2;
        List<Object[]> lineWrites=new java.util.ArrayList<>();
        for (Map<String,String> row:rows) {
            int sourceRowNumber=rowNumber++;
            String description=find(row,"description","productname","itemdescription","title","product");
            String brand=find(row,"brand");
            if(!brand.isBlank()&&!description.toLowerCase().startsWith(brand.toLowerCase())) description=brand+" - "+description;
            String vendorCode=cleanCode(find(row,"vendoritemcode","vendorsku","itemnumber","itemno","sku","shipitem"));
            String identifier=find(row,"upc","ean","gtin","barcode");
            BigDecimal quantity=number(find(row,"shipquantity","shippedquantity","quantity","qty","orderedquantity","orderqty","shippedqty"));
            BigDecimal invoicePrice=number(find(row,"neteach","unitcost","unitprice","price","cost"));
            String packSize=find(row,"packsize","casepack","unitspercase");
            BigDecimal unitsPerCase=packUnits(packSize);
            String invoiceUnit=hasHeading(row,"shipquantity")?"EACH":"CASE";
            boolean casePrice="CASE".equalsIgnoreCase(find(row,"pricebasis"));
            BigDecimal cost=casePrice?invoicePrice.divide(unitsPerCase,4,java.math.RoundingMode.HALF_UP):invoicePrice;
            if(quantity.signum()<=0) continue;
            if(!whole(quantity))throw new IllegalArgumentException("Row "+sourceRowNumber+" has a fractional item quantity. Receiving quantities must be whole units.");
            if(description.isBlank()) throw new IllegalArgumentException("Row "+sourceRowNumber+" has shipped quantity but no product description.");
            if(vendorCode.isBlank()&&identifier.isBlank()) throw new IllegalArgumentException("Row "+sourceRowNumber+" needs a vendor item code or UPC/EAN before it can enter the catalogue.");
            MatchedItem matched=matchOrCreateItem(tenantId,vendorId,actorEmail,description,brand,vendorCode,identifier,cost,
                cleanCurrency,documentType,documentNumber);
            if(packSize.isBlank()){
                BigDecimal savedPack=jdbc.queryForObject("""
                    SELECT coalesce((SELECT packaging.units_per_case FROM vendor_catalog_offers offer
                      JOIN global_product_packaging_versions packaging ON packaging.id=offer.packaging_version_id
                      WHERE offer.tenant_id=a.tenant_id AND offer.account_catalog_item_id=a.id AND offer.vendor_id=?
                        AND offer.effective_to IS NULL ORDER BY offer.updated_at DESC LIMIT 1),g.units_per_case)
                    FROM account_catalog_items a JOIN global_catalog_products g ON g.id=a.global_product_id
                    WHERE a.tenant_id=? AND a.id=?
                    """,BigDecimal.class,vendorId,tenantId,matched.itemId());
                if(savedPack!=null&&savedPack.signum()>0){unitsPerCase=savedPack;cost=casePrice?invoicePrice.divide(unitsPerCase,4,java.math.RoundingMode.HALF_UP):invoicePrice;}
            }
            if(matched.created()&&identifier.isBlank()) jdbc.update("""
                UPDATE account_catalog_items SET completion_status='NEEDS_COMPLETION',missing_fields=ARRAY['UPC/EAN'],updated_at=now()
                WHERE tenant_id=? AND id=?
                """,tenantId,matched.itemId());
            lineWrites.add(new Object[]{tenantId,documentId,blank(vendorCode),blank(identifier),description,quantity,
                invoicePrice,cleanCurrency,matched.itemId(),unitsPerCase,invoiceUnit,sourceRowNumber,toJson(row),
                matched.created()?"NEW_PRODUCT":"MATCHED"});
            BigDecimal expectedEach="CASE".equals(invoiceUnit)?quantity.multiply(unitsPerCase):quantity;
            total=total.add(expectedEach.multiply(cost));
        }
        if(lineWrites.isEmpty()) throw new IllegalArgumentException("We found no usable item rows. Include description, quantity, and unit cost headings.");
        jdbc.batchUpdate("""
                INSERT INTO receiving_document_lines (tenant_id,receiving_document_id,vendor_item_code,
                    source_identifier,source_description,ordered_quantity,invoice_unit_cost,currency,account_catalog_item_id,
                    source_units_per_case,source_invoice_unit,source_row_number,raw_data,match_status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """,lineWrites);
        String poNumber="PO-"+documentId.toString().substring(0,8).toUpperCase();
        UUID poId=jdbc.queryForObject("""
            INSERT INTO purchase_orders (tenant_id,receiving_session_id,receiving_document_id,vendor_id,
                po_number,currency,merchandise_total,created_by)
            VALUES (?,?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?))) RETURNING id
            """,UUID.class,tenantId,sessionId,documentId,vendorId,poNumber,cleanCurrency,total,actorEmail);
        jdbc.update("""
            INSERT INTO purchase_order_items (tenant_id,purchase_order_id,receiving_line_id,vendor_item_code,
                source_identifier,description,ordered_quantity,unit_cost,currency,account_catalog_item_id,
                units_per_case,invoice_unit,match_source)
            SELECT line.tenant_id,?,line.id,line.vendor_item_code,line.source_identifier,line.source_description,line.ordered_quantity,
                   CASE WHEN line.raw_data->>'PriceBasis'='CASE'
                        THEN coalesce(line.invoice_unit_cost,0)/greatest(line.source_units_per_case,1)
                        ELSE coalesce(line.invoice_unit_cost,0) END,
                   line.currency,line.account_catalog_item_id,
                   line.source_units_per_case,
                   line.source_invoice_unit,
                   CASE WHEN line.match_status='NEW_PRODUCT' THEN 'NEW_PRODUCT'
                        WHEN EXISTS (SELECT 1 FROM vendor_catalog_offers offer
                          WHERE offer.tenant_id=line.tenant_id
                            AND offer.account_catalog_item_id=line.account_catalog_item_id
                            AND offer.vendor_id=?
                            AND upper(regexp_replace(coalesce(offer.vendor_item_code,''),'[^A-Za-z0-9]','','g'))=
                                upper(regexp_replace(coalesce(line.vendor_item_code,''),'[^A-Za-z0-9]','','g'))
                            AND offer.effective_from<=current_date
                            AND (offer.effective_to IS NULL OR offer.effective_to>current_date)) THEN 'VENDOR_ITEM'
                        WHEN line.source_identifier IS NOT NULL THEN 'IDENTIFIER' ELSE 'MANUAL' END
            FROM receiving_document_lines line
            JOIN account_catalog_items account_item ON account_item.tenant_id=line.tenant_id AND account_item.id=line.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=account_item.global_product_id
            WHERE line.tenant_id=? AND line.receiving_document_id=?
            """,poId,vendorId,tenantId,documentId);
        jdbc.update("UPDATE receiving_sessions SET status='MATCHING' WHERE tenant_id=? AND id=?",tenantId,sessionId);
        return documentId;
    }

    @Transactional
    public ReceiveProgress receive(UUID tenantId, UUID sessionId, UUID itemId, String actorEmail,
            BigDecimal cases, BigDecimal eaches, BigDecimal unitsPerCase, LocalDate expiration,
            String disposition, String notes,BigDecimal depositFee,BigDecimal otherFee,boolean updateDefaultCost) {
        return receive(tenantId,sessionId,itemId,actorEmail,cases,eaches,unitsPerCase,expiration,disposition,
            notes,depositFee,otherFee,null,updateDefaultCost);
    }

    @Transactional
    public ReceiveProgress receive(UUID tenantId, UUID sessionId, UUID itemId, String actorEmail,
            BigDecimal cases, BigDecimal eaches, BigDecimal unitsPerCase, LocalDate expiration,
            String disposition, String notes,BigDecimal depositFee,BigDecimal otherFee,UUID locationId,boolean updateDefaultCost) {
        setTenant(tenantId);
        lockInventory(tenantId);
        target(tenantId,sessionId,itemId);
        Boolean hasReceipts=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM receiving_line_receipts WHERE tenant_id=? AND purchase_order_item_id=?)",Boolean.class,tenantId,itemId);
        if(Boolean.TRUE.equals(hasReceipts)){
            Boolean unchanged=jdbc.queryForObject("SELECT units_per_case=? AND deposit_fee_per_unit=? AND other_fee_per_unit=? FROM purchase_order_items WHERE tenant_id=? AND id=?",Boolean.class,unitsPerCase,zero(depositFee),zero(otherFee),tenantId,itemId);
            if(!Boolean.TRUE.equals(unchanged))throw new IllegalArgumentException("Pack size and item fees are locked after receiving begins. Keep the original values; use an inventory adjustment for corrections.");
        }
        depositFee=zero(depositFee);otherFee=zero(otherFee);
        if(depositFee.signum()<0||otherFee.signum()<0)throw new IllegalArgumentException("Deposit and other item fees cannot be negative.");
        jdbc.update("UPDATE purchase_order_items SET deposit_fee_per_unit=?,other_fee_per_unit=?,updated_at=now() WHERE tenant_id=? AND id=?",
            depositFee,otherFee,tenantId,itemId);
        cases=zero(cases);eaches=zero(eaches);unitsPerCase=zero(unitsPerCase);
        if(cases.signum()<0||eaches.signum()<0)throw new IllegalArgumentException("Case and each quantities cannot be negative.");
        if(unitsPerCase.signum()<=0)throw new IllegalArgumentException("Units per case must be at least one.");
        if(!whole(cases)||!whole(eaches)||!whole(unitsPerCase))throw new IllegalArgumentException("Cases, units per case, and eaches must be whole numbers.");
        jdbc.update("""
            UPDATE purchase_order_items item SET unit_cost=line.invoice_unit_cost/?
            FROM receiving_document_lines line
            WHERE item.tenant_id=? AND item.id=? AND line.tenant_id=item.tenant_id
              AND line.id=item.receiving_line_id AND line.raw_data->>'PriceBasis'='CASE'
            """,unitsPerCase,tenantId,itemId);
        ReceiveTarget target=target(tenantId,sessionId,itemId);
        UUID receiptLocation=resolveItemLocation(tenantId,target.accountItemId(),locationId);
        if(updateDefaultCost) catalog.updateDefaultFromInvoice(tenantId,actorEmail,target.accountItemId(),target.vendorId(),target.unitCost(),target.currency(),sessionId.toString());
        BigDecimal total=zero(cases).multiply(unitsPerCase).add(zero(eaches));
        if(total.signum()<=0) throw new IllegalArgumentException("Enter at least one case or each.");
        BigDecimal allowed=target.expected().subtract(target.received()).subtract(target.discrepancy()).max(BigDecimal.ZERO);
        String cleanDisposition=List.of("SELLABLE","SHORT_SHIPPED","DAMAGED","MISPICKED","SOON_EXPIRED","EXPIRED","OVER_SHIPPED")
            .contains(disposition)?disposition:"SELLABLE";
        if(target.expirationRequired()&&expiration==null&&List.of("SELLABLE","SOON_EXPIRED","EXPIRED","OVER_SHIPPED").contains(cleanDisposition))
            throw new IllegalArgumentException("Expiration date is required before this inventory can be received.");
        if("SHORT_SHIPPED".equals(cleanDisposition)){
            if(total.compareTo(allowed)>0)throw new IllegalArgumentException("Short-shipped quantity cannot exceed the remaining expected quantity.");
            saveDiscrepancy(tenantId,sessionId,itemId,actorEmail,target,"SHORT_SHIPPED",total,notes);
            refreshItemStatus(tenantId,itemId);refreshSessionStatus(tenantId,sessionId);
            return receiveProgress(tenantId,sessionId,itemId);
        }
        if("OVER_SHIPPED".equals(cleanDisposition)){
            if(total.compareTo(allowed)<=0)throw new IllegalArgumentException("Over-shipped quantity must be greater than the remaining expected quantity.");
            if(allowed.signum()>0)publishReceipt(tenantId,saveReceipt(tenantId,itemId,BigDecimal.ZERO,allowed,
                BigDecimal.ONE,expiration,"SELLABLE",notes,actorEmail,receiptLocation));
            BigDecimal overage=total.subtract(allowed);
            publishReceipt(tenantId,saveReceipt(tenantId,itemId,BigDecimal.ZERO,overage,BigDecimal.ONE,
                expiration,"OVER_SHIPPED",notes,actorEmail,receiptLocation));
            jdbc.update("UPDATE purchase_order_items SET units_per_case=?,received_quantity=received_quantity+?,discrepancy_status='OVER_SHIPPED',updated_at=now() WHERE tenant_id=? AND id=?",
                unitsPerCase,total,tenantId,itemId);
            jdbc.update("UPDATE receiving_document_lines SET received_quantity=received_quantity+?,expiration_date=coalesce(?,expiration_date),updated_at=now() WHERE tenant_id=? AND id=?",
                total,expiration,tenantId,target.lineId());
            updateProductReceivingDefaults(tenantId,target,unitsPerCase,expiration);
            refreshItemStatus(tenantId,itemId);refreshSessionStatus(tenantId,sessionId);
            return receiveProgress(tenantId,sessionId,itemId);
        }
        if(total.compareTo(allowed)>0) throw new IllegalArgumentException("This receipt exceeds the remaining expected quantity. Choose Over shipped when extra units physically arrived.");
        if("SELLABLE".equals(cleanDisposition)&&expiration!=null&&expiration.isBefore(LocalDate.now()))
            throw new IllegalArgumentException("This expiration date has passed. Choose Expired so the quantity stays out of sellable inventory.");
        if("SOON_EXPIRED".equals(cleanDisposition)&&expiration==null)
            throw new IllegalArgumentException("Choose the expiration date for soon-to-expire inventory.");
        if("EXPIRED".equals(cleanDisposition)&&expiration==null)
            throw new IllegalArgumentException("Choose the expiration date for expired inventory.");
        UUID receiptId=saveReceipt(tenantId,itemId,zero(cases),zero(eaches),unitsPerCase,expiration,
            cleanDisposition,notes,actorEmail,receiptLocation);
        jdbc.update("UPDATE purchase_order_items SET units_per_case=?,received_quantity=received_quantity+?,updated_at=now() WHERE tenant_id=? AND id=?",
            unitsPerCase,total,tenantId,itemId);
        jdbc.update("UPDATE receiving_document_lines SET received_quantity=received_quantity+?,expiration_date=coalesce(?,expiration_date),updated_at=now() WHERE tenant_id=? AND id=?",
            total,expiration,tenantId,target.lineId());
        updateProductReceivingDefaults(tenantId,target,unitsPerCase,expiration);
        publishReceipt(tenantId,receiptId);
        if(!"SELLABLE".equals(cleanDisposition)) {
            jdbc.update("UPDATE purchase_order_items SET discrepancy_status=?,discrepancy_notes=? WHERE tenant_id=? AND id=?",
                cleanDisposition,blank(notes),tenantId,itemId);
            BigDecimal prior=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM vendor_credit_requests WHERE tenant_id=? AND purchase_order_item_id=? AND reason=?",BigDecimal.class,tenantId,itemId,cleanDisposition);
            upsertCredit(tenantId,sessionId,itemId,target,cleanDisposition,prior.add(total),notes,actorEmail);
        }
        refreshItemStatus(tenantId,itemId);
        refreshSessionStatus(tenantId,sessionId);
        return receiveProgress(tenantId,sessionId,itemId);
    }

    @Transactional
    public void markDiscrepancy(UUID tenantId, UUID sessionId, UUID itemId, String actorEmail,
            String reason, BigDecimal quantity, String notes) {
        setTenant(tenantId);
        lockInventory(tenantId);
        ReceiveTarget target=target(tenantId,sessionId,itemId);
        if(reason==null||!List.of("SHORT_SHIPPED","DAMAGED","MISPICKED","SOON_EXPIRED","EXPIRED","OTHER").contains(reason))
            throw new IllegalArgumentException("Choose a valid discrepancy reason.");
        String clean=reason;
        BigDecimal remaining=target.expected().subtract(target.received()).subtract(target.discrepancy()).max(BigDecimal.ZERO);
        BigDecimal amount=quantity==null?remaining:quantity;
        if(amount.signum()<=0||amount.stripTrailingZeros().scale()>0||amount.compareTo(remaining)>0) throw new IllegalArgumentException("Use a positive whole-number discrepancy quantity within the remaining expected quantity.");
        saveDiscrepancy(tenantId,sessionId,itemId,actorEmail,target,clean,amount,notes);
        refreshItemStatus(tenantId,itemId);
        refreshSessionStatus(tenantId,sessionId);
    }

    @Transactional
    public void post(UUID tenantId, UUID sessionId, String actorEmail) {
        setTenant(tenantId);
        lockInventory(tenantId);
        String state=jdbc.query("SELECT status FROM receiving_sessions WHERE tenant_id=? AND id=? FOR UPDATE",rs->rs.next()?rs.getString(1):null,tenantId,sessionId);
        if(state==null||"CANCELLED".equals(state))throw new IllegalArgumentException("This receiving is no longer available.");
        if("POSTED".equals(state))return;
        int[] completion=jdbc.query("""
            SELECT count(*),count(*) FILTER (WHERE item.received_quantity+item.discrepancy_quantity <
                item.ordered_quantity*CASE WHEN item.invoice_unit='CASE' THEN item.units_per_case ELSE 1 END)
            FROM purchase_order_items item
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE item.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL)
            """,rs->rs.next()?new int[]{rs.getInt(1),rs.getInt(2)}:new int[]{0,0},tenantId,sessionId);
        if(completion[0]==0) throw new IllegalArgumentException("Add and review at least one invoice before receiving.");
        if(completion[1]>0)
            throw new IllegalArgumentException("Finish or explain every remaining quantity before closing this receiving.");
        Integer missingExpiration=jdbc.queryForObject("""
            SELECT count(*) FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            JOIN account_catalog_items account_item ON account_item.tenant_id=item.tenant_id AND account_item.id=item.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=account_item.global_product_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) AND product.requires_expiration_date
              AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED') AND receipt.expiration_date IS NULL
              AND NOT EXISTS (SELECT 1 FROM inventory_ledger_entries posted
                  WHERE posted.tenant_id=receipt.tenant_id AND posted.source_type='RECEIVING'
                    AND posted.source_id=receipt.id AND posted.idempotency_key='receiving:'||receipt.id)
            """,Integer.class,tenantId,sessionId);
        if(missingExpiration>0) throw new IllegalArgumentException("Add the required expiration date before closing this receiving.");
        BigDecimal sellable=jdbc.queryForObject("""
            SELECT coalesce(sum(receipt.total_each_quantity),0) FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED')
            """,BigDecimal.class,tenantId,sessionId);
        jdbc.update("DELETE FROM receiving_cost_allocations allocation USING receiving_document_lines line,receiving_documents document WHERE allocation.tenant_id=? AND allocation.tenant_id=line.tenant_id AND allocation.receiving_line_id=line.id AND line.tenant_id=document.tenant_id AND line.receiving_document_id=document.id AND document.receiving_session_id=?",tenantId,sessionId);
        if(sellable.signum()>0) jdbc.update("""
            WITH line_totals AS (
                SELECT item.receiving_line_id,item.unit_cost,item.deposit_fee_per_unit,item.other_fee_per_unit,
                       sum(receipt.total_each_quantity) sellable_quantity,
                       sum(receipt.total_each_quantity*item.unit_cost) merchandise_value
                FROM receiving_line_receipts receipt
                JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id AND item.id=receipt.purchase_order_item_id
                JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED')
                GROUP BY item.receiving_line_id,item.unit_cost,item.deposit_fee_per_unit,item.other_fee_per_unit
            ), totals AS (
                SELECT sum(sellable_quantity) total_quantity,sum(merchandise_value) total_value FROM line_totals
            ), allocated AS (
                SELECT line_totals.*,
                    CASE WHEN session.allocation_method='VALUE' AND totals.total_value>0
                         THEN line_totals.merchandise_value/totals.total_value
                         ELSE line_totals.sellable_quantity/totals.total_quantity END allocation_share,
                    session.freight_amount,session.duty_import_amount,session.other_shared_cost
                FROM line_totals CROSS JOIN totals
                JOIN receiving_sessions session ON session.tenant_id=? AND session.id=?
            )
            INSERT INTO receiving_cost_allocations (tenant_id,receiving_line_id,freight_allocated,
                duty_allocated,other_cost_allocated,final_landed_unit_cost,allocation_basis)
            SELECT ?,receiving_line_id,freight_amount*allocation_share,duty_import_amount*allocation_share,
                   other_shared_cost*allocation_share,
                   unit_cost+deposit_fee_per_unit+other_fee_per_unit+
                     ((freight_amount+duty_import_amount+other_shared_cost)*allocation_share/sellable_quantity),
                   allocation_share
            FROM allocated
            """,tenantId,sessionId,tenantId,sessionId,tenantId);
        publishReceipts(tenantId,sessionId);
        jdbc.update("""
            UPDATE inventory_ledger_entries ledger SET
                unit_cost=allocation.final_landed_unit_cost,cost_status='FINAL'
            FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id
                AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            JOIN receiving_cost_allocations allocation ON allocation.tenant_id=item.tenant_id
                AND allocation.receiving_line_id=item.receiving_line_id
            WHERE ledger.tenant_id=? AND ledger.source_type='RECEIVING' AND ledger.source_id=receipt.id
              AND receipt.voided_at IS NULL AND receipt.tenant_id=ledger.tenant_id AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL)
              AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED')
            """,tenantId,sessionId);
        jdbc.update("""
            INSERT INTO inventory_ledger_entries (tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
                expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            SELECT receipt.tenant_id,item.account_catalog_item_id,receipt.location_id,'RECEIPT',receipt.total_each_quantity,
                   receipt.expiration_date,allocation.final_landed_unit_cost,item.currency,'RECEIVING',receipt.id,receipt.received_at,
                   'receiving:'||receipt.id,coalesce(receipt.notes,'Received from vendor'),
                   (SELECT id FROM app_users WHERE lower(email)=lower(?)),'FINAL'
            FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            JOIN receiving_cost_allocations allocation ON allocation.tenant_id=item.tenant_id AND allocation.receiving_line_id=item.receiving_line_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED')
            ON CONFLICT (tenant_id,idempotency_key) DO NOTHING
            """,actorEmail,tenantId,sessionId);
        jdbc.update("""
            INSERT INTO inventory_ledger_entries (tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
                expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            SELECT receipt.tenant_id,item.account_catalog_item_id,receipt.location_id,'RECEIPT',receipt.total_each_quantity,
                   receipt.expiration_date,0,item.currency,'RECEIVING',receipt.id,receipt.received_at,
                   'receiving:'||receipt.id,coalesce(receipt.notes,'Over-shipped inventory received at zero cost'),
                   (SELECT id FROM app_users WHERE lower(email)=lower(?)),'FINAL'
            FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) AND receipt.disposition='OVER_SHIPPED'
            ON CONFLICT (tenant_id,idempotency_key) DO NOTHING
            """,actorEmail,tenantId,sessionId);
        jdbc.update("UPDATE receiving_sessions SET status='POSTED',posted_at=now(),posted_by=(SELECT id FROM app_users WHERE lower(email)=lower(?)) WHERE tenant_id=? AND id=? AND status<>'POSTED'",
            actorEmail,tenantId,sessionId);
        jdbc.update("UPDATE purchase_orders SET status='RECEIVED',updated_at=now() WHERE tenant_id=? AND receiving_session_id=?",tenantId,sessionId);
        jdbc.update("UPDATE receiving_documents SET status='RECEIVED',updated_at=now() WHERE tenant_id=? AND receiving_session_id=? AND removed_at IS NULL",tenantId,sessionId);
    }

    /** Reconciles saved invoice receipts into available inventory; safe to call repeatedly. */
    @Transactional
    public void publishReceipts(UUID tenantId,UUID sessionId){
        setTenant(tenantId);
        jdbc.update("""
            INSERT INTO inventory_ledger_entries (tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
                expiration_date,unit_cost,deposit_fee_per_unit,other_fee_per_unit,currency,source_type,source_id,occurred_at,idempotency_key,
                notes,created_by,cost_status)
            SELECT receipt.tenant_id,item.account_catalog_item_id,receipt.location_id,'RECEIPT',receipt.total_each_quantity,
                   receipt.expiration_date,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 0 ELSE item.unit_cost+item.deposit_fee_per_unit+item.other_fee_per_unit END,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 0 ELSE item.deposit_fee_per_unit END,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 0 ELSE item.other_fee_per_unit END,
                   item.currency,'RECEIVING',receipt.id,receipt.received_at,'receiving:'||receipt.id,
                   coalesce(receipt.notes,CASE receipt.disposition
                       WHEN 'OVER_SHIPPED' THEN 'Over-shipped inventory received at zero cost'
                       WHEN 'SOON_EXPIRED' THEN 'Soon-to-expire stock received into dated inventory'
                       WHEN 'EXPIRED' THEN 'Expired stock received and marked cannot sell'
                       ELSE 'Received from vendor' END),receipt.received_by,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 'FINAL' ELSE 'PROVISIONAL' END
            FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id
                AND item.id=receipt.purchase_order_item_id
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL)
              AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED')
            ON CONFLICT (tenant_id,idempotency_key) DO NOTHING
            """,tenantId,sessionId);
    }

    /** Publishes only the receipt just saved, keeping an individual Receive click constant-time. */
    private void publishReceipt(UUID tenantId,UUID receiptId){
        jdbc.update("""
            INSERT INTO inventory_ledger_entries (tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
                expiration_date,unit_cost,deposit_fee_per_unit,other_fee_per_unit,currency,source_type,source_id,occurred_at,idempotency_key,
                notes,created_by,cost_status)
            SELECT receipt.tenant_id,item.account_catalog_item_id,receipt.location_id,'RECEIPT',receipt.total_each_quantity,
                   receipt.expiration_date,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 0 ELSE item.unit_cost+item.deposit_fee_per_unit+item.other_fee_per_unit END,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 0 ELSE item.deposit_fee_per_unit END,
                   CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 0 ELSE item.other_fee_per_unit END,
                   item.currency,'RECEIVING',receipt.id,receipt.received_at,'receiving:'||receipt.id,
                   coalesce(receipt.notes,CASE receipt.disposition
                       WHEN 'OVER_SHIPPED' THEN 'Over-shipped inventory received at zero cost'
                       WHEN 'SOON_EXPIRED' THEN 'Soon-to-expire stock received into dated inventory'
                       WHEN 'EXPIRED' THEN 'Expired stock received and marked cannot sell'
                       ELSE 'Received from vendor' END),
                   receipt.received_by,CASE WHEN receipt.disposition='OVER_SHIPPED' THEN 'FINAL' ELSE 'PROVISIONAL' END
            FROM receiving_line_receipts receipt
            JOIN purchase_order_items item ON item.tenant_id=receipt.tenant_id
                AND item.id=receipt.purchase_order_item_id
            WHERE receipt.voided_at IS NULL AND receipt.tenant_id=? AND receipt.id=?
              AND receipt.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED')
            ON CONFLICT (tenant_id,idempotency_key) DO NOTHING
            """,tenantId,receiptId);
    }

    private ReceiveProgress receiveProgress(UUID tenantId,UUID sessionId,UUID itemId){
        ReceiveProgress progress=jdbc.query("""
            WITH session_items AS (
                SELECT item.id,item.received_quantity,item.discrepancy_quantity,
                       item.ordered_quantity*CASE WHEN item.invoice_unit='CASE' THEN item.units_per_case ELSE 1 END expected
                FROM purchase_order_items item
                JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE item.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL)
            ), totals AS (
                SELECT count(*) total_lines,
                       count(*) FILTER (WHERE received_quantity+discrepancy_quantity>=expected) completed_lines
                FROM session_items
            )
            SELECT line.received_quantity,greatest(line.expected-line.received_quantity-line.discrepancy_quantity,0),
                   totals.completed_lines,totals.total_lines,session.status
            FROM session_items line CROSS JOIN totals
            JOIN receiving_sessions session ON session.tenant_id=? AND session.id=?
            WHERE line.id=?
            """,rs->rs.next()?new ReceiveProgress(rs.getBigDecimal(1),rs.getBigDecimal(2),rs.getInt(3),
                rs.getInt(4),rs.getString(5)):null,tenantId,sessionId,tenantId,sessionId,itemId);
        if(progress==null)throw new IllegalArgumentException("Receiving line not found.");
        return progress;
    }

    @Transactional
    public void reopenLine(UUID tenantId,UUID sessionId,UUID itemId,String actorEmail){
        throw new IllegalArgumentException("Receipts cannot be reopened or erased. Use Review receipts to undo an untouched receipt or make an inventory adjustment.");
    }

    private ReceiveTarget target(UUID tenantId,UUID sessionId,UUID itemId){
        ReceiveTarget target=jdbc.query("""
            SELECT item.receiving_line_id,item.account_catalog_item_id,po.vendor_id,item.unit_cost,item.currency,
                   item.ordered_quantity*CASE WHEN item.invoice_unit='CASE' THEN item.units_per_case ELSE 1 END,
                   coalesce((SELECT sum(total_each_quantity) FROM receiving_line_receipts r WHERE r.voided_at IS NULL AND r.disposition<>'SHORT_SHIPPED' AND r.tenant_id=item.tenant_id AND r.purchase_order_item_id=item.id),0),
                   item.discrepancy_quantity,product.requires_expiration_date
            FROM purchase_order_items item JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            JOIN receiving_sessions session ON session.tenant_id=po.tenant_id AND session.id=po.receiving_session_id
            JOIN account_catalog_items ac ON ac.tenant_id=item.tenant_id AND ac.id=item.account_catalog_item_id
            JOIN global_catalog_products product ON product.id=ac.global_product_id
            WHERE item.tenant_id=? AND po.receiving_session_id=? AND EXISTS(SELECT 1 FROM receiving_documents active_doc WHERE active_doc.tenant_id=po.tenant_id AND active_doc.id=po.receiving_document_id AND active_doc.removed_at IS NULL) AND item.id=?
              AND session.status NOT IN ('POSTED','CANCELLED')
              AND EXISTS(SELECT 1 FROM receiving_documents d WHERE d.tenant_id=po.tenant_id AND d.id=po.receiving_document_id AND d.closed_at IS NULL AND d.removed_at IS NULL) FOR UPDATE OF item
            """,rs->rs.next()?new ReceiveTarget(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getBigDecimal(4),rs.getString(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getBoolean(9)):null,
                tenantId,sessionId,itemId);
        if(target==null)throw new IllegalArgumentException("This document is closed or unavailable. Original receipts are locked; use an inventory adjustment.");return target;
    }
    private void lockInventory(UUID tenantId){
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?::text,0))",Boolean.class,tenantId)))
            throw new IllegalArgumentException("Inventory is being updated. Nothing changed; please try again in a moment.");
        jdbc.execute("SET LOCAL lock_timeout='750ms'");
    }

    private void refreshItemStatus(UUID tenantId,UUID itemId){jdbc.update("""
        UPDATE purchase_order_items item SET status=CASE
          WHEN received_quantity+discrepancy_quantity>=ordered_quantity*CASE WHEN invoice_unit='CASE' THEN units_per_case ELSE 1 END THEN 'RECEIVED'
          WHEN received_quantity>0 THEN 'PARTIALLY_RECEIVED' ELSE 'OPEN' END,updated_at=now()
        WHERE tenant_id=? AND id=?
        """,tenantId,itemId);}
    private void refreshSessionStatus(UUID tenantId,UUID sessionId){jdbc.update("""
        UPDATE receiving_sessions session SET status=CASE WHEN EXISTS (
            SELECT 1 FROM purchase_order_items item
            JOIN purchase_orders po ON po.tenant_id=item.tenant_id AND po.id=item.purchase_order_id
            WHERE item.tenant_id=session.tenant_id AND po.receiving_session_id=session.id
              AND EXISTS (SELECT 1 FROM receiving_documents d WHERE d.tenant_id=po.tenant_id
                AND d.id=po.receiving_document_id AND d.removed_at IS NULL)
              AND item.received_quantity+item.discrepancy_quantity <
                  item.ordered_quantity*CASE WHEN item.invoice_unit='CASE' THEN item.units_per_case ELSE 1 END
        ) THEN 'MATCHING' ELSE 'READY' END
        WHERE session.tenant_id=? AND session.id=? AND session.status NOT IN ('POSTED','CANCELLED')
        """,tenantId,sessionId);}
    private void upsertCredit(UUID tenantId,UUID sessionId,UUID itemId,ReceiveTarget target,String reason,
            BigDecimal quantity,String notes,String actorEmail){jdbc.update("""
        INSERT INTO vendor_credit_requests (tenant_id,receiving_session_id,purchase_order_item_id,vendor_id,
            reason,quantity,unit_cost,currency,notes,created_by)
        VALUES (?,?,?,?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))
        ON CONFLICT (tenant_id,purchase_order_item_id,reason) DO UPDATE SET
            quantity=EXCLUDED.quantity,notes=EXCLUDED.notes,status='OPEN',updated_at=now()
        """,tenantId,sessionId,itemId,target.vendorId(),reason,quantity,target.unitCost(),target.currency(),blank(notes),actorEmail);}

    private UUID saveReceipt(UUID tenantId,UUID itemId,BigDecimal cases,BigDecimal eaches,BigDecimal unitsPerCase,
            LocalDate expiration,String disposition,String notes,String actorEmail,UUID locationId){return jdbc.queryForObject("""
        INSERT INTO receiving_line_receipts (tenant_id,purchase_order_item_id,case_quantity,each_quantity,
            units_per_case,expiration_date,disposition,notes,received_by,location_id)
        VALUES (?,?,?,?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)),?)
        RETURNING id
        """,UUID.class,tenantId,itemId,cases,eaches,unitsPerCase,expiration,disposition,blank(notes),actorEmail,locationId);}

    private UUID resolveItemLocation(UUID tenantId,UUID accountItemId,UUID requested){
        UUID resolved=jdbc.query("""
            SELECT location.id FROM warehouse_locations location
            LEFT JOIN account_catalog_item_locations assignment ON assignment.tenant_id=location.tenant_id
              AND assignment.location_id=location.id AND assignment.account_catalog_item_id=?
            WHERE location.tenant_id=? AND location.status='ACTIVE'
              AND ((? IS NULL AND assignment.is_default) OR location.id=?) LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,accountItemId,tenantId,requested,requested);
        if(resolved==null)throw new IllegalArgumentException("Choose an active inventory location for this receipt.");
        jdbc.update("""
            INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
            VALUES (?,?,?,false) ON CONFLICT DO NOTHING
            """,tenantId,accountItemId,resolved);
        return resolved;
    }

    private void saveDiscrepancy(UUID tenantId,UUID sessionId,UUID itemId,String actorEmail,ReceiveTarget target,
            String reason,BigDecimal amount,String notes){
        jdbc.update("UPDATE purchase_order_items SET discrepancy_status=?,discrepancy_quantity=discrepancy_quantity+?,discrepancy_notes=?,updated_at=now() WHERE tenant_id=? AND id=?",
            reason,amount,blank(notes),tenantId,itemId);
        BigDecimal prior=jdbc.queryForObject("SELECT coalesce(sum(quantity),0) FROM vendor_credit_requests WHERE tenant_id=? AND purchase_order_item_id=? AND reason=?",BigDecimal.class,tenantId,itemId,reason);
        upsertCredit(tenantId,sessionId,itemId,target,reason,prior.add(amount),notes,actorEmail);
    }

    private void updateProductReceivingDefaults(UUID tenantId,ReceiveTarget target,BigDecimal unitsPerCase,LocalDate expiration){jdbc.update("""
        UPDATE global_catalog_products SET units_per_case=?,requires_expiration_date=requires_expiration_date OR ?
        WHERE id=(SELECT global_product_id FROM account_catalog_items WHERE tenant_id=? AND id=?)
        """,unitsPerCase,expiration!=null,tenantId,target.accountItemId());}

    private MatchedItem matchOrCreateItem(UUID tenantId, UUID vendorId, String actorEmail, String description,
            String brand,String vendorCode,String identifier,BigDecimal cost,String currency,String sourceType,String sourceReference) {
        UUID itemId = null;
        if (!vendorCode.isBlank()) itemId=jdbc.query("""
            SELECT account_catalog_item_id FROM vendor_catalog_offers
            WHERE tenant_id=? AND vendor_id=?
              AND upper(regexp_replace(vendor_item_code,'[^A-Za-z0-9]','','g'))=?
              AND effective_to IS NULL LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId,vendorId,cleanCode(vendorCode));
        if(!vendorCode.isBlank()){
            boolean created=itemId==null;
            itemId=catalog.addImportedVendorProduct(tenantId,actorEmail,vendorId,vendorCode,description,
                blank(brand),identifierType(identifier),identifier,null,false);
            if(created)catalog.saveVendorOffer(tenantId,actorEmail,itemId,vendorId,vendorCode,cost,BigDecimal.ZERO,currency,sourceType,blank(sourceReference));
            return new MatchedItem(itemId,created);
        }
        if(itemId==null&&!identifier.isBlank()) itemId=jdbc.query("""
            SELECT item.id FROM global_product_identifiers identifier
            JOIN account_catalog_items item ON item.global_product_id=identifier.global_product_id
            WHERE item.tenant_id=? AND identifier.identity_key=catalog_identifier_key(?,?) LIMIT 1
            """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId,identifierType(identifier),normalizeIdentifier(identifier));
        boolean created=itemId==null;
        if(created)itemId=catalog.addAccountProduct(tenantId,actorEmail,description,blank(brand),
            identifierType(identifier),identifier,vendorCode,false);
        if(created)
            catalog.saveVendorOffer(tenantId,actorEmail,itemId,vendorId,vendorCode,cost,BigDecimal.ZERO,currency,
                sourceType,blank(sourceReference));
        return new MatchedItem(itemId,created);
    }

    private void setTenant(UUID id){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());}
    private static BigDecimal zero(BigDecimal n){return n==null?BigDecimal.ZERO:n;}
    private static boolean whole(BigDecimal value){return value.stripTrailingZeros().scale()<=0;}
    private static String blank(String s){return s==null||s.isBlank()?null:s.trim();}
    public record UnshippedRow(String code,String product,BigDecimal ordered,BigDecimal shipped,BigDecimal unshipped,String reason){}
    public static List<UnshippedRow> unshippedRows(List<Map<String,String>> rows){
        var result=new java.util.LinkedHashMap<String,UnshippedRow>();int rowIndex=0;
        for(var row:rows){
            String shippedText=find(row,"shipquantity","shippedquantity","shippedqty");
            String missingText=find(row,"quantitynotshipped","notshippedquantity","unshippedquantity");
            if(shippedText.isBlank()&&missingText.isBlank())continue;
            BigDecimal shipped=number(shippedText),ordered=number(find(row,"orderquantity","orderedquantity","orderqty"));
            BigDecimal missing=missingText.isBlank()?ordered.subtract(shipped).max(BigDecimal.ZERO):number(missingText);
            if(missing.signum()<=0)continue;
            String code=cleanCode(find(row,"vendoritemcode","vendorsku","itemnumber","itemno","sku","shipitem"));
            String sourceLine=find(row,"line","linenumber","lineno");
            String reason=find(row,"invalidreason","reason");if(reason.isBlank())reason=find(row,"status");
            var value=new UnshippedRow(code,find(row,"description","productname","itemdescription","title","product"),ordered,shipped,missing,reason);
            // KEHE repeats the same source line as OutOfStock and Shipped. These are not two shortages.
            String key=(sourceLine.isBlank()?"row-"+(rowIndex++):"line-"+sourceLine)+"|"+code+"|"+ordered.stripTrailingZeros()+"|"+missing.stripTrailingZeros();
            result.merge(key,value,(a,b)->new UnshippedRow(a.code(),a.product(),a.ordered(),a.shipped().max(b.shipped()),a.unshipped(),
                a.shipped().signum()==0?a.reason():b.shipped().signum()==0?b.reason():a.reason()));
        }
        return List.copyOf(result.values());
    }
    private static String find(Map<String,String> row,String... names){for(String wanted:names)for(var e:row.entrySet()){String n=e.getKey().toLowerCase().replaceAll("[^a-z0-9]","");if(n.equals(wanted))return e.getValue()==null?"":e.getValue().trim();}return "";}
    private static boolean hasHeading(Map<String,String> row,String name){return row.keySet().stream().map(key->key.toLowerCase().replaceAll("[^a-z0-9]","")).anyMatch(name::equals);}
    private static BigDecimal number(String value){try{return new BigDecimal(value.replace("$","").replace(",","").trim());}catch(Exception e){return BigDecimal.ZERO;}}
    static BigDecimal packUnits(String value){
        if(value==null||value.isBlank())return BigDecimal.ONE;
        var slash=java.util.regex.Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*/").matcher(value);
        BigDecimal detected=slash.find()?number(slash.group(1)):BigDecimal.ZERO;
        if(detected.signum()==0){var direct=java.util.regex.Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*$").matcher(value);detected=direct.find()?number(direct.group(1)):BigDecimal.ZERO;}
        return detected.signum()>0&&whole(detected)?detected:BigDecimal.ONE;
    }
    private static String wholeUnits(BigDecimal value){return zero(value).setScale(0,java.math.RoundingMode.HALF_UP).toPlainString();}
    private static String displayMoney(BigDecimal value){return zero(value).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString();}
    static String cleanCode(String value){return value==null?"":value.replace("=","").replace("\"","").trim().replaceFirst("^0+(?!$)","");}
    private static String toJson(Map<String,String> row){StringBuilder b=new StringBuilder("{");for(var e:row.entrySet()){if(b.length()>1)b.append(',');b.append('"').append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append('"');}return b.append('}').toString();}
    private static String escape(String s){return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"");}
    private static String normalizeIdentifier(String value){return value==null?"":value.replaceAll("[^0-9A-Za-z]","").toUpperCase();}
    private static String identifierType(String value){int length=normalizeIdentifier(value).length();return length==12?"UPC":length==13?"EAN":"GTIN";}
    private static String first(List<Map<String,String>> rows,String... names){for(Map<String,String> row:rows){String value=find(row,names);if(!value.isBlank())return value.replace("=","").replace("\"","").trim();}return "";}
    static LocalDate parseDate(String value){if(value==null||value.isBlank())return null;for(DateTimeFormatter formatter:List.of(DateTimeFormatter.ISO_LOCAL_DATE,DateTimeFormatter.ofPattern("MM/dd/yyyy"),DateTimeFormatter.ofPattern("M/d/yyyy"))){try{return LocalDate.parse(value.trim(),formatter);}catch(DateTimeParseException ignored){}}return null;}
    private static String currency(String value){String clean=value==null?"":value.trim().toUpperCase();return clean.matches("[A-Z]{3}")?clean:"USD";}
    private record MatchedItem(UUID itemId,boolean created){}
    private record ReceiveTarget(UUID lineId,UUID accountItemId,UUID vendorId,BigDecimal unitCost,String currency,
            BigDecimal expected,BigDecimal received,BigDecimal discrepancy,boolean expirationRequired){}
}
