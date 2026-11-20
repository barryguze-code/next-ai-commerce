# Amazon synchronization architecture

## Store lifecycle

Each marketplace connection belongs to exactly one account and marketplace. Amazon US
(`ATVPDKIKX0DER`) is the first supported implementation. A connection moves through:

`PENDING -> INITIALIZING -> ACTIVE`

Failures move it to `ERROR` without deleting imported records or its resumable cursor.
Saving verified credentials creates one `INITIAL_30_DAY` run. It never marks the store
ready merely because authentication succeeded.

## Initial 30-day run

| Order | Job | Readiness gate | Purpose |
|---:|---|:---:|---|
| 1 | `VERIFY_SELLER` | Yes | Confirm seller and Amazon US participation |
| 2 | `LISTINGS_SNAPSHOT` | Yes | Establish the complete SKU/listing foundation |
| 3 | `ORDERS_30_DAY` | Yes | Import order headers and lifecycle states |
| 4 | `ORDER_ITEMS_30_DAY` | Yes | Import quantities, prices, taxes, and seller SKUs |
| 5 | `INVENTORY_SNAPSHOT` | Yes | Establish current MFN and FBA balances |
| 6 | `INVENTORY_LEDGER_30_DAY` | No | Reconcile Amazon inventory movements |
| 7 | `RETURNS_30_DAY` | No | Import available return events |
| 8 | `FINANCES_30_DAY` | No | Import refunds, fees, transactions, and settlements |
| 9 | `REIMBURSEMENTS_30_DAY` | No | Import Amazon reimbursement events |
| 10 | `FEES_SNAPSHOT` | No | Establish fee inputs for active SKUs |
| 11 | `FBA_CUSTOMER_SHIPMENTS_30_DAY` | No | Import outbound FBA customer shipments as the final report |
| 12 | `FINAL_RECONCILIATION` | No | Verify all completed and skipped enrichment datasets |

Only the first five core jobs gate operational access. As soon as seller verification,
listings, orders, order items, and current inventory are ready, the connection becomes active
and the user can work normally. Historical enrichment continues independently and is represented
by one unobtrusive platform-wide information notice. Optional jobs can complete with a visible
warning when Amazon does not grant the corresponding role.

Optional reports do not block one another while Amazon is preparing them. The final reconciliation
does wait for every report job to complete or be safely skipped. `FBA_CUSTOMER_SHIPMENTS_30_DAY`
is deliberately the last downloaded report because it is valuable for shipment reconciliation,
forecasting, and reimbursement analysis but is not required to begin operating the store.

## Execution guarantees

- Every job is scoped by `tenant_id` and `marketplace_connection_id`.
- A database lease prevents two workers from running one job concurrently.
- Upserts use Amazon business identifiers and are safe to replay.
- Pagination/report identifiers are stored in the job cursor after every successful page.
- HTTP 429 and transient 5xx responses return the job to `WAITING` using Amazon's
  response headers and exponential backoff with jitter.
- Permanent authorization errors stop only the affected store and show an actionable message.
- Raw source documents are retained for audit and reprocessing; normalized tables power the UI.
- Sensitive credentials never enter job cursors, logs, or status messages.
- Original Amazon timestamp text is retained in source evidence. Absolute instants are stored as UTC-aware
  PostgreSQL timestamps, while marketplace-local business dates are stored separately for Seller Central-aligned
  reporting. Amazon US uses `America/Los_Angeles`, never a fixed PST offset, so daylight-saving transitions are handled.

## Marketplace SKU workspace

The Marketplace SKUs workspace is always scoped to the account and marketplace connection selected
in the application switcher. It never combines Ibcore, Karaca, or another store into one operational
table. The normalized `amazon_listings` table is the listing identity source, joined to the latest FBA
inventory snapshot, the latest fee estimate, the preceding 30 days of order items, and any active
account-catalogue mapping.

- Summary counts distinguish active in-stock, out-of-stock, inactive-with-stock, Amazon-problem,
  deleted, and unmapped listings. Platform deletion is a reversible state; the Amazon source row remains.
- Search covers Seller SKU, ASIN, and title; status filters, click-to-sort columns, and pagination execute in PostgreSQL.
- The page reads no more than 100 rows at once so large assortments remain responsive.
- Compact black and brown Amazon marks open Seller Central and the Amazon product page without adding text noise.
- Product images use Amazon's synchronized main image when available and fall back gracefully.
- Four trailing seven-day sales buckets are displayed oldest-to-latest beside the 30-day total.
- Profit is deliberately shown as Pending until landed cost and marketplace fees are both final.
- Listing and Amazon inventory remain source-owned; catalogue mappings remain tenant-owned.
- Selecting a different store returns to the same workspace and reloads only that connection's data.
- Walmart is intentionally shown as unsupported until Walmart catalogue synchronization is implemented;
  Amazon data is never presented under a Walmart context.

