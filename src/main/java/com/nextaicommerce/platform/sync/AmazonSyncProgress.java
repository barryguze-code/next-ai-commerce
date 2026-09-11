package com.nextaicommerce.platform.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

public final class AmazonSyncProgress {
    private static final Map<String, Integer> WEIGHTS = weights();

    private AmazonSyncProgress() {}

    public static int weight(String stage) {
        if ("ORDERS_API_DELTA".equals(stage)) return WEIGHTS.get("ORDERS_30_DAY");
        return WEIGHTS.getOrDefault(stage, 0);
    }

    public static int displayPercent(int completedPercent, String stage, String runStatus) {
        if ("COMPLETED".equals(runStatus)) return 100;
        if (!"RUNNING".equals(runStatus)) return completedPercent;
        int activeCredit = Math.max(1, weight(stage) / 3);
        return Math.min(99, completedPercent + activeCredit);
    }

    public static int stageNumber(String stage) {
        int position = 1;
        for (String candidate : WEIGHTS.keySet()) {
            if (candidate.equals(stage)) return position;
            position++;
        }
        return 1;
    }

    public static int stageCount() {
        return WEIGHTS.size();
    }

    public static String stageTiming(String stage) {
        return switch (stage == null ? "" : stage) {
            case "VERIFY_SELLER" -> "Usually under 1 minute";
            case "LISTINGS_SNAPSHOT" -> "Amazon is preparing the product file";
            case "ORDERS_API_DELTA" -> "Usually under 1 minute";
            case "ORDERS_30_DAY", "INVENTORY_SNAPSHOT", "INVENTORY_LEDGER_30_DAY",
                 "FBA_CUSTOMER_SHIPMENTS_30_DAY", "RETURNS_30_DAY",
                 "REIMBURSEMENTS_30_DAY", "FEES_SNAPSHOT" -> "Amazon report preparation may take up to an hour";
            case "FINANCES_30_DAY" -> "Reviewing the 30-day transaction window";
            case "FINAL_RECONCILIATION" -> "Finishing the final data checks";
            default -> "Starting securely in the background";
        };
    }

    private static Map<String, Integer> weights() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("VERIFY_SELLER", 3);
        result.put("LISTINGS_SNAPSHOT", 12);
        result.put("ORDERS_30_DAY", 16);
        result.put("ORDER_ITEMS_30_DAY", 4);
        result.put("INVENTORY_SNAPSHOT", 10);
        result.put("INVENTORY_LEDGER_30_DAY", 15);
        result.put("FBA_CUSTOMER_SHIPMENTS_30_DAY", 8);
        result.put("RETURNS_30_DAY", 7);
        result.put("FINANCES_30_DAY", 10);
        result.put("REIMBURSEMENTS_30_DAY", 7);
        result.put("FEES_SNAPSHOT", 5);
        result.put("FINAL_RECONCILIATION", 3);
        return Collections.unmodifiableMap(result);
    }
}
