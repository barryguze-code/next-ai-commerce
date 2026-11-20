package com.nextaicommerce.platform.sync;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
class FbaParentWorkflowTest {
 @Test void initialAndRecurringFbaContinueIntoParentDiscovery(){for(String profile:new String[]{"INITIAL_30_DAY","FBA_INBOUND","FBA_MANUAL"})assertThat(FbaInboundSync.parentWorkflow(profile)).isTrue();}
 @Test void itemAndOtherDatasetRefreshesDoNotInventParentReads(){for(String profile:new String[]{"ORDER_CHANGES","FBA_FEES","INITIALIZATION",""})assertThat(FbaInboundSync.parentWorkflow(profile)).isFalse();assertThat(FbaInboundSync.parentWorkflow(null)).isFalse();}
}
