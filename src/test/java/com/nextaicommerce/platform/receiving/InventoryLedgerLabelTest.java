package com.nextaicommerce.platform.receiving;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class InventoryLedgerLabelTest {
 private InventoryRepository.LedgerView row(String type,String source,String quantity,String notes){return new InventoryRepository.LedgerView(null,null,java.time.Instant.now(),"Product","SKU",null,type,new BigDecimal(quantity),null,null,"USD",source,null,notes,null,"FINAL");}
 @Test void physicalDisposalAndPlanClearingHaveDifferentLabels(){
  assertThat(row("ADJUSTMENT","ADJUSTMENT","-18","removal · Disposed").movementLabel()).isEqualTo("Disposed");
  assertThat(row("INVENTORY_PLAN","INVENTORY_PLAN","0","Plan cleared").movementLabel()).isEqualTo("Plan cleared");
  assertThat(row("INVENTORY_PLAN","INVENTORY_PLAN","0","Plan cleared · Reviewed").movementLabel()).isEqualTo("Plan cleared");
  assertThat(row("INVENTORY_PLAN","INVENTORY_PLAN","0","REMOVE · OTHER · Disposed").movementLabel()).isEqualTo("Removal planned");
 }
 @Test void arbitraryNoteDoesNotTurnAnAdjustmentIntoADisposal(){
  assertThat(row("ADJUSTMENT","ADJUSTMENT","-18","other · Disposed").movementLabel()).isEqualTo("Adjustment");
  assertThat(row("ADJUSTMENT","ADJUSTMENT","18","removal · Disposed").movementLabel()).isEqualTo("Adjustment");
 }
}
