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
        }else{postgres=io.zonky.test.db.postgres.embedded.EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start();url=postgres.getJdbcUrl("postgres","postgres");}
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",password);
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
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
    @Test void datedTaskPersistsAndCanBeClearedWithoutLosingMessages(){
        tx.executeWithoutResult(s->{
            var date=LocalDate.of(2026,10,2);var posted=repository.createStoreTask(tenant,store,"Check stock","Details",actor,Set.of(),date,"America/Los_Angeles");
            var saved=repository.review(tenant,posted.reviewId(),actor);
            assertThat(saved.dueAt()).isEqualTo(Instant.parse("2026-10-03T07:00:00Z"));
            assertThat(saved.dueDate()).isEqualTo(date);assertThat(saved.storeId()).isEqualTo(store);
            assertThat(saved.subjectType()).isEqualTo("PLATFORM");assertThat(saved.messageCount()).isEqualTo(1);
            assertThat(repository.pictures(tenant,List.of(saved),actor)).isEmpty();
            var past=repository.setDueDate(tenant,posted.reviewId(),actor,LocalDate.of(2000,1,1),"UTC");assertThat(past.overdue()).isTrue();
            var cleared=repository.setDueDate(tenant,posted.reviewId(),actor,null,"UTC");
            assertThat(cleared.dueAt()).isNull();assertThat(cleared.dueDate()).isNull();assertThat(cleared.overdue()).isFalse();assertThat(cleared.messageCount()).isEqualTo(1);
            assertThat(repository.review(other,posted.reviewId(),actor)).isNull();
            assertThatThrownBy(()->repository.setDueDate(other,posted.reviewId(),actor,date,"UTC")).isInstanceOf(IllegalArgumentException.class);
        });
    }
    @Test void optionalDetailsAndDateWorkAndForeignStoreIsRejected(){
        tx.executeWithoutResult(s->{
            var posted=repository.createStoreTask(tenant,store,"General work","",actor,Set.of(),null,"UTC");
            assertThat(repository.review(tenant,posted.reviewId(),actor).dueAt()).isNull();
            assertThat(repository.messages(tenant,posted.reviewId(),actor,"TEAM_CHAT")).extracting(CollaborationRepository.Message::body).containsExactly("General work");
            assertThatThrownBy(()->repository.createStoreTask(other,store,"Wrong store","",actor,Set.of(),null,"UTC")).isInstanceOf(IllegalArgumentException.class);
        });
    }
    @Test void recordConversationAlsoSupportsDueDatesAndKeepsItsContext(){
        tx.executeWithoutResult(s->{
            LocalDate date=LocalDate.of(2026,12,20);
            var posted=repository.createDatedConversation(tenant,"ORDER","TEST-ORDER","Test order",null,"CONVERSATION","Review order","Please check","TEAM_CHAT","{}","/app/orders",actor,null,date,"UTC",List.of());
            var saved=repository.review(tenant,posted.reviewId(),actor);
            assertThat(saved.subjectKey()).isEqualTo("TEST-ORDER");assertThat(saved.dueDate()).isEqualTo(date);
            assertThat(saved.dueAt()).isEqualTo(Instant.parse("2026-12-21T00:00:00Z"));
            assertThat(repository.setDueDate(tenant,posted.reviewId(),actor,null,"UTC").subjectKey()).isEqualTo("TEST-ORDER");
        });
    }
    @Test void deadlineUsesCalendarDaysAcrossDaylightSaving(){
        assertThat(CollaborationRepository.deadline(LocalDate.of(2026,3,8),"America/Los_Angeles")).isEqualTo(Instant.parse("2026-03-09T07:00:00Z"));
        assertThat(CollaborationRepository.deadline(LocalDate.of(2026,11,1),"America/Los_Angeles")).isEqualTo(Instant.parse("2026-11-02T08:00:00Z"));
    }
}
