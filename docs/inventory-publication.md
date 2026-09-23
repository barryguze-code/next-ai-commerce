# Inventory publication — local simulation (2026-09-22)

Production publishing remains disabled by default. Local configuration enables the worker in
`DRY_RUN` mode and keeps `app.amazon.write-enabled=false`. No Amazon reads or writes are made
by the simulation worker. The existing order import worker can continue its normal reads.

## Behaviour

- Durable database invalidations commit/roll back with ledger, reservation, order-state,
  order-item, expiry-action, shelf-life-policy, mapping/component and listing changes.
- Ordinary stock changes expand from catalogue item IDs to the affected mappings. Configuration
  changes use an account sweep. The existing Marketplace SKU availability calculation is reused:
  eligible on-hand less active reservations, divided by component units, rounded down;
  the limiting component determines a bundle. Shared items are NOT divided between SKUs.
- Only explicitly seller-fulfilled Amazon listings on active connections are candidates.
  FBA/unknown fulfilment is excluded. Clearing a previously managed mapping produces zero;
  never-mapped listings are not silently taken over.
- Repeated events coalesce into one desired state per account/connection/marketplace/SKU.
  Unchanged quantities do not create new submissions. A 15-minute sweep catches clock-based
  expiry and missed invalidations using the same database date/shelf-life rules as the UI.
- DRY_RUN records calculated quantities under Inventory Ledger → Inventory sync. The page
  displays the latest 100 records, prioritizing errors. Simulation is not Amazon verification.
- Nearby orders through different SKUs sharing an item produce one idempotent, zero-quantity
  `SHARED_STOCK_SALES` ledger note on the later allocated order. The note names the related
  order and SKU and reports unallocated units at detection. The five-minute purchase-time
  window indicates overlap, not proof of exact simultaneity; it is not an oversell guarantee.

## Live adapter (implemented, not enabled or production-validated)

Quantity-only Listings Items PATCH `merge` preserves lead-time/restock fields. Accepted requests
enter VERIFYING, not COMPLETED. Subsequent reads check live `fulfillmentAvailability`.
Zero updates have priority and remain retryable after three attempts, with capped exponential
backoff/jitter and visible errors. A lower live positive quantity may represent an unimported
sale: the worker flags it for attention rather than blindly restoring an older number.

Outbox rows are locked during individual requests, results use revision checks, and rows with
pending invalidations cannot be sent. A database-backed global budget allows at most one request
per second across worker instances. Confirmed rows are checked again after 15 minutes.
These mechanisms cannot prevent an already in-flight Amazon request or sale; shared inventory
still carries oversell risk during import/processing delays.

The live queue now additionally requires a completed order reconciliation after worker startup,
a successful order window less than 15 minutes old, and no unfinished order-import/reconciliation
jobs. Unresolved orders are checked individually on a durable retry schedule, regardless of age.
Missing/failed responses retain reservations; 30-day pending orders and unconfirmed responses
appear under Inventory sync and generate informational ledger notes. Confirmed cancellations
release reservations with an audit note. Older responses cannot regress cancellation status.

Live startup rejects local mode, disabled Amazon writes, or coexistence with the legacy listing
action worker, and requires `AMAZON_INVENTORY_PUBLICATION_CONNECTIONS` (comma-separated connection
UUIDs). `AMAZON_INVENTORY_PUBLICATION_SKUS` optionally limits a canary to exact comma-separated
SKU names; leave it empty only after the canary succeeds. Both queue selection and the HTTP
path enforce these restrictions. Production activation requires a separate reviewed rollout: seller/marketplace
allowlist, fresh order reconciliation, explicit transition of simulated rows, monitoring, and a
small canary. Do not merely switch the mode to LIVE: DRY_RUN rows intentionally remain unsent.
There is currently no self-service activation endpoint or production deployment in this change.

## Verification

Use only the fixed local test database:

```
mvn -DlocalTestDatabase=true -Dtest=InventoryPublicationDatabaseTest,InventoryPublicationWorkerTest,InventoryShelfLifeTest,TemplateRenderingTest,ReceivingWorkflowDatabaseTest test
```

Tests use isolated disposable schemas and synthetic data, never UAT inventory for test writes.
Coverage includes pack/bundle fan-out, expiry/policy/holds, unmapping, cancellation, rollback,
duplicate reconciliation, zero-stock retry, live-vs-submitted verification, stale revisions,
and rejection of live configuration locally. Browser regressions must also pass before release.

References: https://developer-docs.amazon.com/sp-api/docs/merge-a-listing and
https://developer-docs.amazon.com/sp-api/docs/listings-items-api

### Local verification result

Local build `1.2.7-local.20260922.33` was started through Eclipse. Migration 76 applied,
and 731 publication records were observed in DRY_RUN status. The focused Java regression
selection passed 76 tests; 23 non-browser JavaScript checks passed. The complete release
gate has NOT passed: browser launches fail with macOS Mach-port permission errors,
broad Java test discovery encounters duplicate generated class files (for example
`TenantContextTest 12.class`), and the earlier security-context suite did not finish.
No production deployment or live inventory publication was performed.

The v1.3.0 clean Java verification subsequently passed 264 tests (one skipped; zero failures or
errors). The earlier Java discovery/security-fixture blockers are resolved. Browser-launch
restrictions remain locally; CI must pass. See `releases/V1.3.0.md` for the remaining
partial-shipment review and production activation gate. Production publishing remains disabled.

### Partial-shipment release hardening

Migration 78 records missing historical shipment allocations separately so repeated imports
cannot consume unshipped reservations to make up the missing history. Known partial shipments
split existing reservations and deduct only the new portion, with idempotent shipment entries.
Cancellation releases the remaining reservation, never stock already shipped or packed.
Unaccounted partial shipments produce a zero-movement review note and hold every related SKU
at zero. A physical count must cover every remaining positive location/expiration batch after
detection to clear that hold. Counting only one batch does not release the item.
Direct order checks require imported shipped-item totals to match Amazon before changing a
partial/shipped/cancelled status. Missing totals or mismatches retain reservations and retry;
ordinary item import or manual review must resolve the missing shipment evidence.
