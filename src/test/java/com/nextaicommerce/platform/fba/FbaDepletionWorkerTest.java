package com.nextaicommerce.platform.fba;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
class FbaDepletionWorkerTest {
 @Test void longBacklogYieldsToExistingExecutorAfterEightJobs(){
  var engine=mock(FbaAutomaticDepletion.class);var worker=new FbaDepletionWorker(engine);var resumed=mock(FbaDepletionWorker.class);
  @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<FbaDepletionWorker>)mock(org.springframework.beans.factory.ObjectProvider.class);
  ReflectionTestUtils.setField(worker,"continuation",provider);when(provider.getObject()).thenReturn(resumed);
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),importJob=UUID.randomUUID(),job=UUID.randomUUID();
  when(engine.enabled()).thenReturn(true);when(engine.enqueue(tenant,connection,importJob)).thenReturn(job);when(engine.nextBatch(tenant,connection)).thenReturn(job);when(engine.claim(tenant,connection,job)).thenReturn("lease");
  worker.afterCompletedImport(tenant,connection,importJob);
  verify(engine,times(8)).processOne(tenant,connection,job,"lease");verify(engine,times(7)).nextBatch(tenant,connection);verify(resumed).resumePending(tenant,connection);
 }
 @Test void rejectedContinuationLeavesDurableWorkForNextNotification(){
  var engine=mock(FbaAutomaticDepletion.class);var worker=new FbaDepletionWorker(engine);var resumed=mock(FbaDepletionWorker.class);
  @SuppressWarnings("unchecked") var provider=(org.springframework.beans.factory.ObjectProvider<FbaDepletionWorker>)mock(org.springframework.beans.factory.ObjectProvider.class);
  ReflectionTestUtils.setField(worker,"continuation",provider);when(provider.getObject()).thenReturn(resumed);
  UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),importJob=UUID.randomUUID();when(engine.enabled()).thenReturn(true);when(engine.pending(tenant,connection)).thenReturn(true);
  doThrow(new org.springframework.core.task.TaskRejectedException("Fixture executor full")).when(resumed).resumePending(tenant,connection);
  worker.afterCompletedImport(tenant,connection,importJob);verify(engine,never()).failed(any(),any(),any(),any());verify(resumed).resumePending(tenant,connection);
 }
 @Test void continuationDrainsWithoutRegisteringTheCancelledOriginalImport(){
  var engine=mock(FbaAutomaticDepletion.class);var worker=new FbaDepletionWorker(engine);UUID tenant=UUID.randomUUID(),connection=UUID.randomUUID(),job=UUID.randomUUID();
  when(engine.enabled()).thenReturn(true);when(engine.nextBatch(tenant,connection)).thenReturn(job,null);when(engine.claim(tenant,connection,job)).thenReturn("lease");
  worker.resumePending(tenant,connection);verify(engine).processOne(tenant,connection,job,"lease");verify(engine,never()).enqueue(any(),any(),any());
 }

}
