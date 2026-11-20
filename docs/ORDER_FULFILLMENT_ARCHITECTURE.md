# Live Order Fulfillment Architecture

## Inventory activation boundary

Every marketplace connection owns an immutable `inventory_activated_at` boundary. Orders purchased
before that instant are historical reporting records and never reserve or deduct current inventory.
Orders purchased afterward enter the live workflow. Amazon-fulfilled orders remain Amazon-owned and
do not consume the platform's local warehouse inventory.

The v0.6 migration sets the boundary to migration time for every existing connection. This makes all
orders already imported at deployment historical by design. It avoids reconstructing today's local
inventory from old Amazon activity.

## Readiness workflow

1. A live FBM order item resolves its Seller SKU through the active marketplace mapping.
2. Single-product and bundle components expand into required account-catalogue eaches.
3. Inventory reserves by earliest expiration first (FEFO), then undated FIFO inventory.
4. Fully allocated orders become **Ready to ship**. Missing identity becomes **Needs mapping** and an
   insufficient allocation becomes **Inventory shortage**.
5. Mapping an SKU causes the next queue refresh to retry every affected open order automatically.

Reservations reduce available-to-promise inventory but do not change physical on-hand. Cancelled,
historical, Amazon-fulfilled, held, or already shipped orders release active reservations.

## Shipment truth

The initial workflow uses an explicit **Confirm shipment** action. It converts each active reservation
into one immutable negative inventory-ledger movement against the exact expiration layer, then marks
the reservation shipped. The operation is transactional and idempotent: a failed shipment changes
nothing and a repeated request cannot deduct inventory twice.

Shipping-label purchase, partial shipment, Amazon Buy Shipping, and carrier integrations will extend
this confirmation step. They must preserve the same reservation and ledger identities so actual label
cost and supply consumption can feed the profit architecture without creating a second inventory model.

## Tenant and concurrency guarantees

- Orders, mappings, reservations, and ledger movements always include `tenant_id` and store identity.
- PostgreSQL row-level security applies to reservations.
- Reconciliation uses a transaction-scoped lock per store, processes oldest live orders first, and
  rebuilds active reservations deterministically.
- A background reconciliation runs every minute by default; opening Orders also refreshes the selected
  store immediately, so mapping and newly received stock resolve without manual maintenance.
- Historical source data is never deleted or reclassified merely because a user changes a filter.
