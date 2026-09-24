package com.nextaicommerce.platform.catalog;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class CatalogImportService {
    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);
    private static final int MAX_ROWS = 50_000;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final TypeReference<LinkedHashMap<String,String>> STRING_MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CatalogRepository catalog;
    private final CatalogImportProgress progress;

    @Autowired
    public CatalogImportService(JdbcTemplate jdbc, ObjectMapper json, CatalogRepository catalog,CatalogImportProgress progress) {
        this.jdbc = jdbc;
        this.json = json;
        this.catalog = catalog;
        this.progress=progress;
    }
    public CatalogImportService(JdbcTemplate jdbc,ObjectMapper json,CatalogRepository catalog){this(jdbc,json,catalog,null);}

    public record ImportView(UUID id, String filename, String status, String vendorName,
            List<String> headers, Map<String,String> mapping, List<Map<String,String>> samples,
            int totalRows, int validRows, int rejectedRows,BigDecimal vendorDiscountRate,List<String> validationMessages,
            List<String> ignoredHeaders,String processState,int processedRows,int progressPercent,String processError,
            int mappedSkus,int unmappedSkus) {}

    @Transactional
    public UUID stage(UUID tenantId, String actorEmail, UUID vendorId, MultipartFile file) {
        setTenant(tenantId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a catalogue file.");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("Catalogue files must be 20 MB or smaller.");
        try {
            byte[] bytes = file.getBytes();
            String fileHash=sha256(bytes);
            ExistingImport existing=jdbc.query("""
                SELECT id,status,vendor_id FROM catalog_imports WHERE tenant_id=? AND file_sha256=?
                """,rs->rs.next()?new ExistingImport(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class)):null,tenantId,fileHash);
            if(existing!=null){
                if(!vendorId.equals(existing.vendorId()))throw new IllegalArgumentException(
                    "This exact file is already staged under another vendor. Open that review or upload the correct vendor file.");
                if("COMPLETED".equals(existing.status()))throw new IllegalArgumentException(
                    "This exact catalogue file was already imported. No duplicate products were created.");
                if(!"PROCESSING".equals(existing.status()))jdbc.update(
                    "DELETE FROM catalog_import_progress WHERE tenant_id=? AND catalog_import_id=?",tenantId,existing.id());
                log.info("Catalogue upload resumed: file={} | existing review status={} | next=continue mapping and approval",
                    safeFilename(file.getOriginalFilename()),readableImportStatus(existing.status()));
                return existing.id();
            }
            ParsedSheet sheet = readFile(file.getOriginalFilename(), bytes);
            if (sheet.headers().isEmpty() || sheet.rows().isEmpty())
                throw new IllegalArgumentException("The catalogue must contain headings and at least one product row.");
            if (sheet.rows().size() > MAX_ROWS)
                throw new IllegalArgumentException("This catalogue exceeds the 50,000 row import limit.");
            UUID actorId = jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)", UUID.class, actorEmail);
            Map<String,String> autoMapping = autoMap(sheet.headers());
            String defaultDc=jdbc.queryForObject("SELECT distribution_center FROM vendors WHERE tenant_id=? AND id=?",String.class,tenantId,vendorId);
            if(defaultDc!=null&&!defaultDc.isBlank())autoMapping.put("distributionCenter",defaultDc);
            UUID importId = jdbc.queryForObject("""
                INSERT INTO catalog_imports
                    (tenant_id,vendor_id,original_filename,file_sha256,status,column_mapping,total_rows,uploaded_by)
                VALUES (?,?,?,?,'MAPPED',?::jsonb,?,?) RETURNING id
                """, UUID.class, tenantId, vendorId, safeFilename(file.getOriginalFilename()), fileHash,
                json.writeValueAsString(autoMapping), sheet.rows().size(), actorId);
            List<Object[]> batch = new ArrayList<>(sheet.rows().size());
            int rowNumber = 2;
            for (Map<String,String> row : sheet.rows()) batch.add(new Object[]{tenantId, importId,
                rowNumber++, json.writeValueAsString(row)});
            jdbc.batchUpdate("""
                INSERT INTO catalog_import_rows (tenant_id,catalog_import_id,row_number,source_data)
                VALUES (?,?,?,?::jsonb)
                """, batch);
            if(hasRequiredMappings(autoMapping))validate(tenantId,importId,autoMapping);
            String vendorName=jdbc.queryForObject("SELECT name FROM vendors WHERE tenant_id=? AND id=?",String.class,tenantId,vendorId);
            ValidationSummary validation=jdbc.query("SELECT status,valid_rows,rejected_rows FROM catalog_imports WHERE tenant_id=? AND id=?",
                rs->rs.next()?new ValidationSummary(rs.getString(1),rs.getInt(2),rs.getInt(3)):null,tenantId,importId);
            log.info("Catalogue upload read: file={} | vendor={} | products={} | columns={} | auto-mapped fields={} | validation={} (valid={}, needs attention={}) | next={}",
                safeFilename(file.getOriginalFilename()),vendorName,sheet.rows().size(),sheet.headers().size(),autoMapping.size()-1,
                "VALIDATED".equals(validation.status())?"passed":"mapping review required",validation.valid(),validation.rejected(),
                "VALIDATED".equals(validation.status())?"waiting for user approval":"waiting for user to correct mappings");
            return importId;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Catalogue upload could not be staged: file={} | reason={}",safeFilename(file.getOriginalFilename()),
                e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(),e);
            throw new IllegalArgumentException("We could not prepare this catalogue upload. The application log now shows the exact file, database, or validation reason.", e);
        }
    }

    @Transactional(readOnly = true)
    public ImportView load(UUID tenantId, UUID importId) {
        setTenant(tenantId);
        ImportBase base = jdbc.query("""
            SELECT ci.id,ci.original_filename,ci.status,ci.column_mapping::text,
                   ci.total_rows,ci.valid_rows,ci.rejected_rows,vendor.name,vendor.default_discount_rate,
                   progress.state,coalesce(progress.processed_rows,0),progress.error_message,
                   coalesce(progress.mapped_skus,0),coalesce(progress.unmapped_skus,0)
            FROM catalog_imports ci JOIN vendors vendor ON vendor.id=ci.vendor_id AND vendor.tenant_id=ci.tenant_id
            LEFT JOIN catalog_import_progress progress ON progress.tenant_id=ci.tenant_id AND progress.catalog_import_id=ci.id
            WHERE ci.tenant_id=? AND ci.id=?
            """, rs -> rs.next() ? new ImportBase(rs.getObject(1, UUID.class),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getString(8),rs.getBigDecimal(9),
                rs.getString(10),rs.getInt(11),rs.getString(12),rs.getInt(13),rs.getInt(14)) : null, tenantId, importId);
        if (base == null) throw new IllegalArgumentException("Catalogue import not found.");
        List<Map<String,String>> samples = jdbc.query("""
            SELECT source_data::text FROM catalog_import_rows
            WHERE tenant_id=? AND catalog_import_id=? ORDER BY row_number LIMIT 8
            """, (rs,row)->readMap(rs.getString(1)), tenantId, importId);
        List<String> headers = samples.isEmpty() ? List.of() : List.copyOf(samples.getFirst().keySet());
        List<String> messages=jdbc.query("""
            SELECT 'Row '||row_number||': '||coalesce(validation_messages->>0,'Review this row.')
            FROM catalog_import_rows WHERE tenant_id=? AND catalog_import_id=?
              AND validation_status IN ('REJECTED','WARNING') ORDER BY row_number LIMIT 12
            """,(rs,row)->rs.getString(1),tenantId,importId);
        Map<String,String> mapping=readMap(base.mapping());
        if(!mapping.containsKey("distributionCenter")){
            String dc=jdbc.queryForObject("SELECT vendor.distribution_center FROM catalog_imports ci JOIN vendors vendor ON vendor.id=ci.vendor_id AND vendor.tenant_id=ci.tenant_id WHERE ci.tenant_id=? AND ci.id=?",String.class,tenantId,importId);
            mapping.put("distributionCenter",dc==null?"":dc);
        }
        var mappedHeaders=new java.util.HashSet<>(mapping.values());
        List<String> ignored=headers.stream().filter(header->!mappedHeaders.contains(header)).toList();
        int percent=base.total()==0?0:Math.min(100,(int)Math.floor(base.processed()*100.0/base.total()));
        return new ImportView(base.id(),base.filename(),base.status(),base.vendorName(),headers,
            mapping,samples,base.total(),base.valid(),base.rejected(),base.discountRate(),messages,ignored,
            base.processState(),base.processed(),percent,base.processError(),base.mappedSkus(),base.unmappedSkus());
    }

    @Transactional
    public void validate(UUID tenantId,UUID importId,Map<String,String> mapping){
        setTenant(tenantId);
        require(mapping,"productName","Product name");require(mapping,"vendorItemCode","Vendor item code");require(mapping,"listCost","List cost");
        if(mapping.get("accountSku")!=null&&mapping.get("accountSku").equals(mapping.get("vendorItemCode")))
            throw new IllegalArgumentException("Account SKU and vendor item code cannot use the same spreadsheet column. Leave Account SKU unmapped when no internal SKU exists.");
        List<SourceRow> rows=jdbc.query("SELECT id,row_number,source_data::text FROM catalog_import_rows WHERE tenant_id=? AND catalog_import_id=? ORDER BY row_number",
            (rs,row)->new SourceRow(rs.getObject(1,UUID.class),rs.getInt(2),readMap(rs.getString(3))),tenantId,importId);
        if(rows.isEmpty())throw new IllegalArgumentException("This file has no product rows.");
        Map<String,Integer> itemCodes=new java.util.HashMap<>(),identifiers=new java.util.HashMap<>();
        for(SourceRow row:rows){count(itemCodes,normalizedVendorCode(value(row.data(),mapping,"vendorItemCode")));count(identifiers,normalized(value(row.data(),mapping,"identifier")));}
        List<Object[]> updates=new ArrayList<>(rows.size());int valid=0,rejected=0;
        for(SourceRow row:rows){List<String> problems=new ArrayList<>();String code=normalizedVendorCode(value(row.data(),mapping,"vendorItemCode"));String identifier=normalized(value(row.data(),mapping,"identifier"));
            if(value(row.data(),mapping,"productName").isBlank())problems.add("Product name is missing");
            if(code.isBlank()&&identifier.isBlank())problems.add("Both vendor item code and UPC / EAN are missing");
            else if(code.isBlank())problems.add("Vendor item code is missing");
            if(itemCodes.getOrDefault(code,0)>1)problems.add("Vendor item code is duplicated in this file");
            if(!identifier.isBlank()&&identifiers.getOrDefault(identifier,0)>1)problems.add("UPC / EAN is duplicated in this file");
            try{BigDecimal cost=money(value(row.data(),mapping,"listCost"));if(cost.signum()<0)problems.add("Cost cannot be negative");}catch(Exception e){problems.add("Cost is missing or invalid");}
            String status=problems.isEmpty()?"VALID":"REJECTED";if(problems.isEmpty())valid++;else rejected++;
            updates.add(new Object[]{status,writeMessages(problems),tenantId,row.id()});
        }
        jdbc.batchUpdate("UPDATE catalog_import_rows SET validation_status=?,validation_messages=?::jsonb WHERE tenant_id=? AND id=?",updates);
        jdbc.update("UPDATE catalog_imports SET status=?,column_mapping=?::jsonb,valid_rows=?,rejected_rows=? WHERE tenant_id=? AND id=?",
            rejected==0?"VALIDATED":"MAPPED",writeMap(mapping),valid,rejected,tenantId,importId);
    }

    @Transactional
    public void approve(UUID tenantId, String actorEmail, UUID importId, Map<String,String> mapping) {
        setTenant(tenantId);
        CatalogIdentity.lock(jdbc);
        require(mapping, "productName", "Product name");
        require(mapping, "vendorItemCode", "Vendor item code");
        require(mapping, "listCost", "List cost");
        ImportVendor source = jdbc.query("""
            SELECT ci.vendor_id,vendor.currency,vendor.default_discount_rate,vendor.name,vendor.vendor_code,vendor.distribution_center FROM catalog_imports ci
            JOIN vendors vendor ON vendor.tenant_id=ci.tenant_id AND vendor.id=ci.vendor_id
            WHERE ci.tenant_id=? AND ci.id=? AND ci.status='VALIDATED' FOR UPDATE
            """, rs -> rs.next() ? new ImportVendor(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getBigDecimal(3),rs.getString(4),rs.getString(5),rs.getString(6)) : null,
            tenantId, importId);
        if (source == null) throw new IllegalArgumentException("This catalogue was already processed or is unavailable.");
        String dc=CatalogIdentity.distributionCenter(mapping.getOrDefault("distributionCenter",source.distributionCenter()));
        if(!dc.equals(source.distributionCenter())&&Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM vendor_catalog_offers WHERE tenant_id=? AND vendor_id=?)",Boolean.class,tenantId,source.vendorId())))
            throw new IllegalArgumentException("This vendor already has catalogue history for another DC / branch. Add a separate vendor entry for the new branch to preserve receiving and prices.");
        jdbc.update("UPDATE vendors SET distribution_center=? WHERE tenant_id=? AND id=?",dc,tenantId,source.vendorId());
        source=new ImportVendor(source.vendorId(),source.currency(),source.discountRate(),source.vendorName(),source.vendorCode(),dc);
        List<SourceRow> rows = jdbc.query("""
            SELECT id,row_number,source_data::text FROM catalog_import_rows
            WHERE tenant_id=? AND catalog_import_id=? ORDER BY row_number
            """, (rs,row)->new SourceRow(rs.getObject(1,UUID.class),rs.getInt(2),readMap(rs.getString(3))), tenantId, importId);
        List<String> errors = new ArrayList<>();
        for (SourceRow row : rows) {
            if (value(row.data(),mapping,"productName").isBlank()) errors.add("Row "+row.number()+": product name is missing.");
            if (value(row.data(),mapping,"vendorItemCode").isBlank()) errors.add("Row "+row.number()+": vendor item code is missing.");
            try { money(value(row.data(),mapping,"listCost")); }
            catch (Exception e) { errors.add("Row "+row.number()+": list cost is not a valid number."); }
            if (errors.size() >= 20) break;
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join(" ", errors));
        jdbc.update("UPDATE catalog_imports SET status='PROCESSING',column_mapping=?::jsonb,approved_by=(SELECT id FROM app_users WHERE lower(email)=lower(?)),approved_at=now() WHERE tenant_id=? AND id=?",
            writeMap(mapping), actorEmail, tenantId, importId);
        int imported=bulkImport(tenantId,actorEmail,importId,mapping,source,rows);
        jdbc.update("UPDATE catalog_imports SET status='COMPLETED',valid_rows=?,rejected_rows=0 WHERE tenant_id=? AND id=?",
            imported, tenantId, importId);
        log.info("Catalogue import complete: vendor={} | products merged={} | global products reused when vendor item code or UPC matched | account catalogue updated",
            source.vendorName(),imported);
    }

    private int bulkImport(UUID tenantId,String actorEmail,UUID importId,Map<String,String> mapping,
            ImportVendor source,List<SourceRow> rows){
        UUID actorId=jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)",UUID.class,actorEmail);
        String identifierType=mapping.getOrDefault("identifierType","UPC").toUpperCase(Locale.ROOT);
        String vendorKey=normalized(source.vendorCode()==null?source.vendorName():source.vendorCode());
        String scope=CatalogIdentity.scope(tenantId,source.distributionCenter());
        jdbc.execute("DROP TABLE IF EXISTS catalog_import_work");
        jdbc.execute("""
            CREATE TEMP TABLE catalog_import_work(
              row_id uuid PRIMARY KEY,row_number integer,vendor_item_code text,normalized_code text,
              product_name text,brand text,identifier_type text,identifier_value text,account_sku text,
              expiration_required boolean,category text,uom text,package_size text,units_per_case numeric,
              units_of_sale numeric,effective_from date,list_cost numeric,discount_rate numeric,
              suggested_retail numeric,minimum_quantity numeric,vendor_global_id uuid,identifier_global_id uuid,
              global_id uuid,is_new boolean default false,item_id uuid,packaging_id uuid,offer_id uuid
            ) ON COMMIT DROP
            """);
        List<Object[]> batch=new ArrayList<>(rows.size());
        for(SourceRow row:rows){
            String code=value(row.data(),mapping,"vendorItemCode");
            String identifier=normalized(value(row.data(),mapping,"identifier"));
            BigDecimal discount=value(row.data(),mapping,"discountRate").isBlank()?source.discountRate():optionalMoney(value(row.data(),mapping,"discountRate"));
            batch.add(new Object[]{row.id(),row.number(),code,normalizedVendorCode(code),value(row.data(),mapping,"productName"),
                blank(value(row.data(),mapping,"brand")),identifierType,blank(identifier),blank(value(row.data(),mapping,"accountSku")),
                yes(value(row.data(),mapping,"expirationRequired")),blank(value(row.data(),mapping,"category")),
                blank(value(row.data(),mapping,"unitOfMeasure")),blank(value(row.data(),mapping,"size")),
                optionalPositive(value(row.data(),mapping,"casePack")),optionalPositive(value(row.data(),mapping,"unitOfSale")),
                optionalDate(value(row.data(),mapping,"effectiveDate")),money(value(row.data(),mapping,"listCost")),discount,
                optionalMoneyOrNull(value(row.data(),mapping,"suggestedRetail")),optionalPositive(value(row.data(),mapping,"minimumQuantity"))});
        }
        jdbc.batchUpdate("""
            INSERT INTO catalog_import_work(row_id,row_number,vendor_item_code,normalized_code,product_name,brand,
              identifier_type,identifier_value,account_sku,expiration_required,category,uom,package_size,
              units_per_case,units_of_sale,effective_from,list_cost,discount_rate,suggested_retail,minimum_quantity)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,batch);
        reportProgress(tenantId,importId,Math.max(1,rows.size()/10));
        Integer duplicateKeys=jdbc.queryForObject("""
            SELECT count(*) FROM (SELECT catalog_identifier_key(identifier_type,identifier_value)
              FROM catalog_import_work WHERE identifier_value IS NOT NULL
              GROUP BY catalog_identifier_key(identifier_type,identifier_value) HAVING count(*)>1) duplicate
            """,Integer.class);
        if(duplicateKeys!=null&&duplicateKeys>0)throw new IllegalArgumentException("Duplicate equivalent UPC / EAN product rows in this file. Review them before importing; no catalogue changes were saved.");
        jdbc.update("""
            UPDATE catalog_import_work work SET vendor_global_id=code.global_product_id
            FROM global_product_vendor_codes code WHERE code.vendor_key=? AND code.catalog_scope=? AND code.normalized_item_code=work.normalized_code
            """,vendorKey,scope);
        jdbc.update("""
            UPDATE catalog_import_work work SET identifier_global_id=identifier.global_product_id
            FROM global_product_identifiers identifier
            WHERE work.identifier_value IS NOT NULL
              AND identifier.identity_key=catalog_identifier_key(work.identifier_type,work.identifier_value)
            """);
        if(source.distributionCenter().isBlank()){
            List<String> branchConflicts=jdbc.query("""
                SELECT work.row_number,work.vendor_item_code FROM catalog_import_work work
                WHERE work.vendor_global_id IS NULL AND EXISTS (
                  SELECT 1 FROM global_product_vendor_codes code
                  WHERE code.vendor_key=? AND code.normalized_item_code=work.normalized_code AND code.catalog_scope<>?
                    AND (work.identifier_global_id IS NULL OR code.global_product_id<>work.identifier_global_id))
                ORDER BY work.row_number LIMIT 8
                """,(rs,n)->"row "+rs.getInt(1)+" (item "+rs.getString(2)+")",vendorKey,scope);
            if(!branchConflicts.isEmpty())throw new IllegalArgumentException("Enter the supplier DC / branch on this import: "+String.join(", ",branchConflicts)+" conflicts with another branch. No catalogue changes were saved.");
        }
        Integer conflicts=jdbc.queryForObject("SELECT count(*) FROM catalog_import_work WHERE vendor_global_id IS NOT NULL AND identifier_global_id IS NOT NULL AND vendor_global_id<>identifier_global_id",Integer.class);
        if(conflicts!=null&&conflicts>0)throw new IllegalArgumentException(conflicts+" rows have vendor item codes and UPC/EAN values belonging to different global products.");
        jdbc.update("UPDATE catalog_import_work SET global_id=coalesce(identifier_global_id,vendor_global_id)");
        Integer duplicateProducts=jdbc.queryForObject("SELECT count(*) FROM (SELECT global_id FROM catalog_import_work WHERE global_id IS NOT NULL GROUP BY global_id HAVING count(*)>1) repeated",Integer.class);
        if(duplicateProducts!=null&&duplicateProducts>0)throw new IllegalArgumentException("Multiple rows resolve to the same global barcode/product. Review duplicate product rows before importing; no catalogue changes were saved.");
        jdbc.update("UPDATE catalog_import_work SET global_id=gen_random_uuid(),is_new=true WHERE global_id IS NULL");
        jdbc.update("""
            INSERT INTO global_catalog_products(id,canonical_name,brand,requires_expiration_date,source_tenant_id,created_by)
            SELECT global_id,product_name,brand,expiration_required,?,? FROM catalog_import_work WHERE is_new
            """,tenantId,actorId);
        mergeIdentifiers();
        jdbc.update("""
            INSERT INTO global_product_vendor_codes(global_product_id,vendor_key,catalog_scope,vendor_name,vendor_item_code,normalized_item_code,source_tenant_id)
            SELECT global_id,?, ?,?,vendor_item_code,normalized_code,? FROM catalog_import_work
            ON CONFLICT(vendor_key,catalog_scope,normalized_item_code) DO UPDATE SET last_seen_at=now()
            """,vendorKey,scope,source.vendorName(),tenantId);
        reportProgress(tenantId,importId,rows.size()*3/10);
        jdbc.update("""
            INSERT INTO account_catalog_items(tenant_id,global_product_id,account_sku,created_by)
            SELECT ?,global_id,account_sku,? FROM catalog_import_work
            ON CONFLICT(tenant_id,global_product_id) DO UPDATE SET
              account_sku=coalesce(EXCLUDED.account_sku,account_catalog_items.account_sku),updated_at=now()
            """,tenantId,actorId);
        jdbc.update("""
            UPDATE catalog_import_work work SET item_id=item.id FROM account_catalog_items item
            WHERE item.tenant_id=? AND item.global_product_id=work.global_id
            """,tenantId);
        jdbc.update("""
            UPDATE global_catalog_products product SET category=coalesce(work.category,product.category),
              unit_of_measure=coalesce(upper(work.uom),product.unit_of_measure),
              package_size=coalesce(work.package_size,product.package_size),
              units_per_case=coalesce(work.units_per_case,product.units_per_case),updated_at=now()
            FROM catalog_import_work work WHERE product.id=work.global_id AND work.is_new
            """);
        jdbc.update("UPDATE account_catalog_items item SET preferred_vendor_id=coalesce(item.preferred_vendor_id,?),updated_at=now() FROM catalog_import_work work WHERE item.tenant_id=? AND item.id=work.item_id",source.vendorId(),tenantId);
        reportProgress(tenantId,importId,rows.size()/2);
        jdbc.update("""
            UPDATE global_product_packaging_versions version SET status='SUPERSEDED',
              effective_to=greatest(version.effective_from,coalesce(work.effective_from,current_date)),updated_at=now()
            FROM catalog_import_work work WHERE version.vendor_key=? AND version.catalog_scope=? AND version.normalized_vendor_item_code=work.normalized_code
              AND version.status='ACTIVE' AND (coalesce(version.package_size,'')<>coalesce(work.package_size,'')
                OR version.unit_of_measure<>coalesce(upper(work.uom),'EA')
                OR version.units_per_case<>coalesce(work.units_per_case,1)
                OR coalesce(version.units_of_sale,1)<>coalesce(work.units_of_sale,1))
            """,vendorKey,scope);
        jdbc.update("""
            INSERT INTO global_product_packaging_versions(global_product_id,vendor_key,catalog_scope,normalized_vendor_item_code,
              package_size,unit_of_measure,units_per_case,units_of_sale,effective_from)
            SELECT work.global_id,?,?,work.normalized_code,work.package_size,coalesce(upper(work.uom),'EA'),
              coalesce(work.units_per_case,1),work.units_of_sale,coalesce(work.effective_from,current_date)
            FROM catalog_import_work work WHERE NOT EXISTS(SELECT 1 FROM global_product_packaging_versions version
              WHERE version.vendor_key=? AND version.catalog_scope=? AND version.normalized_vendor_item_code=work.normalized_code AND version.status='ACTIVE')
            """,vendorKey,scope,vendorKey,scope);
        jdbc.update("""
            UPDATE catalog_import_work work SET packaging_id=version.id FROM global_product_packaging_versions version
            WHERE version.vendor_key=? AND version.catalog_scope=? AND version.normalized_vendor_item_code=work.normalized_code AND version.status='ACTIVE'
            """,vendorKey,scope);
        jdbc.update("""
            UPDATE vendor_catalog_offers offer SET is_default=false FROM catalog_import_work work
            WHERE offer.tenant_id=? AND offer.account_catalog_item_id=work.item_id AND offer.is_default
            """,tenantId);
        jdbc.update("""
            INSERT INTO vendor_catalog_offers(tenant_id,vendor_id,account_catalog_item_id,vendor_item_code,list_cost,
              discount_rate,currency,is_default,units_of_sale,suggested_retail,minimum_order_quantity,effective_from,packaging_version_id)
            SELECT ?,?,item_id,vendor_item_code,list_cost,discount_rate,?,true,units_of_sale,suggested_retail,
              coalesce(minimum_quantity,1),coalesce(effective_from,current_date),packaging_id FROM catalog_import_work
            ON CONFLICT(tenant_id,vendor_id,account_catalog_item_id,effective_from) DO UPDATE SET
              vendor_item_code=EXCLUDED.vendor_item_code,list_cost=EXCLUDED.list_cost,discount_rate=EXCLUDED.discount_rate,
              currency=EXCLUDED.currency,is_default=true,units_of_sale=EXCLUDED.units_of_sale,
              suggested_retail=EXCLUDED.suggested_retail,minimum_order_quantity=EXCLUDED.minimum_order_quantity,
              packaging_version_id=EXCLUDED.packaging_version_id,updated_at=now()
            """,tenantId,source.vendorId(),source.currency());
        jdbc.update("""
            UPDATE catalog_import_work work SET offer_id=offer.id FROM vendor_catalog_offers offer
            WHERE offer.tenant_id=? AND offer.vendor_id=? AND offer.account_catalog_item_id=work.item_id
              AND offer.effective_from=coalesce(work.effective_from,current_date)
            """,tenantId,source.vendorId());
        reportProgress(tenantId,importId,rows.size()*8/10);
        jdbc.update("""
            INSERT INTO vendor_cost_history(tenant_id,vendor_offer_id,source_type,source_reference,list_cost,
              discount_rate,net_unit_cost,currency,changed_by)
            SELECT ?,offer_id,'CATALOG_IMPORT',?,list_cost,discount_rate,
              list_cost*(1-discount_rate/100),?,? FROM catalog_import_work
            """,tenantId,importId.toString(),source.currency(),actorId);
        jdbc.update("""
            UPDATE catalog_import_rows row SET validation_status='IMPORTED',account_catalog_item_id=work.item_id,
              global_product_id=work.global_id FROM catalog_import_work work
            WHERE row.tenant_id=? AND row.id=work.row_id
            """,tenantId);
        reportProgress(tenantId,importId,rows.size());
        return rows.size();
    }

    // Called inside the import transaction. Lock in stable order so overlapping imports
    // cannot both elect a primary identifier for the same existing product.
    void mergeIdentifiers(){
        jdbc.queryForList("""
            SELECT product.id FROM global_catalog_products product
            WHERE product.id IN (SELECT global_id FROM catalog_import_work WHERE identifier_value IS NOT NULL)
            ORDER BY product.id FOR UPDATE
            """,UUID.class);
        // A vendor code may have been reused or linked incorrectly. Never turn a
        // different product's barcode into an alias just to bypass the unique index.
        List<String> mismatches=jdbc.query("""
            SELECT work.row_number,work.vendor_item_code
            FROM catalog_import_work work
            WHERE work.identifier_value IS NOT NULL
              AND EXISTS (SELECT 1 FROM global_product_identifiers existing
                WHERE existing.global_product_id=work.global_id AND existing.is_primary)
              AND NOT EXISTS (SELECT 1 FROM global_product_identifiers known
                WHERE known.global_product_id=work.global_id
                  AND known.identity_key=catalog_identifier_key(work.identifier_type,work.identifier_value))
            ORDER BY work.row_number LIMIT 10
            """,(rs,row)->"row "+rs.getInt(1)+" (item "+rs.getString(2)+")");
        if(!mismatches.isEmpty())throw new IllegalArgumentException(
            "Barcode differs from the existing global product: "+String.join(", ",mismatches)
            +". Review these vendor item links before retrying. No catalogue changes were saved.");
        jdbc.update("""
            INSERT INTO global_product_identifiers(global_product_id,identifier_type,identifier_value,is_primary)
            SELECT global_id,identifier_type,identifier_value,false FROM catalog_import_work WHERE identifier_value IS NOT NULL
            ON CONFLICT DO NOTHING
            """);
        jdbc.update("""
            WITH candidates AS (
              SELECT DISTINCT ON (identifier.global_product_id) identifier.id
              FROM global_product_identifiers identifier
              JOIN catalog_import_work work ON work.global_id=identifier.global_product_id
                AND identifier.identity_key=catalog_identifier_key(work.identifier_type,work.identifier_value)
              WHERE NOT EXISTS (SELECT 1 FROM global_product_identifiers existing
                WHERE existing.global_product_id=identifier.global_product_id AND existing.is_primary)
              ORDER BY identifier.global_product_id,work.row_number,identifier.id
            )
            UPDATE global_product_identifiers identifier SET is_primary=true
            FROM candidates WHERE identifier.id=candidates.id
            """);
    }

    private void reportProgress(UUID tenantId,UUID importId,int rows){if(progress!=null)progress.processing(tenantId,importId,rows);}
    private static String blank(String value){return value==null||value.isBlank()?null:value.trim();}
    private static String normalizedVendorCode(String value){String result=normalized(value);return result.matches("\\d+")?result.replaceFirst("^0+(?!$)",""):result;}

    public ParsedSheet readFile(String filename, byte[] bytes) throws Exception {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) return parsePdf(bytes);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls")
            ? parseXlsx(bytes) : parseDelimited(bytes, lower.endsWith(".tsv") ? '\t' : ',');
    }

    private ParsedSheet parsePdf(byte[] bytes) throws Exception {
        try (var document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            ParsedSheet kehe=parseKehePdf(text);
            if(kehe!=null)return kehe;
            ParsedSheet outerAisle=parseOuterAislePdf(text);
            if(outerAisle!=null)return outerAisle;
            List<String> lines = text.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
            for (int h = 0; h < lines.size(); h++) {
                List<String> headers = splitColumns(lines.get(h));
                String normalized = String.join("", headers).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (headers.size() >= 3 && normalized.matches(".*(item|sku|upc).*.*(qty|quantity|shipped).*.*(cost|price|amount).*")) {
                    List<Map<String,String>> rows = new ArrayList<>();
                    for (int r = h + 1; r < lines.size(); r++) {
                        List<String> values = splitColumns(lines.get(r));
                        if (values.size() < 2) continue;
                        LinkedHashMap<String,String> row = new LinkedHashMap<>();
                        for (int c = 0; c < headers.size(); c++) row.put(headers.get(c), c < values.size() ? values.get(c) : "");
                        rows.add(row);
                    }
                    if (!rows.isEmpty()) return new ParsedSheet(headers, rows);
                }
            }
            var pattern = java.util.regex.Pattern.compile("^([A-Za-z0-9-]{3,})\\s+(.+?)\\s+(\\d+(?:\\.\\d+)?)\\s+\\$?([\\d,]+(?:\\.\\d+)?)$");
            List<Map<String,String>> rows = new ArrayList<>();
            for (String line : lines) {
                var match = pattern.matcher(line);
                if (match.matches()) rows.add(new LinkedHashMap<>(Map.of("Item Code",match.group(1),
                    "Description",match.group(2),"Quantity",match.group(3),"Unit Cost",match.group(4))));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("This PDF does not contain a readable item table. Export it as XLSX/CSV or use a text-based PDF.");
            return new ParsedSheet(List.of("Item Code","Description","Quantity","Unit Cost"), rows);
        }
    }

    private static ParsedSheet parseKehePdf(String text) {
        if(!text.toUpperCase(Locale.ROOT).contains("KEHE DISTRIBUTORS")||!text.contains("LINE ITEM NO"))return null;
        String invoiceNumber=match(text,"STOP INVOICE #[\\s\\S]{0,260}?\\b(\\d{8,})\\b");
        String invoiceDate=match(text,"BILLING DATE[\\s\\S]{0,220}?\\b(\\d{1,2}/\\d{1,2}/\\d{2,4})\\b");
        var rowPattern=java.util.regex.Pattern.compile("^\\s*(\\d+)\\s+(\\d{7})\\s+(.+?)\\s+(\\d{12,13})\\s+(\\d*\\.\\d{3})\\s+([\\d,]+\\.\\d{2})\\s+(?:[A-Z]{1,2}\\s+)?(\\d+)%\\s+([\\d,]+\\.\\d{2})\\s+(.+?)\\s+(SHIPPED AS ORDERED|SHIPPED PARTIAL).*$");
        List<Map<String,String>> rows=new ArrayList<>();
        for(String line:text.lines().toList()){
            var rowMatch=rowPattern.matcher(line);
            if(!rowMatch.matches())continue;
            QuantityPair quantities=pdfQuantities(rowMatch.group(9),rowMatch.group(10));
            if(quantities==null||quantities.shipped().signum()<=0)continue;
            String sizeAndDescription=rowMatch.group(3).trim();
            String description=sizeAndDescription.replaceFirst("^\\.?\\d+(?:\\.\\d+)?\\s+(?:OZ|GM|EA|PC|FO|VC)\\s+","").trim();
            BigDecimal extended=money(rowMatch.group(8));
            BigDecimal netEach=extended.divide(quantities.shipped(),4,java.math.RoundingMode.HALF_UP);
            LinkedHashMap<String,String> row=new LinkedHashMap<>();
            row.put("Line",rowMatch.group(1));row.put("Status",rowMatch.group(10));
            row.put("OrderQuantity",quantities.ordered().stripTrailingZeros().toPlainString());
            row.put("ShipQuantity",quantities.shipped().stripTrailingZeros().toPlainString());
            row.put("ShipItem",rowMatch.group(2));row.put("PackSize","1");
            row.put("Description",description);row.put("Upc",rowMatch.group(4));
            row.put("NetEach",netEach.stripTrailingZeros().toPlainString());
            row.put("InvoiceNumber",invoiceNumber);row.put("InvoiceDate",invoiceDate);
            rows.add(row);
        }
        return rows.isEmpty()?null:new ParsedSheet(List.copyOf(rows.getFirst().keySet()),rows);
    }

    static ParsedSheet parseOuterAislePdf(String text) {
        String upper=text.toUpperCase(Locale.ROOT);
        if(!upper.contains("OUTER AISLE GOURMET")||!upper.contains("SALES ORDER"))return null;
        String invoiceNumber=match(text,"Sales Order[^A-Z0-9]*(SO\\d+)");
        String invoiceDate=match(text,"(?m)^.*?\\b(\\d{1,2}/\\d{1,2}/\\d{4})\\s+\\$[\\d,]+\\.\\d{2}\\s*$");
        var rowPattern=java.util.regex.Pattern.compile("(?m)^(\\d{5})-US\\s+-\\s+(.+?\\b(\\d+)\\s+packs?\\s+of\\s+\\d+)\\s+(\\d+)\\s+\\$([\\d,]+\\.\\d{2})\\s+\\$[\\d,]+\\.\\d{2}\\s*$");
        List<Map<String,String>> rows=new ArrayList<>();
        var matcher=rowPattern.matcher(text);
        while(matcher.find()){
            LinkedHashMap<String,String> row=new LinkedHashMap<>();
            row.put("VendorItemCode",matcher.group(1));
            row.put("Description",matcher.group(2).replaceFirst("^[A-Z]{2,4}\\s+-\\s+","").trim());
            row.put("Brand","Outer Aisle Gourmet");
            row.put("Quantity",matcher.group(4));
            row.put("CasePack",matcher.group(3));
            row.put("UnitPrice",matcher.group(5).replace(",",""));
            row.put("PriceBasis","CASE");
            row.put("InvoiceNumber",invoiceNumber);
            row.put("InvoiceDate",invoiceDate);
            rows.add(row);
        }
        return rows.isEmpty()?null:new ParsedSheet(List.copyOf(rows.getFirst().keySet()),rows);
    }

    private static QuantityPair pdfQuantities(String value,String result) {
        String clean=value.replace(",","").trim();
        var tail=java.util.regex.Pattern.compile("^\\d+\\.\\d{2}\\s*(.*)$").matcher(clean);
        if(!tail.matches())return null;
        String quantityText=tail.group(1).trim();
        if(quantityText.contains(" ")){
            String[] parts=quantityText.split("\\s+");
            if(parts.length>=2)return new QuantityPair(money(parts[parts.length-2]),money(parts[parts.length-1]));
        }
        String digits=quantityText.replaceAll("\\D","");
        if(digits.length()<2)return null;
        if("SHIPPED AS ORDERED".equals(result)&&digits.length()%2==0){
            String left=digits.substring(0,digits.length()/2),right=digits.substring(digits.length()/2);
            if(left.equals(right))return new QuantityPair(money(left),money(right));
        }
        QuantityPair best=null;int bestDistance=Integer.MAX_VALUE;
        for(int split=1;split<digits.length();split++){
            BigDecimal ordered=money(digits.substring(0,split)),shipped=money(digits.substring(split));
            if(ordered.signum()>0&&shipped.signum()>0&&ordered.compareTo(shipped)>=0){
                int distance=Math.abs(split-(digits.length()-split));
                if(distance<bestDistance){best=new QuantityPair(ordered,shipped);bestDistance=distance;}
            }
        }
        return best;
    }

    private static String match(String text,String regex){var match=java.util.regex.Pattern.compile(regex).matcher(text);return match.find()?match.group(1):"";}

    private static List<String> splitColumns(String line) {
        return java.util.Arrays.stream(line.split("\\s{2,}|\\t"))
            .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private ParsedSheet parseXlsx(byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0); var formatter = new DataFormatter(Locale.US);
            if (sheet.getPhysicalNumberOfRows()==0) return new ParsedSheet(List.of(),List.of());
            var headerRow=sheet.getRow(sheet.getFirstRowNum());
            List<String> headers=new ArrayList<>();
            for(int c=0;c<headerRow.getLastCellNum();c++) headers.add(uniqueHeader(headers,formatter.formatCellValue(headerRow.getCell(c)),c));
            List<Map<String,String>> rows=new ArrayList<>();
            for(int r=sheet.getFirstRowNum()+1;r<=sheet.getLastRowNum();r++) {
                var source=sheet.getRow(r); if(source==null) continue; LinkedHashMap<String,String> row=new LinkedHashMap<>(); boolean any=false;
                for(int c=0;c<headers.size();c++){String value=formatter.formatCellValue(source.getCell(c)).trim();row.put(headers.get(c),value);any|=!value.isBlank();}
                if(any) rows.add(row);
            }
            return new ParsedSheet(headers,rows);
        }
    }

    private ParsedSheet parseDelimited(byte[] bytes, char delimiter) {
        List<List<String>> lines=parseRecords(new String(bytes,StandardCharsets.UTF_8),delimiter);
        if(lines.isEmpty()) return new ParsedSheet(List.of(),List.of());
        List<String> headers=new ArrayList<>(); for(int i=0;i<lines.getFirst().size();i++) headers.add(uniqueHeader(headers,lines.getFirst().get(i),i));
        List<Map<String,String>> rows=new ArrayList<>();
        for(int r=1;r<lines.size();r++){LinkedHashMap<String,String> row=new LinkedHashMap<>();boolean any=false;for(int c=0;c<headers.size();c++){String value=c<lines.get(r).size()?lines.get(r).get(c).trim():"";row.put(headers.get(c),value);any|=!value.isBlank();}if(any)rows.add(row);}
        return new ParsedSheet(headers,rows);
    }

    private static List<List<String>> parseRecords(String text,char delimiter){List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(ch=='"'){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){value.append('"');i++;}else quoted=!quoted;}else if(ch==delimiter&&!quoted){row.add(value.toString());value.setLength(0);}else if((ch=='\n'||ch=='\r')&&!quoted){if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.add(value.toString());value.setLength(0);if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);row=new ArrayList<>();}else value.append(ch);}row.add(value.toString());if(row.stream().anyMatch(v->!v.isBlank()))rows.add(row);return rows;}
    private static String uniqueHeader(List<String> existing,String value,int index){String base=value==null||value.isBlank()?"Column "+(index+1):value.trim();String result=base;int n=2;while(existing.contains(result))result=base+" ("+n+++ ")";return result;}
    private static Map<String,String> autoMap(List<String> headers){Map<String,String> map=new LinkedHashMap<>();for(String h:headers){String n=h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");if(n.matches(".*(productname|itemdescription|description|title).*"))map.putIfAbsent("productName",h);if(n.matches(".*(upc|ean|gtin|barcode).*"))map.putIfAbsent("identifier",h);if(n.matches(".*brand.*"))map.putIfAbsent("brand",h);if(n.matches("(item|vendorsku|vendoritem|vendoritemcode|itemcode|itemnumber|itemno)"))map.putIfAbsent("vendorItemCode",h);if(n.matches(".*(accountsku|internalsku|sku).*"))map.putIfAbsent("accountSku",h);if(n.matches("(wholesale|unitcost|listcost|price|cost)"))map.putIfAbsent("listCost",h);if(n.matches(".*discount.*"))map.putIfAbsent("discountRate",h);if(n.equals("size"))map.putIfAbsent("size",h);if(n.matches("(unitofmeasure|uom)"))map.putIfAbsent("unitOfMeasure",h);if(n.matches("(casepack|packsize)"))map.putIfAbsent("casePack",h);if(n.matches("(unitofsale|unitssold)"))map.putIfAbsent("unitOfSale",h);if(n.matches("(suggestedretail|msrp|retailprice)"))map.putIfAbsent("suggestedRetail",h);if(n.equals("category"))map.putIfAbsent("category",h);if(n.matches("(effectivedate|priceeffectivedate)"))map.putIfAbsent("effectiveDate",h);if(n.matches("(minquantity|minimumquantity|moq)"))map.putIfAbsent("minimumQuantity",h);}map.put("identifierType","UPC");return map;}
    private static void require(Map<String,String> map,String key,String label){if(map.get(key)==null||map.get(key).isBlank())throw new IllegalArgumentException("Map the “"+label+"” field before importing.");}
    private static boolean hasRequiredMappings(Map<String,String> map){return java.util.stream.Stream.of("productName","vendorItemCode","listCost").allMatch(key->map.get(key)!=null&&!map.get(key).isBlank());}
    private static String value(Map<String,String> row,Map<String,String> mapping,String key){String header=mapping.get(key);return header==null?"":row.getOrDefault(header,"").trim();}
    private static BigDecimal money(String value){return new BigDecimal(value.replace("$","").replace(",","").trim());}
    private static BigDecimal optionalMoney(String value){return value==null||value.isBlank()?BigDecimal.ZERO:money(value);}
    private static BigDecimal optionalMoneyOrNull(String value){return value==null||value.isBlank()?null:money(value);}
    private static BigDecimal optionalPositive(String value){BigDecimal result=optionalMoneyOrNull(value);return result!=null&&result.signum()>0?result:null;}
    private static LocalDate optionalDate(String value){if(value==null||value.isBlank())return null;for(var formatter:List.of(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy"),java.time.format.DateTimeFormatter.ofPattern("M/d/yy"))){try{return LocalDate.parse(value.trim(),formatter);}catch(java.time.format.DateTimeParseException ignored){}}return null;}
    private static boolean yes(String value){return Set.of("yes","true","y","1","required").contains(value.toLowerCase(Locale.ROOT));}
    private static String normalized(String value){return value==null?"":value.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);}
    private static void count(Map<String,Integer> values,String value){if(!value.isBlank())values.merge(value,1,Integer::sum);}
    private String writeMessages(List<String> values){try{return json.writeValueAsString(values);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,String> readMap(String value){try{return json.readValue(value,STRING_MAP);}catch(Exception e){throw new IllegalStateException("Stored catalogue data is invalid.",e);}}
    private String writeMap(Map<String,String> value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Column mapping is invalid.",e);}}
    private static String safeFilename(String value){if(value==null||value.isBlank())return "catalogue";return value.replaceAll("[\\\\/]","_").substring(0,Math.min(500,value.length()));}
    private static String sha256(byte[] value)throws Exception{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static String readableImportStatus(String status){return switch(status){case "VALIDATED"->"validated and waiting for approval";case "MAPPED"->"mapping review needed";case "PROCESSING"->"import in progress";default->status.toLowerCase(Locale.ROOT).replace('_',' ');};}
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",String.class,tenantId.toString());}
    public record ParsedSheet(List<String> headers,List<Map<String,String>> rows){}
    private record QuantityPair(BigDecimal ordered,BigDecimal shipped){}
    private record ImportBase(UUID id,String filename,String status,String mapping,int total,int valid,int rejected,String vendorName,BigDecimal discountRate,String processState,int processed,String processError,int mappedSkus,int unmappedSkus){}
    private record ImportVendor(UUID vendorId,String currency,BigDecimal discountRate,String vendorName,String vendorCode,String distributionCenter){}
    private record SourceRow(UUID id,int number,Map<String,String> data){}
    private record ValidationSummary(String status,int valid,int rejected){}
    private record ExistingImport(UUID id,String status,UUID vendorId){}
}
