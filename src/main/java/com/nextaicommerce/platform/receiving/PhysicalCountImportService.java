package com.nextaicommerce.platform.receiving;

import com.nextaicommerce.platform.catalog.CatalogImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Stages and applies physical counts to the internal ledger; it never publishes marketplace stock. */
@Service
public class PhysicalCountImportService {
    private static final int MAX_ROWS=50_000;
    private static final long MAX_BYTES=20L*1024*1024;
    private static final TypeReference<List<String>> STRING_LIST=new TypeReference<>(){};
    private static final TypeReference<LinkedHashMap<String,String>> STRING_MAP=new TypeReference<>(){};
    private final InventoryRepository inventory;
    private final CatalogImportService files;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PhysicalCountImportService(InventoryRepository inventory,CatalogImportService files,JdbcTemplate jdbc,ObjectMapper json){
        this.inventory=inventory;this.files=files;this.jdbc=jdbc;this.json=json;
    }

    public record ImportView(UUID id,String filename,String status,List<String> headers,Map<String,String> mapping,
            List<Map<String,String>> samples,int totalRows,int appliedRows,UUID vendorId){}
    public record ImportHistory(UUID id,String filename,String state,int totalRows,int appliedRows,String vendor,
            String uploadedBy,Instant uploadedAt,Instant appliedAt,String error){
        private static final DateTimeFormatter DISPLAY=DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a");
        public String uploadedDisplay(){return DISPLAY.format(uploadedAt.atZone(ZoneId.systemDefault()));}
        public String appliedDisplay(){return appliedAt==null?"Not applied":DISPLAY.format(appliedAt.atZone(ZoneId.systemDefault()));}
    }
    public record UploadedRow(int rowNumber,Map<String,String> values){}
    public record UploadedRowPage(List<UploadedRow> rows,long total,int page,int size){
        public int totalPages(){return total==0?1:(int)Math.ceil((double)total/size);}
        public boolean hasPrevious(){return page>0;} public boolean hasNext(){return page+1<totalPages();}
    }
    private record ImportBase(UUID id,String filename,String status,String headers,String mapping,int totalRows,int appliedRows,UUID vendorId){}

    @Transactional
    public UUID stage(UUID tenantId,String actorEmail,UUID vendorId,MultipartFile file){
        setTenant(tenantId);
        if(file==null||file.isEmpty())throw new IllegalArgumentException("Choose a physical-count file.");
        if(file.getSize()>MAX_BYTES)throw new IllegalArgumentException("Physical-count files must be 20 MB or smaller.");
        String filename=safeFilename(file.getOriginalFilename()),lower=filename.toLowerCase(Locale.ROOT);
        if(!lower.endsWith(".csv")&&!lower.endsWith(".tsv")&&!lower.endsWith(".xls")&&!lower.endsWith(".xlsx"))
            throw new IllegalArgumentException("Use a CSV, TSV, XLS, or XLSX physical-count file.");
        try{
            var sheet=files.readFile(filename,file.getBytes());
            if(sheet.headers().isEmpty()||sheet.rows().isEmpty())throw new IllegalArgumentException("The physical-count file needs headings and at least one product row.");
            if(sheet.rows().size()>MAX_ROWS)throw new IllegalArgumentException("This physical count exceeds the 50,000 row limit.");
            Map<String,String> mapping=autoMap(sheet.headers());
            UUID actorId=actorId(actorEmail);
            UUID importId=jdbc.queryForObject("""
                INSERT INTO physical_count_imports
                  (tenant_id,vendor_id,original_filename,headers,column_mapping,total_rows,uploaded_by)
                VALUES (?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id
                """,UUID.class,tenantId,vendorId,filename,json.writeValueAsString(sheet.headers()),
                json.writeValueAsString(mapping),sheet.rows().size(),actorId);
            List<Object[]> batch=new ArrayList<>(sheet.rows().size());int rowNumber=2;
            for(var row:sheet.rows())batch.add(new Object[]{tenantId,importId,rowNumber++,json.writeValueAsString(row)});
            jdbc.batchUpdate("""
                INSERT INTO physical_count_import_rows(tenant_id,physical_count_import_id,row_number,source_data)
                VALUES (?,?,?,?::jsonb)
                """,batch);
            return importId;
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("We could not prepare this physical-count upload. Nothing was changed.",e);}
    }

    @Transactional(readOnly=true)
    public ImportView load(UUID tenantId,UUID importId){
        setTenant(tenantId);
        ImportBase base=jdbc.query("""
            SELECT id,original_filename,status,headers::text,column_mapping::text,total_rows,applied_rows,vendor_id
            FROM physical_count_imports WHERE tenant_id=? AND id=?
            """,rs->rs.next()?new ImportBase(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getObject(8,UUID.class)):null,tenantId,importId);
        if(base==null)throw new IllegalArgumentException("Physical-count review was not found.");
        try{
            List<Map<String,String>> samples=jdbc.query("""
                SELECT source_data::text FROM physical_count_import_rows
                WHERE tenant_id=? AND physical_count_import_id=? ORDER BY row_number LIMIT 5
                """,(rs,row)->json.readValue(rs.getString(1),STRING_MAP),tenantId,importId);
            return new ImportView(base.id(),base.filename(),base.status(),json.readValue(base.headers(),STRING_LIST),
                json.readValue(base.mapping(),STRING_MAP),samples,base.totalRows(),base.appliedRows(),base.vendorId());
        }catch(Exception e){throw new IllegalArgumentException("Physical-count review could not be loaded.",e);}
    }

