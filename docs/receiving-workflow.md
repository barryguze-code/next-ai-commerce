# Receiving: document-first workflow

## User flow

- Upload an invoice or packing list now and receive it later. Uploading alone does not add stock.
- Select multiple documents (up to 12) from the document queue and open one receiving checklist. Each source retains its own quantities, vendor, receipt history, and original shared-cost allocation group.
- Receive positive whole-number each quantities, one location and expiration batch at a time. Review case size and per-each fees before the first receipt. Invalid quantities, missing required dates, inactive locations, and inappropriate conditions are rejected without changing stock.
- Save partial deliveries and return anytime. Closing a partial document shows received and outstanding quantities, requires a reason and explicit acknowledgement, and optionally creates an internal vendor follow-up. It does not contact the vendor or issue a credit.
- Closed documents cannot be reopened, edited, or deleted. Closing does not remove their warehouse stock. Use receipt history to adjust stock in the same compact right-hand drawer.
- Only a document with no receipt or vendor-claim history can be deleted from the queue. The source and deletion audit are retained, and a corrected upload is then allowed.
- Do not receive both an invoice and its packing list as independent stock for the same delivery. Duplicate vendor references/files are rejected at intake; the checklist also explains this distinction.

## Receipt governance

An undo is an audited reversal, not deletion. The original receipt remains visible, and a compensating ledger movement removes its physical quantity. Repeated publication cannot restore a voided receipt.

Undo is blocked when the document is closed, its vendor follow-up is submitted/settled, its batch has been shipped/moved/adjusted, any quantity in that batch is reserved by active orders, or the original quantity is no longer on hand. Batch-level locking is intentionally conservative: stock sharing a product, location and expiration cannot always be attributed to an individual receipt.

Adjustments append new ledger entries and retain the original invoice/receipt totals. A decrease cannot take a batch below its active reservations. Closed receipts can still be the starting point for these traceable adjustments.

Every new mutation uses a tenant-scoped transaction and the same advisory lock as order allocation. Busy inventory writers fail quickly with a retry message. Request identifiers and fingerprints prevent duplicate writes after a retry or an uncertain response. POST role checks, CSRF protection, tenant predicates and tenant row-level-security policies remain in place.

## Physical counts with expired dates

The default scope updates only the listed product + location + expiration batches. It records the difference from current stock, rather than adding the uploaded quantity as a new receipt. Unlisted batches stay unchanged, and reapplying the same count does not add stock again.

The optional complete-product scope also sets omitted batches for included products to zero; its confirmation explicitly warns about this. Neither scope can reduce quantities below active order commitments. Expired physical units remain visible as cannot sell; they are not silently removed from warehouse inventory.

## Performance and verification

Measured against local PostgreSQL using synthetic data in isolated schemas, not operational tenant inventory:

- Receipt database operations: approximately 111–200 ms in the measured regression runs.
- Document search/pagination across 2,000 documents: 211–253 ms for a 25-document page.
- Chrome-triggered controller actions: pack/fees 364 ms, valid receipt 266 ms, partial close 936 ms, adjustment 711 ms. These include transaction and idempotency work, but not browser rendering or network latency outside this local setup.
- The primary receipt path groups receipt, ledger, source totals, vendor follow-up and audit writes in one database round trip. Document lists are server-paginated; related lines are fetched in bulk. Supporting tenant/source/reservation indexes are in migration V63.

The sub-second goal is verified for these measured local operations, not guaranteed for arbitrary cloud latency, very large invoices, document parsing, bulk counts, or final cost allocation across unusually large receiving groups. Bulk import processing retains its existing progress workflow.

Verification includes 142 passing Maven tests (11 real-PostgreSQL workflow tests covering migration, tenant isolation, retries, partial/full close, undo, stock usage, reservations, physical counts, overages, fees, shortages, concurrency and pagination), JavaScript syntax checks, three passing CSV unit tests, and a manual Chrome walkthrough in light/dark appearance plus a narrow viewport. One optional real-vendor PDF parser test was skipped because its diagnostic PDF was not supplied. The separate existing headless table-browser suite could not launch Chrome in the local sandbox; table search was also exercised through the supported Chrome controls.

## Rollout

Restart the Eclipse application to load the updated Java and apply V63 through the normal Flyway startup. Refresh the browser afterward. The implementation does not deploy to the cloud, change the application version, or enable Amazon inventory publishing. The browser verification harness lives only in test sources, binds to localhost, creates a generated synthetic schema, and removes that schema on shutdown.
