# Profit Truth Architecture

Profit is an operational result, not a single editable number. Next AI Commerce will preserve every
source amount and progressively replace estimates with actual charges without rewriting history.

## Order profit formula

Recognized product revenue minus marketplace fees, landed COGS, outbound shipping, shipping
supplies, refunds, credits, and later carrier adjustments equals order contribution profit. Bundle
COGS is the sum of every mapped account-catalogue component multiplied by its marketplace quantity
and the inventory cost layer actually fulfilled.

## Accuracy ladder

1. **Estimate** — SKU defaults provide expected outbound shipping and a default supply recipe.
2. **Planned** — the fulfillment workflow records the chosen package and carrier quote.
3. **Actual** — the purchased label charge and supplies actually consumed become the order truth.
4. **Reconciled** — carrier adjustments, marketplace settlements, refunds, and credits finalize profit.

Actual values take precedence in calculations, but estimates and earlier versions remain auditable.
Every amount should retain its source, currency, effective time, recorded time, and confidence state.

## Low-effort inputs

- Marketplace SKUs may define a default shipping estimate and supply recipe.
- Supplies such as insulated envelopes, boxes, coolant, and dry ice belong to a reusable supply
  catalogue with effective-dated costs.
- Packing asks only the questions needed for the current order and remembers reusable answers.
- Profit can appear before every input is complete, with a quiet Estimated, Actual, or Reconciled
  state instead of forcing the user through a large setup process.

Future FedEx and UPS integrations will provide quotes, purchased-label charges, voids, refunds, and
post-shipment adjustments. Carrier data will enrich the same order cost records rather than create a
second profit model.

## Data boundaries for implementation

- SKU shipping defaults and supply recipes are tenant/store-owned configuration.
- Order fulfillment costs are immutable, order-owned snapshots.
- Inventory COGS comes from the received cost layer allocated to the order.
- Marketplace fees and financial settlements retain their Amazon or Walmart source identity.
- Carrier adjustments append corrections; they never overwrite the original label transaction.

