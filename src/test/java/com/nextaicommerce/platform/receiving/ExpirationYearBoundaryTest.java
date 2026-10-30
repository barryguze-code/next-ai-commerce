package com.nextaicommerce.platform.receiving;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ExpirationYearBoundaryTest {
 @Test void receivingRejectsAncientYearBeforeAnyDatabaseWork(){
  var jdbc=mock(JdbcTemplate.class);
  var work=new ReceivingWorkflowRepository(jdbc,null,null);
  assertThatThrownBy(()->work.receive(UUID.randomUUID(),"test",UUID.randomUUID(),BigDecimal.ONE,LocalDate.of(27,7,14),"EXPIRED",UUID.randomUUID(),""))
    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000");
  verifyNoInteractions(jdbc);
 }
 @Test void expiryCorrectionRejectsAncientTargetBeforeAnyDatabaseWork(){
  var jdbc=mock(JdbcTemplate.class);
  assertThatThrownBy(()->new InventoryRepository(jdbc).changeExpiration(UUID.randomUUID(),"test",UUID.randomUUID(),UUID.randomUUID(),LocalDate.of(2027,8,10),LocalDate.of(27,8,10),BigDecimal.ONE,null))
    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000");
  verifyNoInteractions(jdbc);
 }
 @Test void ordinaryDatesKeepTheirMeaning(){
  assertThatCode(()->ExpirationDates.requireSupported(LocalDate.of(2027,7,14))).doesNotThrowAnyException();
  assertThatCode(()->ExpirationDates.requireSupported(LocalDate.of(2026,1,1))).doesNotThrowAnyException();
  assertThatCode(()->ExpirationDates.requireSupported(null)).doesNotThrowAnyException();
  assertThatThrownBy(()->ExpirationDates.requireSupported(LocalDate.of(2100,1,1))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void deliveryRejectsAncientYearBeforeCatalogueOrReceiptChanges(){
  var jdbc=mock(JdbcTemplate.class);
  var work=new ReceivingWorkflowRepository(jdbc,null,null);
  assertThatThrownBy(()->work.receiveDelivery(UUID.randomUUID(),"test",UUID.randomUUID(),BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,LocalDate.of(27,8,10),UUID.randomUUID(),null,true))
    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000");
  verifyNoInteractions(jdbc);
 }
 @Test void positiveAdjustmentAndSnapshotRejectAncientDatesBeforeWrites(){
  var jdbc=mock(JdbcTemplate.class);var inventory=new InventoryRepository(jdbc);
  assertThatThrownBy(()->inventory.adjustInventory(UUID.randomUUID(),"test",UUID.randomUUID(),LocalDate.of(27,7,14),UUID.randomUUID(),BigDecimal.ONE,"COUNT_CORRECTION",""))
    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000");
  assertThatThrownBy(()->inventory.reconcilePhysicalCountSnapshot(UUID.randomUUID(),"test",UUID.randomUUID(),null,java.util.List.of(new InventoryRepository.PhysicalCountRow(1,"440818",BigDecimal.ONE,LocalDate.of(27,7,14),"MAIN")),false))
    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2000");
  verifyNoInteractions(jdbc);
 }
 @Test void actualInventoryTemplateExpressionsExposeTheStoredCentury() throws Exception {
  var engine=new org.thymeleaf.spring6.SpringTemplateEngine();
  engine.setTemplateResolver(new org.thymeleaf.templateresolver.StringTemplateResolver());
  for(String file:java.util.List.of("inventory.html","inventory-ledger.html")){
   String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/templates",file));
   var matcher=java.util.regex.Pattern.compile("th:text=\"([^\"]*#temporals.format\\(row.expirationDate[^\"]*)\"").matcher(source);
   assertThat(matcher.find()).isTrue();String template="<span th:text=\""+matcher.group(1)+"\"></span>";
   var context=new org.thymeleaf.context.Context();
   context.setVariable("row",java.util.Map.of("expirationDate",LocalDate.of(27,7,14)));
   assertThat(engine.process(template,context)).contains("07/14/0027");
   context.setVariable("row",java.util.Map.of("expirationDate",LocalDate.of(2027,7,14)));
   assertThat(engine.process(template,context)).contains("07/14/27");
  }
 }
}
