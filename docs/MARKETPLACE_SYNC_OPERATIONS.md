# Marketplace synchronization operations

This document is the operational reference for ongoing marketplace synchronization after a
store's initial import. The database is authoritative for scheduling, leases, retries, source
evidence, normalized records, and progress. Every record is scoped by both `tenant_id` and
`marketplace_connection_id`.

## Execution model

1. The scheduler scans once per minute for due work belonging to active, authorized stores.
2. It creates at most one active synchronization run per store. A large store cannot create
   overlapping runs for itself or overwrite another tenant's work.
3. Every run has a named profile, an explicit marketplace-time window, and one or more jobs.
4. Workers claim jobs with PostgreSQL row locks and renewable leases. A crashed JVM can resume
   expired work without creating a second successful import.
5. Amazon report IDs and polling state are persisted. Application restarts resume polling rather
   than requesting the same report again.
6. Rate limits and transient failures use the existing Amazon retry/backoff path. Permanent failure
   of optional data is recorded and skipped so later schedules continue.
7. Raw reports/API pages are retained. Normalizers upsert by marketplace business keys, so overlap
   windows update late changes without duplicating rows.
8. Successful runs update `amazon_sync_watermarks`; schedule health is recorded in
   `marketplace_sync_schedules`.
9. A newly created schedule looks at the most recent completed import of the same dataset and waits
   for its full cadence. Daily reports are therefore not requested again minutes after initialization.
10. If Amazon marks an optional recurring report `CANCELLED` or `FATAL`, the run records a warning
    and moves on immediately. It does not spend an hour retrying an unavailable duplicate report.

Console activity includes the schedule profile and the actual `windowStart`/`windowEnd`. Some
low-level job identifiers retain `_30_DAY` for compatibility with the established normalizers;
the run window—not that internal identifier—controls what dates Amazon receives.

## Amazon US recurring plan (v0.3)

| Profile | Cadence | Overlap/window | Jobs and purpose |
|---|---:|---:|---|
| `ORDER_CHANGES` | 15 minutes | Previous 24 hours | Order and item status, price, quantity, cancellation changes |
| `CURRENT_INVENTORY` | 1 hour | Current snapshot | Current fulfillable, reserved, inbound, and unsellable FBA balances |
| `RECENT_ORDER_RECONCILIATION` | 6 hours | Previous 7 days | Safety reconciliation for delayed updates and cancellations |
| `RETURNS` | 4 hours | Previous 7 days | Customer return events and disposition changes |
| `FINANCES` | 4 hours | Previous 7 days | Financial transactions, fees, refunds, and adjustments |
| `ORDER_LIFECYCLE` | Daily | Previous 30 days | Wider cancellation/refund lifecycle reconciliation |
| `LISTINGS` | Daily | Current full listing file | SKU, ASIN, offer, fulfillment, price, and listing-state refresh |
| `INVENTORY_LEDGER` | Daily | Previous 14 marketplace-local days | Authoritative versioned inventory-event replacement |
| `REIMBURSEMENTS` | Daily | Previous 30 days | New and changed FBA reimbursement events |
| `FBA_FEES` | Daily | Current snapshot | Effective-dated FBA fee estimates |
| `FBA_CUSTOMER_SHIPMENTS` | Daily, lowest priority | Previous 7 days | Outbound FBA shipment reconciliation and enrichment |

`FBA_CUSTOMER_SHIPMENTS` is intentionally the final/lowest-priority report. It improves shipment,
forecasting, finance, and reimbursement analysis, but never blocks current operational work.

The historical Inventory Ledger is different from append-only datasets. Each overlapping report
is treated as a new authoritative version of its covered marketplace-local dates. Missing old rows
are superseded, identical rows retain deterministic occurrence numbers, and corrected rows replace
the active projection without destroying raw evidence.

## Configuration

| Environment value | Default | Meaning |
|---|---:|---|
| `AMAZON_SYNC_ENABLED` | `true` | Enables workers that call Amazon and normalize responses |
| `AMAZON_RECURRING_SYNC_ENABLED` | `true` | Enables creation of recurring runs |
| `AMAZON_WORKER_DELAY_MS` | `5000` | Delay between worker claims |
| `AMAZON_SCHEDULER_DELAY_MS` | `60000` | Delay between due-schedule scans |

Cadences and lookback windows are seeded into `marketplace_sync_schedules`. This makes schedule
health queryable and allows a future administration screen to pause or tune a store without
changing application code. Code defaults are reapplied only for missing schedules; an operator's
existing database configuration is preserved.

## Operational queries

Set the tenant context in the same DBeaver session before querying tenant-protected tables:

```sql
SELECT set_config('app.tenant_id', 'PASTE_TENANT_UUID_HERE', false);

SELECT schedule_key, enabled, next_run_at, last_enqueued_at, last_success_at, last_error
FROM marketplace_sync_schedules
ORDER BY priority;

SELECT sync_profile, run_type, status, window_start, window_end, created_at, completed_at
FROM marketplace_sync_runs
ORDER BY created_at DESC
LIMIT 50;

SELECT dataset, high_watermark, last_success_at, last_reconciliation_at
FROM amazon_sync_watermarks
ORDER BY dataset;
```

## Walmart direction

Walmart credentials and tenant/store access are already modeled, but v0.3 does **not** claim that
Walmart business-data synchronization is live. Walmart will use the same scheduling contracts,
leases, source-document retention, watermarks, overlap windows, and normalized commerce tables
through a channel-specific worker/adapter.

Planned Walmart profiles mirror the business outcomes rather than Amazon report names:

- Orders: incremental API polling plus a wider daily lifecycle reconciliation.
- Inventory: current item/inventory feed with overlapping safety checks.
- Returns and refunds: incremental event ingestion and daily reconciliation.
- Settlement/reconciliation reports: daily import with stable transaction keys.
- Items and offers: nightly full reconciliation plus change-triggered refresh.

Walmart schedules must remain disabled until its normalizers and API behavior have automated tests.
Amazon and Walmart jobs will share orchestration but never share credentials, cursors, rate-limit
buckets, or marketplace identifiers.

## Data retention and disconnect behavior

Disconnecting a store removes credentials and stops future runs. Imported operational records,
source reports, watermarks, and historical audit evidence remain. A separate, strongly confirmed
Super Admin retention workflow will be required for permanent deletion.
