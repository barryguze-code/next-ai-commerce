package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** User-supplied estimated package rates; never used to buy a label. */
public final class ProfitShippingRates {
    private ProfitShippingRates() {}
    public static final LocalDate START = LocalDate.of(2025,1,1);
    public enum PackageType { FEDEX_XSMALL, FEDEX_PAK, FEDEX_SMALL, FEDEX_MEDIUM, FEDEX_LARGE, FEDEX_XLARGE, UPS }
    public record Rate(PackageType packageType, LocalDate effectiveFrom, BigDecimal amount) {
        public Rate {
            if (packageType==null || effectiveFrom==null || amount==null || amount.signum()<0)
                throw new IllegalArgumentException("Package, effective date and nonnegative cost are required");
            if (amount.scale()>2) throw new IllegalArgumentException("Shipping cost must use at most two decimal places");
        }
    }
    public static List<Rate> defaults() {
        return List.of(rate(PackageType.FEDEX_XSMALL,"9.99"),rate(PackageType.FEDEX_PAK,"12.08"),
            rate(PackageType.FEDEX_SMALL,"13.86"),rate(PackageType.FEDEX_MEDIUM,"17.04"),
            rate(PackageType.FEDEX_LARGE,"23.59"),rate(PackageType.FEDEX_XLARGE,"32.30"),rate(PackageType.UPS,"14.20"));
    }
    public static BigDecimal cost(List<Rate> rates,PackageType type,LocalDate shippingDate) {
        if (type==null || shippingDate==null) return null;
        return rates.stream().filter(r -> r.packageType()==type && !r.effectiveFrom().isAfter(shippingDate))
            .max(Comparator.comparing(Rate::effectiveFrom)).map(Rate::amount).orElse(null);
    }
    private static Rate rate(PackageType type,String amount) { return new Rate(type,START,new BigDecimal(amount)); }
}
