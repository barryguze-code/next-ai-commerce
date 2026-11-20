package com.nextaicommerce.platform.sync;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
class FbaManualActivationTest {
 final Instant now=Instant.parse("2026-11-20T00:00:00Z");
 @Test void restartedWaitingJobWithoutWorkerCanResumeExistingCheckpoint(){assertThat(FbaManualSync.activeState("WAITING","FBA_MANUAL",false,false,null,now)).isFalse();var c=new ObjectMapper().createObjectNode().put("next","saved-token").put("pages",900);assertThat(FbaShipmentReadPlan.resume(c,now)).isFalse();assertThat(c.path("next").asText()).isEqualTo("saved-token");assertThat(c.path("pages").asInt()).isEqualTo(900);}
 @Test void expiredRunningClaimIsResumableButUnexpiredFenceCannotBeBypassed(){assertThat(FbaManualSync.activeState("RUNNING","FBA_MANUAL",false,false,now.minusSeconds(1),now)).isFalse();assertThat(FbaManualSync.activeState("RUNNING","FBA_MANUAL",false,false,now.plusSeconds(60),now)).isTrue();}
 @Test void residentWorkerWaitingForRateLimitStillDisablesDuplicateActivation(){assertThat(FbaManualSync.activeState("WAITING","FBA_MANUAL",false,true,null,now)).isTrue();assertThat(FbaManualSync.activeState("WAITING","FBA_MANUAL",true,true,null,now)).isTrue();}
 @Test void explicitPauseResumesWithFreshBudgetWithoutDroppingPagination(){var c=new ObjectMapper().createObjectNode().put("paused",true).put("pauseReason","READ_BUDGET").put("next","token").put("pages",1800);c.putObject("readBudget").put("requests",600);assertThat(FbaShipmentReadPlan.resume(c,now)).isTrue();assertThat(c.path("pages").asInt()).isEqualTo(1800);assertThat(c.path("next").asText()).isEqualTo("token");assertThat(c.path("readBudget").path("requests").asInt()).isZero();}
 @Test void automaticWorkRemainsRunningUntilItsOwnQueueCompletes(){assertThat(FbaManualSync.activeState("WAITING","FBA_INBOUND",false,false,null,now)).isTrue();assertThat(FbaManualSync.activeState("COMPLETED","FBA_MANUAL",false,true,now.plusSeconds(60),now)).isFalse();}
}
