package com.nextaicommerce.platform.sync;

import com.nextaicommerce.platform.fees.AmazonFeeEvidence;
import com.nextaicommerce.platform.web.MarketplaceCredentialService;
import com.nextaicommerce.platform.web.WorkspaceAccessRepository;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicit foreground command. No Boot application, scan, scheduler, web server or enqueue. */
public final class AmazonFeeOperator {
    record Grant(String format,String jdbcUrl,String database,String schema,String serverAddress,int serverPort,long databaseOid,
                 String role,UUID tenant,UUID connection,String marketplace,UUID job,String contractSha256,
                 String publicationMode,Instant expiresAt,boolean currencyFallback) {
        Grant {
            if(!"FEE_OPERATOR_V1".equals(format)||jdbcUrl==null||!jdbcUrl.startsWith("jdbc:postgresql://")
                ||database==null||database.isBlank()||schema==null||!schema.matches("[a-z_][a-z0-9_]{0,62}")||serverAddress==null||serverAddress.isBlank()
                ||serverPort<1||serverPort>65535||databaseOid<1||role==null||!role.matches("[a-z_][a-z0-9_]{0,62}")
                ||tenant==null||connection==null||job==null||marketplace==null||marketplace.isBlank()
                ||contractSha256==null||!contractSha256.matches("[a-f0-9]{64}")||expiresAt==null
                ||!Set.of("ARCHIVE_ONLY","ESTIMATES_AND_EVIDENCE").contains(publicationMode))throw new IllegalArgumentException("Invalid operator grant");
        }
    }
    enum Result { COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, CANCELLED, EXPIRED, BLOCKED }
    record State(String status,String runStatus,Instant now,Instant deadline,Instant available,String owner) {}
    @FunctionalInterface interface Pause { void sleep() throws InterruptedException; }
    @EnableTransactionManagement
    static class Transactions {}

