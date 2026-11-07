package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class CollaborationQueueDatabaseTest {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    CollaborationTaskQueueRepository queue;
    UUID tenant,other,user,stranger;String email,otherEmail;

    @BeforeAll static void setup() throws Exception {
        schema="collaboration_queue_test_"+UUID.randomUUID().toString().replace("-","");
        String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")) {
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=Files.readString(Path.of(System.getProperty("testCredentialFile",".local/database-password"))).trim();
        }else{
            postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();
            url=postgres.getJdbcUrl("postgres","postgres");schema="public";
        }
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
            .locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).target("129").load().migrate();
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbc!=null&&schema!=null&&schema.matches("collaboration_queue_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }
    @BeforeEach void fixture() {
        queue=new CollaborationTaskQueueRepository(jdbc);
        tenant=UUID.randomUUID();other=UUID.randomUUID();user=UUID.randomUUID();stranger=UUID.randomUUID();email=user+"@example.test";otherEmail=stranger+"@example.test";
        tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Creator'),(?,?,'Other member')",user,email,stranger,otherEmail);
            for(UUID id:List.of(tenant,other))jdbc.update("INSERT INTO parent_tenants(id,name,created_by) VALUES (?,'Parent test',?)",id,user);
            jdbc.update("INSERT INTO parent_tenant_memberships(parent_id,user_id,role) VALUES (?,?,'OWNER'),(?,?,'MEMBER')",tenant,user,tenant,stranger);
        });
    }
    CollaborationTaskQueueRepository.Position create(String title) {
        return tx.execute(s->queue.create(tenant,null,null,title,"Body",email,Set.of()));
    }
    List<UUID> order(){return jdbc.queryForList("SELECT id FROM collaboration_tasks WHERE parent_id=? AND queue_position IS NOT NULL ORDER BY queue_position",UUID.class,tenant);}
    long revision(){return jdbc.queryForObject("SELECT revision FROM collaboration_queue_state WHERE parent_id=?",Long.class,tenant);}
    void contiguous(){assertThat(jdbc.queryForList("SELECT queue_position FROM collaboration_tasks WHERE parent_id=? AND queue_position IS NOT NULL ORDER BY queue_position",Integer.class,tenant)).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,order().size()).boxed().toList());}

    @Test void completionNotifiesAllParticipantsOnceButReopenAndRepeatedCloseDoNot() {
        var publisher=org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class);
        queue=new CollaborationTaskQueueRepository(jdbc,publisher);
        var task=tx.execute(s->queue.create(tenant,null,null,"Completion","",email,Set.of(stranger)));
        org.mockito.Mockito.clearInvocations(publisher);
        tx.execute(s->queue.state(tenant,task.reviewId(),email,"DONE",revision()));
        var event=org.mockito.ArgumentCaptor.forClass(CollaborationTaskQueueRepository.TaskNotification.class);
        org.mockito.Mockito.verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue().kind()).isEqualTo("COMPLETED");
        assertThat(event.getValue().people()).containsExactlyInAnyOrder(user,stranger);
        var mailer=org.mockito.Mockito.mock(com.nextaicommerce.platform.invitation.PlatformMailer.class);
        var listener=new CollaborationTaskNotificationListener(jdbc,queue,mailer,new DataSourceTransactionManager(jdbc.getDataSource()),true,"https://example.test");
        org.mockito.Mockito.doThrow(new IllegalStateException("Fixture delivery failure")).when(mailer).sendHtml(org.mockito.ArgumentMatchers.eq(email),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
        listener.notify(event.getValue());
        org.mockito.Mockito.verify(mailer).sendHtml(org.mockito.ArgumentMatchers.eq(email),org.mockito.ArgumentMatchers.contains("Conversation completed"),org.mockito.ArgumentMatchers.contains("Task completed by"));
        org.mockito.Mockito.verify(mailer).sendHtml(org.mockito.ArgumentMatchers.eq(otherEmail),org.mockito.ArgumentMatchers.contains("Conversation completed"),org.mockito.ArgumentMatchers.contains("Task completed by"));
        tx.execute(s->queue.state(tenant,task.reviewId(),email,"DONE",revision()));
        tx.execute(s->queue.state(tenant,task.reviewId(),email,"OPEN",revision()));
        org.mockito.Mockito.verifyNoMoreInteractions(publisher);
    }

    @Test void participantIsNotNecessarilyAnAssignee() {
        var task=tx.execute(s->queue.create(tenant,null,null,"Assignment truth","",email,Set.of(stranger)));
        var access=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),access);
        assertThat(tx.execute(s->personal.view(email,false,"ACTIVE","")).rows().stream().filter(r->r.card().key().id().equals(task.reviewId())).findFirst().orElseThrow().card().assignedToMe()).isFalse();
        assertThat(tx.execute(s->personal.view(otherEmail,false,"ACTIVE","")).rows().stream().filter(r->r.card().key().id().equals(task.reviewId())).findFirst().orElseThrow().card().assignedToMe()).isTrue();
    }

    @Test void permissionQueryTuningIsTransactionLocalAndRestoresConnectionSetting() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()){
            var source=new SingleConnectionDataSource(connection,true);
            var same=new JdbcTemplate(source);same.execute("SET jit=on");
            var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.executeWithoutResult(status->{
                new CollaborationRepository(same).reviewsByIds(UUID.randomUUID(),List.of(UUID.randomUUID()),email);
                assertThat(same.queryForObject("show jit",String.class)).isEqualTo("off");
            });
            assertThat(same.queryForObject("show jit",String.class)).isEqualTo("on");
        }
    }

    @Test void convertedReviewsStaySuppressedWhenTaskAndSourceHaveOppositeStates() {
        UUID account=UUID.randomUUID(),review=UUID.randomUUID(),task=UUID.randomUUID();
        jdbc.update("insert into tenants(id,slug,display_name) values (?,?,'Origin')",account,account.toString());
        jdbc.update("insert into parent_tenant_accounts(parent_id,account_id) values (?,?)",tenant,account);
        jdbc.update("insert into collaboration_reviews(id,tenant_id,subject_type,subject_key,subject_label,title,requested_by) values (?,?,'GENERAL','test','Source','Source',?)",review,account,email);
        jdbc.update("insert into collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body) values (?,?,?,?,'TEAM_CHAT','visible')",account,review,email,user);
        jdbc.update("insert into collaboration_tasks(id,parent_id,account_id,title,created_by,source_review_id,state,queue_position,closed_at) values (?,?,?,'Converted',?,?,'DONE',null,now())",task,tenant,account,user,review);
        jdbc.update("insert into collaboration_task_participants(parent_id,task_id,user_id,assigned,added_by) values (?,?,?,true,?)",tenant,task,user,user);
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        org.mockito.Mockito.when(accounts.listAccountOptions(email,false)).thenReturn(List.of(new com.nextaicommerce.platform.web.WorkspaceAccessRepository.AccountOption(account,"Origin","O",null,List.of())));
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","")).rows()).isEmpty();
        assertThat(tx.execute(t->personal.view(email,false,"CLOSED","")).rows()).extracting(r->r.card().key().id()).containsExactly(task);
        jdbc.update("update collaboration_tasks set state='OPEN',queue_position=1,closed_at=null where id=?",task);
        jdbc.update("update collaboration_reviews set status='CLOSED',closed_at=now() where id=?",review);
        assertThat(tx.execute(t->personal.view(email,false,"CLOSED","")).rows()).isEmpty();
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","")).rows()).extracting(r->r.card().key().id()).containsExactly(task);
    }

    @Test void unrankedViewsNeverCreateOrLockPersonalOrdering() {
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        org.mockito.Mockito.when(accounts.listAccountOptions(email,false)).thenReturn(List.of());
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        for(String view:List.of("ACTIVE","CLOSED","PRIVATE")) {
            tx.execute(t->personal.view(email,false,view,""));
            assertThat(jdbc.queryForObject("select count(*) from collaboration_personal_queues where user_id=?",Integer.class,user))
                .as(view+" must not reconcile or lock the unrelated personal ordering").isZero();
        }
    }

    @Test void activeLegacyStatusAndMessageSearchShareTheSameAudience() {
        UUID account=UUID.randomUUID(),active=UUID.randomUUID(),closed=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Legacy account')",account,account.toString());
        for(UUID id:List.of(active,closed)){
            jdbc.update("INSERT INTO collaboration_reviews(id,tenant_id,subject_type,subject_key,subject_label,title,requested_by,assigned_to,status) VALUES (?,?,'INVENTORY','stock','Stock','Review',?,?,'ACTIVE')",id,account,email,user);
            jdbc.update("INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body) VALUES (?,?,?,?,'TEAM_CHAT','publicNeedle')",account,id,email,user);
        }
        jdbc.update("UPDATE collaboration_reviews SET status='CLOSED',closed_at=now() WHERE id=?",closed);
        jdbc.update("INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body) VALUES (?,?,?,?,'PRIVATE_NOTE','hiddenNeedle')",account,active,otherEmail,stranger);
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        org.mockito.Mockito.when(accounts.listAccountOptions(email,false)).thenReturn(List.of(
            new com.nextaicommerce.platform.web.WorkspaceAccessRepository.AccountOption(account,"Legacy account","LA",null,List.of())));
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","publicNeedle")).rows()).extracting(r->r.card().key().id()).containsExactly(active);
        assertThat(tx.execute(t->personal.view(email,false,"CLOSED","publicNeedle")).rows()).extracting(r->r.card().key().id()).containsExactly(closed);
        assertThat(tx.execute(t->personal.view(email,false,"ASSIGNED","")).rows()).extracting(r->r.card().key().id()).containsExactly(active);
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","hiddenNeedle")).rows()).isEmpty();
        assertThat(tx.execute(t->personal.view(email,false,"PRIVATE","hiddenNeedle")).rows()).isEmpty();
    }

    @Test void taskAudienceRetainsOriginIdentityWithoutGrantingSourceAccountAccess(){
        UUID account=UUID.randomUUID(),store=UUID.randomUUID(),task=UUID.randomUUID(),source=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Origin account')",account,account.toString());
        jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?)",tenant,account);
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test://none','ACTIVE','Origin store','UTC',now())",store,account,store.toString());
        jdbc.update("INSERT INTO collaboration_reviews(id,tenant_id,subject_type,subject_key,subject_label,title,requested_by) VALUES (?,?,'PLATFORM','fixture','Source','Source',?)",source,account,email);
        jdbc.update("INSERT INTO collaboration_tasks(id,parent_id,account_id,store_id,title,created_by,queue_position,subject_type,source_review_id) VALUES (?,?,?,?,'Shared task',?,1,'PLATFORM',?)",task,tenant,account,store,user,source);
        jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,assigned,added_by) VALUES (?,?,?,true,?)",tenant,task,user,user);
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        var card=tx.execute(t->personal.view(email,false,"ACTIVE","")).rows().getFirst().card();
        assertThat(card.contextAccount()).isEqualTo(account);assertThat(card.contextStore()).isEqualTo(store);assertThat(card.context()).isEqualTo("Origin account");
        assertThat(card.assignedToMe()).isTrue();
        jdbc.update("UPDATE collaboration_task_participants SET assigned=false WHERE task_id=? AND user_id=?",task,user);
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","")).rows().getFirst().card().assignedToMe()).isFalse();
        assertThat(card.sourceRestricted()).isTrue();assertThat(card.account()).isNull();assertThat(card.store()).isNull();
        assertThatThrownBy(()->tx.execute(t->personal.authorized(email,false,new PersonalCollaborationQueue.Key("REVIEW",source)))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->tx.execute(t->personal.authorized(otherEmail,false,new PersonalCollaborationQueue.Key("TASK",task)))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void fbaShipmentCollaborationUsesExactStoreAndRejectsForeignSources(){
        UUID account=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),foreign=UUID.randomUUID();
        for(UUID id:List.of(account,foreign))jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'FBA fixture')",id,id.toString());
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role,stores_restricted) VALUES (?,?,'OPERATOR',true)",account,user);
        for(UUID store:List.of(a,b)){
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test://none','ACTIVE','Store','UTC',now())",store,account,store.toString());
            jdbc.update("INSERT INTO amazon_fba_shipments(tenant_id,marketplace_connection_id,shipment_id) VALUES (?,?,'FBA-SAME')",account,store);
        }
        jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",account,user,a,user);
        var reviews=new CollaborationRepository(jdbc);
        var created=tx.execute(t->reviews.create(account,"FBA_SHIPMENT",a+"|FBA-SAME","FBA shipment FBA-SAME","Amazon","CONVERSATION","FBA shipment","Body","TEAM_CHAT","{}","/app/inventory/fba-shipments?q=FBA-SAME",email,null,null,List.of()));
        assertThat(jdbc.queryForObject("SELECT marketplace_connection_id FROM collaboration_reviews WHERE id=?",UUID.class,created.reviewId())).isEqualTo(a);
        var summaries=tx.execute(t->reviews.openSubjectSummaries(account,"FBA_SHIPMENT",List.of(a+"|FBA-SAME"),email));assertThat(summaries).containsKey(a+"|FBA-SAME");
        assertThatThrownBy(()->tx.execute(t->reviews.create(account,"FBA_SHIPMENT",b+"|FBA-SAME","FBA shipment","Amazon","CONVERSATION","Title","Body","TEAM_CHAT","{}","/app/inventory/fba-shipments",email,null,null,List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tx.execute(t->reviews.create(foreign,"FBA_SHIPMENT",a+"|FBA-SAME","FBA shipment","Amazon","CONVERSATION","Title","Body","TEAM_CHAT","{}","/app/inventory/fba-shipments",email,null,null,List.of()))).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("DELETE FROM membership_store_access WHERE tenant_id=? AND user_id=? AND marketplace_connection_id=?",account,user,a);
        assertThat((Object)tx.execute(t->reviews.review(account,created.reviewId(),email))).isNull();
    }

    @Test void sameAccountStoreRestrictionsProtectLegacySourcesFilesAndMutations(){
        UUID account=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),allowed=UUID.randomUUID(),denied=UUID.randomUUID(),task=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Two stores')",account,account.toString());
        jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?)",tenant,account);
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role,stores_restricted) VALUES (?,?,'OPERATOR',true)",account,user);
        for(UUID store:List.of(a,b))jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test://none','ACTIVE','Store','UTC',now())",store,account,store.toString());
        jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",account,user,a,user);
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role,stores_restricted) VALUES (?,?,'VIEWER',true)",account,stranger);
        jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",account,stranger,b,user);
        var files=new HashMap<UUID,UUID>();
        for(UUID id:List.of(allowed,denied)){
            jdbc.update("INSERT INTO collaboration_reviews(id,tenant_id,marketplace_connection_id,subject_type,subject_key,subject_label,title,requested_by,assigned_to) VALUES (?,?,?,'GENERAL',?,'Source','Store source',?,?)",id,account,id.equals(allowed)?a:b,id.toString(),email,user);
            UUID message=UUID.randomUUID(),file=UUID.randomUUID();files.put(id,file);
            jdbc.update("INSERT INTO collaboration_messages(id,tenant_id,review_id,author_email,sender_id,message_type,body) VALUES (?,?,?,?,?,'TEAM_CHAT','storeNeedle')",message,account,id,email,user);
            jdbc.update("INSERT INTO collaboration_attachments(id,tenant_id,review_id,message_id,uploaded_by_email,file_name,content_type,size_bytes,content) VALUES (?,?,?,?,?,'fixture.txt','text/plain',1,?)",file,account,id,message,email,new byte[]{1});
        }
        jdbc.update("INSERT INTO collaboration_tasks(id,parent_id,account_id,store_id,title,created_by,queue_position,subject_type,source_review_id) VALUES (?,?,?,?,'Shared B task',?,1,'GENERAL',?)",task,tenant,account,b,user,denied);
        jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,assigned,added_by) VALUES (?,?,?,true,?)",tenant,task,user,user);
        var access=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var storeA=new com.nextaicommerce.platform.web.WorkspaceAccessRepository.ConnectionView(a,account,"Two stores","A","AMAZON","a","ATVPDKIKX0DER","Amazon US","US","ACTIVE",null,false);
        org.mockito.Mockito.when(access.listAccountOptions(email,false)).thenReturn(List.of(new com.nextaicommerce.platform.web.WorkspaceAccessRepository.AccountOption(account,"Two stores","TS",null,List.of(storeA))));
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),access);var reviews=new CollaborationRepository(jdbc);
        long before=jdbc.queryForObject("SELECT count(*) FROM collaboration_reviews WHERE tenant_id=?",Long.class,account);
        assertThatThrownBy(()->tx.execute(t->reviews.createStoreTask(account,a,"Cannot assign","Body",email,Set.of(stranger),null,"UTC"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collaboration_reviews WHERE tenant_id=?",Long.class,account)).isEqualTo(before);
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id) VALUES (?,?,'ATVPDKIKX0DER','order-A'),(?,?,'ATVPDKIKX0DER','order-B')",account,a,account,b);
        var created=tx.execute(t->reviews.create(account,"ORDER","order-A","Order A","AMAZON","CONVERSATION","Order A","Body","TEAM_CHAT","{}","/app/orders",email,null,null,List.of()));
        assertThat(jdbc.queryForObject("SELECT marketplace_connection_id FROM collaboration_reviews WHERE id=?",UUID.class,created.reviewId())).isEqualTo(a);
        var assigned=tx.execute(t->reviews.create(account,"ORDER","order-A","Order A","AMAZON","CONVERSATION","Assigned A","Body","TEAM_CHAT","{}","/app/orders",email,user,null,List.of()));
        assertThat(jdbc.queryForObject("SELECT assigned_to FROM collaboration_reviews WHERE id=?",UUID.class,assigned.reviewId())).isEqualTo(user);
        assertThatThrownBy(()->tx.execute(t->reviews.create(account,"ORDER","order-A","Order A","AMAZON","CONVERSATION","Denied assignment","Body","TEAM_CHAT","{}","/app/orders",email,stranger,null,List.of()))).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(()->tx.execute(t->reviews.create(account,"ORDER","order-B","Order B","AMAZON","CONVERSATION","Order B","Body","TEAM_CHAT","{}","/app/orders",email,null,null,List.of()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        UUID legacy=UUID.randomUUID();
        jdbc.update("INSERT INTO collaboration_reviews(id,tenant_id,subject_type,subject_key,subject_label,title,requested_by) VALUES (?,?,'ORDER','order-B','Old B','Old B',?)",legacy,account,email);
        jdbc.update("INSERT INTO collaboration_messages(tenant_id,review_id,author_email,sender_id,message_type,body) VALUES (?,?,?,?,'TEAM_CHAT','legacy B')",account,legacy,email,user);
        assertThat((Object)tx.execute(t->reviews.review(account,legacy,email))).isNull();
        assertThatThrownBy(()->tx.execute(t->reviews.messages(account,legacy,email,"TEAM_CHAT"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("UPDATE collaboration_reviews SET subject_key='missing-order' WHERE id=?",legacy);
        assertThat((Object)tx.execute(t->reviews.review(account,legacy,email))).isNull();
        // A duplicate source key across stores cannot establish a safe source store.
        jdbc.update("UPDATE collaboration_reviews SET subject_key='order-A' WHERE id=?",legacy);
        jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id) VALUES (?,?,'ATVPDKIKX0DER','order-A')",account,b);
        assertThat((Object)tx.execute(t->reviews.review(account,legacy,email))).isNull();
        assertThatThrownBy(()->tx.execute(t->reviews.create(account,"ORDER","order-A","Ambiguous","AMAZON","CONVERSATION","Ambiguous","Body","TEAM_CHAT","{}","/app/orders",email,null,null,List.of()))).isInstanceOf(IllegalArgumentException.class);

        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","storeNeedle")).rows()).extracting(r->r.card().key().id()).contains(allowed).doesNotContain(denied);
        assertThatThrownBy(()->tx.execute(t->personal.authorized(email,false,new PersonalCollaborationQueue.Key("REVIEW",denied)))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var nativeTask=tx.execute(t->personal.authorized(email,false,new PersonalCollaborationQueue.Key("TASK",task)));
        assertThat(nativeTask.sourceRestricted()).isTrue();assertThat(nativeTask.account()).isNull();
        assertThat((Object)tx.execute(t->reviews.review(account,denied,email))).isNull();
        var batchReviews=tx.execute(t->reviews.reviewsByIds(account,List.of(allowed,denied),email));
        assertThat(batchReviews).extracting(CollaborationRepository.Review::id).containsExactly(allowed);
        assertThat((Object)tx.execute(t->reviews.attachment(account,allowed,files.get(denied),email))).isNull();
        assertThat((Object)tx.execute(t->reviews.attachment(account,allowed,files.get(allowed),email))).isNotNull();
        assertThatThrownBy(()->tx.execute(t->reviews.messages(account,denied,email,"TEAM_CHAT"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->tx.execute(t->reviews.reply(account,denied,email,"not allowed","TEAM_CHAT",null,List.of()))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",account,user,b,user);
        assertThat((Object)tx.execute(t->reviews.review(account,denied,email))).isNotNull();
        // Even when both stores are granted, URL context A cannot retrieve B's file.
        assertThat((Object)tx.execute(t->reviews.attachment(account,allowed,files.get(denied),email))).isNull();
        assertThat((Object)tx.execute(t->reviews.attachment(account,denied,files.get(denied),email))).isNotNull();
        jdbc.update("DELETE FROM membership_store_access WHERE tenant_id=? AND user_id=? AND marketplace_connection_id=?",account,user,b);
        assertThat((Object)tx.execute(t->reviews.attachment(account,denied,files.get(denied),email))).isNull();
        assertThat((Object)tx.execute(t->reviews.review(account,denied,email))).isNull();
        var general=tx.execute(t->reviews.create(account,"GENERAL","general","General",null,"CONVERSATION","General","Body","TEAM_CHAT","{}","/app/collaboration",email,null,null,List.of()));
        assertThat(tx.execute(t->reviews.queueMentions(account,general.reviewId(),general.messageId(),Set.of(stranger))).intValue()).isEqualTo(1);
        jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id=? AND user_id=?",account,stranger);
        var pending=tx.execute(t->reviews.claimPendingMentions(account,20));assertThat(pending).isEmpty();
    }

    @Test void personalTabsKeepAudienceAndInsertAfterUsesTargetBoundary() {
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        var a=create("First").reviewId();var b=create("Second").reviewId();
        var shared=tx.execute(t->queue.create(tenant,null,null,"Mentioned only","",otherEmail,Set.of(stranger))).reviewId();
        tx.executeWithoutResult(t->jdbc.update("INSERT INTO collaboration_task_participants(parent_id,task_id,user_id,assigned,added_by) VALUES (?,?,?,false,?)",tenant,shared,user,stranger));
        var page=tx.execute(t->personal.load(email,false,null,null,null));
        var moved=tx.execute(t->personal.load(email,false,new PersonalCollaborationQueue.Key("TASK",a),new PersonalCollaborationQueue.Key("TASK",b),page.revision(),true));
        // A fresh repository has no session or browser cache: ranks come from persisted rows.
        var restarted=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        var restored=tx.execute(t->restarted.load(email,false,null,null,null));
        assertThat(restored.rows().stream().map(r->r.card().key()).toList()).isEqualTo(moved.rows().stream().map(r->r.card().key()).toList());
        assertThat(moved.rows()).extracting(r->r.card().key().id()).containsExactly(b,a);
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","")).rows()).extracting(r->r.card().key().id()).containsExactlyInAnyOrder(a,b,shared);
        assertThat(tx.execute(t->personal.view(email,false,"ACTIVE","")).rows()).extracting(r->r.card().subjectType()).containsOnly("PLATFORM");
        assertThat(tx.execute(t->personal.view(email,false,"PRIVATE","")).rows()).isEmpty();
        assertThat(tx.execute(t->personal.view(email,false,"ASSIGNED","")).rows()).extracting(r->r.card().key().id()).containsExactly(b,a);
    }

    @Test void personalSearchIncludesUnloadedMessageBodiesAndKeepsPrivateTasksOut() {
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        for(int i=0;i<7;i++) create("Earlier "+i);
        var target=tx.execute(s->queue.create(tenant,null,null,"Plain title","Special conversation needle",email,Set.of())).reviewId();
        tx.execute(s->queue.create(tenant,null,null,"Hidden needle","Secret needle",otherEmail,Set.of()));
        var page=tx.execute(s->personal.load(email,false,null,null,null));
        var result=tx.execute(s->personal.search(page,email,"needle"));
        assertThat(result.rows()).extracting(r->r.card().key().id()).containsExactly(target);
        assertThat(result.rows().getFirst().position()).isEqualTo(8);
    }

    @Test void personalMoveIsPrivateAndMissedReopenGoesFirstForBothAssignees() {
        var accounts=org.mockito.Mockito.mock(com.nextaicommerce.platform.web.WorkspaceAccessRepository.class);
        var personal=new PersonalCollaborationQueue(jdbc,new ParentTenantRepository(jdbc),accounts);
        var a=tx.execute(s->queue.create(tenant,null,null,"First","",email,Set.of(user,stranger))).reviewId();
        var b=tx.execute(s->queue.create(tenant,null,null,"Second","",email,Set.of(user,stranger))).reviewId();
        var first=tx.execute(s->personal.load(email,false,null,null,null));
        var second=tx.execute(s->personal.load(otherEmail,false,null,null,null));
        var ka=new PersonalCollaborationQueue.Key("TASK",a);
        var kb=new PersonalCollaborationQueue.Key("TASK",b);
        var moved=tx.execute(s->personal.load(email,false,kb,ka,first.revision()));
        assertThat(moved.rows()).extracting(r->r.card().key()).containsExactly(kb,ka);
        assertThat(tx.execute(s->personal.load(otherEmail,false,null,null,null)).rows()).isEqualTo(second.rows());
        assertThat(order()).containsExactly(a,b);
        tx.execute(s->queue.state(tenant,b,email,"DONE",revision()));
        assertThat(tx.execute(s->personal.load(email,false,null,null,null)).rows().getLast().position()).isNull();
        tx.execute(s->queue.state(tenant,b,email,"OPEN",revision()));
        for(String owner:List.of(email,otherEmail))
            assertThat(tx.execute(s->personal.load(owner,false,null,null,null)).rows()).extracting(r->r.card().key()).containsExactly(kb,ka);
    }

    @Test void discussionConversionCopiesSharedMessagesAndFilesOnceAndProtectsLinkedTitle() {
        UUID account = UUID.randomUUID(), source = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Discussion account')", account, account.toString());
        jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?)", tenant, account);
        jdbc.update("INSERT INTO collaboration_reviews(id,tenant_id,subject_type,subject_key,subject_label,title,requested_by) VALUES (?,?,'INVENTORY','test','Stock','Count',?)",source,account,email);
        for (String kind : List.of("TEAM_CHAT","PRIVATE_NOTE")) {
            UUID message = UUID.randomUUID();
            jdbc.update("INSERT INTO collaboration_messages(id,tenant_id,review_id,author_email,sender_id,message_type,body) VALUES (?,?,?,?,?,?,?)",message,account,source,email,user,kind,kind);
            jdbc.update("INSERT INTO collaboration_attachments(tenant_id,review_id,message_id,uploaded_by_email,file_name,content_type,size_bytes,content) VALUES (?,?,?,?,?,'text/plain',1,?)",account,source,message,email,kind,new byte[]{1});
        }
        var discussions = org.mockito.Mockito.mock(CollaborationRepository.class);
        var review = new CollaborationRepository.Review(source,"INVENTORY","test","Stock",null,"TEAM_REVIEW","Count","ACTIVE",email,null,null,null,java.time.Instant.now(),java.time.Instant.now(),null,2,1,null,"{}",null,false);
        org.mockito.Mockito.when(discussions.review(account,source,email)).thenReturn(review);
        var service = new DiscussionTaskConversion(jdbc,discussions,new ParentTenantRepository(jdbc),queue);
        var link=tx.execute(s->service.convert(account,source,email));
        assertThat(tx.execute(s->service.convert(account,source,email)).task()).isEqualTo(link.task());
        assertThat(jdbc.queryForList("SELECT body FROM collaboration_task_messages WHERE task_id=?",String.class,link.task())).containsExactly("TEAM_CHAT");
        assertThat(jdbc.queryForList("SELECT file_name FROM collaboration_task_files WHERE task_id=?",String.class,link.task())).containsExactly("TEAM_CHAT");
        var board=new CollaborationBoardRepository(jdbc,queue);
        assertThatThrownBy(()->tx.execute(s->board.taskTitle(tenant,link.task(),otherEmail))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void homeAdminInheritsOnlyItsAccountsAndRevocationPreservesDirectRole() {
        UUID account = UUID.randomUUID(), separate = UUID.randomUUID();
        for(UUID id:List.of(account,separate))
            jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Fixture')",id,id.toString());
        jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?),(?,?)",tenant,account,other,separate);
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role,stores_restricted) VALUES (?,?,'VIEWER',true)",account,user);
        var members=new com.nextaicommerce.platform.web.MemberAccessService(jdbc);
        assertThat(tx.execute(s->members.current(account,email)).role()).isEqualTo("VIEWER");
        jdbc.update("UPDATE parent_tenant_memberships SET home_membership=true WHERE parent_id=? AND user_id=?",tenant,user);
        assertThat(tx.execute(s->members.current(account,email)).role()).isEqualTo("OWNER");
        assertThat(tx.<com.nextaicommerce.platform.web.MemberAccessService.Access>execute(s->members.current(separate,email))).isNull();
        assertThatThrownBy(()->jdbc.update("INSERT INTO parent_tenant_memberships(parent_id,user_id,role,home_membership) VALUES (?,?,'ADMIN',true)",other,user)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("UPDATE parent_tenant_memberships SET active=false WHERE parent_id=? AND user_id=?",tenant,user);
        assertThat(tx.execute(s->members.current(account,email)).role()).isEqualTo("VIEWER");
    }

    @Test void guestAcceptanceRechecksStoreAuthorityAndCreatesOnlyAnAccountGrant() {
        UUID account=UUID.randomUUID(),store=UUID.randomUUID();
        jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Guest fixture')",account,account.toString());
        jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?)",tenant,account);
        jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role,stores_restricted) VALUES (?,?,'ADMIN',true)",account,user);
        jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test://none','ACTIVE','Test','UTC',now())",store,account,store.toString());
        jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",account,user,store,user);
        var invitations=new com.nextaicommerce.platform.invitation.InvitationService(new com.nextaicommerce.platform.invitation.JdbcInvitationRepository(jdbc));
        var invite=tx.execute(s->invitations.create(account,user,otherEmail,com.nextaicommerce.platform.invitation.InvitationRole.VIEWER,Set.of(store),null));
        jdbc.update("UPDATE tenant_invitations SET tenant_access_kind='GUEST',home_or_guest_tenant=? WHERE id=?",tenant,invite.invitationId());
        jdbc.update("DELETE FROM membership_store_access WHERE tenant_id=? AND user_id=?",account,user);
        assertThatThrownBy(()->tx.executeWithoutResult(s->invitations.accept(account,stranger,otherEmail,invite.token())))
            .isInstanceOf(com.nextaicommerce.platform.invitation.InvitationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM tenant_invitations WHERE id=?",String.class,invite.invitationId())).isEqualTo("PENDING");
        jdbc.update("INSERT INTO membership_store_access(tenant_id,user_id,marketplace_connection_id,granted_by) VALUES (?,?,?,?)",account,user,store,user);
        tx.executeWithoutResult(s->invitations.accept(account,stranger,otherEmail,invite.token()));
        assertThat(jdbc.queryForList("SELECT account_id FROM guest_account_grants WHERE user_id=?",UUID.class,stranger)).containsExactly(account);
        assertThat(jdbc.queryForList("SELECT marketplace_connection_id FROM membership_store_access WHERE user_id=?",UUID.class,stranger)).containsExactly(store);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM parent_tenant_memberships WHERE user_id=? AND home_membership",Integer.class,stranger)).isZero();
    }

    @Test void moveCloseReopenPreservesHiddenOrderAndOneSharedPosition() {
        var a=create("A");var b=create("B");var c=create("C");
        var moved=tx.execute(s->queue.move(tenant,c.reviewId(),email,1,c.revision()));
        assertThat(order()).containsExactly(c.reviewId(),a.reviewId(),b.reviewId());contiguous();
        var done=tx.execute(s->queue.state(tenant,a.reviewId(),email,"DONE",moved.revision()));
        assertThat(order()).containsExactly(c.reviewId(),b.reviewId());contiguous();
        var reopened=tx.execute(s->queue.state(tenant,a.reviewId(),email,"IN_PROGRESS",done.revision()));
        assertThat(order()).containsExactly(a.reviewId(),c.reviewId(),b.reviewId());contiguous();
        assertThat(reopened.position()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collaboration_task_events WHERE parent_id=?",Integer.class,tenant)).isEqualTo(6);
    }
    @Test void nonparticipantAndCrossTenantFailWithoutLeakingRevision() {
        var a=create("Private");
        assertThatThrownBy(()->tx.execute(s->queue.move(tenant,a.reviewId(),otherEmail,1,-1))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->tx.execute(s->queue.move(other,a.reviewId(),email,1,-1))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(revision()).isEqualTo(a.revision());
    }
    @Test void staleRequestsDoNotShiftAnythingAndConstraintRejectsActiveWithoutPosition() {
        var a=create("A");var b=create("B");
        assertThatThrownBy(()->tx.execute(s->queue.move(tenant,b.reviewId(),email,1,a.revision()))).isInstanceOf(CollaborationTaskQueueRepository.QueueChanged.class);
        assertThat(order()).containsExactly(a.reviewId(),b.reviewId());
        assertThatThrownBy(()->tx.executeWithoutResult(s->jdbc.update("UPDATE collaboration_tasks SET queue_position=NULL WHERE id=?",a.reviewId()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        contiguous();
    }
    @Test void simultaneousMovesHaveOneWinnerAndNoDuplicatePositions() throws Exception {
        var a=create("A");var b=create("B");var c=create("C");long expected=c.revision();
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(UUID id:List.of(b.reviewId(),c.reviewId()))futures.add(pool.submit(()->{
                ready.countDown();go.await();
                try{tx.execute(s->queue.move(tenant,id,email,1,expected));return true;}
                catch(CollaborationTaskQueueRepository.QueueChanged conflict){return false;}
            }));
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();go.countDown();
            int wins=0;for(var future:futures)if(future.get(10,TimeUnit.SECONDS))wins++;
            assertThat(wins).isEqualTo(1);assertThat(revision()).isEqualTo(expected+1);contiguous();
            assertThat(order()).containsExactlyInAnyOrder(a.reviewId(),b.reviewId(),c.reviewId());
        }finally{pool.shutdownNow();}
    }
    @Test void restrictedDatabaseRoleHidesOtherTasksWhileQueueShiftRemainsAtomic(){
        var mine=create("Private to creator");
        var hidden=tx.execute(s->queue.create(tenant,null,null,"Other member private task","Body",otherEmail,Set.of()));
        String role="queue_reader_"+UUID.randomUUID().toString().replace("-","");
        jdbc.update("INSERT INTO collaboration_personal_queues(user_id) VALUES (?)",stranger);
        jdbc.execute("CREATE ROLE "+role+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
        try{
            jdbc.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
            jdbc.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "+schema+" TO "+role);
            tx.executeWithoutResult(s->{
                jdbc.execute("SET LOCAL ROLE "+role);
                jdbc.queryForObject("SELECT set_config('app.parent_id',?,true)",String.class,tenant.toString());
                jdbc.queryForObject("SELECT set_config('app.user_email',?,true)",String.class,email);
                jdbc.update("INSERT INTO collaboration_personal_queues(user_id) VALUES (?)",user);
                assertThat(jdbc.update("UPDATE collaboration_personal_queues SET revision=7 WHERE user_id=?",stranger)).isZero();
                assertThat(jdbc.queryForList("SELECT user_id FROM collaboration_personal_queues",UUID.class)).containsExactly(user);
                assertThat(jdbc.queryForList("SELECT id FROM collaboration_tasks",UUID.class)).containsExactly(mine.reviewId());
                queue.move(tenant,mine.reviewId(),email,2,hidden.revision());
                assertThat(jdbc.queryForList("SELECT id FROM collaboration_tasks",UUID.class)).containsExactly(mine.reviewId());
                assertThat(jdbc.queryForObject("SELECT current_setting('app.collaboration_queue_mutation')",String.class)).isEqualTo("false");
                jdbc.queryForObject("SELECT set_config('app.parent_id',?,true)",String.class,other.toString());
                assertThat(jdbc.queryForList("SELECT id FROM collaboration_tasks",UUID.class)).isEmpty();
            });
            assertThat(order()).containsExactly(hidden.reviewId(),mine.reviewId());contiguous();
        }finally{jdbc.execute("DROP OWNED BY "+role);jdbc.execute("DROP ROLE "+role);}
    }
    @Test void boundedBoardExcludesNonAudienceAndDoneBucketsExcludeLatestFive(){
        var board=new CollaborationBoardRepository(jdbc,queue);
        for(int i=0;i<9;i++){var task=create("Task "+i);tx.execute(s->queue.state(tenant,task.reviewId(),email,"DONE",task.revision()));}
        tx.execute(s->queue.create(tenant,null,null,"Hidden private","Secret",otherEmail,Set.of()));
        var now=java.time.Instant.now().plusMillis(1);
        var recent=tx.execute(s->board.page(tenant,email,"DONE",false,null,null,"",5,0,null,null,now,null,"UTC"));
        assertThat(recent.groups()).containsExactlyEntriesOf(Map.of("THIS_WEEK",4L));assertThat(recent.cards()).hasSize(5);assertThat(recent.more()).isTrue();
        var older=tx.execute(s->board.page(tenant,email,"DONE",false,null,null,"",5,0,null,null,now,"THIS_WEEK","UTC"));
        assertThat(older.cards()).hasSize(4);
        assertThat(older.cards()).extracting(CollaborationBoardRepository.Card::id).doesNotContainAnyElementsOf(recent.cards().stream().map(CollaborationBoardRepository.Card::id).toList());
        assertThat(tx.execute(s->board.page(tenant,email,"OPEN",false,null,null,"",5,0,null,null,null,null,"UTC")).cards()).isEmpty();
        assertThat(tx.execute(s->board.page(tenant,otherEmail,"OPEN",true,null,null,"",5,0,null,null,null,null,"UTC")).cards()).hasSize(1);
    }
    @Test void mentionsOnlyAddAudienceAndExplicitAssigneeSelfRemovalFallsBack(){
        var task=tx.execute(s->queue.create(tenant,null,null,"Mention default","Please ask @other.member",email,Set.of()));
        assertThat(jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE task_id=? AND assigned",UUID.class,task.reviewId())).containsExactly(user);
        tx.executeWithoutResult(s->queue.assign(tenant,task.reviewId(),email,Set.of(stranger),false));
        tx.executeWithoutResult(s->queue.assign(tenant,task.reviewId(),otherEmail,Set.of(),true));
        assertThat(jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE task_id=? AND assigned",UUID.class,task.reviewId())).containsExactly(user);
        var board=new CollaborationBoardRepository(jdbc,queue);
        tx.executeWithoutResult(s->board.reply(tenant,task.reviewId(),email,"@other.member please review"));
        assertThat(jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE task_id=? AND assigned",UUID.class,task.reviewId())).containsExactly(user);
        assertThat(jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE task_id=?",UUID.class,task.reviewId())).containsExactlyInAnyOrder(user,stranger);
    }
    @Test void durableChatRestoresAndConvertsMessagesFilesAndAudienceOnce(){
        UUID account=UUID.randomUUID();jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,'Chat account')",account,account.toString());
        jdbc.update("INSERT INTO parent_tenant_accounts(parent_id,account_id) VALUES (?,?)",tenant,account);
        var json=new tools.jackson.databind.ObjectMapper();var storage=new HuddlePersistenceRepository(jdbc,json,queue);
        var collaboration=org.mockito.Mockito.mock(CollaborationRepository.class);var notifications=org.mockito.Mockito.mock(CollaborationNotificationWorker.class);
        var first=new HuddleService(collaboration,notifications,json,storage);
        var creator=new HuddleService.Participant(user,"Creator",email);var guest=new HuddleService.Participant(stranger,"Other member",otherEmail);
        var room=tx.execute(s->first.create(account,creator,"PLATFORM","DIRECT:test","Private chat",null,"{}",List.of(guest)));
        tx.executeWithoutResult(s->first.message(account,room.id(),user,"Keep this tomorrow"));
        UUID file=tx.execute(s->storage.file(account,room.id(),"note.txt","text/plain","Retained bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var restarted=new HuddleService(collaboration,notifications,json,storage);
        assertThat(tx.execute(s->restarted.find(account,room.id(),user)).messages()).hasSize(1);
        assertThat(restarted.expire(java.time.Instant.now().plusSeconds(864000))).isEmpty();
        assertThatThrownBy(()->restarted.end(account,room.id(),stranger)).isInstanceOf(IllegalArgumentException.class);
        tx.executeWithoutResult(s->restarted.leave(account,room.id(),stranger));
        assertThat(tx.execute(s->restarted.find(account,room.id(),user)).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(()->tx.execute(s->restarted.save(account,room.id(),user,false,"Follow up",null,"America/Los_Angeles",Set.of(UUID.randomUUID())))).hasMessageContaining("participants");
        assertThat(tx.execute(s->restarted.find(account,room.id(),user)).status()).isEqualTo("ACTIVE");
        var saved=tx.execute(s->restarted.save(account,room.id(),user,false,"Follow up",java.time.LocalDate.of(2026,12,1),"America/Los_Angeles",Set.of(stranger)));
        assertThat(jdbc.queryForList("SELECT user_id FROM collaboration_task_participants WHERE task_id=? AND assigned",UUID.class,saved.reviewId())).containsExactly(stranger);
        assertThat(tx.execute(s->restarted.save(account,room.id(),user,false)).reviewId()).isEqualTo(saved.reviewId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM collaboration_tasks WHERE parent_id=?",Integer.class,tenant)).isEqualTo(1);
        var board=new CollaborationBoardRepository(jdbc,queue);
        assertThat(tx.execute(s->board.file(tenant,saved.reviewId(),file,otherEmail)).bytes()).isEqualTo("Retained bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var notes=tx.execute(s->board.messages(tenant,saved.reviewId(),email,null,null));
        assertThat(notes).extracting(CollaborationBoardRepository.Note::body).contains("Keep this tomorrow");
        assertThat(jdbc.queryForObject("SELECT due_date FROM collaboration_tasks WHERE id=?",java.time.LocalDate.class,saved.reviewId())).isEqualTo(java.time.LocalDate.of(2026,12,1));
    }
}
