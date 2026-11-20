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
        // Follow receiving's tenant-lock-before-catalogue-lock order.
        for(UUID scope:scopes(tenant,global))
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",r->{},scope.toString());
        CatalogIdentity.lock(jdbc);
        Review review=inspect(tenant,id,global,true);
        if("BLOCKED".equals(review.operation()))throw new IllegalArgumentException(review.message());
        if(!review.operation().equals(expected))throw new IllegalArgumentException("This product’s dependencies changed. Review removal again; nothing was changed.");
        String table=global?"global_catalog_products":"account_catalog_items";
        String where=global?"id=?":"id=? AND tenant_id=?";
        Object[] args=global?new Object[]{id}:new Object[]{id,tenant};
        if("RETAINED_DELETE".equals(expected)){retire(tenant,id,global);return "Product deleted from the catalogue. Inventory history is retained.";}
        if("DELETE".equals(expected))jdbc.update("DELETE FROM "+table+" WHERE "+where,args);
        else jdbc.update("UPDATE "+table+" SET status='"+(global?"ARCHIVED":"INACTIVE")+"',updated_at=now() WHERE "+where,args);
        return "DELETE".equals(expected)?"Product deleted.":global?"Product archived.":"Account product marked inactive.";
    }

    private Review inspect(UUID tenant,UUID id,boolean global,boolean lock){
        if(!global){if(tenant==null)throw new IllegalArgumentException("Choose an account.");jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
        String sql=global?"SELECT canonical_name,status FROM global_catalog_products WHERE id=? AND deleted_at IS NULL":"SELECT coalesce(display_name,account_sku,'Product'),status FROM account_catalog_items WHERE id=? AND tenant_id=? AND deleted_at IS NULL";
        var names=jdbc.query(sql+(lock?" FOR UPDATE":""),(r,n)->new String[]{r.getString(1),r.getString(2)},global?new Object[]{id}:new Object[]{id,tenant});
        if(names.isEmpty())throw new IllegalArgumentException("Product was not found in this catalogue.");
        if(lock && global){
            // Match stock writers' row guard before inspecting any linked balance.
            try{
                for(UUID scope:scopes(tenant,true)){
                    context(scope);
                    jdbc.query("SELECT id FROM account_catalog_items WHERE tenant_id=? AND global_product_id=? AND deleted_at IS NULL ORDER BY id FOR UPDATE",r->{},scope,id);
                }
            }finally{if(tenant!=null)context(tenant);}
        }
        Boolean used=hasDependencies(id,global);
        if(used==null)return new Review(names.getFirst()[0],"BLOCKED","Delete product","We couldn’t verify linked records. Try again. Nothing was changed.");
        if(hasStock(tenant,id,global))return new Review(names.getFirst()[0],"BLOCKED","Delete product",
            "This product still has stock or active reservations. Zero out its inventory and resolve reservations before deleting it. Nothing will be changed.");
        return new Review(names.getFirst()[0],"RETAINED_DELETE","Delete product",
            "No stock remains. Remove this product from the catalogue and release its codes. Previous receipts and history remain available.");
    }

    private java.util.List<UUID> scopes(UUID tenant,boolean global){
        if(!global)return java.util.List.of(tenant);
        // Global removal is restricted to platform administrators by the controller.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT row_security_active('tenants'::regclass)",Boolean.class)))
            throw new IllegalArgumentException("Could not verify all linked accounts. Nothing was changed.");
        return jdbc.queryForList("SELECT id FROM tenants ORDER BY id",UUID.class);
    }
    private void context(UUID tenant){jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());}
    private boolean hasStock(UUID tenant,UUID id,boolean global){
        try{
            for(UUID scope:scopes(tenant,global)){
                context(scope);
                Boolean stock=jdbc.queryForObject("""
                    SELECT EXISTS(
                      SELECT 1 FROM inventory_ledger_entries l JOIN account_catalog_items a
                        ON a.tenant_id=l.tenant_id AND a.id=l.account_catalog_item_id
                      WHERE a.tenant_id=? AND (CASE WHEN ? THEN a.global_product_id ELSE a.id END)=?
                      GROUP BY l.account_catalog_item_id,l.location_id,l.expiration_date
                      HAVING sum(l.quantity)<>0
                    ) OR EXISTS(
                      SELECT 1 FROM order_inventory_reservations r JOIN account_catalog_items a
                        ON a.tenant_id=r.tenant_id AND a.id=r.account_catalog_item_id
                      WHERE a.tenant_id=? AND (CASE WHEN ? THEN a.global_product_id ELSE a.id END)=?
                        AND r.status='ACTIVE' AND r.quantity>0)
                    """,Boolean.class,scope,global,id,scope,global,id);
                if(Boolean.TRUE.equals(stock))return true;
            }
            return false;
        }finally{if(tenant!=null)context(tenant);}
    }
    private void retire(UUID tenant,UUID id,boolean global){
        for(UUID scope:scopes(tenant,global)){
            context(scope);
            jdbc.update("""
                UPDATE account_catalog_items a SET deleted_identity=jsonb_build_object(
                    'account_sku',a.account_sku,'actor',current_setting('app.user_email',true),
                    'vendor_codes',coalesce((SELECT jsonb_agg(jsonb_build_object('vendor_id',o.vendor_id,'code',o.vendor_item_code))
                      FROM vendor_catalog_offers o WHERE o.tenant_id=a.tenant_id AND o.account_catalog_item_id=a.id),'[]'::jsonb)),
                    account_sku=NULL,status='INACTIVE',deleted_at=now(),updated_at=now()
                WHERE a.tenant_id=? AND (CASE WHEN ? THEN a.global_product_id ELSE a.id END)=? AND a.deleted_at IS NULL
                """,scope,global,id);
        }
        if(global){
            jdbc.update("""
                UPDATE global_catalog_products p SET deleted_identity=jsonb_build_object(
                  'actor',current_setting('app.user_email',true),
                  'identifiers',coalesce((SELECT jsonb_agg(to_jsonb(i)) FROM global_product_identifiers i WHERE i.global_product_id=p.id),'[]'::jsonb),
                  'vendor_codes',coalesce((SELECT jsonb_agg(to_jsonb(v)) FROM global_product_vendor_codes v WHERE v.global_product_id=p.id),'[]'::jsonb)),
                  status='ARCHIVED',deleted_at=now(),updated_at=now() WHERE id=? AND deleted_at IS NULL
                """,id);
            jdbc.update("DELETE FROM global_product_identifiers WHERE global_product_id=?",id);
            jdbc.update("DELETE FROM global_product_vendor_codes WHERE global_product_id=?",id);
            jdbc.update("UPDATE global_product_packaging_versions SET status='SUPERSEDED' WHERE global_product_id=? AND status='ACTIVE'",id);
        }
        if(tenant!=null)context(tenant);
    }

    private void lockLogicalReferences(){
        var tables=jdbc.queryForList("SELECT DISTINCT quote_ident(n.nspname)||'.'||quote_ident(c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname=current_schema() AND c.relkind IN ('r','p') AND NOT a.attisdropped AND a.attname IN ('subject_key','source_entity_id','entity_id') ORDER BY 1",String.class);
        for(String table:tables)jdbc.execute("LOCK TABLE "+table+" IN SHARE MODE");
    }

    private Boolean hasDependencies(UUID id,boolean global){
        // Check every account through the existing RLS policies, never by requesting a bypass.
        // Unsupported policies or permissions fail closed; savepoint rollback restores all contexts.
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            var savepoint=connection.setSavepoint();
            try{
                try(var statement=connection.createStatement()){statement.execute("SET LOCAL row_security=on");}
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
                            if(referenceExists(connection,refs.getString(1),refs.getString(2),id,false)){connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return true;}
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
                    while(refs.next())if(referenceExists(connection,refs.getString(1),refs.getString(2),id,true)){connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return true;}
                }
                connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return false;
            }catch(java.sql.SQLException cannotVerify){connection.rollback(savepoint);connection.releaseSavepoint(savepoint);return null;}
        });
    }
    private boolean referenceExists(java.sql.Connection connection,String table,String column,UUID id,boolean text) throws java.sql.SQLException {
        boolean secured;
        try(var query=connection.prepareStatement("SELECT row_security_active(?::regclass)")){
            query.setString(1,table);try(var r=query.executeQuery()){r.next();secured=r.getBoolean(1);}
        }
        String scope=null;boolean platformRead=false;boolean parentMember=false;
        if(secured){
            // Only a plain, permissive scope equality proves complete coverage. Unknown or
            // restrictive policies must not silently hide a dependency from this check.
            try(var query=connection.prepareStatement("SELECT p.polpermissive,pg_get_expr(p.polqual,p.polrelid) FROM pg_policy p WHERE p.polrelid=?::regclass AND p.polcmd IN ('r','*') AND (0=ANY(p.polroles) OR EXISTS(SELECT 1 FROM unnest(p.polroles) role_id WHERE pg_has_role(current_user,role_id,'USAGE')))")){
                query.setString(1,table);try(var r=query.executeQuery()){
                    while(r.next()){
                        String expression=normalizePolicy(r.getString(2));
                        if(!r.getBoolean(1)){
                            String taskAudience=normalizePolicy("""
                                current_setting('app.collaboration_queue_mutation'::text,true)='true'::text OR EXISTS (
                                SELECT 1 FROM collaboration_task_participants p JOIN app_users u ON u.id=p.user_id
                                WHERE p.parent_id=collaboration_tasks.parent_id AND p.task_id=collaboration_tasks.id
                                AND u.status::text='ACTIVE'::text AND lower(u.email::text)=lower(NULLIF(current_setting('app.user_email'::text,true),''::text)))
                                """);
                            String membership=normalizePolicy("EXISTS(SELECT 1 FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.parent_id=collaboration_tasks.parent_id AND m.active AND u.status='ACTIVE' AND lower(u.email)=lower(nullif(current_setting('app.user_email',true),'')))");
                            if(table.equals("public.collaboration_tasks")&&expression.equals(membership)){parentMember=true;continue;}
                            if(!table.equals("public.collaboration_tasks")||!expression.equals(taskAudience))throw new java.sql.SQLException("Unsupported restrictive catalogue dependency policy");
                            try(var context=connection.createStatement()){context.execute("SET LOCAL app.collaboration_queue_mutation='true'");}
                        }
                        if(expression.equals("platform_product_discussion_authorized"))platformRead=true;
                        if(expression.equals("tenant_id=nullifcurrent_setting'app.tenant_id',true,''::uuid"))scope="tenant";
                        if(expression.equals("parent_id=nullifcurrent_setting'app.parent_id',true,''::uuid"))scope="parent";
                    }
                }
            }
            if(scope==null&&platformRead&&table.equals("public.platform_product_discussions")){
                try(var query=connection.createStatement();var result=query.executeQuery("SELECT platform_product_discussion_authorized()")){
                    if(result.next()&&result.getBoolean(1))return probeReference(connection,table,column,id,text);
                }
            }
            if(scope==null)throw new java.sql.SQLException("Cannot verify complete catalogue dependency scope: "+table);
            String scopeColumn=scope.equals("tenant")?"tenant_id":"parent_id";
            try(var query=connection.prepareStatement("SELECT attnotnull FROM pg_attribute WHERE attrelid=?::regclass AND attname=? AND NOT attisdropped")){
                query.setString(1,table);query.setString(2,scopeColumn);
                try(var r=query.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new java.sql.SQLException("Nullable catalogue dependency scope");}
            }
            String scopeTable=scope.equals("tenant")?"tenants":"parent_tenants";
            if(scope.equals("parent")){
                // The existing platform directory policy supplies complete parent IDs only.
                String directory=normalizePolicy("EXISTS(SELECT 1 FROM platform_administrators a JOIN app_users u ON u.id=a.user_id WHERE a.active AND u.status='ACTIVE' AND lower(u.email)=lower(current_setting('app.user_email',true)))");
                boolean complete=false;
                try(var query=connection.createStatement();var policies=query.executeQuery("SELECT polpermissive,pg_get_expr(polqual,polrelid) FROM pg_policy WHERE polrelid='parent_tenants'::regclass AND polcmd IN ('r','*') AND (0=ANY(polroles) OR EXISTS(SELECT 1 FROM unnest(polroles) role_id WHERE pg_has_role(current_user,role_id,'USAGE')))")){
                    while(policies.next()){if(!policies.getBoolean(1))throw new java.sql.SQLException("Restrictive parent directory policy");if(directory.equals(normalizePolicy(policies.getString(2))))complete=true;}
                }
                if(!complete)throw new java.sql.SQLException("Cannot verify parent directory policy");
                try(var query=connection.createStatement();var admin=query.executeQuery("SELECT EXISTS(SELECT 1 FROM platform_administrators a JOIN app_users u ON u.id=a.user_id WHERE a.active AND u.status='ACTIVE' AND lower(u.email)=lower(current_setting('app.user_email',true)))")){
                    if(!admin.next()||!admin.getBoolean(1))throw new java.sql.SQLException("Cannot verify parent directory in caller context");
                }
            }else{
                try(var query=connection.prepareStatement("SELECT row_security_active(?::regclass)")){
                    query.setString(1,scopeTable);try(var r=query.executeQuery()){r.next();if(r.getBoolean(1))throw new java.sql.SQLException("Cannot enumerate catalogue dependency scopes");}
                }
            }
            var scopeIds=new java.util.ArrayList<String>();
            try(var query=connection.createStatement();var scopes=query.executeQuery("SELECT id FROM "+scopeTable)){while(scopes.next())scopeIds.add(scopes.getString(1));}
            for(String scopeId:scopeIds){
                    try(var context=connection.prepareStatement("SELECT set_config(?,?,true)")){
                        context.setString(1,"app."+scope+"_id");context.setString(2,scopeId);context.execute();
                    }
                    if(parentMember){
                        try(var query=connection.createStatement();var member=query.executeQuery("SELECT EXISTS(SELECT 1 FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.parent_id=nullif(current_setting('app.parent_id',true),'')::uuid AND m.active AND u.status='ACTIVE' AND lower(u.email)=lower(current_setting('app.user_email',true)))")){
                            if(!member.next()||!member.getBoolean(1))throw new java.sql.SQLException("Cannot verify parent in caller membership context");
                        }
                    }
                    if(probeReference(connection,table,column,id,text))return true;
            }
            return false;
        }
        return probeReference(connection,table,column,id,text);
    }
    private static String normalizePolicy(String expression){
        if(expression==null)return "";
        var normalized=new StringBuilder();boolean quoted=false;
        for(int i=0;i<expression.length();i++){
            char c=expression.charAt(i);
            if(c=='\''){
                normalized.append(c);
                if(quoted&&i+1<expression.length()&&expression.charAt(i+1)=='\''){normalized.append(expression.charAt(++i));continue;}
                quoted=!quoted;
            }else if(quoted)normalized.append(c);
            else if(expression.regionMatches(true,i,"::text",0,6))i+=5;
            else if(!Character.isWhitespace(c)&&c!='('&&c!=')')normalized.append(Character.toLowerCase(c));
        }
        return normalized.toString();
    }

    private boolean probeReference(java.sql.Connection connection,String table,String column,UUID id,boolean text) throws java.sql.SQLException {
        try(var probe=connection.prepareStatement("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE "+column+(text?"::text":"")+"=?)")){
            probe.setObject(1,text?id.toString():id);try(var result=probe.executeQuery()){result.next();return result.getBoolean(1);}
        }
    }

}
