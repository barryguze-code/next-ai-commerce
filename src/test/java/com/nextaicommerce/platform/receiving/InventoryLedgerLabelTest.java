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
 private InventoryRepository.LedgerView packed(String qty,String pack){return new InventoryRepository.LedgerView(null,null,java.time.Instant.EPOCH,"P","SKU",null,"ADJUSTMENT",new BigDecimal(qty),null,null,"USD","ADJUSTMENT",null,null,null,"FINAL",pack==null?null:new BigDecimal(pack));}
 @Test void wholeCaseEquivalentIsExactSignedAndDisplayOnly(){assertThat(packed("-24","12").wholeCases()).isEqualTo("-2 cases");assertThat(packed("12.0000","12.0000").wholeCases()).isEqualTo("+1 case");assertThat(packed("-12","12").quantityUnits()).isEqualTo("-12");for(String[] values:new String[][]{{"25","12"},{"0","12"},{"12",null},{"12","0"},{"12","-2"},{"12","2.5"},{"2.5","1"}})assertThat(packed(values[0],values[1]).wholeCases()).isNull();}
}