### Vendor-code SKU auto-mapping

After each Amazon listing refresh, and once shortly after application startup for existing data,
the mapper compares exact normalized seller-SKU tokens with current vendor item codes in the selected
account. The mapping is created only when exactly one account catalogue item matches. Ambiguous or
unmatched SKUs remain visible for manual mapping. A user can map one marketplace SKU to several
account products and whole-unit quantities for bundles. Manual mappings win over automation; clearing
a manual mapping records an explicit block so the reconciliation job cannot silently restore it.
Missing matches remain visibly Unmapped for review.

Seller-SKU endings `2xEA`, `2XEA`, `EAx2`, and `EAX2` are equivalent. The number becomes
`quantity_per_marketplace_unit`; a SKU without a recognized ending defaults to one each. The mapper
never guesses between multiple products and never uses fuzzy title matching as product identity.

## Inspecting tenant data in DBeaver

The `tenants` table uses `display_name`; it does not have a `name` column. Operational Amazon
tables use forced row-level security, so select the tenant context in the same database session
before querying them:

```sql
SELECT id, display_name, slug, status
FROM tenants
ORDER BY display_name;

SELECT set_config('app.tenant_id', 'PASTE_TENANT_UUID_HERE', false);

SELECT count(*) FROM amazon_inventory_snapshots;
SELECT count(*) FROM amazon_inventory_events WHERE active;
SELECT count(*) FROM amazon_financial_transactions;
SELECT count(*) FROM amazon_import_rejections;
```

## Recurring schedule after activation

| Dataset | Normal schedule | Reconciliation window | Persistence model |
|---|---|---|---|
| Order changes | Every 15 minutes (notifications planned) | Overlapping 24 hours | Upsert by Amazon order/item ID |
| Recent orders | Every 6 hours | Previous 7 days | Lifecycle upsert |
| Cancellations and refunds | Nightly | Previous 30 days | Lifecycle/transaction upsert |
| Listings and SKUs | Daily (notifications planned) | Full current catalogue | Current-state upsert |
| Current FBA inventory | Hourly | Current snapshot | Immutable snapshots plus current projection |
| Inventory Ledger recent reconciliation | Nightly | Previous 14 marketplace-local days | Versioned authoritative replacement |
| Inventory Ledger wider reconciliation | Weekly | Previous 90 marketplace-local days | Versioned authoritative replacement |
| Inventory Ledger historical audit | Monthly or on demand | Previous 12–18 months | Versioned authoritative replacement |
| Customer returns | Every 4 hours | Previous 7 days | Event upsert |
| Financial transactions | Every 4 hours | Previous 7 days | Transaction upsert |
| Settlements | Daily | Open/recent settlement periods | Settlement upsert |
| Reimbursements | Daily | Previous 30 days | Event upsert and case reconciliation |
| FBA fee estimates | Nightly and after listing changes | Current active SKUs | Effective-dated snapshot |
| FBA customer shipments | Daily, lowest priority | Previous 7 days | Shipment event upsert |

Initial connection imports the most recent 30 days first. Older history is backfilled in
bounded windows after the store becomes usable. Observed Amazon usage-plan headers override
the defaults. Work is fairly scheduled across stores, so one large seller cannot starve another
account.

## Inventory Ledger reconciliation

Inventory Ledger is not append-only. Amazon can revise, add, or remove rows for a previously
reported day. Every report is therefore retained as an immutable source document and parsed into
a candidate version before it affects operational inventory.

- Reconciliation boundaries use the marketplace's local business dates.
- A row fingerprint is calculated from normalized business fields.
- Identical rows are preserved with a deterministic occurrence number within each fingerprint.
- Replaying the same report is idempotent.
- Within the report's covered window, rows missing from the new authoritative version are marked
  superseded instead of being physically deleted.
- Newly added or corrected rows become the active version atomically after validation.
- Changes rebuild affected daily inventory projections and out-of-stock periods from the earliest
  changed business date forward.
- Raw source values and source-document relationships remain available for audit and rollback.


## Forecasting invariant

Out-of-stock periods are explicit dated intervals by store and SKU. Replenishment demand
velocity uses only in-stock selling time. Zero sales during an out-of-stock interval are not
treated as zero demand; lost demand is estimated separately.
