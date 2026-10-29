package com.nextaicommerce.platform.shopify;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Pure stock selection for new Shopify reservations. Never moves or writes inventory. */
public final class ShopifyStockAllocation {
    private ShopifyStockAllocation() {}

    /** Available is the cost layer's remaining physical stock less every marketplace's active reservations. */
    public record Lot(UUID tenant, UUID item, UUID layer, UUID location, boolean locationActive,
                      LocalDate expiration, Instant received, BigDecimal available) {
        public Lot {
            Objects.requireNonNull(tenant); Objects.requireNonNull(item); Objects.requireNonNull(layer);
            Objects.requireNonNull(location); Objects.requireNonNull(received); Objects.requireNonNull(available);
        }
    }
    public record Pick(UUID layer, UUID location, LocalDate expiration, BigDecimal quantity) {}
    public record Plan(List<Pick> picks, BigDecimal shortage) {
        public Plan { picks = List.copyOf(picks); }
    }

    /** Same expiry/receipt ordering as Amazon, across active local locations; no default-location fallback. */
    public static Plan reserve(UUID tenant, UUID item, BigDecimal required,
                               LocalDate lastIneligibleExpiry, Collection<Lot> lots) {
        Objects.requireNonNull(tenant); Objects.requireNonNull(item); Objects.requireNonNull(lastIneligibleExpiry);
        if (required == null || required.signum() < 0) throw new IllegalArgumentException("Invalid reservation demand.");
        var seen = new HashSet<UUID>();
        for (var lot : lots) {
            if (!tenant.equals(lot.tenant()) || !item.equals(lot.item()))
                throw new IllegalArgumentException("Inventory layer is outside the requested account or product.");
            if (!seen.add(lot.layer())) throw new IllegalArgumentException("Duplicate inventory layer.");
        }
        var eligible = lots.stream().filter(lot -> lot.locationActive() && lot.available().signum() > 0
                && (lot.expiration() == null || lot.expiration().isAfter(lastIneligibleExpiry)))
            .sorted(Comparator.comparing(Lot::expiration, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Lot::received).thenComparing(lot -> lot.layer().toString())).toList();
        BigDecimal remaining = required;
        var picks = new ArrayList<Pick>();
        for (var lot : eligible) {
            if (remaining.signum() == 0) break;
            BigDecimal take = remaining.min(lot.available());
            picks.add(new Pick(lot.layer(), lot.location(), lot.expiration(), take));
            remaining = remaining.subtract(take);
        }
        return new Plan(picks, remaining);
    }
}
