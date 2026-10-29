package com.nextaicommerce.platform.shopify;

import java.time.Instant;
import java.util.*;

/**
 * Pure decision contract for a complete, scoped Shopify order line observation.
 * Writers must fence source revision, lease and permissions and apply the plan atomically.
 * Payment is not evidence of physical departure. Only independently observed SUCCESS fulfillments qualify.
 */
public final class ShopifyOrderLifecycle {
    private ShopifyOrderLifecycle() {}
    public enum Ownership { MERCHANT, EXTERNAL, UNKNOWN }
    public enum State { LIVE, REPORTING_ONLY, TEST, NEEDS_REVIEW, CANCELLED }
    public record Identity(UUID tenant, UUID connection, String order, String line) {
        public Identity {
            Objects.requireNonNull(tenant); Objects.requireNonNull(connection);
            gid(order, "Order"); gid(line, "LineItem");
        }
    }
    /** Quantity is in sold marketplace units, never component eaches. */
    public record Fulfillment(String gid, String lineGid, int quantity, String status, Ownership ownership, Instant occurred) {
        public Fulfillment {
            ShopifyOrderLifecycle.gid(gid, "Fulfillment"); ShopifyOrderLifecycle.gid(lineGid, "FulfillmentLineItem");
            if (quantity < 0) throw new IllegalArgumentException("Negative fulfillment quantity.");
            Objects.requireNonNull(status); Objects.requireNonNull(ownership); Objects.requireNonNull(occurred);
        }
        public String key() { return gid + ":" + lineGid; }
    }
    public record Observation(Identity id, Instant created, Instant updated, boolean complete, boolean test,
                              boolean cancelled, String payment, boolean requiresShipping, Ownership ownership,
                              int ordered, int unfulfilled, List<Fulfillment> fulfillments, String hash) {
        public Observation {
            Objects.requireNonNull(id); Objects.requireNonNull(created); Objects.requireNonNull(updated);
            Objects.requireNonNull(payment); Objects.requireNonNull(ownership); Objects.requireNonNull(hash);
            fulfillments = List.copyOf(fulfillments);
            if (updated.isBefore(created) || ordered < 0 || unfulfilled < 0 || unfulfilled > ordered)
                throw new IllegalArgumentException("Inconsistent Shopify line quantities or revision.");
            if (hash.isBlank()) throw new IllegalArgumentException("Missing observation hash.");
            var keys = new HashSet<String>();
            for (var f : fulfillments) if (!keys.add(f.key()))
                throw new IllegalArgumentException("Duplicate fulfillment identity.");
        }
    }
    /** Seen successful quantities include baseline/unrecorded events: later stock cannot backfill old shipments. */
    public record History(Identity id, Instant revision, String hash, State state, Map<String,Integer> seenSuccess) {
        public History {
            Objects.requireNonNull(id); Objects.requireNonNull(revision); Objects.requireNonNull(hash);
            Objects.requireNonNull(state); seenSuccess = Map.copyOf(seenSuccess);
            if (seenSuccess.values().stream().anyMatch(q -> q == null || q < 0))
                throw new IllegalArgumentException("Invalid fulfillment checkpoint.");
        }
    }
    /** A writer may consume only prior ACTIVE reservations for these new units, retaining actual lot provenance. */
    public record Shipment(String fulfillmentKey, int marketplaceUnits) {}
    public record Plan(State state, Integer reserveUnits, boolean releaseReservations, boolean unchanged,
                       List<Shipment> shipments, Map<String,Integer> nextSuccess, String review) {
        public Plan { shipments = List.copyOf(shipments); nextSuccess = Map.copyOf(nextSuccess); }
    }

    public static Plan plan(Observation now, History old, Instant activatedAt) {
        if (old != null && !old.id().equals(now.id()))
            throw new IllegalArgumentException("Order evidence is outside the stored account, connection or line.");
        if (old != null && now.updated().isBefore(old.revision())) return keep(old, "Stale source observation.");
        if (old != null && now.updated().equals(old.revision())) {
            if (!now.hash().equals(old.hash())) return keep(old, "Conflicting evidence at the same source revision.");
            return keep(old, null);
        }
        if (!now.complete()) return keep(old, "Incomplete order or fulfillment evidence.");

        var seen = new LinkedHashMap<String,Integer>(old == null ? Map.of() : old.seenSuccess());
        var successful = new LinkedHashMap<String,Fulfillment>();
        for (var f : now.fulfillments()) if ("SUCCESS".equals(f.status())) successful.put(f.key(), f);
        // A disappearing/cancelled/decreased posted fulfillment is a review, never an automatic physical return.
        if (old != null) for (var prior : old.seenSuccess().entrySet()) {
            var current = successful.get(prior.getKey());
            if (current == null || current.quantity() < prior.getValue())
                return keep(old, "Previously observed shipment changed; physical stock requires review.");
        }
        if (now.test()) return closed(State.TEST, seen, "Test order; no warehouse operation.");
        if (activatedAt == null || now.created().isBefore(activatedAt)
                || (old != null && old.state() == State.REPORTING_ONLY)) {
            successful.forEach((key, f) -> seen.put(key, f.quantity()));
            return closed(State.REPORTING_ONLY, seen, null);
        }
        if (!now.requiresShipping() || now.ownership() != Ownership.MERCHANT)
            return keep(old, "Merchant-managed warehouse ownership is not verified for this line.");

        var shipments = new ArrayList<Shipment>();
        boolean baseline = old == null;
        for (var f : successful.values()) {
            int previous = seen.getOrDefault(f.key(), 0);
            if (f.quantity() > previous && f.ownership() != Ownership.MERCHANT)
                return keep(old, "Fulfillment ownership is not verified as merchant-managed.");
            if (!baseline && f.quantity() > previous) shipments.add(new Shipment(f.key(), f.quantity() - previous));
            seen.put(f.key(), f.quantity());
        }
        if (baseline && now.unfulfilled() == 0 && !successful.isEmpty())
            return closed(State.REPORTING_ONLY, seen, "First seen already fulfilled; no retrospective stock deduction.");
        boolean cancel = now.cancelled() || "VOIDED".equals(now.payment());
        // Awaiting-payment orders reserve too. Refunding is not proof that shipped items returned.
        return new Plan(cancel ? State.CANCELLED : State.LIVE, cancel ? 0 : now.unfulfilled(), cancel,
            false, shipments, seen, null);
    }

    private static Plan closed(State state, Map<String,Integer> seen, String review) {
        return new Plan(state, 0, true, false, List.of(), seen, review);
    }
    private static Plan keep(History old, String review) {
        return new Plan(old == null ? State.NEEDS_REVIEW : old.state(), null, false, review == null,
            List.of(), old == null ? Map.of() : old.seenSuccess(), review);
    }
    private static void gid(String value, String kind) {
        if (value == null || !value.matches("gid://shopify/" + kind + "/[0-9]+"))
            throw new IllegalArgumentException("Invalid Shopify " + kind + " identity.");
    }
}
