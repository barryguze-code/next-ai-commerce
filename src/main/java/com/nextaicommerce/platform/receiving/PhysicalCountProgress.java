package com.nextaicommerce.platform.receiving;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PhysicalCountProgress {
    public record View(String state,int percent,String phase,String error,int shortageOrders,
            String filename,int totalRows,int appliedRows){}
    private final JdbcTemplate jdbc;
    PhysicalCountProgress(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean queue(UUID tenantId,UUID importId){setTenant(tenantId);return jdbc.update("""
        INSERT INTO physical_count_import_progress(tenant_id,physical_count_import_id,state,progress_percent,phase)
        VALUES(?,?,'QUEUED',3,'Preparing your count') ON CONFLICT(tenant_id,physical_count_import_id) DO UPDATE SET
          state='QUEUED',progress_percent=3,phase='Preparing your count',error_message=NULL,
          started_at=NULL,completed_at=NULL,updated_at=now()
        WHERE physical_count_import_progress.state NOT IN ('QUEUED','PROCESSING')
           OR physical_count_import_progress.updated_at<now()-interval '2 minutes'
        """,tenantId,importId)>0;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void update(UUID tenantId,UUID importId,int percent,String phase){setTenant(tenantId);jdbc.update("""
        UPDATE physical_count_import_progress SET state='PROCESSING',progress_percent=?,phase=?,
          started_at=coalesce(started_at,now()),updated_at=now() WHERE tenant_id=? AND physical_count_import_id=?
        """,percent,phase,tenantId,importId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(UUID tenantId,UUID importId,int shortageOrders){setTenant(tenantId);jdbc.update("""
        UPDATE physical_count_import_progress SET state='COMPLETED',progress_percent=100,phase='Inventory count complete',
          shortage_orders=?,completed_at=now(),updated_at=now() WHERE tenant_id=? AND physical_count_import_id=?
        """,shortageOrders,tenantId,importId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void fail(UUID tenantId,UUID importId,String message){setTenant(tenantId);String safe=message==null?"The physical count needs attention.":message;safe=safe.substring(0,Math.min(500,safe.length()));jdbc.update("""
        UPDATE physical_count_import_progress SET state='FAILED',phase='Needs your attention',error_message=?,
          completed_at=now(),updated_at=now() WHERE tenant_id=? AND physical_count_import_id=?
        """,safe,tenantId,importId);}
    @Transactional(readOnly=true)
    public View view(UUID tenantId,UUID importId){setTenant(tenantId);return jdbc.query("""
        SELECT CASE WHEN progress.state IN ('QUEUED','PROCESSING') AND progress.updated_at<now()-interval '2 minutes' THEN 'FAILED' ELSE progress.state END,
          progress.progress_percent,
          CASE WHEN progress.state IN ('QUEUED','PROCESSING') AND progress.updated_at<now()-interval '2 minutes' THEN 'Needs your attention' ELSE progress.phase END,
          CASE WHEN progress.state IN ('QUEUED','PROCESSING') AND progress.updated_at<now()-interval '2 minutes'
            THEN 'Processing was interrupted before completion. Your staged count is safe; choose Validate and apply to resume.'
            ELSE progress.error_message END,
          progress.shortage_orders,physical_count.original_filename,physical_count.total_rows,physical_count.applied_rows
        FROM physical_count_import_progress progress
        JOIN physical_count_imports physical_count ON physical_count.tenant_id=progress.tenant_id
          AND physical_count.id=progress.physical_count_import_id
        WHERE progress.tenant_id=? AND progress.physical_count_import_id=?
        """,rs->rs.next()?new View(rs.getString(1),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getInt(5),
            rs.getString(6),rs.getInt(7),rs.getInt(8)):null,tenantId,importId);}
    private void setTenant(UUID tenantId){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenantId.toString());}
}