    public static void main(String[] args) {
        int code=2;
        try {
            if(args.length!=3)throw new IllegalArgumentException("Expected private grant, checksum and credential file");
            var json=new ObjectMapper();byte[] bytes=privateBytes(Path.of(args[0]));
            if(!args[1].matches("[a-f0-9]{64}")||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(args[1]))throw new IllegalArgumentException("Grant checksum mismatch");
            var grant=json.readValue(bytes,Grant.class);
            if(!Instant.now().isBefore(grant.expiresAt())||grant.expiresAt().isAfter(Instant.now().plusSeconds(1800)))throw new IllegalArgumentException("Grant expired or outside approved window");
            var secrets=new Properties();try(var input=new java.io.ByteArrayInputStream(privateBytes(Path.of(args[2])))){secrets.load(input);}
            if(!secrets.stringPropertyNames().equals(Set.of("jdbc.url","jdbc.username","jdbc.password","credential.key"))
                ||!grant.jdbcUrl().equals(secrets.getProperty("jdbc.url"))||!grant.role().equals(secrets.getProperty("jdbc.username"))
                ||secrets.getProperty("jdbc.password").isBlank()||Base64.getDecoder().decode(secrets.getProperty("credential.key")).length!=32)throw new IllegalArgumentException("Private operator configuration mismatch");
            try(var context=context(grant,new DriverManagerDataSource(grant.jdbcUrl(),grant.role(),secrets.getProperty("jdbc.password")),secrets.getProperty("credential.key"))){
                var jdbc=context.getBean(JdbcTemplate.class);verifyDatabase(jdbc,grant);
                Result result=execute(grant,jdbc,context.getBean(TransactionTemplate.class),context.getBean(AmazonProductFeeJobs.class),context.getBean(AmazonProductFeeSync.class),()->Thread.sleep(1000));
                System.out.println("Fee operator: "+result);code=result==Result.COMPLETED?0:2;
            }
        } catch(Exception error) {
            // Database/transport messages and nested causes may contain private connection details.
            System.err.println("Fee operator stopped: "+error.getClass().getSimpleName());
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
        }
        System.exit(code);
    }
    static byte[] privateBytes(Path path)throws java.io.IOException {
        var permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||Files.size(path)>65536
            ||permissions.stream().anyMatch(p->p!=PosixFilePermission.OWNER_READ&&p!=PosixFilePermission.OWNER_WRITE))throw new IllegalArgumentException("Private regular file required");
        return Files.readAllBytes(path);
    }
    static AnnotationConfigApplicationContext context(Grant grant,DataSource ds,String key) {
        var c=new AnnotationConfigApplicationContext();
        // No ambient application.yml, environment or system-property activation is accepted.
        var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("operator",Map.of(
            "app.integrations.external-enabled","true","app.amazon.write-enabled","false",
            "app.amazon.product-fees-batch-enabled","true","app.amazon.product-fees-marketplace-currency-fallback",Boolean.toString(grant.currencyFallback()),
            "app.amazon.fee-pilot.publication-mode",grant.publicationMode())));c.setEnvironment(env);
        c.register(Transactions.class);
        c.registerBean(DataSource.class,()->ds);c.registerBean(JdbcTemplate.class,()->new JdbcTemplate(ds));
        c.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(ds));
        c.registerBean(TransactionTemplate.class,()->new TransactionTemplate(c.getBean(DataSourceTransactionManager.class)));
        c.registerBean(ObjectMapper.class,()->new ObjectMapper());
        c.registerBean(WorkspaceAccessRepository.class,()->new WorkspaceAccessRepository(c.getBean(JdbcTemplate.class)));
        c.registerBean(MarketplaceCredentialService.class,()->MarketplaceCredentialService.readOnly(c.getBean(WorkspaceAccessRepository.class),key));
        c.registerBean(AmazonSpApiClient.class,()->new AmazonSpApiClient(c.getBean(MarketplaceCredentialService.class),c.getBean(ObjectMapper.class)));
        c.registerBean(AmazonSyncStore.class,()->new AmazonSyncStore(c.getBean(JdbcTemplate.class),c.getBean(TransactionTemplate.class)));
        c.registerBean(AmazonFeeEvidence.class,()->new AmazonFeeEvidence(c.getBean(JdbcTemplate.class),c.getBean(ObjectMapper.class),grant.publicationMode().equals("ESTIMATES_AND_EVIDENCE")));
        c.registerBean(AmazonProductFeeJobs.class,()->new AmazonProductFeeJobs(c.getBean(JdbcTemplate.class),c.getBean(TransactionTemplate.class),c.getBean(ObjectMapper.class),c.getBean(AmazonSyncStore.class),0.5));
        c.registerBean(AmazonProductFeeSync.class,()->new AmazonProductFeeSync(c.getBean(AmazonSpApiClient.class),c.getBean(AmazonProductFeeJobs.class),true));
        try{c.refresh();return c;}catch(RuntimeException error){c.close();throw error;}
    }
    static void verifyDatabase(JdbcTemplate jdbc,Grant g) {
        var row=jdbc.queryForMap("""
            SELECT current_database() db,inet_server_addr()::text address,inet_server_port() port,
             (SELECT oid::bigint FROM pg_database WHERE datname=current_database()) oid,current_user role,session_user login,
             current_schema() schema,NOT rolsuper AND NOT rolbypassrls AND NOT rolinherit AND NOT rolcreatedb
             AND NOT rolcreaterole AND NOT rolreplication AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE member=r.oid) restricted
            FROM pg_roles r WHERE rolname=current_user
            """);
        if(!g.database().equals(row.get("db"))||!g.serverAddress().equals(row.get("address"))||g.serverPort()!=((Number)row.get("port")).intValue()
            ||g.databaseOid()!=((Number)row.get("oid")).longValue()||!g.role().equals(row.get("role"))||!g.role().equals(row.get("login"))
            ||!g.schema().equals(row.get("schema"))||!Boolean.TRUE.equals(row.get("restricted")))throw new IllegalStateException("Operator database identity or role mismatch");
    }
    static Result execute(Grant g,JdbcTemplate jdbc,TransactionTemplate tx,AmazonProductFeeJobs jobs,AmazonProductFeeSync sync,Pause pause)throws InterruptedException {
        String worker="fee-operator-"+UUID.randomUUID();long began=System.nanoTime();
        for(int ticks=0;ticks<=300&&System.nanoTime()-began<Duration.ofSeconds(300).toNanos();ticks++){
            State state=state(g,jdbc,tx,jobs);
            if(state==null)return Result.BLOCKED;
            if("CANCELLED".equals(state.runStatus()))return Result.CANCELLED;
            if("FAILED".equals(state.status())||"FAILED".equals(state.runStatus()))return Result.FAILED;
            if("SKIPPED".equals(state.status())||"COMPLETED_WITH_WARNINGS".equals(state.runStatus()))return Result.COMPLETED_WITH_WARNINGS;
            if("COMPLETED".equals(state.status()))return Result.COMPLETED;
            if(!state.now().isBefore(state.deadline()))return Result.EXPIRED;
            if(state.owner()!=null&&!worker.equals(state.owner()))return Result.BLOCKED;
            if(!state.now().isBefore(state.available())){
                var job=jobs.claimPilot(g.tenant(),g.connection(),g.marketplace(),g.job(),g.contractSha256(),worker,g.publicationMode());
                if(job==null||!jobs.matchesPilot(job,g.contractSha256()))return Result.BLOCKED;
                sync.processPage(job);
            }
            pause.sleep();
        }
        return Result.EXPIRED;
    }
    static State state(Grant g,JdbcTemplate jdbc,TransactionTemplate tx,AmazonProductFeeJobs jobs) {
        return tx.execute(s->{
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,g.tenant().toString());
            var rows=jdbc.queryForList("""
                SELECT j.status,r.status run_status,j.cursor->'feeRun' contract,j.started_at,j.available_at,
                 CASE WHEN j.lease_expires_at>clock_timestamp() THEN j.lease_owner END owner,clock_timestamp() db_now
                FROM marketplace_sync_jobs j JOIN marketplace_sync_runs r ON r.id=j.sync_run_id AND r.tenant_id=j.tenant_id
                JOIN marketplace_connections c ON c.id=j.marketplace_connection_id AND c.tenant_id=j.tenant_id
                JOIN tenants t ON t.id=j.tenant_id
                WHERE j.id=? AND j.tenant_id=? AND j.marketplace_connection_id=? AND j.job_type='FEES_SNAPSHOT'
                 AND c.marketplace_identifier=? AND c.channel='AMAZON' AND c.status='ACTIVE' AND t.status='ACTIVE'
                """,g.job(),g.tenant(),g.connection(),g.marketplace());
            if(rows.size()!=1)return null;var row=rows.getFirst();
            var contract=new ObjectMapper().readTree(Objects.toString(row.get("contract"),"null"));
            if(!g.contractSha256().equals(jobs.pilotContractHash(contract))||!g.publicationMode().equals(contract.path("publicationMode").asText())
                ||!g.expiresAt().toString().equals(contract.path("approvalExpiresAt").asText())
                ||!g.tenant().toString().equals(contract.path("tenant").asText())||!g.connection().toString().equals(contract.path("connection").asText())
                ||!g.marketplace().equals(contract.path("marketplace").asText()))return null;
            for(var cap:Map.of("maxTargets",20,"maxHttp",1,"maxOauth",2,"maxSeconds",300).entrySet())
                if(!contract.path(cap.getKey()).isInt()||contract.path(cap.getKey()).asInt()<1||contract.path(cap.getKey()).asInt()>cap.getValue())return null;
            Instant deadline=g.expiresAt();if(row.get("started_at") instanceof java.sql.Timestamp started){var relative=started.toInstant().plusSeconds(contract.path("maxSeconds").asInt());if(relative.isBefore(deadline))deadline=relative;}
            return new State(row.get("status").toString(),row.get("run_status").toString(),((java.sql.Timestamp)row.get("db_now")).toInstant(),deadline,((java.sql.Timestamp)row.get("available_at")).toInstant(),(String)row.get("owner"));
        });
    }
}
