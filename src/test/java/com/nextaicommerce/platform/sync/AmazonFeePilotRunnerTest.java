package com.nextaicommerce.platform.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AmazonFeePilotRunnerTest {
    final AmazonProductFeeJobs jobs=mock(AmazonProductFeeJobs.class);
    final AmazonProductFeeSync sync=mock(AmazonProductFeeSync.class);
    final UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),id=UUID.randomUUID();
    final String hash="a".repeat(64),market="SYNTHETIC";
    AmazonFeePilotRunner runner(){return new AmazonFeePilotRunner(jobs,sync,true,tenant.toString(),connection.toString(),market,id.toString(),hash);}
    AmazonSyncStore.Job job(){return new AmazonSyncStore.Job(id,tenant,UUID.randomUUID(),connection,"FEES_SNAPSHOT","FBA_FEES",market,Instant.now(),Instant.now(),null,0,0,false,null,"pilot");}
    @Test void constructionDoesNotEnqueueOrDispatch(){runner();verifyNoInteractions(jobs,sync);}
    @Test void exactClaimAndDispatchBothRequireTheApprovedContract(){
        var job=job();when(jobs.claimPilot(eq(tenant),eq(connection),eq(market),eq(id),eq(hash),anyString())).thenReturn(job);
        runner().tick();verifyNoInteractions(sync);
        when(jobs.matchesPilot(job,hash)).thenReturn(true);runner().tick();verify(sync).processPage(job);
    }
    @Test void missingGrantOrClaimFailureNeverUsesAGenericFallback(){
        runner().tick();verifyNoInteractions(sync);
        when(jobs.claimPilot(any(),any(),anyString(),any(),anyString(),anyString())).thenThrow(new IllegalStateException("synthetic"));
        runner().tick();verifyNoInteractions(sync);
    }
    @Test void missingIdentityOrSelectiveModeFailsBeforeAnyWork(){
        assertThatThrownBy(()->new AmazonFeePilotRunner(jobs,sync,false,tenant.toString(),connection.toString(),market,id.toString(),hash)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new AmazonFeePilotRunner(jobs,sync,true,"",connection.toString(),market,id.toString(),hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AmazonFeePilotRunner(jobs,sync,true,tenant.toString(),connection.toString(),market,id.toString(),"bad")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jobs,sync);
    }
}
