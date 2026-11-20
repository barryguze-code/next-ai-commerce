# Reviewed zero-stock catalogue reconciliation

This maintenance operation is **not** an automatic merge, migration or backfill.
It accepts a reviewed manifest for one verified same-account product pair. It is
restricted to a legacy source with zero net stock in every location/expiry bucket,
no active pair reservations (Amazon or Shopify), no pre-existing frozen Shopify source lines,
no open warehouse orders, unfinished purchases/receiving or active global supplier
identity dependencies. Source history, internal SKU, cost layers and quantities
remain unchanged. Source becomes inactive; its unused global identity is archived.
Only reviewed current Amazon/Shopify mapping references change, preserving component quantities.

## Execution

- Run all `CatalogReconciliationDatabaseTest` checks on disposable PostgreSQL.
- Review the manifest, source/target UUIDs, authoritative barcode/vendor identity,
  parent/component row revisions and exact stock/reservation revisions.
- The runner requires root maintenance access, pinned PostgreSQL peer access on
  `/var/run/postgresql:5432`, user `postgres`, database `next_ai_commerce`. It drops
  ambient PostgreSQL routing variables and does not load a `.psqlrc`.
- `python3 run.py /reviewed/manifest.json` performs the exact operation and rolls it
  back. Any SQL error aborts; no failed transaction can commit.
- `--apply` requires the approved publication hold plus a fully stopped loaded
  application service (`inactive/dead/MainPID0`). Stop the existing application
  service gracefully and confirm no remaining application/worker processes or
  in-flight publication before applying. Do not kill an unrelated service.
- Never disable publication triggers. They queue normal dirty reconciliation in
  the repair transaction. These records remain available when workers resume.
- Apply rechecks all preconditions under tenant inventory then catalogue identity
  advisory locks and ordered row locks. A changed revision aborts the whole repair.
- Audit records store the approved manifest, before/after parent/components and
  preserved history hashes. Repeat request is a no-op only while the audited
  resulting mapping state still matches. Changed intent/state is rejected.
- Verify stock/reservation hashes, mapping quantities, source inactive/global
  archived and target stock before restoring the original service. Then verify
  startup health and normal publication reconciliation. Never run blind reverse
  SQL after new orders/receipts/reservations have been processed.

## Scope and limitations

No account SKU is moved: inactive identities retain their unique SKU and history.
Images and conversations are retained with archived history, not deleted. This
operation does not acquire missing barcodes or authorize the other 27 records.
Stocked duplicates, reservations, open work, live/incomplete Shopify order dependencies, unsupported mappings, shared
legacy globals and identity conflicts need a separate reviewed consolidation.
The runner does not pause/start services itself and cannot attest to another host's
workers. A single-host stopped-service deployment must be verified operationally.
No application JAR, schema, product creation rule or marketplace API changes.

The reviewed manifest pins `providerCounts` (component counts by channel). Shopify
order lines require an exact `shopifyHistory` evidence snapshot. Only complete,
fulfilled, noncancelled reporting-only lines with zero unfulfilled units and a
current mapping basis qualify. Their complete existing composition is copied to
the existing frozen-components table before remapping, without rewriting raw
orders/lines/variants or allocating historical inventory. Changes and existing
frozen components abort; the audit records before/after evidence.

Mapping triggers use the zero-UUID ALL-items sentinel in
`inventory_publication_dirty`; verification must accept that normal account-wide
invalidation, rather than require a separate canonical-item entry.

## Invoice-verified metadata lane

`enrich-identity.sql` and `run-metadata.py` support a separate, narrowly scoped
operation. ADD_IDENTIFIER adds an unowned, checksum-valid invoice GTIN to the
existing global product. LINK_GLOBAL reconnects the same account-item ID to an
existing verified global identity only when that account has no competing item
for the target. Neither operation consolidates stocked account items.

The manifest pins full source/target product and account rows, current vendor
offer scope, original invoice rows, supplier aliases and protected inventory,
reservation, mapping, receiving and purchase hashes. Missing, contradictory or
alphanumeric invoice barcode evidence rejects the operation. Missing identifiers
alone never prove a duplicate. Shared source globals, changed expiration policy,
foreign supplier aliases and active packaging versions block linking.

Owned supplier aliases retain their IDs and vendor/branch/item-code identity;
only their global reference follows the verified link. The unused source global
is archived. Target global metadata and other accounts remain unchanged. Existing
account units-per-case overrides, stock IDs, quantities and historical rows remain
unchanged. Audit action is CATALOGUE_IDENTITY_ENRICHMENT.

`metadata-protected-evidence.sql` and `metadata-invoice-evidence.sql` provide the
read-only manifest evidence. The runner defaults to rollback and requires the
same stopped-service approval checks for --apply. Run a combined reviewed batch
inside one transaction, then independently compare all protected hashes before
restoring the original service. Do not retry a committed operation blindly after
new business activity. Forty-five isolated PostgreSQL checks passed for both
maintenance lanes; see the engineering handoff for exact production evidence.

## Approved stocked lane (Ozery)

`stocked-pair.sql`, `run-stocked.py` and `stocked-manifest-evidence.sql` implement
the separately approved `FINAL_LAYER_TRANSFER` operation. This is not a generic
duplicate purge. Both account-item identities require exact original invoice
barcode evidence, matching product/expiry policy, full account/product revisions
and current same-vendor supplier offers. Retained invoice and purchase rows,
mapping parents/components, reservations and original ledger rows are pinned.

Remaining layers follow the actual order allocator's per-location/expiry FIFO
depletion. Only FINAL, known-cost remaining source layers qualify. Provisional
or unknown remaining cost, negative buckets, overlapping live target FIFO buckets,
future entries, active reservations/open work or stale evidence stop the repair.
All original ledger rows remain immutable. Paired current-time TRANSFER entries
carry each original layer ID, quantity, location, expiry, cost, currency, lot and
fees. This preserves quantities and valuation per batch without backdating or
averaging costs. The original receipt/cost provenance remains in the audit.

The old current vendor-offer versions are ended on the preceding date, retaining
their IDs, costs and historical references. Only offers effective before today
can be ended. The canonical offers are retained. This prevents the obsolete
item being selected again by supplier-code matching. Global supplier aliases or
active packaging versions on the source remain blockers for this narrow lane.

Current mappings follow the canonical item with unchanged pack quantities.
Complete fulfilled reporting-only Shopify history is preserved using the existing
snapshot contract; unsupported/frozen/open dependencies stop the operation.
The exhausted source account/global is inactivated/archived, never deleted.
Audit action: CATALOGUE_STOCKED_RECONCILIATION. The runner defaults to exact
rollback and requires the existing stopped-service hold for --apply.

Sixty-nine focused disposable PostgreSQL checks pass, including actual Amazon
FEFO/FIFO allocation before/after transfer, multiple final layers, both-provider
pack quantities, reporting-only history, rollback/idempotence, invoice conflicts,
reservation/open-order guards, negative/future buckets, account drift and another
account sharing the canonical global. See the handoff for live preflight blockers;
passing fixtures alone do not authorize bypassing production evidence.

The stocked lane also recognizes the exact `Shipped - Picked Up` status as a
completed warehouse departure, matching OrderRepository.statusLeavesWarehouse.
`Partially Shipped` still blocks; unknown status values are not broadly accepted.
