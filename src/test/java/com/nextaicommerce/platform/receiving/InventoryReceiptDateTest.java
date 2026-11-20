package com.nextaicommerce.platform.receiving;
import java.sql.*;import java.time.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.springframework.jdbc.core.*;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;import static org.assertj.core.api.Assertions.*;
class InventoryReceiptDateTest {
 @Test void physicalDatesAreBoundToTenantItemAndExactNullablePosition()throws Exception{
  var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();var item=UUID.randomUUID();var location=UUID.randomUUID();var at=Instant.parse("2026-10-25T12:00:00Z");
  when(jdbc.query(eq(InventoryRepository.LAST_SUCCESSFUL_RECEIPT_SQL),any(ResultSetExtractor.class),eq(tenant),eq(item))).thenAnswer(call->{var rs=mock(ResultSet.class);when(rs.next()).thenReturn(true,false);when(rs.getObject("location_id",UUID.class)).thenReturn(location);when(rs.getObject("expiration_date",LocalDate.class)).thenReturn(null);when(rs.getTimestamp("last_received")).thenReturn(Timestamp.from(at));return ((ResultSetExtractor<?>)call.getArgument(1)).extractData(rs);});
  var dates=new InventoryRepository(jdbc).lastSuccessfulReceiptDates(tenant,item);assertThat(dates).containsExactly(entry(new InventoryRepository.ReceiptPosition(location,null),at));
  verify(jdbc).queryForObject(contains("set_config"),eq(String.class),eq(tenant.toString()));
  assertThat(InventoryRepository.LAST_SUCCESSFUL_RECEIPT_SQL).contains("max(receipt.received_at)","receipt.tenant_id=ledger.tenant_id","item.tenant_id=receipt.tenant_id","receipt.voided_at IS NULL","ledger.entry_type='RECEIPT'","ledger.source_type='RECEIVING'","IS NOT DISTINCT FROM").doesNotContain("min(","max(ledger.occurred_at)");
 }
 @Test void unknownReceiptIsNeverReplacedByAMovementDate(){assertThat(InventoryRepository.receiptDateDisplay(null)).isEqualTo("Not recorded");}
}
