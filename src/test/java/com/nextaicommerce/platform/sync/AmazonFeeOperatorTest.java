package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.web.MarketplaceCredentialService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class AmazonFeeOperatorTest {
    AmazonFeeOperator.Grant grant(){return new AmazonFeeOperator.Grant("FEE_OPERATOR_V1","jdbc:postgresql://127.0.0.1:1/synthetic","synthetic","public","127.0.0.1",1,1,"synthetic_writer",UUID.randomUUID(),UUID.randomUUID(),"SYNTHETIC",UUID.randomUUID(),"a".repeat(64),"ARCHIVE_ONLY",Instant.now().plusSeconds(60),false);}
    @Test void explicitContextContainsOnlyAuditedDependenciesAndCannotEnqueueOrSchedule(){
        var scanner=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(org.springframework.stereotype.Component.class));
        assertThat(scanner.findCandidateComponents("com.nextaicommerce.platform.sync")).noneMatch(b->b.getBeanClassName().startsWith(AmazonFeeOperator.class.getName()));
        var ds=mock(DataSource.class);
        try(var c=AmazonFeeOperator.context(grant(),ds,Base64.getEncoder().encodeToString(new byte[32]))){
            assertThat(c.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
            assertThat(c.getBeansOfType(ApplicationRunner.class)).isEmpty();assertThat(c.getBeansOfType(CommandLineRunner.class)).isEmpty();
            assertThat(c.getBeansOfType(AmazonInitializationService.class)).isEmpty();assertThat(c.getBeansOfType(AmazonFeePilotRunner.class)).isEmpty();
            assertThat(c.getBeanDefinitionNames()).filteredOn(n->!n.startsWith("org.springframework.")).containsExactlyInAnyOrder(
                "amazonFeeOperator.Transactions","dataSource","jdbcTemplate","dataSourceTransactionManager","transactionTemplate","objectMapper",
                "workspaceAccessRepository","marketplaceCredentialService","amazonSpApiClient","amazonSyncStore","amazonFeeEvidence","amazonProductFeeJobs","amazonProductFeeSync","transactionInterceptor","transactionAttributeSource");
            assertThatThrownBy(()->c.getBean(MarketplaceCredentialService.class).connect(UUID.randomUUID(),UUID.randomUUID(),Map.of())).isInstanceOf(IllegalStateException.class);
            assertThat(org.springframework.test.util.ReflectionTestUtils.getField(c.getBean(AmazonSpApiClient.class),"writeEnabled")).isEqualTo(false);
            verifyNoInteractions(ds);
        }
    }
    @Test void databaseTargetAndEffectiveLoginMustExactlyMatch(){
        var g=grant();var jdbc=mock(JdbcTemplate.class);
        var row=new HashMap<String,Object>(Map.of("db",g.database(),"address",g.serverAddress(),"port",g.serverPort(),"oid",g.databaseOid(),"role",g.role(),"login",g.role(),"schema",g.schema(),"restricted",true));
        when(jdbc.queryForMap(anyString())).thenReturn(row);AmazonFeeOperator.verifyDatabase(jdbc,g);
        for(String key:List.of("db","address","port","oid","role","login","schema","restricted")){
            Object prior=row.get(key);row.put(key,switch(key){case "port"->2;case "oid"->2L;case "restricted"->false;default->"wrong";});
            assertThatThrownBy(()->AmazonFeeOperator.verifyDatabase(jdbc,g)).isInstanceOf(IllegalStateException.class);row.put(key,prior);
        }
    }
    @Test void grantAndCredentialFilesMustBePrivateRegularFiles(@TempDir Path dir)throws Exception {
        Path p=dir.resolve("grant");Files.writeString(p,"{}");Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(AmazonFeeOperator.privateBytes(p)).containsExactly((byte)'{',(byte)'}');
        Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));assertThatThrownBy(()->AmazonFeeOperator.privateBytes(p)).isInstanceOf(IllegalArgumentException.class);
        Path link=dir.resolve("link");Files.createSymbolicLink(link,p);assertThatThrownBy(()->AmazonFeeOperator.privateBytes(link)).isInstanceOf(IllegalArgumentException.class);
    }
}