    @Transactional(readOnly=true)
    public List<ImportHistory> history(UUID tenantId){
        setTenant(tenantId);
        return jdbc.query("""
            SELECT physical_count.id,physical_count.original_filename,
                   coalesce(progress.state,physical_count.status),physical_count.total_rows,physical_count.applied_rows,
                   vendor.name,uploader.email,physical_count.created_at,physical_count.applied_at,progress.error_message
            FROM physical_count_imports physical_count
            LEFT JOIN physical_count_import_progress progress ON progress.tenant_id=physical_count.tenant_id
              AND progress.physical_count_import_id=physical_count.id
            LEFT JOIN vendors vendor ON vendor.tenant_id=physical_count.tenant_id AND vendor.id=physical_count.vendor_id
            JOIN app_users uploader ON uploader.id=physical_count.uploaded_by
            WHERE physical_count.tenant_id=? ORDER BY physical_count.created_at DESC LIMIT 50
            """,(rs,row)->new ImportHistory(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),
                rs.getInt(5),rs.getString(6),rs.getString(7),rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9)==null?null:rs.getTimestamp(9).toInstant(),rs.getString(10)),tenantId);
    }

    @Transactional(readOnly=true)
    public UploadedRowPage uploadedRows(UUID tenantId,UUID importId,String search,int requestedPage,int requestedSize){
        setTenant(tenantId);String query=search==null?"":search.trim();String pattern="%"+query+"%";
        int size=Math.max(10,Math.min(requestedSize,200));
        Long count=jdbc.queryForObject("SELECT count(*) FROM physical_count_import_rows WHERE tenant_id=? AND physical_count_import_id=? AND (?='' OR source_data::text ILIKE ?)",
            Long.class,tenantId,importId,query,pattern);long total=count==null?0:count;int page=Math.max(0,requestedPage);
        if(total>0&&(long)page*size>=total)page=(int)((total-1)/size);
        List<UploadedRow> rows=jdbc.query("""
            SELECT row_number,source_data::text FROM physical_count_import_rows
            WHERE tenant_id=? AND physical_count_import_id=? AND (?='' OR source_data::text ILIKE ?)
            ORDER BY row_number LIMIT ? OFFSET ?
            """,(rs,row)->{try{return new UploadedRow(rs.getInt(1),json.readValue(rs.getString(2),STRING_MAP));}
                catch(Exception e){throw new IllegalArgumentException("An uploaded count row could not be read.",e);}},
            tenantId,importId,query,pattern,size,(long)page*size);
        return new UploadedRowPage(rows,total,page,size);
    }

    @Transactional
    public int apply(UUID tenantId,String actorEmail,UUID importId,UUID vendorId,Map<String,String> mapping){
        return apply(tenantId,actorEmail,importId,vendorId,mapping,(percent,phase)->{});
    }

    @Transactional
    public void saveSelection(UUID tenantId,UUID importId,UUID vendorId,Map<String,String> mapping){
        setTenant(tenantId);
        int changed=jdbc.update("UPDATE physical_count_imports SET vendor_id=?,column_mapping=?::jsonb,updated_at=now() WHERE tenant_id=? AND id=? AND status<>'APPLIED'",
            vendorId,json.writeValueAsString(mapping),tenantId,importId);
        if(changed==0)throw new IllegalArgumentException("This physical-count review is no longer available.");
    }

    @Transactional
    public int apply(UUID tenantId,String actorEmail,UUID importId,UUID vendorId,Map<String,String> mapping,
            java.util.function.BiConsumer<Integer,String> progress){
        setTenant(tenantId);
        String status=jdbc.query("SELECT status FROM physical_count_imports WHERE tenant_id=? AND id=? FOR UPDATE",
            rs->rs.next()?rs.getString(1):null,tenantId,importId);
        if(status==null)throw new IllegalArgumentException("Physical-count review was not found.");
        if("APPLIED".equals(status))throw new IllegalArgumentException("This physical count was already applied.");
        require(mapping,"itemCode","Choose the item code, UPC, or SKU column.");
        require(mapping,"quantity","Choose the counted quantity column.");
        progress.accept(18,"Validating every uploaded row");
        List<RowSource> sources=jdbc.query("""
            SELECT row_number,source_data::text FROM physical_count_import_rows
            WHERE tenant_id=? AND physical_count_import_id=? ORDER BY row_number
            """,(rs,row)->new RowSource(rs.getInt(1),rs.getString(2)),tenantId,importId);
        List<InventoryRepository.PhysicalCountRow> counts=new ArrayList<>(sources.size());
        try{
            for(RowSource source:sources){
                Map<String,String> row=json.readValue(source.json(),STRING_MAP);
                String code=value(row,mapping.get("itemCode")),amount=value(row,mapping.get("quantity"));
                String date=value(row,mapping.get("expiration")),location=value(row,mapping.get("location"));
                if(code.isBlank()&&amount.isBlank())continue;
                if(code.isBlank())throw new IllegalArgumentException("Row "+source.number()+" needs an item code, UPC, or SKU.");
                BigDecimal quantity;
                try{quantity=new BigDecimal(amount.replace(",","").trim());}catch(Exception e){throw new IllegalArgumentException("Row "+source.number()+" has an invalid quantity.");}
                if(quantity.signum()<0||quantity.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Row "+source.number()+" needs a whole-number count of zero or more.");
                LocalDate expiration=null;
                if(!date.isBlank())try{expiration=parseDate(date);}catch(Exception e){throw new IllegalArgumentException("Row "+source.number()+" has an invalid expiration date. Use YYYY-MM-DD, MM/DD/YYYY, or an Excel date cell.");}
                counts.add(new InventoryRepository.PhysicalCountRow(source.number(),code,quantity,expiration,location));
            }
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("The uploaded rows could not be read. Nothing was changed.",e);}
        if(counts.isEmpty())throw new IllegalArgumentException("The physical-count file has no usable rows.");
        progress.accept(48,"Matching catalogue products and locations");
        int applied;
        try{progress.accept(68,"Reconciling expiration batches and quantities");applied=inventory.reconcilePhysicalCountSnapshot(tenantId,actorEmail,importId,vendorId,counts,"REPLACE_PRODUCTS".equals(mapping.get("scope")));}
        catch(IllegalArgumentException e){
            String locationColumn=mapping.getOrDefault("location","");
            if(!locationColumn.isBlank()&&e.getMessage()!=null&&e.getMessage().contains("uses location"))
                throw new IllegalArgumentException(e.getMessage()+" Read from uploaded column “"+locationColumn+"”.",e);
            throw e;
        }
        jdbc.update("""
            UPDATE physical_count_imports SET status='APPLIED',vendor_id=?,column_mapping=?::jsonb,
              applied_rows=?,applied_by=?,applied_at=now(),updated_at=now() WHERE tenant_id=? AND id=?
            """,vendorId,json.writeValueAsString(mapping),applied,actorId(actorEmail),tenantId,importId);
        progress.accept(94,"Finalizing the inventory ledger");
        return applied;
    }

    /** Compatibility entry point for callers that deliberately want immediate application. */
    @Transactional
    public int importCount(UUID tenantId,String actorEmail,UUID vendorId,MultipartFile file){
        UUID id=stage(tenantId,actorEmail,vendorId,file);ImportView view=load(tenantId,id);
        return apply(tenantId,actorEmail,id,vendorId,view.mapping());
    }

    private record RowSource(int number,String json){}
    private UUID actorId(String email){return jdbc.queryForObject("SELECT id FROM app_users WHERE lower(email)=lower(?)",UUID.class,email);}
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",String.class,tenantId.toString());}
    private static void require(Map<String,String> mapping,String key,String message){if(mapping.getOrDefault(key,"").isBlank())throw new IllegalArgumentException(message);}
    private static String value(Map<String,String> row,String header){if(header==null||header.isBlank())return "";return row.getOrDefault(header,"").trim();}
    private static Map<String,String> autoMap(List<String> headers){
        Map<String,String> result=new LinkedHashMap<>();
        detect(headers,result,"itemCode","itemcode","vendoritemcode","item","sku","upc","ean","barcode");
        detect(headers,result,"quantity","quantity","qty","count","physicalcount","onhand","qtyinhand","quantityinhand","inventoryquantity");
        detect(headers,result,"expiration","expirationdate","expiredate","expirydate","expiration","expire","expiry","bestby","bestbefore");
        detect(headers,result,"location","location","locationcode","bin","bincode","shelf","warehouselocation",
            "defloc","defaultloc","defaultlocation");
        return result;
    }
    private static void detect(List<String> headers,Map<String,String> target,String field,String...aliases){
        for(String header:headers){String normalized=key(header);for(String alias:aliases)if(normalized.equals(alias)){target.put(field,header);return;}}
    }
    private static String key(String value){return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private static LocalDate parseDate(String value){
        String clean=value.trim();
        for(DateTimeFormatter format:List.of(DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("M/d/uuuu"),DateTimeFormatter.ofPattern("M-d-uuuu"))){
            try{return LocalDate.parse(clean,format);}catch(DateTimeParseException ignored){}
        }
        if(clean.matches("\\d+(\\.0+)?")){
            long serial=(long)Double.parseDouble(clean);
            if(serial>0&&serial<100000)return LocalDate.of(1899,12,30).plusDays(serial);
        }
        throw new DateTimeParseException("Unsupported date",clean,0);
    }
    private static String safeFilename(String value){String name=value==null?"physical-count":value.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).trim();return name.isBlank()?"physical-count":name.substring(0,Math.min(500,name.length()));}
}
