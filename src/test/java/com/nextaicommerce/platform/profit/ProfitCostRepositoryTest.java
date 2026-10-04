package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProfitCostRepositoryTest {
    @Test void packingSlipNamesUseTheConfiguredDollarAmounts() {
        var names=List.of("XSmall","Pak","Small","Medium","Large","Xlarge","Insulated UPS box");
        var amounts=List.of("9.99","12.08","13.86","17.04","23.59","32.30","14.20");
        for(int i=0;i<names.size();i++)assertThat(ProfitShippingRates.cost(ProfitShippingRates.defaults(),
            ProfitRepository.packageType("",names.get(i),""),LocalDate.of(2026,10,3))).isEqualByComparingTo(amounts.get(i));
        assertThat(ProfitRepository.packageType("","Unknown box","")).isNull();
        assertThat(ProfitRepository.packageType("","FedEx Small Box","")).isEqualTo(ProfitShippingRates.PackageType.FEDEX_SMALL);
    }
    @Test void initialDefaultsAreAvailableWithoutTenantWrites() {
        var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant))).thenReturn(List.of());
        assertThat(new ProfitCostRepository(jdbc).rates(tenant)).containsExactlyElementsOf(ProfitShippingRates.defaults());
        verify(jdbc).queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    }
    @Test void sameDateAccountOverrideWinsOverDefault() {
        var jdbc=mock(JdbcTemplate.class);var tenant=UUID.randomUUID();
        var rate=new ProfitShippingRates.Rate(ProfitShippingRates.PackageType.UPS,ProfitShippingRates.START,new BigDecimal("15.00"));
        when(jdbc.query(anyString(),any(RowMapper.class),eq(tenant))).thenReturn(List.of(rate));
        var rates=new ProfitCostRepository(jdbc).rates(tenant);
        assertThat(rates).hasSize(7);
        assertThat(ProfitShippingRates.cost(rates,ProfitShippingRates.PackageType.UPS,LocalDate.of(2025,6,1))).isEqualByComparingTo("15.00");
    }
    @Test void duplicateEffectiveDateIsRejectedRatherThanOverwritingHistory() {
        var jdbc=mock(JdbcTemplate.class);
        var rate=new ProfitShippingRates.Rate(ProfitShippingRates.PackageType.UPS,ProfitShippingRates.START,new BigDecimal("15.00"));
        assertThatThrownBy(()->new ProfitCostRepository(jdbc).addRate(UUID.randomUUID(),rate,"test@example.com"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
    }
    @Test void migrationDefaultsExistingAndFutureSkusAndProtectsRates() throws Exception {
        try(var stream=getClass().getResourceAsStream("/db/migration/V91__profit_estimation_costs.sql")) {
            assertThat(stream).isNotNull();
            String sql=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(sql).contains("other_cost_per_sku NUMERIC(19,4) NOT NULL DEFAULT 1.00",
                "FORCE ROW LEVEL SECURITY","WITH CHECK","PRIMARY KEY (tenant_id,package_type,effective_from)");
        }
    }
}
