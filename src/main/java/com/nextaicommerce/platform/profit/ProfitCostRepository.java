package com.nextaicommerce.platform.profit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProfitCostRepository {
    private final JdbcTemplate jdbc;
    public ProfitCostRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Transactional(readOnly=true)
    public List<ProfitShippingRates.Rate> rates(UUID tenant) {
        scope(tenant);
        var overrides=jdbc.query("SELECT package_type,effective_from,amount FROM profit_shipping_rates WHERE tenant_id=? ORDER BY effective_from,package_type",
            (rs,row)->new ProfitShippingRates.Rate(ProfitShippingRates.PackageType.valueOf(rs.getString(1)),
                rs.getDate(2).toLocalDate(),rs.getBigDecimal(3)),tenant);
        var result=new ArrayList<ProfitShippingRates.Rate>();
        for (var original:ProfitShippingRates.defaults()) {
            if (overrides.stream().noneMatch(r -> r.packageType()==original.packageType() && r.effectiveFrom().equals(original.effectiveFrom())))
                result.add(original);
        }
        result.addAll(overrides);
        return List.copyOf(result);
    }
    /** Dates cannot be silently overwritten: new prices must have a new effective date. */
    @Transactional
    public void addRate(UUID tenant, ProfitShippingRates.Rate rate, String actor) {
        if (rate.effectiveFrom().isBefore(ProfitShippingRates.START))
            throw new IllegalArgumentException("Rates start on 01/01/25");
        if (actor==null || actor.isBlank()) throw new IllegalArgumentException("User is required");
        scope(tenant);
        int inserted=jdbc.update("""
            INSERT INTO profit_shipping_rates(tenant_id,package_type,effective_from,amount,created_by)
            VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
            """,tenant,rate.packageType().name(),rate.effectiveFrom(),rate.amount(),actor);
        if (inserted!=1) throw new IllegalArgumentException("A rate already exists for this package and date");
    }
    private void scope(UUID tenant) {
        if (tenant==null) throw new IllegalArgumentException("Account is required");
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    }
}
