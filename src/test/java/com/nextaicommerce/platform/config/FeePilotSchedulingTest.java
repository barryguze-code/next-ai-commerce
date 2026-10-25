package com.nextaicommerce.platform.config;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.nextaicommerce.platform.sync.AmazonFeePilotRunner;

class FeePilotSchedulingTest {
    private String[] safe(){var values=new ArrayList<String>();FeePilotSchedulingConfiguration.REQUIRED.forEach((key,value)->values.add(key+"="+value));
        values.add("spring.profiles.active=local");values.add("app.amazon.fee-pilot.only-scheduling=true");values.add("app.amazon.product-fees-marketplace-currency-fallback=false");return values.toArray(String[]::new);}
    private ApplicationContextRunner runner(){
        TaskScheduler scheduler=mock(TaskScheduler.class,invocation->{if(ScheduledFuture.class.isAssignableFrom(invocation.getMethod().getReturnType()))return mock(ScheduledFuture.class);return RETURNS_DEFAULTS.answer(invocation);});
        return new ApplicationContextRunner().withUserConfiguration(FeePilotSchedulingConfiguration.class,LocalEnvironmentSafety.class).withBean(TaskScheduler.class,()->scheduler);
    }
    @Test void defaultOffRegistersNoScheduler(){runner().run(c->assertThat(c).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));}
    @Test void onlyFeeTickRegistersFromTheEntireApplicationInventory()throws Exception{
        Map<String,Object> inventory=new LinkedHashMap<>();
        try(var paths=Files.walk(Path.of("src/main/java"))){for(var file:paths.filter(p->p.toString().endsWith(".java")).toList()){
            String name=Path.of("src/main/java").relativize(file).toString().replace('/','.').replaceAll("\\.java$","");Class<?> type=Class.forName(name,false,getClass().getClassLoader());
            if(Arrays.stream(type.getMethods()).anyMatch(m->m.getAnnotationsByType(Scheduled.class).length>0)
                ||Arrays.stream(type.getDeclaredMethods()).anyMatch(m->m.getAnnotationsByType(Scheduled.class).length>0))inventory.put(name,mock(type));
        }}
        assertThat(inventory).containsKeys(AmazonFeePilotRunner.class.getName(),"com.nextaicommerce.platform.sync.AmazonSyncWorker","com.nextaicommerce.platform.sync.AmazonVariationRelationshipSync");
        var jobs=mock(com.nextaicommerce.platform.sync.AmazonProductFeeJobs.class);var sync=mock(com.nextaicommerce.platform.sync.AmazonProductFeeSync.class);
        inventory.put(AmazonFeePilotRunner.class.getName(),new AmazonFeePilotRunner(jobs,sync,true,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"SYNTHETIC",UUID.randomUUID().toString(),"a".repeat(64)));
        runner().withPropertyValues(safe()).run(c->{assertThat(c).hasNotFailed();var processor=c.getBean(ScheduledAnnotationBeanPostProcessor.class);
            inventory.forEach((name,bean)->processor.postProcessAfterInitialization(bean,name));assertThat(processor.getScheduledTasks()).hasSize(1);
            assertThat(processor.getScheduledTasks().iterator().next().getTask().getRunnable().toString()).contains("AmazonFeePilotRunner.tick");
            verifyNoInteractions(jobs,sync);inventory.values().stream().filter(bean->mockingDetails(bean).isMock()).forEach(bean->verifyNoInteractions(bean));
            var normal=new ScheduledAnnotationBeanPostProcessor();normal.setScheduler(c.getBean(TaskScheduler.class));normal.setEmbeddedValueResolver(c.getEnvironment()::resolveRequiredPlaceholders);
            try{inventory.forEach((name,bean)->normal.postProcessAfterInitialization(bean,name));assertThat(normal.getScheduledTasks().size()).isGreaterThan(1);}finally{normal.destroy();}
        });
    }
    @Test void everySafetyGuardMustBeExplicitAndCorrect(){
        for(var property:safe()){
            if(property.startsWith("app.amazon.fee-pilot.only-scheduling=")||property.startsWith("app.amazon.product-fees-marketplace-currency-fallback="))continue;
            String key=property.substring(0,property.indexOf('=')),wrong=property.endsWith("=false")?"true":property.endsWith("=true")?"false":"wrong";
            runner().withPropertyValues(safe()).withPropertyValues(key+"="+wrong).run(c->assertThat(c).hasFailed());
            runner().withPropertyValues(Arrays.stream(safe()).filter(v->!v.startsWith(key+"=")).toArray(String[]::new)).run(c->assertThat(c).hasFailed());
        }
    }
    @Test void mixedProfilesOtherSelectiveModesAndCustomTasksFailClosed(){
        runner().withPropertyValues(safe()).withPropertyValues("spring.profiles.active=local,production").run(c->assertThat(c).hasFailed());
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.variation.trial-only-scheduling=true").run(c->assertThat(c).hasFailed());
        runner().withPropertyValues(safe()).withBean(SchedulingConfigurer.class,()->registrar->{}).run(c->assertThat(c).hasFailed());
    }
    @Test void currencyFallbackRequiresAnExplicitOperatorChoice(){
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.product-fees-marketplace-currency-fallback=true").run(c->assertThat(c).hasNotFailed());
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.product-fees-marketplace-currency-fallback=guess").run(c->assertThat(c).hasFailed());
        runner().withPropertyValues(Arrays.stream(safe()).filter(v->!v.startsWith("app.amazon.product-fees-marketplace-currency-fallback=")).toArray(String[]::new)).run(c->assertThat(c).hasFailed());
    }
    @Test void prospectiveModeRequiresExplicitEvidenceAndKeepsAllOtherGuards(){
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.fee-pilot.publication-mode=ESTIMATES_AND_EVIDENCE","app.amazon.fee-evidence-enabled=true").run(c->assertThat(c).hasNotFailed());
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.fee-pilot.publication-mode=ESTIMATES_AND_EVIDENCE").run(c->assertThat(c).hasFailed());
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.fee-evidence-enabled=true").run(c->assertThat(c).hasFailed());
        runner().withPropertyValues(safe()).withPropertyValues("app.amazon.fee-pilot.publication-mode=guess").run(c->assertThat(c).hasFailed());
    }

}
