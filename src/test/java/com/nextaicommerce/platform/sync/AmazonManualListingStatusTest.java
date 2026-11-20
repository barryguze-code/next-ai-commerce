package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class AmazonManualListingStatusTest {
 @Test void dueScheduleWithoutWorkersIsNotRefreshing(){var s=AmazonManualListingSync.status(false,false,false,false,true,null);assertThat(s.running()).isFalse();assertThat(s.allowed()).isFalse();assertThat(s.statusLabel()).isEqualTo("● Automatic sync off");assertThat(s.message()).contains("disabled");}
 @Test void dueScheduleIsQueuedNotRunning(){var s=AmazonManualListingSync.status(true,true,false,false,true,null);assertThat(s.statusLabel()).contains("Queued");assertThat(s.running()).isFalse();}
 @Test void onlyLiveJobShowsRefreshing(){assertThat(AmazonManualListingSync.status(true,true,true,true,false,null).statusLabel()).contains("Refreshing");}
 @Test void queuedJobIsNotRefreshing(){assertThat(AmazonManualListingSync.status(true,true,true,false,false,null).statusLabel()).contains("Queued");}
 @Test void idlePreservesLastCheck(){Instant last=Instant.now().minusSeconds(600);var s=AmazonManualListingSync.status(true,true,false,false,false,last);assertThat(s.allowed()).isTrue();assertThat(s.lastCompleted()).isEqualTo(last);}
}
