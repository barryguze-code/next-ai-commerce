package com.nextaicommerce.platform.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MarketplaceSkuSearchTest {
    @Test void searchesMappedCodesWithBoundParametersAndKeepsScope(){
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);
            assertThat(sql).contains("mapping_codes ILIKE '%'||?||'%'");
            assertThat(sql).contains("coalesce(ci.account_sku,'')||' '||coalesce(vo.vendor_item_code,'')");
            assertThat(sql).contains("WHERE listing.tenant_id=? AND listing.marketplace_connection_id=?");
            assertThat(sql).doesNotContain("1000000354001");
            Object[] args=Arrays.copyOfRange(call.getArguments(),2,call.getArguments().length);
            assertThat(args).startsWith(tenant,store,tenant,store);
            assertThat(Arrays.stream(args).filter("1000000354001"::equals).count()).isEqualTo(7);
            assertThat(sql.chars().filter(c->c=='?').count()).isEqualTo(args.length);
            return List.of();
        });
        var page=new MarketplaceSkuRepository(jdbc).list(tenant,store,"1000000354001","ALL","status","asc",0,25);
        assertThat(page.rows()).isEmpty();
        verify(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
}
