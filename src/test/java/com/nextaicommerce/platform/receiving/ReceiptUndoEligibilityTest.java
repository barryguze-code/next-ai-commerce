package com.nextaicommerce.platform.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

class ReceiptUndoEligibilityTest {
    private ReceivingWorkflowRepository.Receipt receipt(int onHand,int reserved,boolean used,boolean closed,boolean settled) throws Exception {
        var jdbc=mock(JdbcTemplate.class);var identity=mock(ResultSet.class);var row=mock(ResultSet.class);var shortage=mock(ResultSet.class);
        when(identity.next()).thenReturn(true);for(int col=1;col<=3;col++)when(identity.getObject(col,UUID.class)).thenReturn(UUID.randomUUID());when(identity.getBoolean(4)).thenReturn(closed);
        when(row.getObject(anyInt(),eq(UUID.class))).thenReturn(UUID.randomUUID());
        when(row.getBigDecimal(2)).thenReturn(new BigDecimal("90"));when(row.getBigDecimal(9)).thenReturn(BigDecimal.valueOf(onHand));when(row.getBigDecimal(10)).thenReturn(BigDecimal.valueOf(reserved));
        when(row.getString(6)).thenReturn("SELLABLE");when(row.getTimestamp(7)).thenReturn(Timestamp.from(Instant.parse("2026-11-11T10:00:00Z")));when(row.getBoolean(11)).thenReturn(used);when(row.getBoolean(12)).thenReturn(settled);
        doAnswer(call->{ResultSetExtractor<?> extractor=call.getArgument(1);return extractor.extractData(call.<String>getArgument(0).contains("SELECT d.id")?identity:shortage);}).when(jdbc).query(anyString(),any(ResultSetExtractor.class),any(Object[].class));
        doAnswer(call->{RowMapper<?> mapper=call.getArgument(1);return List.of(mapper.mapRow(row,0));}).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        var repository=new ReceivingWorkflowRepository(jdbc,mock(ReceivingRepository.class),mock(InventoryRepository.class));
        return repository.receipts(UUID.randomUUID(),UUID.randomUUID()).getFirst();
    }
    @Test void unreservedReceiptWithOtherBatchMovementsCanBeUndone() throws Exception {assertThat(receipt(126,0,true,false,false).canUndo()).isTrue();}
    @Test void reservedBatchRemainsBlocked() throws Exception {assertThat(receipt(126,1,true,false,false).blockedReason()).contains("active orders");}
    @Test void insufficientStockRemainsBlocked() throws Exception {assertThat(receipt(89,0,true,false,false).canUndo()).isFalse();}
    @Test void exactRemainingQuantityCanBeUndone() throws Exception {assertThat(receipt(90,0,true,false,false).canUndo()).isTrue();}
    @Test void explicitlyClosedAndSettledVendorRecordsRemainProtected() throws Exception {assertThat(receipt(126,0,false,true,false).canUndo()).isFalse();assertThat(receipt(126,0,false,false,true).canUndo()).isFalse();}
}
