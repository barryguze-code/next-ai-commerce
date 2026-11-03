package com.nextaicommerce.platform.collaboration;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL regression: the original date-present save failed at JDBC binding. */
class CollaborationTaskDatabaseTest {
    static io.zonky.test.db.postgres.embedded.EmbeddedPostgres postgres;
    static JdbcTemplate jdbc;static TransactionTemplate tx;static String schema;
    CollaborationRepository repository;UUID tenant,other,user,store;String actor;
    @BeforeAll static void setup() throws Exception {
        schema="collaboration_task_test_"+UUID.randomUUID().toString().replace("-","");String url,password=null;
        if(Boolean.getBoolean("localTestDatabase")){
            url="jdbc:postgresql://127.0.0.1:55432/next_ai_commerce_test";
            password=java.nio.file.Files.readString(java.nio.file.Path.of(".local/database-password")).trim();
        }else{schema="public"; // This branch owns a disposable cluster; match production function lookup.
        postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations(com.nextaicommerce.platform.testing.FixtureMigrations.location(schema)).load().migrate();
    }
    @AfterAll static void cleanup() throws Exception {
        if(jdbc!=null&&schema!=null&&schema.matches("collaboration_task_test_[a-f0-9]{32}"))jdbc.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        if(postgres!=null)postgres.close();
    }
    @BeforeEach void fixture(){
        repository=new CollaborationRepository(jdbc);tenant=UUID.randomUUID();other=UUID.randomUUID();user=UUID.randomUUID();store=UUID.randomUUID();actor=user+"@example.test";
        tx.executeWithoutResult(s->{
            for(UUID id:List.of(tenant,other))jdbc.update("INSERT INTO tenants(id,slug,display_name) VALUES (?,?,?)",id,id.toString(),"Task test");
            jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Task tester')",user,actor);
            jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'ADMIN')",tenant,user);
            jdbc.update("INSERT INTO marketplace_connections(id,tenant_id,channel,seller_identifier,marketplace_identifier,credential_secret_ref,status,display_name,reporting_timezone,inventory_activated_at) VALUES (?,?,'AMAZON',?,'ATVPDKIKX0DER','test://none','ACTIVE','Test store','America/Los_Angeles',now())",store,tenant,store.toString());
        });
    }
    @Test void completionIncludesCloserSilentAssigneeAndTeamAuthorsButNotPrivateOnlyUsers() {
        tx.executeWithoutResult(s->{
            UUID assignee=UUID.randomUUID(),writer=UUID.randomUUID(),privateOnly=UUID.randomUUID(),admin=UUID.randomUUID();
            for(UUID id:List.of(assignee,writer,privateOnly)){
                jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Participant')",id,id+"@example.test");
                jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'ADMIN')",tenant,id);
            }
            jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Staff participant')",admin,admin+"@example.test");
            jdbc.update("INSERT INTO platform_administrators(user_id) VALUES (?)",admin);
            var task=repository.createStoreTask(tenant,null,"Completion audience","Details",actor,Set.of(assignee),null,"UTC");
            repository.reply(tenant,task.reviewId(),writer+"@example.test","Team reply","TEAM_CHAT",null,List.of());
            repository.reply(tenant,task.reviewId(),privateOnly+"@example.test","Private reply","PRIVATE_NOTE",null,List.of());
            repository.reply(tenant,task.reviewId(),admin+"@example.test","Staff reply","TEAM_CHAT",null,List.of());
            var closed=repository.reply(tenant,task.reviewId(),actor,null,"TEAM_CHAT","CLOSED",List.of());
            var people=repository.participantMemberIds(tenant,task.reviewId(),actor);
            assertThat(people).containsExactlyInAnyOrder(user,assignee,writer,admin);
            assertThat(repository.queueNotifications(tenant,task.reviewId(),closed.messageId(),people,"COMPLETED")).isEqualTo(4);
            assertThat(repository.queueNotifications(tenant,task.reviewId(),closed.messageId(),people,"COMPLETED")).isZero();
        });
    }

    @Test void concurrentClosesProduceOnlyOneCompletionMessage() throws Exception {
        var task=tx.execute(s->repository.createStoreTask(tenant,null,"Concurrent close","Details",actor,Set.of(),null,"UTC"));
        jdbc.execute("CREATE FUNCTION pause_completion_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.body LIKE 'Conversation completed by %' THEN PERFORM pg_sleep(0.3); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER pause_completion_test BEFORE INSERT ON collaboration_messages FOR EACH ROW EXECUTE FUNCTION pause_completion_test()");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.Callable<CollaborationRepository.PostedMessage> close=()->{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);return tx.execute(s->repository.reply(tenant,task.reviewId(),actor,null,"TEAM_CHAT","CLOSED",List.of()));};
            var one=pool.submit(close);var two=pool.submit(close);
            var results=List.of(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().filter(r->r.messageId()!=null).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM collaboration_messages WHERE review_id=? AND body LIKE 'Conversation completed by %'",Integer.class,task.reviewId())).isEqualTo(1);
        }finally{pool.shutdownNow();jdbc.execute("DROP TRIGGER pause_completion_test ON collaboration_messages");jdbc.execute("DROP FUNCTION pause_completion_test()");}
    }

    @Test void hiddenPrivateThreadCannotBeRepliedToOrClosedByAnotherMember(){
        tx.executeWithoutResult(s->{
            UUID stranger=UUID.randomUUID();jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Stranger')",stranger,"stranger@example.test");
            jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'ADMIN')",tenant,stranger);
            var note=repository.create(tenant,"PLATFORM","private-key","Private subject",null,"TASK","Private title","Private body","PRIVATE_NOTE","{}",null,actor,null,null,List.of());
            assertThat(repository.review(tenant,note.reviewId(),"stranger@example.test")).isNull();
            assertThatThrownBy(()->repository.reply(tenant,note.reviewId(),"stranger@example.test","Forged","TEAM_CHAT",null,List.of())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(()->repository.reply(tenant,note.reviewId(),"stranger@example.test",null,"PRIVATE_NOTE","CLOSED",List.of())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            repository.reply(tenant,note.reviewId(),actor,"Own reply","PRIVATE_NOTE",null,List.of());
            assertThat(repository.messages(tenant,note.reviewId(),actor,"PRIVATE_NOTE")).hasSize(2);
        });
    }

    @Test void initialAssignmentUsesExplicitThenMentionsThenSelf(){
        tx.executeWithoutResult(s->{
            UUID teammate=UUID.randomUUID();jdbc.update("INSERT INTO app_users(id,email,display_name) VALUES (?,?,'Teammate')",teammate,"teammate@example.test");
            jdbc.update("INSERT INTO tenant_memberships(tenant_id,user_id,role) VALUES (?,?,'ADMIN')",tenant,teammate);
            var member=repository.members(tenant).stream().filter(m->m.id().equals(teammate)).findFirst().orElseThrow();
            var self=repository.createStoreTask(tenant,null,"Self task","No mentions",actor,Set.of(),null,"UTC");
            assertThat(repository.review(tenant,self.reviewId(),actor).assignedTo(actor)).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM collaboration_mentions WHERE review_id=?",Integer.class,self.reviewId())).isZero();
            var mentioned=repository.createStoreTask(tenant,null,"Mentioned task","Please @"+member.handle(),actor,Set.of(),null,"UTC");
            assertThat(repository.review(tenant,mentioned.reviewId(),actor).assignedTo("teammate@example.test")).isTrue();
            var explicit=repository.createStoreTask(tenant,null,"Explicit task","Please @"+member.handle(),actor,Set.of(user),null,"UTC");
            assertThat(repository.review(tenant,explicit.reviewId(),actor).assignedTo(actor)).isTrue();
            assertThat(repository.review(tenant,explicit.reviewId(),actor).assignedTo("teammate@example.test")).isFalse();
            var mentions=new CollaborationMentionService(repository,org.mockito.Mockito.mock(CollaborationNotificationWorker.class));
            mentions.queue(tenant,mentioned,"Please @"+member.handle(),actor);
            assertThat(jdbc.queryForList("SELECT notification_kind FROM collaboration_mentions WHERE review_id=? AND mentioned_user_id=?",String.class,mentioned.reviewId(),teammate)).containsExactly("ASSIGNED");
            mentions.queue(tenant,explicit,"Please @"+member.handle(),actor);
            assertThat(repository.review(tenant,explicit.reviewId(),actor).assignedTo("teammate@example.test")).isFalse();
            assertThat(jdbc.queryForList("SELECT notification_kind FROM collaboration_mentions WHERE review_id=? AND mentioned_user_id=?",String.class,explicit.reviewId(),teammate)).containsExactly("MENTION");
        });
    }

    @Test void datedTaskPersistsAndCanBeClearedWithoutLosingMessages(){
        tx.executeWithoutResult(s->{
            var date=LocalDate.of(2026,10,2);var posted=repository.createStoreTask(tenant,store,"Check stock","Details",actor,Set.of(),date,"America/Los_Angeles");
            var saved=repository.review(tenant,posted.reviewId(),actor);
            assertThat(saved.dueAt()).isEqualTo(Instant.parse("2026-10-03T07:00:00Z"));
            assertThat(saved.dueDate()).isEqualTo(date);assertThat(saved.storeId()).isEqualTo(store);
            assertThat(saved.subjectType()).isEqualTo("PLATFORM");assertThat(saved.messageCount()).isEqualTo(2);
            assertThat(repository.pictures(tenant,List.of(saved),actor)).isEmpty();
            var past=repository.setDueDate(tenant,posted.reviewId(),actor,LocalDate.of(2000,1,1),"UTC");assertThat(past.overdue()).isTrue();
            var cleared=repository.setDueDate(tenant,posted.reviewId(),actor,null,"UTC");
            assertThat(cleared.dueAt()).isNull();assertThat(cleared.dueDate()).isNull();assertThat(cleared.overdue()).isFalse();assertThat(cleared.messageCount()).isEqualTo(2);
            assertThat(repository.review(other,posted.reviewId(),actor)).isNull();
            assertThatThrownBy(()->repository.setDueDate(other,posted.reviewId(),actor,date,"UTC")).isInstanceOf(IllegalArgumentException.class);
        });
    }
    @Test void optionalDetailsAndDateWorkAndForeignStoreIsRejected(){
        tx.executeWithoutResult(s->{
            var posted=repository.createStoreTask(tenant,store,"General work","",actor,Set.of(),null,"UTC");
            assertThat(repository.review(tenant,posted.reviewId(),actor).dueAt()).isNull();
            assertThat(repository.messages(tenant,posted.reviewId(),actor,"TEAM_CHAT")).extracting(CollaborationRepository.Message::body).containsExactly("General work","Task tester changed assignment from unassigned to Task tester.");
            assertThatThrownBy(()->repository.createStoreTask(other,store,"Wrong store","",actor,Set.of(),null,"UTC")).isInstanceOf(IllegalArgumentException.class);
        });
    }
    @Test void recordConversationAlsoSupportsDueDatesAndKeepsItsContext(){
        tx.executeWithoutResult(s->{
            jdbc.update("INSERT INTO amazon_orders(tenant_id,marketplace_connection_id,marketplace_id,amazon_order_id) VALUES (?,?,'ATVPDKIKX0DER','TEST-ORDER')",tenant,store);
            LocalDate date=LocalDate.of(2026,12,20);
            var posted=repository.createDatedConversation(tenant,"ORDER","TEST-ORDER","Test order",null,"CONVERSATION","Review order","Please check","TEAM_CHAT","{}","/app/orders",actor,null,date,"UTC",List.of());
            var saved=repository.review(tenant,posted.reviewId(),actor);
            assertThat(saved.subjectKey()).isEqualTo("TEST-ORDER");assertThat(saved.dueDate()).isEqualTo(date);
            assertThat(saved.dueAt()).isEqualTo(Instant.parse("2026-12-21T00:00:00Z"));
            assertThat(repository.setDueDate(tenant,posted.reviewId(),actor,null,"UTC").subjectKey()).isEqualTo("TEST-ORDER");
        });
    }
    @Test void subjectEditsPreserveSourceAndRejectForeignThreads(){
        tx.executeWithoutResult(s->{
            var posted=repository.createStoreTask(tenant,store,"Original subject","Initial details",actor,Set.of(user),null,"UTC");
            var before=repository.review(tenant,posted.reviewId(),actor);
            var saved=repository.setTitle(tenant,posted.reviewId(),actor,"  Revised subject  ");
            assertThat(saved.title()).isEqualTo("Revised subject");
            assertThat(saved.subjectLabel()).isEqualTo(before.subjectLabel());
            assertThat(saved.subjectKey()).isEqualTo(before.subjectKey());assertThat(saved.storeId()).isEqualTo(store);
            assertThat(saved.messageCount()).isEqualTo(before.messageCount());assertThat(saved.assignedTo(actor)).isTrue();
            assertThatThrownBy(()->repository.setTitle(other,posted.reviewId(),actor,"Wrong account")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(()->repository.setTitle(tenant,posted.reviewId(),actor," ")).isInstanceOf(IllegalArgumentException.class);
            repository.reply(tenant,posted.reviewId(),actor,null,"TEAM_CHAT","CLOSED",List.of());
            var clarified=repository.setTitle(tenant,posted.reviewId(),actor,"Closed clarification");
            assertThat(clarified.title()).isEqualTo("Closed clarification");assertThat(clarified.status()).isEqualTo("CLOSED");
        });
    }
    @Test void deadlineUsesCalendarDaysAcrossDaylightSaving(){
        assertThat(CollaborationRepository.deadline(LocalDate.of(2026,3,8),"America/Los_Angeles")).isEqualTo(Instant.parse("2026-03-09T07:00:00Z"));
        assertThat(CollaborationRepository.deadline(LocalDate.of(2026,11,1),"America/Los_Angeles")).isEqualTo(Instant.parse("2026-11-02T08:00:00Z"));
    }
}
