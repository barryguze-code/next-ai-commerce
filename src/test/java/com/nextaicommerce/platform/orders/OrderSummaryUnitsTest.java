package com.nextaicommerce.platform.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderSummaryUnitsTest {
    @Test void readsUnitSumWithoutCountingBundleComponentsAsOrders() {
        var jdbc=new JdbcTemplate(){
            @Override public <T>T queryForObject(String sql,Class<T> type,Object...args){return null;}
            @Override public <T>T queryForObject(String sql,RowMapper<T> mapper,Object...args){
                assertThat(sql).contains("sum(item.quantity_ordered)","sum(seller_sales.units)","definition.reporting_timezone","orders.fulfillment_state<>'CANCELLED'");
                try{var rs=mock(ResultSet.class);when(rs.getLong(1)).thenReturn(2L);when(rs.getLong(7)).thenReturn(9L);return mapper.mapRow(rs,0);}
                catch(SQLException e){throw new IllegalStateException(e);}
            }
        };
        var summary=new OrderRepository(jdbc).summary(UUID.randomUUID(),UUID.randomUUID());
        assertThat(summary.todayOrders()).isEqualTo(2);assertThat(summary.todayUnits()).isEqualTo(9);
    }
}
