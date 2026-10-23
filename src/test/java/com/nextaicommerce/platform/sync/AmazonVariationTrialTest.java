package com.nextaicommerce.platform.sync;

import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.transaction.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipProvider.Scope;

class AmazonVariationTrialTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);
    TransactionTemplate tx=new TransactionTemplate(new AbstractPlatformTransactionManager(){
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object o,TransactionDefinition d){}
        protected void doCommit(DefaultTransactionStatus s){}
        protected void doRollback(DefaultTransactionStatus s){}
    });
    AmazonVariationTrial trial=new AmazonVariationTrial(jdbc,tx,true);
    @Test void exactScopeAndLiteralSeedOnly(){
        var scope=new Scope(AmazonVariationTrial.TENANT,AmazonVariationTrial.CONNECTION,AmazonVariationTrial.MARKET);
        AmazonVariationTrial.validate(scope,List.of(AmazonVariationTrial.SEED));
        assertThatThrownBy(()->AmazonVariationTrial.validate(scope,List.of("4d-2phn-6kx3"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AmazonVariationTrial.validate(scope,List.of(AmazonVariationTrial.SEED,"other"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AmazonVariationTrial.validate(new Scope(UUID.randomUUID(),scope.connection(),scope.marketplace()),List.of(AmazonVariationTrial.SEED))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AmazonVariationTrial.validate(new Scope(scope.tenant(),UUID.randomUUID(),scope.marketplace()),List.of(AmazonVariationTrial.SEED))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->AmazonVariationTrial.validate(new Scope(scope.tenant(),scope.connection(),"A1PA6795UKMFR9"),List.of(AmazonVariationTrial.SEED))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledStartDoesNotTouchDatabaseOrQueue(){
        var jobs=mock(AmazonVariationRelationshipJobs.class);
        assertThatThrownBy(()->new AmazonVariationTrial(jdbc,tx,false).start(UUID.randomUUID(),jobs)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc,jobs);
    }
    @Test void durableSameKeyReturnsExistingJobAndDifferentKeyCannotRenew(){
        UUID job=UUID.randomUUID(),key=UUID.randomUUID();var jobs=mock(AmazonVariationRelationshipJobs.class);
        when(jdbc.queryForList(startsWith("SELECT job_id,idempotency_key"),eq(AmazonVariationTrial.TENANT),eq(AmazonVariationTrial.CONNECTION)))
            .thenReturn(List.of(Map.of("job_id",job,"idempotency_key",key)));
        assertThat(trial.start(key,jobs)).isEqualTo(job);
        // A fresh service instance models loss of all in-memory state after a restart.
        assertThat(new AmazonVariationTrial(jdbc,tx,true).start(key,jobs)).isEqualTo(job);
        assertThatThrownBy(()->trial.start(UUID.randomUUID(),jobs)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jobs);
    }
    @Test void missingSellerRollsBackStartRatherThanLeavingUncontrolledJob(){
        var jobs=mock(AmazonVariationRelationshipJobs.class);when(jobs.enqueueTrial(any(),any())).thenReturn(UUID.randomUUID());
        when(jdbc.queryForObject(startsWith("SELECT count(*)"),eq(Integer.class),any(UUID.class))).thenReturn(0);
        assertThatThrownBy(()->trial.start(UUID.randomUUID(),jobs)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Seller identity");
    }
    @Test void disabledAcquisitionCannotStart(){
        var jobs=mock(AmazonVariationRelationshipJobs.class);
        var sync=new AmazonVariationRelationshipSync(jobs,mock(com.nextaicommerce.platform.marketplace.AmazonVariationRelationshipPublisher.class),mock(AmazonSpApiClient.class),false);
        sync.tick();assertThatThrownBy(()->sync.startTrial(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jobs);
    }
    AmazonVariationRelationshipJobs.Lease lease(){return new AmazonVariationRelationshipJobs.Lease(new AmazonSyncStore.Job(UUID.randomUUID(),AmazonVariationTrial.TENANT,UUID.randomUUID(),AmazonVariationTrial.CONNECTION,"VARIATION_RELATIONSHIPS","VARIATION_RELATIONSHIPS",AmazonVariationTrial.MARKET,Instant.now(),Instant.now(),null,1,0,false,null),"owner",1,new tools.jackson.databind.ObjectMapper().readTree("{}"),"synthetic");}
    @Test void cancelledLeaseCannotPublish(){
        var guarded=mock(AmazonVariationTrial.class);var jobs=new AmazonVariationRelationshipJobs(jdbc,tx,new tools.jackson.databind.ObjectMapper(),guarded);var lease=lease();
        when(jdbc.queryForList(startsWith("SELECT j.id"),any(Object[].class))).thenReturn(List.of(Map.of("id",lease.job().id())));
        when(guarded.allowed(lease)).thenReturn(false);
        java.util.concurrent.atomic.AtomicBoolean published=new java.util.concurrent.atomic.AtomicBoolean();
        Boolean result=jobs.owned(lease,j->{published.set(true);return true;});assertThat(result).isNull();assertThat(published).isFalse();
    }
    @Test void elapsedDeadlineDeniesTransportTimeout(){
        var lease=lease();
        when(jdbc.queryForObject(startsWith("SELECT extract"),eq(Double.class),any(UUID.class))).thenReturn(-0.1);
        assertThatThrownBy(()->trial.remaining(lease)).isInstanceOf(AmazonSpApiClient.VariationDeferredException.class);
    }
    @Test void springCannotConstructAnUnguardedQueue(){
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withBean(JdbcTemplate.class,()->jdbc).withBean(TransactionTemplate.class,()->tx).withBean(tools.jackson.databind.ObjectMapper.class,tools.jackson.databind.ObjectMapper::new)
            .withUserConfiguration(AmazonVariationRelationshipJobs.class);
        runner.run(context->assertThat(context).hasFailed());
        runner.withBean(AmazonVariationTrial.class,()->trial).run(context->assertThat(context).hasFailed());
        runner.withBean(AmazonVariationTrial.class,()->trial).withBean(AmazonVariationCoverageGrant.class,()->mock(AmazonVariationCoverageGrant.class)).run(context->{assertThat(context).hasNotFailed();assertThat(context.getBean(AmazonVariationRelationshipJobs.class).guarded()).isTrue();});
    }

    @Test void operatorControllerIsAbsentByDefault(){
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(AmazonVariationTrialController.class);
        runner.run(context->assertThat(context).doesNotHaveBean(AmazonVariationTrialController.class));
        runner.withPropertyValues("app.amazon.variation.operator-enabled=true")
            .withBean(AmazonVariationTrial.class,()->trial).withBean(AmazonVariationRelationshipSync.class,()->mock(AmazonVariationRelationshipSync.class))
            .withBean(com.nextaicommerce.platform.web.MemberAccessService.class,()->mock(com.nextaicommerce.platform.web.MemberAccessService.class))
            .run(context->assertThat(context).hasSingleBean(AmazonVariationTrialController.class));
    }

}
