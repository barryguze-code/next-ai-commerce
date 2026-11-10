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
    public record PackageRate(String key,java.time.LocalDate effectiveFrom,java.math.BigDecimal amount,String createdBy,java.time.Instant createdAt) {}
    public record PackageRates(java.util.Map<String,List<PackageRate>> entries,java.util.Set<String> independentOnly) {
        private static String key(String name){return name==null?"":name.trim().replaceAll("\\s+"," ").toLowerCase(java.util.Locale.ROOT);}
        public PackageRate applicable(String name,java.time.LocalDate date){return date==null?null:entries.getOrDefault(key(name),List.of()).stream().filter(r->!r.effectiveFrom().isAfter(date)).max(java.util.Comparator.comparing(PackageRate::effectiveFrom)).orElse(null);}
        public java.math.BigDecimal cost(String name,java.time.LocalDate date,java.math.BigDecimal inherited){var rate=applicable(name,date);return rate!=null?rate.amount():independentOnly.contains(key(name))?null:inherited;}
    }
    /** Exactly two bounded reads per page; never one query per order/SKU. Names are resolved cost identities. */
    @Transactional(readOnly=true)
    public PackageRates packageRates(UUID tenant,java.util.Collection<String> names){
        scope(tenant);var keys=names.stream().filter(java.util.Objects::nonNull).map(PackageRates::key).filter(n->!n.isBlank()).distinct().toList();
        if(keys.isEmpty())return new PackageRates(java.util.Map.of(),java.util.Set.of());
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);var args=java.util.Map.of("tenant",tenant,"keys",keys);
        var rows=named.query("SELECT package_key,effective_from,amount,created_by,created_at FROM packing_package_rates WHERE tenant_id=:tenant AND currency='USD' AND package_key IN (:keys) ORDER BY effective_from DESC",args,(rs,n)->new PackageRate(rs.getString(1),rs.getDate(2).toLocalDate(),rs.getBigDecimal(3),rs.getString(4),rs.getTimestamp(5).toInstant()));
        var grouped=new java.util.HashMap<String,List<PackageRate>>();for(var row:rows)grouped.computeIfAbsent(row.key(),k->new ArrayList<>()).add(row);
        var independent=new java.util.HashSet<>(named.queryForList("SELECT source_key FROM packing_package_aliases WHERE tenant_id=:tenant AND source_key IN (:keys) AND independent_rate_only",args,String.class));
        return new PackageRates(java.util.Map.copyOf(grouped),java.util.Set.copyOf(independent));
    }
    private void scope(UUID tenant) {
        if (tenant==null) throw new IllegalArgumentException("Account is required");
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
    }
}
