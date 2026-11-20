# Catalogue and calculator cost defaults

Invoice defaults are initialized only for an item with no vendor price history.
A positive, normalized per-each invoice cost and a non-voided physical receipt
are required. Packing lists, missing/zero invoice prices, shortages and free
overages do not establish buying cost. Existing vendor offers are never replaced
by this initialization. Migration V101 repairs qualifying historical items and
the receipt trigger covers future receipts, with invoice-linked cost history.

Account catalogue **Edit product** requires a default item cost per each. It
uses the current cost vendor; a missing vendor must be selected explicitly.
The amount is a net buying cost (discount already included). Historical receipt
and ledger amounts are untouched.

All table profit calculators use the same editor and estimate repository:

- SKU Cost: an optional per-complete-marketplace-unit override, specific to the
  account and store. **Use catalogue costs** removes the override.
- Catalogue item costs: per each, propagated through mapping quantities to all
  non-overridden SKUs. Bundle components remain individually editable.
- Shipping and other costs retain their existing scope; order shipping is
  prorated by ordered units.

Preview recalculates during editing. **Save costs** persists changes atomically,
records cost history and reloads visible estimates. It does not publish Amazon
prices, change inventory, or rewrite historical revenue/receipt amounts. Profit
is still an estimate using current defaults, not historical FIFO expense.
The aggregate profit summary cache expires within 60 seconds.

Verification: PostgreSQL tests cover invoice initialization, preservation of
existing defaults, invoice item-code search, SKU overrides/reset, item pack
calculation, account/store scope and rollback on invalid package input.

FBA shipping estimates use the latest **complete** shipment for the exact seller SKU,
account and connection, ordered by the posted charge date. Both transportation and
placement charges must be explicitly present, released, in USD, and non-negative;
a missing charge is never assumed to be zero. Charges are divided by all shipped
units on that shipment before allocating the SKU/order quantity. The calculator
and fee tooltip identify the source shipment and charge date. This is a current
planning estimate, not historical order-to-replenishment or settled expense.

Legacy regional fee reports can repeat a seller SKU across currencies. Only rows
whose explicit currency matches the requested marketplace definition are normalized;
other or unknown currencies are recorded as import rejections. Raw reports remain
unchanged. This prevents future mixed-currency snapshot collisions; it does not
repair already inconsistent fee snapshots or authorize a live fee refresh.

Fee readers also require exact connection marketplace and its defined currency,
and reject an explicit report country/currency that contradicts the normalized
snapshot. Blank listing currency uses the existing marketplace definition; it
never causes another marketplace's fee to be substituted. Imported snapshot date
and whole-day age are evidence metadata, not an assertion of current pricing or
freshness; no new expiration threshold is applied. Transportation and placement
are displayed separately and deducted once each. Uploaded commercial item/SKU
costs remain exactly as supplied.
