package com.nextaicommerce.platform.catalog;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, dependency-safe catalogue removal. Never redirects references or changes quantities. */
@Service
public class CatalogRemovalService {
    private final JdbcTemplate jdbc;
    public CatalogRemovalService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Review(String name,String operation,String label,String message){}

    @Transactional
    public Review review(UUID tenant,UUID id,boolean global){return inspect(tenant,id,global,false);}

    @Transactional
    public String remove(UUID tenant,UUID id,boolean global,String expected){
        CatalogIdentity.lock(jdbc);
        if("DELETE".equals(expected))lockLogicalReferences();
        Review review=inspect(tenant,id,global,true);
        if("BLOCKED".equals(review.operation()))throw new IllegalArgumentException(review.message());
        if(!review.operation().equals(expected))throw new IllegalArgumentException("This product’s dependencies changed. Review removal again; nothing was changed.");
        String table=global?"global_catalog_products":"account_catalog_items";
        String where=global?"id=?":"id=? AND tenant_id=?";
        Object[] args=global?new Object[]{id}:new Object[]{id,tenant};
        if("DELETE".equals(expected))jdbc.update("DELETE FROM "+table+" WHERE "+where,args);
        else jdbc.update("UPDATE "+table+" SET status='"+(global?"ARCHIVED":"INACTIVE")+"',updated_at=now() WHERE "+where,args);
        return "DELETE".equals(expected)?"Product deleted.":global?"Product archived.":"Account product marked inactive.";
    }

    private Review inspect(UUID tenant,UUID id,boolean global,boolean lock){
        if(!global){if(tenant==null)throw new IllegalArgumentException("Choose an account.");jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
        String sql=global?"SELECT canonical_name,status FROM global_catalog_products WHERE id=?":"SELECT coalesce(display_name,account_sku,'Product'),status FROM account_catalog_items WHERE id=? AND tenant_id=?";
        var names=jdbc.query(sql+(lock?" FOR UPDATE":""),(r,n)->new String[]{r.getString(1),r.getString(2)},global?new Object[]{id}:new Object[]{id,tenant});
        if(names.isEmpty())throw new IllegalArgumentException("Product was not found in this catalogue.");
        Boolean used=hasDependencies(id,global);
        if(used==null)return new Review(names.getFirst()[0],"BLOCKED","Delete product","We couldn’t verify linked records. Try again. Nothing was changed.");
        boolean retained=global?"ARCHIVED".equals(names.getFirst()[1]):!"ACTIVE".equals(names.getFirst()[1]);
        if(used&&retained)return new Review(names.getFirst()[0],"BLOCKED","Delete product",
            global?"This product is already archived. It cannot be deleted while linked accounts or other dependent records remain. No changes will be made.":"This product is already inactive. It cannot be deleted while inventory, mappings, receipts or other dependent records remain. No changes will be made.");
        String operation=used?(global?"ARCHIVE":"INACTIVE"):"DELETE";
        return new Review(names.getFirst()[0],operation,used?(global?"Archive product":"Mark inactive"):"Delete product",
            used?(global?"Archive this shared product? This change applies across accounts. Existing records and links will be retained.":"Mark this product inactive in your account? Existing records and links will be retained."):
                "Permanently delete this unused "+(global?"shared global product":"account product")+"? This cannot be undone.");
    }

    private void lockLogicalReferences(){
        var tables=jdbc.queryForList("SELECT DISTINCT quote_ident(n.nspname)||'.'||quote_ident(c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname=current_schema() AND c.relkind IN ('r','p') AND NOT a.attisdropped AND a.attname IN ('subject_key','source_entity_id','entity_id') ORDER BY 1",String.class);
        for(String table:tables)jdbc.execute("LOCK TABLE "+table+" IN SHARE MODE");
    }

    private Boolean hasDependencies(UUID id,boolean global){
        // RLS must never turn an invisible dependency into permission to delete. If the role
        // cannot inspect all references, conservatively offer the non-destructive status action.
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            var savepoint=connection.setSavepoint();
            try{
                try(var statement=connection.createStatement()){statement.execute("SET LOCAL row_security=off");}
                String target=global?"global_catalog_products":"account_catalog_items";
                try(var query=connection.prepareStatement("""
                    SELECT DISTINCT quote_ident(ns.nspname)||'.'||quote_ident(t.relname),quote_ident(a.attname),t.relname
                    FROM pg_constraint fk JOIN pg_class t ON t.oid=fk.conrelid
                    JOIN pg_namespace ns ON ns.oid=t.relnamespace
                    CROSS JOIN LATERAL unnest(fk.conkey,fk.confkey) keys(local_key,remote_key)
                    JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=keys.local_key
                    JOIN pg_attribute parent ON parent.attrelid=fk.confrelid AND parent.attnum=keys.remote_key
                    WHERE fk.contype='f' AND fk.confrelid=to_regclass(?) AND parent.attname='id'
                    """)){
                    query.setString(1,target);
                    try(var refs=query.executeQuery()){
                        while(refs.next()){
                            // A global product's own identifiers are intrinsic identity, not usage.
                            if(global&&"global_product_identifiers".equals(refs.getString(3)))continue;
                            try(var probe=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM "+refs.getString(1)+" WHERE "+refs.getString(2)+"=?)")){
                                probe.setObject(1,id);try(var result=probe.executeQuery()){result.next();if(result.getBoolean(1)){connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return true;}}
                            }
                        }
                    }
                }
                // Collaboration uses polymorphic textual keys rather than foreign keys.
                try(var query=connection.prepareStatement("""
                    SELECT quote_ident(n.nspname)||'.'||quote_ident(c.relname),quote_ident(a.attname)
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
                    WHERE n.nspname=current_schema() AND c.relkind IN ('r','p') AND NOT a.attisdropped
                    AND a.attname IN ('subject_key','source_entity_id','entity_id')
                    """ );var refs=query.executeQuery()){
                    while(refs.next())try(var probe=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM "+refs.getString(1)+" WHERE "+refs.getString(2)+"::text=?)")){
                        probe.setString(1,id.toString());try(var result=probe.executeQuery()){result.next();if(result.getBoolean(1)){connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return true;}}
                    }
                }
                connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return false;
            }catch(java.sql.SQLException cannotVerify){connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return null;}
        });
    }
}
