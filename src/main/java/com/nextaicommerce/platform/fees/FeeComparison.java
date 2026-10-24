package com.nextaicommerce.platform.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;

/** Pure comparison of a frozen order basis and a matched, complete charge group. */
public final class FeeComparison {
    private FeeComparison() {}
    public record Result(String state, String reason, BigDecimal expected, BigDecimal actual, BigDecimal difference) {}
    public static Result compare(String currency, Instant purchase, Instant boundary,
                                 BigDecimal expected, BigDecimal actual, boolean complete) {
        if (purchase == null || boundary == null || !purchase.isAfter(boundary) || expected == null)
            return new Result("NOT_COMPARABLE", "Basis unavailable", null, null, null);
        if (!complete || actual == null) return new Result("AWAITING", "Awaiting complete charges", expected, null, null);
        int digits;
        try { digits = Currency.getInstance(currency).getDefaultFractionDigits(); }
        catch (RuntimeException error) { digits = -1; }
        if (digits < 0 || digits > 4 || expected.signum() < 0 || actual.signum() < 0)
            return new Result("NOT_COMPARABLE", "Currency or charge basis unavailable", expected, null, null);
        var e = expected.setScale(digits, RoundingMode.HALF_UP);
        var a = actual.setScale(digits, RoundingMode.HALF_UP);
        var delta = a.subtract(e);
        return new Result(delta.signum() > 0 ? "HIGHER" : delta.signum() < 0 ? "LOWER" : "MATCHES",
                "Comparable order-item totals", e, a, delta);
    }
}
