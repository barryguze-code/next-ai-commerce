package com.nextaicommerce.platform.receiving;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Document lifecycle and immutable receipt corrections. All stock writers share the order allocator lock. */
@Repository
public class ReceivingWorkflowRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate sql;
    private final ReceivingRepository receiving;
    private final InventoryRepository inventory;
    public ReceivingWorkflowRepository(JdbcTemplate jdbc,ReceivingRepository receiving,InventoryRepository inventory){
        this.jdbc=jdbc;this.sql=new NamedParameterJdbcTemplate(jdbc);this.receiving=receiving;this.inventory=inventory;
    }
    public record Document(UUID id,UUID sessionId,String vendor,String type,String number,String filename,
            LocalDate date,Instant uploadedAt,String currency,BigDecimal expected,BigDecimal received,
            BigDecimal outstanding,boolean closed,boolean partial,boolean hasHistory,String closeReason){
        public String label(){return ("INVOICE".equals(type)?"Invoice":"Packing list")+" · "+(number==null||number.isBlank()?filename:number);}
        public String progress(){return closed?(partial?"Closed · Partial":"Closed · Received"):
            received.signum()==0?"Not received":outstanding.signum()>0?"Partially received":"Fully received";}
    }
    public record WorkLine(UUID id,UUID documentId,UUID sessionId,UUID productId,String product,String code,
            BigDecimal expected,BigDecimal received,BigDecimal remaining,BigDecimal unitsPerCase,
            BigDecimal unitCost,String currency,boolean requiresExpiration,boolean closed,int receipts,
            BigDecimal depositFee,BigDecimal otherFee,UUID locationId){}
    public record Receipt(UUID id,BigDecimal quantity,LocalDate expiration,UUID locationId,String location,
            String disposition,Instant receivedAt,boolean undone,String blockedReason,BigDecimal onHand,BigDecimal reserved){
        public boolean canUndo(){return !undone&&blockedReason==null;}
    }
    public record DocumentPage(List<Document> items,long total,int page,int size){
        public int pages(){return Math.max(1,(int)((total+size-1)/size));}
        public boolean hasPrevious(){return page>0;}
        public boolean hasNext(){return page+1<pages();}
    }
    private void tenant(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private void lock(UUID tenant){
        Boolean acquired=jdbc.query("SELECT set_config('app.tenant_id',?,true),set_config('lock_timeout','750ms',true),pg_try_advisory_xact_lock(hashtextextended(?::text,0))",
            rs->rs.next()&&rs.getBoolean(3),tenant.toString(),tenant);
        if(!Boolean.TRUE.equals(acquired))
            throw new IllegalArgumentException("Inventory is being updated by another operation. Nothing changed; please try again in a moment.");
    }
    private static final String DOCUMENTS="""
        SELECT d.id,d.receiving_session_id,v.name,d.document_type,d.document_number,d.original_filename,
          d.document_date,d.created_at,coalesce(d.currency,s.currency),coalesce(t.expected,0),coalesce(t.received,0),
          coalesce(t.outstanding,0),d.closed_at IS NOT NULL OR s.status='POSTED',
          d.closed_partial OR (s.status='POSTED' AND coalesce(t.received,0)<coalesce(t.expected,0)),
          EXISTS(SELECT 1 FROM receiving_line_receipts r JOIN purchase_order_items i
            ON i.tenant_id=r.tenant_id AND i.id=r.purchase_order_item_id JOIN purchase_orders p
            ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
            WHERE r.tenant_id=d.tenant_id AND p.receiving_document_id=d.id)
          OR EXISTS(SELECT 1 FROM vendor_credit_requests c JOIN purchase_order_items i
            ON i.tenant_id=c.tenant_id AND i.id=c.purchase_order_item_id JOIN purchase_orders p
            ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
            WHERE c.tenant_id=d.tenant_id AND p.receiving_document_id=d.id),d.close_reason
        FROM receiving_documents d JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
        JOIN vendors v ON v.tenant_id=d.tenant_id AND v.id=d.vendor_id
        LEFT JOIN LATERAL (SELECT sum(i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END) expected,
          sum(i.received_quantity) received,sum(greatest(i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END
            -i.received_quantity-i.discrepancy_quantity,0)) outstanding
          FROM purchase_orders p JOIN purchase_order_items i ON i.tenant_id=p.tenant_id AND i.purchase_order_id=p.id
          WHERE p.tenant_id=d.tenant_id AND p.receiving_document_id=d.id) t ON true
        WHERE d.tenant_id=:tenant AND d.removed_at IS NULL AND s.status<>'CANCELLED'
        """;
    @Transactional(readOnly=true)
    public List<Document> documents(UUID tenant){return documents(tenant,List.of());}
    @Transactional(readOnly=true)
    public DocumentPage documentPage(UUID tenant,String query,int requestedPage,int requestedSize){
        tenant(tenant);int size=List.of(25,50,100).contains(requestedSize)?requestedSize:25;
        String q=Objects.toString(query,"").trim();if(q.length()>200)q=q.substring(0,200);
        String from="""
            FROM receiving_documents d JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
            JOIN vendors v ON v.tenant_id=d.tenant_id AND v.id=d.vendor_id
            WHERE d.tenant_id=:tenant AND d.removed_at IS NULL AND s.status<>'CANCELLED'
              AND (:query='' OR concat_ws(' ',d.document_number,d.original_filename,v.name,replace(d.document_type,'_',' ')) ILIKE '%'||:query||'%')
            """;
        Map<String,Object> args=new HashMap<>(Map.of("tenant",tenant,"query",q,"size",size));
        long count=sql.queryForObject("SELECT count(*) "+from,args,Long.class);
        int page=Math.max(0,Math.min(requestedPage,(int)Math.max(0,(count-1)/size)));args.put("offset",page*size);
        var ids=sql.query("SELECT d.id "+from+" ORDER BY d.created_at DESC,d.id LIMIT :size OFFSET :offset",args,(rs,n)->rs.getObject(1,UUID.class));
        return new DocumentPage(ids.isEmpty()?List.of():documents(tenant,ids),count,page,size);
    }
    @Transactional(readOnly=true)
    public List<Document> documents(UUID tenant,List<UUID> ids){
        tenant(tenant);
        return sql.query(DOCUMENTS+(ids.isEmpty()?"":" AND d.id IN (:ids)")+" ORDER BY d.created_at DESC",
            Map.of("tenant",tenant,"ids",ids),(rs,n)->new Document(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
            rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,LocalDate.class),rs.getTimestamp(8).toInstant(),
            rs.getString(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getBigDecimal(12),rs.getBoolean(13),rs.getBoolean(14),rs.getBoolean(15),rs.getString(16)));
    }
    @Transactional(readOnly=true)
    public List<WorkLine> lines(UUID tenant,List<UUID> ids){
        tenant(tenant);if(ids.isEmpty())return List.of();
        return sql.query("""
            SELECT i.id,d.id,d.receiving_session_id,i.account_catalog_item_id,i.description,i.vendor_item_code,
              i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END expected,
              i.received_quantity,greatest(i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END
                -i.received_quantity-i.discrepancy_quantity,0),i.units_per_case,i.unit_cost,i.currency,coalesce(g.requires_expiration_date,false),
              d.closed_at IS NOT NULL OR s.status='POSTED',
              (SELECT count(*) FROM receiving_line_receipts r WHERE r.tenant_id=i.tenant_id AND r.purchase_order_item_id=i.id),
              i.deposit_fee_per_unit,i.other_fee_per_unit,
              (SELECT a.location_id FROM account_catalog_item_locations a WHERE a.tenant_id=i.tenant_id
                AND a.account_catalog_item_id=i.account_catalog_item_id AND a.is_default LIMIT 1)
            FROM receiving_documents d JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
            JOIN purchase_orders p ON p.tenant_id=d.tenant_id AND p.receiving_document_id=d.id
            JOIN purchase_order_items i ON i.tenant_id=p.tenant_id AND i.purchase_order_id=p.id
            LEFT JOIN account_catalog_items a ON a.tenant_id=i.tenant_id AND a.id=i.account_catalog_item_id
            LEFT JOIN global_catalog_products g ON g.id=a.global_product_id
            WHERE d.tenant_id=:tenant AND d.id IN (:ids) AND d.removed_at IS NULL
            ORDER BY d.created_at,i.created_at,i.id
            """,Map.of("tenant",tenant,"ids",ids),(rs,n)->new WorkLine(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
            rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getBigDecimal(7),rs.getBigDecimal(8),
            rs.getBigDecimal(9),rs.getBigDecimal(10),rs.getBigDecimal(11),rs.getString(12),rs.getBoolean(13),rs.getBoolean(14),
            rs.getInt(15),rs.getBigDecimal(16),rs.getBigDecimal(17),rs.getObject(18,UUID.class)));
    }
    private record Identity(UUID document,UUID session,UUID product,boolean closed){}
    private Identity identity(UUID tenant,UUID line){
        Identity result=jdbc.query("""
            SELECT d.id,d.receiving_session_id,i.account_catalog_item_id,d.closed_at IS NOT NULL OR s.status='POSTED'
            FROM purchase_order_items i JOIN purchase_orders p ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
            JOIN receiving_documents d ON d.tenant_id=p.tenant_id AND d.id=p.receiving_document_id
            JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
            WHERE i.tenant_id=? AND i.id=? AND d.removed_at IS NULL AND s.status<>'CANCELLED'
            """,rs->rs.next()?new Identity(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getBoolean(4)):null,tenant,line);
        if(result==null)throw new IllegalArgumentException("This receiving line is not available in this account.");return result;
    }
    @Transactional(readOnly=true)
    public List<Receipt> receipts(UUID tenant,UUID line){
        tenant(tenant);Identity identity=identity(tenant,line);
        return jdbc.query("""
            SELECT r.id,r.total_each_quantity,r.expiration_date,r.location_id,l.code,r.disposition,r.received_at,r.voided_at,
              coalesce(stock.on_hand,0),coalesce(reserved.quantity,0),
              EXISTS(SELECT 1 FROM inventory_ledger_entries used WHERE used.tenant_id=r.tenant_id
                AND used.account_catalog_item_id=? AND used.location_id=r.location_id
                AND used.expiration_date IS NOT DISTINCT FROM r.expiration_date
                AND used.occurred_at>=r.received_at AND used.source_type NOT IN ('RECEIVING','RECEIVING_CORRECTION'))
              OR EXISTS(SELECT 1 FROM order_inventory_reservations shipped WHERE shipped.tenant_id=r.tenant_id
                AND shipped.account_catalog_item_id=? AND shipped.location_id=r.location_id
                AND shipped.expiration_date IS NOT DISTINCT FROM r.expiration_date AND shipped.status='SHIPPED'
              ),
              EXISTS(SELECT 1 FROM vendor_credit_requests c WHERE c.tenant_id=r.tenant_id
                AND c.purchase_order_item_id=r.purchase_order_item_id AND c.reason=r.disposition
                AND c.status NOT IN ('OPEN','CANCELLED'))
            FROM receiving_line_receipts r LEFT JOIN warehouse_locations l ON l.tenant_id=r.tenant_id AND l.id=r.location_id
            LEFT JOIN LATERAL (SELECT sum(quantity) on_hand FROM inventory_ledger_entries e WHERE e.tenant_id=r.tenant_id
              AND e.account_catalog_item_id=? AND e.location_id=r.location_id AND e.expiration_date IS NOT DISTINCT FROM r.expiration_date) stock ON true
            LEFT JOIN LATERAL (SELECT sum(quantity) quantity FROM order_inventory_reservations a WHERE a.tenant_id=r.tenant_id
              AND a.account_catalog_item_id=? AND a.location_id=r.location_id AND a.expiration_date IS NOT DISTINCT FROM r.expiration_date
              AND a.status='ACTIVE') reserved ON true
            WHERE r.tenant_id=? AND r.purchase_order_item_id=? ORDER BY r.received_at DESC,r.id
            """,(rs,n)->{
                BigDecimal quantity=rs.getBigDecimal(2),onHand=rs.getBigDecimal(9),reserved=rs.getBigDecimal(10);
                boolean physical=List.of("SELLABLE","SOON_EXPIRED","EXPIRED","OVER_SHIPPED").contains(rs.getString(6));
                String blocked=identity.closed()?"This document is closed. Its receipts are locked; use an inventory adjustment.":
                    rs.getBoolean(12)?"Vendor follow-up has already been submitted or settled. The original receipt must be retained.":
                    physical&&rs.getBoolean(11)?"Stock in this batch has been shipped, moved, or adjusted. Keep the original receipt and use an adjustment.":
                    physical&&reserved.signum()>0?reserved.stripTrailingZeros().toPlainString()+" units in this batch are committed to active orders. Undo is unavailable.":
                    physical&&onHand.compareTo(quantity)<0?"The original quantity is no longer on hand. Use an adjustment instead.":null;
                return new Receipt(rs.getObject(1,UUID.class),quantity,rs.getObject(3,LocalDate.class),rs.getObject(4,UUID.class),rs.getString(5),
                    rs.getString(6),rs.getTimestamp(7).toInstant(),rs.getTimestamp(8)!=null,blocked,onHand,reserved);
            },identity.product(),identity.product(),identity.product(),identity.product(),tenant,line);
    }
    @Transactional
    public void undo(UUID tenant,String actor,UUID line,UUID receiptId,String reason){
        lock(tenant);reason=reason(reason);Identity id=identity(tenant,line);
        Receipt receipt=receipts(tenant,line).stream().filter(r->r.id().equals(receiptId)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Receipt not found in this invoice."));
        if(receipt.undone())throw new IllegalArgumentException("This receipt was already undone. No further inventory was changed.");
        if(!receipt.canUndo())throw new IllegalArgumentException(receipt.blockedReason());
        jdbc.update("""
            INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,
              expiration_date,unit_cost,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
            SELECT tenant_id,account_catalog_item_id,location_id,'ADJUSTMENT',-quantity,expiration_date,unit_cost,currency,
              'RECEIVING_CORRECTION',source_id,now(),'receiving-correction:'||source_id,?,
              (SELECT id FROM app_users WHERE lower(email)=lower(?)),'FINAL'
            FROM inventory_ledger_entries WHERE tenant_id=? AND source_type='RECEIVING' AND source_id=?
            ON CONFLICT(tenant_id,idempotency_key) DO NOTHING
            ""","Undo receipt · "+reason,actor,tenant,receiptId);
        jdbc.update("UPDATE receiving_line_receipts SET voided_at=now(),voided_by=(SELECT id FROM app_users WHERE lower(email)=lower(?)),void_reason=? WHERE tenant_id=? AND id=? AND voided_at IS NULL",actor,reason,tenant,receiptId);
        jdbc.update("""
            UPDATE purchase_order_items SET received_quantity=greatest(received_quantity-?,0),
              status=CASE WHEN received_quantity-?<=0 THEN 'OPEN' ELSE 'PARTIALLY_RECEIVED' END
            WHERE tenant_id=? AND id=?
            """,receipt.quantity(),receipt.quantity(),tenant,line);
        jdbc.update("""
            UPDATE receiving_document_lines l SET received_quantity=i.received_quantity
            FROM purchase_order_items i WHERE i.tenant_id=? AND i.id=? AND l.tenant_id=i.tenant_id AND l.id=i.receiving_line_id
            """,tenant,line);
        // Preserve other receipts' claims and retain cancelled claims for audit (quantity cannot be zero).
        jdbc.update("""
            UPDATE vendor_credit_requests SET
              status=CASE WHEN quantity<=? THEN 'CANCELLED' ELSE 'OPEN' END,
              quantity=CASE WHEN quantity>? THEN quantity-? ELSE quantity END,
              notes=left(coalesce(notes,'')||' · Receipt undone',500)
            WHERE tenant_id=? AND purchase_order_item_id=? AND reason=? AND status='OPEN'
            """,receipt.quantity(),receipt.quantity(),receipt.quantity(),tenant,line,receipt.disposition());
        jdbc.update("UPDATE receiving_sessions SET status='MATCHING' WHERE tenant_id=? AND id=? AND status<>'POSTED'",tenant,id.session());
        audit(tenant,id.document(),receiptId,"UNDO_RECEIPT",reason,actor);
    }
    @Transactional
    public void adjust(UUID tenant,String actor,UUID line,UUID receiptId,BigDecimal quantity,String direction,String reason,String notes){
        lock(tenant);Identity id=identity(tenant,line);
        if(quantity==null||quantity.signum()<=0||quantity.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Enter a positive whole-number quantity.");
        if(direction==null||!List.of("INCREASE","DECREASE").contains(direction))throw new IllegalArgumentException("Choose increase or decrease.");
        Receipt receipt=receipts(tenant,line).stream().filter(r->r.id().equals(receiptId)&&!r.undone()).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Choose a current receipt batch."));
        if(!List.of("SELLABLE","SOON_EXPIRED","EXPIRED","OVER_SHIPPED").contains(receipt.disposition()))
            throw new IllegalArgumentException("This condition did not add warehouse stock. Only physical inventory batches can be adjusted.");
        inventory.adjustInventory(tenant,actor,id.product(),receipt.expiration(),receipt.locationId(),
            "DECREASE".equals(direction)?quantity.negate():quantity,reason,notes);
        audit(tenant,id.document(),receiptId,"ADJUST_INVENTORY",direction+" "+quantity.toPlainString()+" · "+reason+" · "+Objects.toString(notes,""),actor);
    }
    @Transactional
    public void removeDocument(UUID tenant,String actor,UUID document){
        lock(tenant);Document d=one(tenant,document);
        if(d.closed())throw new IllegalArgumentException("This document is closed and linked to inventory history. It cannot be deleted; use an inventory adjustment.");
        if(d.hasHistory()||d.received().signum()>0)throw new IllegalArgumentException("This document has receipt history. It cannot be deleted, even after undo. Review receipts or adjust inventory.");
        Boolean financialWork=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vendor_credit_requests c JOIN purchase_order_items i ON i.tenant_id=c.tenant_id AND i.id=c.purchase_order_item_id JOIN purchase_orders p ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id WHERE c.tenant_id=? AND p.receiving_document_id=?)",Boolean.class,tenant,document);
        if(Boolean.TRUE.equals(financialWork))throw new IllegalArgumentException("This document has vendor discrepancy history. Close it instead of deleting it.");
        jdbc.update("UPDATE receiving_documents SET removed_at=now(),removed_by=(SELECT id FROM app_users WHERE lower(email)=lower(?)) WHERE tenant_id=? AND id=?",actor,tenant,document);
        audit(tenant,document,null,"REMOVE_UNUSED_DOCUMENT","Unused document removed; source rows retained for audit.",actor);
        Boolean finish=jdbc.queryForObject("""
            SELECT count(*)>0 AND count(*) FILTER(WHERE closed_at IS NULL)=0
            FROM receiving_documents WHERE tenant_id=? AND receiving_session_id=? AND removed_at IS NULL
            """,Boolean.class,tenant,d.sessionId());
        if(Boolean.TRUE.equals(finish))receiving.post(tenant,d.sessionId(),actor);
    }
    @Transactional
    public void closeDocument(UUID tenant,String actor,UUID document,String reason,boolean creditExpected,
            BigDecimal previewReceived,BigDecimal previewOutstanding){
        lock(tenant);Document d=one(tenant,document);if(d.closed())throw new IllegalArgumentException("This document is already closed.");
        if(previewReceived==null||previewOutstanding==null||d.received().compareTo(previewReceived)!=0||d.outstanding().compareTo(previewOutstanding)!=0)
            throw new IllegalArgumentException("Receiving changed since this review was opened. Refresh the document and review the current quantities before closing.");
        String note=d.outstanding().signum()>0?reason(reason):"All quantities resolved";
        if(d.outstanding().signum()>0){
            if(creditExpected&&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vendor_credit_requests c JOIN purchase_orders p
                  ON p.tenant_id=c.tenant_id AND p.receiving_session_id=c.receiving_session_id
                  JOIN purchase_order_items i ON i.tenant_id=p.tenant_id AND i.purchase_order_id=p.id AND i.id=c.purchase_order_item_id
                  WHERE c.tenant_id=? AND p.receiving_document_id=? AND c.reason='SHORT_SHIPPED'
                    AND c.status NOT IN ('OPEN','CANCELLED'))
                """,Boolean.class,tenant,document)))
                throw new IllegalArgumentException("A shortage claim has already been submitted or settled. Close without creating another claim, and handle the additional balance in vendor follow-up.");
            if(creditExpected)jdbc.update("""
                INSERT INTO vendor_credit_requests(tenant_id,receiving_session_id,purchase_order_item_id,vendor_id,reason,
                  quantity,unit_cost,currency,notes,created_by)
                SELECT i.tenant_id,p.receiving_session_id,i.id,p.vendor_id,'SHORT_SHIPPED',
                  greatest(i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END-i.received_quantity-i.discrepancy_quantity,0),
                  i.unit_cost,i.currency,?,(SELECT id FROM app_users WHERE lower(email)=lower(?))
                FROM purchase_orders p JOIN purchase_order_items i ON i.tenant_id=p.tenant_id AND i.purchase_order_id=p.id
                WHERE p.tenant_id=? AND p.receiving_document_id=? AND i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END>i.received_quantity+i.discrepancy_quantity
                ON CONFLICT(tenant_id,purchase_order_item_id,reason) DO UPDATE SET
                  quantity=CASE WHEN vendor_credit_requests.status='CANCELLED' THEN EXCLUDED.quantity ELSE vendor_credit_requests.quantity+EXCLUDED.quantity END,
                  status='OPEN'
                """,note,actor,tenant,document);
            jdbc.update("""
                UPDATE purchase_order_items i SET discrepancy_quantity=greatest(i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END-i.received_quantity,i.discrepancy_quantity),
                  discrepancy_status=CASE WHEN discrepancy_status='NONE' THEN 'OTHER' ELSE discrepancy_status END,
                  discrepancy_notes=left(concat_ws(' · ',discrepancy_notes,?),500),status='RECEIVED'
                FROM purchase_orders p WHERE p.tenant_id=? AND p.receiving_document_id=? AND i.tenant_id=p.tenant_id AND i.purchase_order_id=p.id
                """,note,tenant,document);
        }
        boolean partial=d.received().compareTo(d.expected())<0;
        jdbc.update("UPDATE receiving_documents SET closed_at=now(),closed_by=(SELECT id FROM app_users WHERE lower(email)=lower(?)),close_reason=?,closed_partial=?,status='RECEIVED' WHERE tenant_id=? AND id=?",actor,note,partial,tenant,document);
        audit(tenant,document,null,"CLOSE_DOCUMENT",note,actor);
        Integer open=jdbc.queryForObject("SELECT count(*) FROM receiving_documents WHERE tenant_id=? AND receiving_session_id=? AND closed_at IS NULL AND removed_at IS NULL",Integer.class,tenant,d.sessionId());
        if(open!=null&&open==0)receiving.post(tenant,d.sessionId(),actor);
    }
    @Transactional
    public boolean execute(UUID tenant,UUID request,String operation,UUID target,String fingerprint,Runnable change){
        if(request==null)throw new IllegalArgumentException("This action is missing its safety reference. Reopen the panel and try again.");
        lock(tenant);
        String existing=jdbc.query("SELECT operation||':'||target_id||':'||fingerprint FROM receiving_commands WHERE tenant_id=? AND request_id=?",
            rs->rs.next()?rs.getString(1):null,tenant,request);
        if(existing!=null){
            if(!existing.equals(operation+":"+target+":"+fingerprint))throw new IllegalArgumentException("This safety reference was used for a different action. Reopen the panel.");
            return false;
        }
        change.run();
        jdbc.update("INSERT INTO receiving_commands(tenant_id,request_id,operation,target_id,fingerprint) VALUES (?,?,?,?,?)",tenant,request,operation,target,fingerprint);
        return true;
    }
    @Transactional
    public void prepare(UUID tenant,String actor,UUID line,BigDecimal pack,BigDecimal deposit,BigDecimal other){
        if(pack==null||pack.signum()<=0||pack.stripTrailingZeros().scale()>0||deposit==null||deposit.signum()<0||other==null||other.signum()<0)
            throw new IllegalArgumentException("Use a positive whole pack size and non-negative item fees.");
        lock(tenant);Identity id=identity(tenant,line);
        if(id.closed())throw new IllegalArgumentException("Closed document costs and pack sizes are locked.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM receiving_line_receipts WHERE tenant_id=? AND purchase_order_item_id=?)",Boolean.class,tenant,line)))
            throw new IllegalArgumentException("Receiving has already begun. Pack size and fees are locked to protect the original receipts.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM vendor_credit_requests WHERE tenant_id=? AND purchase_order_item_id=?)",Boolean.class,tenant,line)))
            throw new IllegalArgumentException("This line has vendor follow-up. Keep its pack size and costs unchanged.");
        jdbc.update("""
            UPDATE purchase_order_items i SET units_per_case=?,deposit_fee_per_unit=?,other_fee_per_unit=?,
              unit_cost=CASE WHEN l.raw_data->>'PriceBasis'='CASE' THEN l.invoice_unit_cost/? ELSE i.unit_cost END
            FROM receiving_document_lines l WHERE i.tenant_id=? AND i.id=? AND l.tenant_id=i.tenant_id AND l.id=i.receiving_line_id
            """,pack,deposit,other,pack,tenant,line);
        audit(tenant,id.document(),null,"PREPARE_LINE","Pack "+pack+" · deposit "+deposit+" · other fee "+other,actor);
    }
    private record ReceiveTarget(UUID document,UUID session,UUID product,UUID sourceLine,UUID vendor,
            BigDecimal remaining,boolean dateRequired,boolean closed,UUID location,String claimStatus){}
    @Transactional
    public void receive(UUID tenant,String actor,UUID line,BigDecimal quantity,LocalDate expiration,String disposition,UUID location,String notes){
        if(quantity==null||quantity.signum()<=0||quantity.stripTrailingZeros().scale()>0)
            throw new IllegalArgumentException("Enter a positive whole-number quantity.");
        if(disposition==null||!List.of("SELLABLE","SOON_EXPIRED","EXPIRED","OVER_SHIPPED","DAMAGED","MISPICKED","SHORT_SHIPPED").contains(disposition))
            throw new IllegalArgumentException("Choose the condition of this delivery.");
        if(notes!=null&&notes.length()>500)throw new IllegalArgumentException("Keep notes within 500 characters.");
        lock(tenant);
        ReceiveTarget target=jdbc.query("""
            SELECT d.id,d.receiving_session_id,i.account_catalog_item_id,i.receiving_line_id,p.vendor_id,
              greatest(i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END-i.received_quantity-i.discrepancy_quantity,0),
              g.requires_expiration_date,d.closed_at IS NOT NULL OR s.status='POSTED',loc.id,claim.status
            FROM purchase_order_items i JOIN purchase_orders p ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
            JOIN receiving_documents d ON d.tenant_id=p.tenant_id AND d.id=p.receiving_document_id
            JOIN receiving_sessions s ON s.tenant_id=d.tenant_id AND s.id=d.receiving_session_id
            JOIN account_catalog_items a ON a.tenant_id=i.tenant_id AND a.id=i.account_catalog_item_id AND a.status='ACTIVE'
            JOIN global_catalog_products g ON g.id=a.global_product_id
            LEFT JOIN warehouse_locations loc ON loc.tenant_id=i.tenant_id AND loc.status='ACTIVE'
              AND loc.id=coalesce(CAST(? AS uuid),(SELECT location_id FROM account_catalog_item_locations al
                WHERE al.tenant_id=i.tenant_id AND al.account_catalog_item_id=i.account_catalog_item_id AND al.is_default))
            LEFT JOIN vendor_credit_requests claim ON claim.tenant_id=i.tenant_id AND claim.purchase_order_item_id=i.id AND claim.reason=?
            WHERE i.tenant_id=? AND i.id=? AND d.removed_at IS NULL AND s.status<>'CANCELLED' FOR UPDATE OF i
            """,rs->rs.next()?new ReceiveTarget(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getBigDecimal(6),rs.getBoolean(7),rs.getBoolean(8),
                rs.getObject(9,UUID.class),rs.getString(10)):null,location,disposition,tenant,line);
        if(target==null)throw new IllegalArgumentException("This document or product is unavailable in this account.");
        if(target.closed())throw new IllegalArgumentException("This document is closed. Use an inventory adjustment.");
        if(target.location()==null)throw new IllegalArgumentException("Choose an active inventory location.");
        boolean shortage="SHORT_SHIPPED".equals(disposition),overage="OVER_SHIPPED".equals(disposition);
        boolean physical=List.of("SELLABLE","SOON_EXPIRED","EXPIRED","OVER_SHIPPED").contains(disposition);
        if(physical&&(target.dateRequired()||List.of("SOON_EXPIRED","EXPIRED").contains(disposition))&&expiration==null)
            throw new IllegalArgumentException("Enter the expiration date for this batch.");
        if("SELLABLE".equals(disposition)&&expiration!=null&&expiration.isBefore(LocalDate.now()))
            throw new IllegalArgumentException("This expiration has passed. Choose Expired so the units remain visible as cannot sell.");
        if(!overage&&quantity.compareTo(target.remaining())>0)
            throw new IllegalArgumentException("This exceeds the remaining expected quantity. Choose Extra units received if an overage arrived.");
        if(overage&&quantity.compareTo(target.remaining())<=0)
            throw new IllegalArgumentException("Extra units must exceed the remaining expected quantity.");
        boolean claim=!List.of("SELLABLE","OVER_SHIPPED").contains(disposition);
        if(claim&&target.claimStatus()!=null&&!List.of("OPEN","CANCELLED").contains(target.claimStatus()))
            throw new IllegalArgumentException("Vendor follow-up for this condition is already submitted or settled. Resolve that claim before adding to it.");
        Map<String,Object> args=new HashMap<>();
        args.put("tenant",tenant);args.put("line",line);args.put("document",target.document());args.put("session",target.session());
        args.put("product",target.product());args.put("sourceLine",target.sourceLine());args.put("vendor",target.vendor());
        args.put("location",target.location());args.put("quantity",quantity);args.put("expiration",expiration);args.put("condition",disposition);
        args.put("mainCondition",overage?"SELLABLE":disposition);args.put("mainQuantity",shortage?BigDecimal.ZERO:overage?target.remaining():quantity);
        args.put("overQuantity",overage?quantity.subtract(target.remaining()):BigDecimal.ZERO);
        args.put("received",shortage?BigDecimal.ZERO:quantity);args.put("missing",shortage?quantity:BigDecimal.ZERO);
        args.put("claim",claim);args.put("notes",notes==null?"":notes);args.put("actor",actor);
        // One write round-trip: receipt, ledger, source balance, vendor claim and audit commit atomically.
        sql.update("""
            WITH base AS (
              SELECT i.*,(SELECT id FROM app_users WHERE lower(email)=lower(:actor)) actor
              FROM purchase_order_items i WHERE i.tenant_id=:tenant AND i.id=:line
            ), assignment AS (
              INSERT INTO account_catalog_item_locations(tenant_id,account_catalog_item_id,location_id,is_default)
              VALUES (:tenant,:product,:location,false) ON CONFLICT DO NOTHING
            ), portions(quantity,condition) AS (
              VALUES (CAST(:mainQuantity AS numeric),CAST(:mainCondition AS text)),(CAST(:overQuantity AS numeric),'OVER_SHIPPED')
            ), receipts AS (
              INSERT INTO receiving_line_receipts(tenant_id,purchase_order_item_id,each_quantity,units_per_case,expiration_date,disposition,notes,received_by,location_id)
              SELECT :tenant,:line,portions.quantity,1,:expiration,portions.condition,:notes,base.actor,:location
              FROM portions CROSS JOIN base WHERE portions.quantity>0 RETURNING *
            ), ledger AS (
              INSERT INTO inventory_ledger_entries(tenant_id,account_catalog_item_id,location_id,entry_type,quantity,expiration_date,
                unit_cost,deposit_fee_per_unit,other_fee_per_unit,currency,source_type,source_id,occurred_at,idempotency_key,notes,created_by,cost_status)
              SELECT :tenant,:product,:location,'RECEIPT',r.total_each_quantity,r.expiration_date,
                CASE WHEN r.disposition='OVER_SHIPPED' THEN 0 ELSE base.unit_cost+base.deposit_fee_per_unit+base.other_fee_per_unit END,
                CASE WHEN r.disposition='OVER_SHIPPED' THEN 0 ELSE base.deposit_fee_per_unit END,
                CASE WHEN r.disposition='OVER_SHIPPED' THEN 0 ELSE base.other_fee_per_unit END,
                base.currency,'RECEIVING',r.id,r.received_at,'receiving:'||r.id,:notes,base.actor,
                CASE WHEN r.disposition='OVER_SHIPPED' THEN 'FINAL' ELSE 'PROVISIONAL' END
              FROM receipts r CROSS JOIN base WHERE r.disposition IN ('SELLABLE','SOON_EXPIRED','EXPIRED','OVER_SHIPPED')
            ), item_update AS (
              UPDATE purchase_order_items SET received_quantity=received_quantity+:received,
                discrepancy_quantity=discrepancy_quantity+:missing,
                discrepancy_status=CASE WHEN :condition='SELLABLE' THEN discrepancy_status ELSE :condition END,
                discrepancy_notes=CASE WHEN :condition='SELLABLE' THEN discrepancy_notes ELSE :notes END,
                status=CASE WHEN received_quantity+discrepancy_quantity+:quantity>=ordered_quantity*
                  CASE WHEN invoice_unit='CASE' THEN units_per_case ELSE 1 END THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END
              WHERE tenant_id=:tenant AND id=:line
            ), source_update AS (
              UPDATE receiving_document_lines SET received_quantity=received_quantity+:received,
                expiration_date=CASE WHEN :received>0 THEN coalesce(:expiration,expiration_date) ELSE expiration_date END
              WHERE tenant_id=:tenant AND id=:sourceLine
            ), credit AS (
              INSERT INTO vendor_credit_requests(tenant_id,receiving_session_id,purchase_order_item_id,vendor_id,reason,quantity,unit_cost,currency,notes,created_by)
              SELECT :tenant,:session,:line,:vendor,:condition,:quantity,unit_cost,currency,:notes,actor FROM base WHERE :claim
              ON CONFLICT(tenant_id,purchase_order_item_id,reason) DO UPDATE SET
                quantity=CASE WHEN vendor_credit_requests.status='CANCELLED' THEN EXCLUDED.quantity ELSE vendor_credit_requests.quantity+EXCLUDED.quantity END,
                notes=EXCLUDED.notes,status='OPEN'
            ), audit AS (
              INSERT INTO receiving_audit_events(tenant_id,document_id,action,reason,actor_id)
              SELECT :tenant,:document,'RECEIVE',left(CAST(:quantity AS text)||' each · '||:condition,500),actor FROM base
            )
            UPDATE receiving_sessions s SET status=CASE WHEN EXISTS(
              SELECT 1 FROM purchase_order_items i JOIN purchase_orders p ON p.tenant_id=i.tenant_id AND p.id=i.purchase_order_id
              JOIN receiving_documents d ON d.tenant_id=p.tenant_id AND d.id=p.receiving_document_id AND d.removed_at IS NULL
              WHERE i.tenant_id=:tenant AND p.receiving_session_id=:session
                AND i.received_quantity+i.discrepancy_quantity+CASE WHEN i.id=:line THEN :quantity ELSE 0 END
                  <i.ordered_quantity*CASE WHEN i.invoice_unit='CASE' THEN i.units_per_case ELSE 1 END
            ) THEN 'MATCHING' ELSE 'READY' END
            WHERE s.tenant_id=:tenant AND s.id=:session AND s.status NOT IN ('POSTED','CANCELLED')
            """,args);
    }
    @Transactional(readOnly=true)
    public List<UUID> documentsForSession(UUID tenant,UUID session){
        tenant(tenant);
        return jdbc.query("SELECT id FROM receiving_documents WHERE tenant_id=? AND receiving_session_id=? AND removed_at IS NULL ORDER BY created_at",(rs,n)->rs.getObject(1,UUID.class),tenant,session);
    }
    private Document one(UUID tenant,UUID id){return documents(tenant,List.of(id)).stream().findFirst().orElseThrow(()->new IllegalArgumentException("This document is not available in this account."));}
    private static String reason(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Add a reason so your team can understand this correction.");if(value.length()>500)throw new IllegalArgumentException("Keep the explanation within 500 characters.");return value.trim();}
    private void audit(UUID tenant,UUID document,UUID receipt,String action,String reason,String actor){
        jdbc.update("INSERT INTO receiving_audit_events(tenant_id,document_id,receipt_id,action,reason,actor_id) VALUES (?,?,?,?,?,(SELECT id FROM app_users WHERE lower(email)=lower(?)))",tenant,document,receipt,action,reason.length()>500?reason.substring(0,500):reason,actor);
    }
}
