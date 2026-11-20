package com.nextaicommerce.platform.catalog;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CatalogImportProgress {
    private final JdbcTemplate jdbc;
    CatalogImportProgress(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void queue(UUID tenantId,UUID importId,int total){setTenant(tenantId);jdbc.update("""
        INSERT INTO catalog_import_progress(tenant_id,catalog_import_id,state,processed_rows,total_rows)
        VALUES(?,?,'QUEUED',0,?) ON CONFLICT(tenant_id,catalog_import_id) DO UPDATE SET
          state='QUEUED',processed_rows=0,total_rows=EXCLUDED.total_rows,error_message=NULL,
          started_at=NULL,completed_at=NULL,updated_at=now()
        """,tenantId,importId,total);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void processing(UUID tenantId,UUID importId,int processed){setTenant(tenantId);jdbc.update("UPDATE catalog_import_progress SET state='PROCESSING',processed_rows=?,started_at=coalesce(started_at,now()),updated_at=now() WHERE tenant_id=? AND catalog_import_id=?",processed,tenantId,importId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void mapping(UUID tenantId,UUID importId){setTenant(tenantId);jdbc.update("UPDATE catalog_import_progress SET state='MAPPING',processed_rows=total_rows,updated_at=now() WHERE tenant_id=? AND catalog_import_id=?",tenantId,importId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(UUID tenantId,UUID importId){setTenant(tenantId);jdbc.update("UPDATE catalog_import_progress SET state='COMPLETED',processed_rows=total_rows,completed_at=now(),updated_at=now() WHERE tenant_id=? AND catalog_import_id=?",tenantId,importId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(UUID tenantId,UUID importId,int mapped,int unmapped){setTenant(tenantId);jdbc.update("UPDATE catalog_import_progress SET state='COMPLETED',processed_rows=total_rows,mapped_skus=?,unmapped_skus=?,completed_at=now(),updated_at=now() WHERE tenant_id=? AND catalog_import_id=?",mapped,unmapped,tenantId,importId);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void fail(UUID tenantId,UUID importId,String message){setTenant(tenantId);jdbc.update("UPDATE catalog_import_progress SET state='FAILED',error_message=?,completed_at=now(),updated_at=now() WHERE tenant_id=? AND catalog_import_id=?",truncate(message),tenantId,importId);}
    private void setTenant(UUID id){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,id.toString());}
    private static String truncate(String value){String text=value==null?"Catalogue import failed.":value;return text.substring(0,Math.min(500,text.length()));}
}
