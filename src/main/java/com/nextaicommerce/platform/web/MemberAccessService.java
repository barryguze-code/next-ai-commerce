package com.nextaicommerce.platform.web;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account-scoped membership changes. Never deletes a login or another account's access. */
@Service
public class MemberAccessService {
    private final JdbcTemplate jdbc;
    public MemberAccessService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private void scope(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    }
    public record Access(String role, boolean restricted, Set<UUID> stores) {}
    @Transactional(readOnly=true)
    public Access current(UUID tenant,String email) {
        scope(tenant);
        var rows=jdbc.query("""
            SELECT m.role,m.stores_restricted,u.id FROM tenant_memberships m
            JOIN app_users u ON u.id=m.user_id JOIN tenants t ON t.id=m.tenant_id
            WHERE m.tenant_id=? AND lower(u.email)=lower(?) AND u.status='ACTIVE' AND t.status='ACTIVE'
            """,(rs,n)->new Object[]{rs.getString(1),rs.getBoolean(2),rs.getObject(3,UUID.class)},tenant,email);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();String role=(String)row[0];
        boolean restricted=!("OWNER".equals(role)||("ADMIN".equals(role)&&!(boolean)row[1]));
        var stores=jdbc.query("""
            SELECT marketplace_connection_id FROM membership_store_access WHERE tenant_id=? AND user_id=?
            """,(rs,n)->rs.getObject(1,UUID.class),tenant,row[2]);
        return new Access(role,restricted,Set.copyOf(stores));
    }

    @Transactional
    public void revokeStore(UUID tenant,UUID target,UUID store,String email) {
        scope(tenant);
        jdbc.queryForObject("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",UUID.class,tenant);
        var access=current(tenant,jdbc.queryForObject("SELECT email FROM app_users WHERE id=?",String.class,target));
        if(access==null)throw new IllegalArgumentException("This user no longer belongs to this account.");
        Set<UUID> remaining=new HashSet<>(access.restricted()?access.stores():jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND status<>'DISABLED'",UUID.class,tenant));
        if(!remaining.remove(store))throw new IllegalArgumentException("This store is not assigned to this user.");
        update(tenant,target,email,access.role(),remaining,remaining.isEmpty());
    }

    @Transactional
    public void update(UUID tenant,UUID target,String email,String role,Set<UUID> stores,boolean revoke) {
        scope(tenant);
        // Serialize administrators' changes, including concurrent last-admin removals.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",UUID.class,tenant);
        var actors=jdbc.query("""
            SELECT u.id, EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=u.id AND p.active)
            FROM app_users u WHERE lower(u.email)=lower(?) AND u.status='ACTIVE'
            AND (EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=u.id AND p.active)
              OR EXISTS(SELECT 1 FROM tenant_memberships m WHERE m.tenant_id=? AND m.user_id=u.id AND m.role IN ('OWNER','ADMIN')))
            """,(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getBoolean(2)},email,tenant);
        if(actors.isEmpty())throw new AccessDeniedException("Only this account's administrators can manage access.");
        UUID actor=(UUID)actors.getFirst()[0];boolean platform=(boolean)actors.getFirst()[1];
        var members=jdbc.query("""
            SELECT m.role, EXISTS(SELECT 1 FROM platform_administrators p WHERE p.user_id=m.user_id AND p.active)
            FROM tenant_memberships m WHERE m.tenant_id=? AND m.user_id=? FOR UPDATE
            """,(rs,n)->new Object[]{rs.getString(1),rs.getBoolean(2)},tenant,target);
        if(members.isEmpty())throw new IllegalArgumentException("This user no longer belongs to this account. Refresh the page.");
        String previous=(String)members.getFirst()[0];
        validateChange(actor.equals(target),"OWNER".equals(previous)||(boolean)members.getFirst()[1],role,revoke);
        if("ADMIN".equals(previous) && (revoke || !"ADMIN".equals(role))) {
            int others=jdbc.queryForObject("""
                SELECT count(*) FROM tenant_memberships m JOIN app_users u ON u.id=m.user_id
                WHERE m.tenant_id=? AND m.user_id<>? AND m.role IN ('OWNER','ADMIN') AND u.status='ACTIVE'
                """,Integer.class,tenant,target);
            if(others==0)throw new IllegalArgumentException("Keep at least one active administrator in this account.");
        }
        var before=jdbc.queryForList("SELECT marketplace_connection_id FROM membership_store_access WHERE tenant_id=? AND user_id=?",UUID.class,tenant,target);
        if(!revoke) {
            var valid=Set.copyOf(jdbc.queryForList("SELECT id FROM marketplace_connections WHERE tenant_id=? AND status<>'DISABLED'",UUID.class,tenant));
            if(!valid.containsAll(stores))throw new IllegalArgumentException("Choose stores belonging to this account.");
            if(stores.isEmpty())throw new IllegalArgumentException("Select a store, or use Revoke account access to remove all access.");
            jdbc.update("UPDATE tenant_memberships SET role=?,stores_restricted=true WHERE tenant_id=? AND user_id=?",role,tenant,target);
            jdbc.update("DELETE FROM membership_store_access WHERE tenant_id=? AND user_id=?",tenant,target);
            for(UUID store:stores)jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",tenant,target,store,actor);
        }else {
            jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,target);
        }
        // Include previous and resulting grants without interpolating user-controlled SQL.
        jdbc.update("""
            INSERT INTO audit_events(tenant_id,actor_user_id,actor_is_super_admin,action,target_type,target_id,reason,details)
            VALUES (?,?,?,?, 'USER',?,'Account administrator managed user access',
              jsonb_build_object('previousRole',?::text,'role',?::text,'previousStores',?::text,'stores',?::text))
            """,tenant,actor,platform,revoke?"MEMBERSHIP_REVOKED":"MEMBERSHIP_UPDATED",target.toString(),previous,revoke?null:role,before.toString(),revoke?"[]":stores.toString());
    }
    static void validateChange(boolean self,boolean protectedMember,String role,boolean revoke) {
        if(self)throw new IllegalArgumentException("Ask another administrator to change your own access.");
        if(protectedMember)throw new IllegalArgumentException("Owner and platform-administrator access cannot be changed here.");
        if(!revoke&&!Set.of("ADMIN","OPERATOR","VIEWER").contains(role))throw new IllegalArgumentException("Choose Administrator, Operator, or Viewer.");
    }
}
