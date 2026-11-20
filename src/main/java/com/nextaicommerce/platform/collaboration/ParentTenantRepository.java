package com.nextaicommerce.platform.collaboration;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Explicit collaboration grouping. This never grants child-account membership. */
@Repository
public class ParentTenantRepository {
    public record Parent(UUID id,String name) {}
    public record Person(UUID id,String name,String email) {}
    private final JdbcTemplate jdbc;
    public ParentTenantRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private void context(UUID parent,String email){
        jdbc.queryForObject("SELECT set_config('app.parent_id',?,true)",String.class,parent==null?"":parent.toString());
        jdbc.queryForObject("SELECT set_config('app.user_email',?,true)",String.class,email);
    }
    @Transactional(readOnly=true)
    public List<Parent> available(String email){
        context(null,email);
        return jdbc.query("""
            SELECT p.id,p.name FROM parent_tenants p JOIN parent_tenant_memberships m ON m.parent_id=p.id
            JOIN app_users u ON u.id=m.user_id WHERE lower(u.email)=lower(?) AND u.status='ACTIVE' AND m.active
            ORDER BY p.name,p.id LIMIT 100
            """,(rs,n)->new Parent(rs.getObject(1,UUID.class),rs.getString(2)),email);
    }
    @Transactional(readOnly=true)
    public List<Person> members(UUID parent,String email){
        context(parent,email);requireMember(parent,email);
        return jdbc.query("SELECT u.id,u.display_name,u.email FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.parent_id=? AND m.active AND u.status='ACTIVE' ORDER BY lower(u.display_name),u.id LIMIT 500",
            (rs,n)->new Person(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),parent);
    }
    @Transactional(readOnly=true)
    public List<Parent> staffDirectory(String email) {
        context(null,email);
        administrator(email);
        return jdbc.query("SELECT id,name FROM parent_tenants ORDER BY name,id LIMIT 100",
                (row,n)->new Parent(row.getObject(1,UUID.class),row.getString(2)));
    }
    private UUID requireMember(UUID parent,String email){
        var ids=jdbc.queryForList("SELECT u.id FROM parent_tenant_memberships m JOIN app_users u ON u.id=m.user_id WHERE m.parent_id=? AND m.active AND lower(u.email)=lower(?) AND u.status='ACTIVE'",UUID.class,parent,email);
        if(ids.size()!=1)throw new AccessDeniedException("This parent tenant is not available.");return ids.getFirst();
    }
    private UUID administrator(String email){
        var ids=jdbc.queryForList("SELECT u.id FROM app_users u JOIN platform_administrators a ON a.user_id=u.id WHERE lower(u.email)=lower(?) AND u.status='ACTIVE' AND a.active",UUID.class,email);
        if(ids.size()!=1)throw new AccessDeniedException("A platform administrator must configure parent tenants.");return ids.getFirst();
    }
    @Transactional(readOnly=true)
    public List<Person> setupPeople(String email){administrator(email);return jdbc.query("SELECT id,display_name,email FROM app_users WHERE status='ACTIVE' ORDER BY lower(display_name),id LIMIT 500",(rs,n)->new Person(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)));}
    @Transactional
    public UUID create(String email,String name,Set<UUID> accounts,Set<UUID> people,UUID manager,String managerRole){
        UUID actor=administrator(email);if(name==null||name.isBlank()||name.length()>160||accounts.isEmpty()||accounts.size()>100||people.size()>500)throw new IllegalArgumentException("Enter a parent name and select its accounts and people.");
        if(manager==null || !Set.of("OWNER","ADMIN").contains(managerRole))throw new IllegalArgumentException("Choose the tenant manager and role.");
        UUID parent=UUID.randomUUID();context(parent,email);
        // Lock accounts in stable order; the unique association constraint also fences concurrent setup.
        for(UUID account:new TreeSet<>(accounts)){
            var rows=jdbc.queryForList("SELECT id FROM tenants WHERE id=? AND status='ACTIVE' FOR UPDATE",UUID.class,account);
            if(rows.isEmpty())throw new IllegalArgumentException("Choose active accounts.");
        }
        jdbc.update("INSERT INTO parent_tenants(id,name,created_by) VALUES (?,?,?)",parent,name.trim(),actor);
        var members=new LinkedHashSet<>(people);members.add(manager);
        for(UUID user:members){
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM app_users WHERE id=? AND status='ACTIVE')",Boolean.class,user)))throw new IllegalArgumentException("Choose active people.");
            jdbc.update("INSERT INTO parent_tenant_memberships(parent_id,user_id,role,home_membership) VALUES (?,?,?,true)",parent,user,user.equals(manager)?managerRole:"MEMBER");
        }
        for(UUID account:accounts)jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?)",parent,account);
        // JSON consists only of server-validated UUIDs; no credentials or arbitrary field content.
        String details="{\"accounts\":["+accounts.stream().sorted().map(id->"\""+id+"\"").collect(java.util.stream.Collectors.joining(","))+"],\"members\":["+members.stream().sorted().map(id->"\""+id+"\"").collect(java.util.stream.Collectors.joining(","))+"]}";
        jdbc.update("INSERT INTO parent_tenant_events(parent_id,actor_id,event_kind,details) VALUES (?,?,'CREATED',CAST(? AS jsonb))",parent,actor,details);
        return parent;
    }
    @Transactional(readOnly=true)
    public Set<UUID> accounts(UUID parent,String email){context(parent,email);requireMember(parent,email);return new LinkedHashSet<>(jdbc.queryForList("SELECT account_id FROM parent_tenant_accounts WHERE parent_id=?",UUID.class,parent));}
}
