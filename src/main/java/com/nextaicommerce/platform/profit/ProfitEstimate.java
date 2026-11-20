package com.nextaicommerce.platform.profit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Configured USD planning estimate, not an Amazon settlement or accounting profit. */
public final class ProfitEstimate {
    private ProfitEstimate() {}
    public static final BigDecimal DEFAULT_OTHER_COST = new BigDecimal("1.00");
    public record Line(BigDecimal unitPrice, int quantity, BigDecimal productCostPerSku,
                       BigDecimal otherCostPerSku, BigDecimal customerShipping) {
        public Line {
            if (quantity < 1) throw new IllegalArgumentException("Quantity must be positive");
            requireNonNegative(unitPrice, "Selling price");
            requireNonNegative(productCostPerSku, "Product cost");
            requireNonNegative(otherCostPerSku, "Other cost");
            requireNonNegative(customerShipping, "Customer shipping");
        }
    }
    public record Breakdown(BigDecimal revenue, BigDecimal productCost, BigDecimal referralFee,
                            BigDecimal shippingCost, BigDecimal otherCost, BigDecimal profit) {
        public boolean complete() { return profit != null; }
    }
    public static BigDecimal referralRate(BigDecimal unitPrice) {
        if (unitPrice == null) throw new IllegalArgumentException("Selling price is required");
        requireNonNegative(unitPrice, "Selling price");
        return unitPrice.compareTo(new BigDecimal("15.00")) <= 0
            ? new BigDecimal("0.08") : new BigDecimal("0.15");
    }
    /** Each package is charged once, independently of the number of order lines in it. */
    public static Breakdown calculate(List<Line> lines, List<BigDecimal> packageCosts) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("At least one order line is required");
        BigDecimal revenue=BigDecimal.ZERO, products=BigDecimal.ZERO, fees=BigDecimal.ZERO, other=BigDecimal.ZERO;
        boolean revenueKnown=true, productsKnown=true, otherKnown=true;
        for (Line line : lines) {
            BigDecimal quantity=BigDecimal.valueOf(line.quantity());
            if (line.unitPrice()==null || line.customerShipping()==null) revenueKnown=false;
            else {
                BigDecimal lineRevenue=line.unitPrice().multiply(quantity).add(line.customerShipping());
                revenue=revenue.add(lineRevenue);
                fees=fees.add(money(lineRevenue.multiply(referralRate(line.unitPrice()))));
            }
            if (line.productCostPerSku()==null) productsKnown=false;
            else products=products.add(line.productCostPerSku().multiply(quantity));
            if (line.otherCostPerSku()==null) otherKnown=false;
            else other=other.add(line.otherCostPerSku().multiply(quantity));
        }
        BigDecimal shipping=null;
        if (packageCosts!=null && !packageCosts.isEmpty() && packageCosts.stream().allMatch(c -> c!=null)) {
            shipping=BigDecimal.ZERO;
            for (BigDecimal cost : packageCosts) {
                requireNonNegative(cost,"Package cost");
                shipping=shipping.add(cost);
            }
            shipping=money(shipping);
        }
        revenue=revenueKnown?money(revenue):null;
        fees=revenueKnown?money(fees):null;
        products=productsKnown?money(products):null;
        other=otherKnown?money(other):null;
        BigDecimal profit=revenue!=null && products!=null && other!=null && shipping!=null
            ? money(revenue.subtract(products).subtract(fees).subtract(other).subtract(shipping)) : null;
        return new Breakdown(revenue,products,fees,shipping,other,profit);
    }
    private static BigDecimal money(BigDecimal value) { return value.setScale(2,RoundingMode.HALF_UP); }
    private static void requireNonNegative(BigDecimal value,String name) {
        if (value!=null && value.signum()<0) throw new IllegalArgumentException(name+" cannot be negative");
    }
}
