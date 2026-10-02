package com.nextaicommerce.platform.web;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.access.AccessDeniedException;

/** Fixed isolated test destination. No application/production connection configuration is used. */
class MemberAccessDatabaseTest {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    UUID tenant,other,admin,user,store,second,foreign;String actor,email;
    MemberAccessService service;WorkspaceAccessRepository access;
    @BeforeAll static void setup() throws Exception {
        schema="member_access_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();
        }else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbc!=null&&schema!=null&&schema.matches("member_access_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }
    @BeforeEach void fixture(){
        tenant=UUID.randomUUID();other=UUID.randomUUID();admin=UUID.randomUUID();user=UUID.randomUUID();store=UUID.randomUUID();second=UUID.randomUUID();foreign=UUID.randomUUID();actor=admin+"@example.test";email=user+"@example.test";
        service=new MemberAccessService(jdbc);access=new WorkspaceAccessRepository(jdbc);
        tx.executeWithoutResult(s->{
            for(UUID t:List.of(tenant,other))jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,?)",t,t.toString(),"Test account");
            jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Admin'),(?,?,'Member')",admin,actor,user,email);
            jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'ADMIN'),(?,?,'ADMIN'),(?,?,'VIEWER')",tenant,admin,tenant,user,other,user);
            for(UUID c:List.of(store,second,foreign))jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,inventory_activated_at,reporting_timezone) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test://none','ACTIVE','Test store',now(),'America/Los_Angeles')",c,c.equals(foreign)?other:tenant,c.toString());
            jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?),(?,?,?,?),(?,?,?,?)",tenant,user,store,admin,tenant,user,second,admin,other,user,foreign,admin);
        });
    }
    @Test void selectedStoreRevocationWorksForAdminAndPreservesOtherAccount(){
        tx.executeWithoutResult(s->service.update(tenant,user,actor,"ADMIN",Set.of(store),false));
        tx.executeWithoutResult(s->{
            assertThat(service.current(tenant,email).stores()).containsExactly(store);assertThat(service.current(tenant,email).restricted()).isTrue();
            assertThat(service.current(other,email).stores()).containsExactly(foreign);
            var option=access.listAccountOptions(email,false).stream().filter(a->a.id().equals(tenant)).findFirst().orElseThrow();assertThat(option.stores()).extracting(WorkspaceAccessRepository.ConnectionView::id).containsExactly(store);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id=? AND target_id=?",Integer.class,tenant,user.toString())).isEqualTo(1);
        });
    }
    @Test void revokeAllRemovesOnlySelectedMembership(){
        tx.executeWithoutResult(s->service.update(tenant,user,actor,"VIEWER",Set.of(),true));
        tx.executeWithoutResult(s->{assertThat(service.current(tenant,email)).isNull();assertThat(service.current(other,email)).isNotNull();});
    }
    @Test void directStoreRevocationPreservesOtherStoresAndAccounts(){
        service.revokeStore(tenant,user,store,actor);
        assertThat(service.current(tenant,email).stores()).containsExactly(second);
        assertThat(service.current(other,email).stores()).containsExactly(foreign);
        service.revokeStore(tenant,user,second,actor);
        assertThat(service.current(tenant,email)).isNull();
        assertThat(service.current(other,email)).isNotNull();
    }
    @Test void directStoreRevocationRejectsForeignStoreAndSelf(){
        assertThatThrownBy(()->service.revokeStore(tenant,user,foreign,actor)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.revokeStore(tenant,admin,store,actor)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.current(tenant,email).role()).isEqualTo("ADMIN");
    }
    @Test void platformAdministratorAppearsWithoutAccountMembership(){
        jdbc.update("INSERT INTO platform_administrators(user_id) VALUES (?)",admin);
        jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,admin);
        var member=access.listMembers(tenant).stream().filter(m->m.id().equals(admin)).findFirst().orElseThrow();
        assertThat(member.role()).isEqualTo("SUPER ADMIN");assertThat(member.protectedMember()).isTrue();
        assertThat(member.storeIds()).containsExactlyInAnyOrder(store,second);
    }
    @Test void ownPortraitIsGlobalAndViewerCanUploadIt() throws Exception {
        var controller=new com.nextaicommerce.platform.orders.RecordPictureController(jdbc,tx,access);
        var session=new org.springframework.mock.web.MockHttpSession();
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(email,"",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER")));
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        assertThat(controller.ownUpload(new org.springframework.mock.web.MockMultipartFile("image","portrait.png","image/png",out.toByteArray()),session,auth).getStatusCode().value()).isEqualTo(200);
        session.setAttribute("selectedTenantId",other);
        assertThat(controller.ownPicture(session,auth).getBody()).isEqualTo(out.toByteArray());
    }
    @Test void cannotGrantForeignStoreAndRollsBackRoleChange(){
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.update(tenant,user,actor,"VIEWER",Set.of(foreign),false))).isInstanceOf(IllegalArgumentException.class);
        tx.executeWithoutResult(s->assertThat(service.current(tenant,email).role()).isEqualTo("ADMIN"));
    }
    @Test void adminInAnotherAccountCannotManageThisAccount(){
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.update(other,user,actor,"VIEWER",Set.of(foreign),false))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void cannotChangeSelfOrOwner(){
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.update(tenant,admin,actor,"VIEWER",Set.of(store),false))).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE tenant_memberships SET role='OWNER' WHERE tenant_id=? AND user_id=?",tenant,user);
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.update(tenant,user,actor,"VIEWER",Set.of(store),false))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresStoreOrExplicitRevoke(){
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.update(tenant,user,actor,"VIEWER",Set.of(),false))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void protectsLastAdminEvenFromPlatformAdmin(){
        jdbc.update("INSERT INTO platform_administrators(user_id) VALUES (?)",admin);
        jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=? AND user_id=?",tenant,admin);
        assertThatThrownBy(()->tx.executeWithoutResult(s->service.update(tenant,user,actor,"VIEWER",Set.of(store),false))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }
    @Test void overviewProjectionReflectsEffectiveAdminGrants(){
        tx.executeWithoutResult(s->assertThat(access.listMembers(tenant).stream().filter(m->m.id().equals(user)).findFirst().orElseThrow().storeIds()).containsExactlyInAnyOrder(store,second));
        tx.executeWithoutResult(s->service.update(tenant,user,actor,"VIEWER",Set.of(store),false));
        tx.executeWithoutResult(s->assertThat(access.listMembers(tenant).stream().filter(m->m.id().equals(user)).findFirst().orElseThrow().storeIds()).containsExactly(store));
    }
    @Test void accountPortraitUploadAndReadAreScoped() throws Exception {
        var controller=new com.nextaicommerce.platform.orders.RecordPictureController(jdbc,tx,access);
        var session=new org.springframework.mock.web.MockHttpSession();session.setAttribute("selectedTenantId",tenant);
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,"",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
        var response=controller.upload("USER",user,new org.springframework.mock.web.MockMultipartFile("image","portrait.png","image/png",out.toByteArray()),session,auth);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(controller.get("USER",user,session,auth).getBody()).isEqualTo(out.toByteArray());
        session.setAttribute("selectedTenantId",other);
        assertThatThrownBy(()->controller.get("USER",user,session,auth)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void collaborationAssignmentsValidateAccountAndRecordHistoryAndNotification(){
        var repository=new com.nextaicommerce.platform.collaboration.CollaborationRepository(jdbc);
        tx.executeWithoutResult(s->{
            var posted=repository.create(tenant,"PLATFORM","GENERAL","Team",null,"CONVERSATION","Task","Check stock","TEAM_CHAT","{}",null,actor,null,null,List.of());
            var changed=repository.assign(tenant,posted.reviewId(),actor,Set.of(user),true);
            assertThat(changed).isNotNull();assertThat(repository.review(tenant,posted.reviewId(),email).assignedTo(email)).isTrue();
            assertThat(repository.assign(tenant,posted.reviewId(),actor,Set.of(user),true)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM collaboration_mentions WHERE tenant_id=? AND notification_kind='ASSIGNED'",Integer.class,tenant)).isEqualTo(1);
            assertThat(repository.messages(tenant,posted.reviewId(),actor,"TEAM_CHAT")).anyMatch(m->m.body().contains("Admin changed assignment from unassigned to Member"));
            repository.assign(tenant,posted.reviewId(),actor,Set.of(),true);
            assertThat(repository.review(tenant,posted.reviewId(),email).assignedTo(email)).isFalse();
            assertThat(repository.messages(tenant,posted.reviewId(),actor,"TEAM_CHAT")).anyMatch(m->m.body().contains("from Member to unassigned"));
        });
    }
    @Test void collaborationCannotAssignOutsiderOrAssignAsViewer(){
        var repository=new com.nextaicommerce.platform.collaboration.CollaborationRepository(jdbc);
        var posted=tx.execute(s->repository.create(tenant,"PLATFORM","GENERAL","Team",null,"CONVERSATION","Task","Check stock","TEAM_CHAT","{}",null,actor,null,null,List.of()));
        assertThatThrownBy(()->tx.execute(s->repository.assign(tenant,posted.reviewId(),actor,Set.of(UUID.randomUUID()),true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.execute(s->repository.assign(other,posted.reviewId(),email,Set.of(user),true))).isInstanceOf(AccessDeniedException.class);
    }
}
