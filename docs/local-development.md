# Local development and owner UAT

Owner environment policy:2026-10-26. Follow [root governance](../AGENTS.md#one-local-database-no-staging--owner-decision-2026-10-26).
One existing local test/UAT database serves development and owner testing; production
is separate. No staging environment, separate local automated-test DB, per-feature
preview DB or new rehearsal DB is a default prerequisite. Historical1.0 instructions
are retained in the [original](archive/replaced-20261026/one-local-database/docs/local-development.md),
not current instructions to provision extra environments.

## Required Eclipse startup workflow

Inspect actual listeners, current profile/connection target and existing guards first.
Use `Local PostgreSQL - Start.launch`, then `Local UAT - Start Application.launch`
in Eclipse External Tools, keeping logs visible in Console. Use Status/Stop controls
only for identified related processes. Preserve every .launch file and the current
approved canonical database target. No hidden terminal replacement services.
If Eclipse GUI launch is unavailable, ask the owner to launch and wait for confirmation.
Restart for changed Java/classes only when authorized runtime verification requires it;
source/templates alone do not prove the running artifact changed.

Owner decision 2026-10-29: for an already authorized local reload, if Eclipse Stop
fails, standing permission allows one graceful SIGTERM to the verified local app
PID after checking its exact reviewed JAR/arguments and intended loopback listener.
Confirm it stopped, then relaunch through Eclipse and verify the running artifact
and guards. This does not authorize force-kill, stopping PostgreSQL/other apps or
production, hidden terminal launches, or new feature/integration scope. Do not ask
again for this same local fallback within authorized reload work.

## Canonical local database

Use the effective loopback database from the existing approved Eclipse launch/profile
and any current owner-authorized refresh. Verify actual target rather than assuming
legacy names such as next_ai_commerce_local or next_ai_commerce_test. Do not start
another target, rename/drop databases, switch datasource or remove old DBs merely to
meet the one-database policy. Cleanup/consolidation requires separate scoped approval.
Use the established local PostgreSQL runtime under `.local/postgres18/pgsql`; preserve
data directory/version compatibility and scoped database roles. Never weaken tenant
checks, RLS, credentials or production safeguards to make one shared test target work.

## Unit tests and optional database checks

Eligible hotfixes need passing focused unit tests only; owner runs UAT/other testing
after local or production delivery under the [hotfix lane](../DEPLOYMENT.md#hotfix-lane).
Prefer mocks/in-memory fixtures for unit tests; provision no new local PostgreSQL DB.
If database integration checks are explicitly needed/authorized, use isolated fixtures
and transactions within the canonical database without modifying the owner's records
or dropping shared schemas. Existing runners may require separate DBs/clusters; that
is an implementation constraint to report, not permission to run them against UAT,
change their configuration or create more environments. Existing CI embedded test
infrastructure is unchanged and does not constitute local staging.
Keep full suites in CI as actually configured; do not repeat them locally by habit.

## Copy existing application data

An explicitly authorized refresh may use a trusted PostgreSQL18 custom-format backup
and the reviewed existing copy/sanitization workflow. Verify exact loopback destination,
owner-data preservation/recovery and source freshness before any replacement. Preserve
business UUIDs, mappings, quantities/history and applicable account/private boundaries.
Retain production authentication records, marketplace credentials and connection references
with the matching encryption key for an owner-authorized refresh. Protect private/PII/label
data in restricted local storage; keep outbound writes and automatic collection disabled.
No export, restore, migration or overwrite follows from this Markdown policy itself.
Never restore untrusted archives, bypass actual script destination/nonempty guards,
or copy production secrets into launch files, logs, chat or source control.
Do not treat the shared local database as disposable. The old copy/start scripts may
still create two databases or refuse a populated destination: documentation changes
neither behavior. Report that mismatch and use only an independently reviewed,
explicitly authorized adaptation; do not improvise destructive cleanup.

Production-derived credentials survive ordinary local builds and restarts. Do not overlay
older local credentials or sandbox carrier profiles during an owner-authorized refresh.
Keep datasource, launch configuration and integration safety settings local. Before replacing
data, retain a private recovery snapshot of the existing canonical database and runtime/config.
Quarantine restored pending integration jobs locally before startup; a production queue is
not authorization to call any integration from Local UAT.

## Manual reads and fail-closed outbound operations — 2026-11-20

Local marketplace/carrier reads require an explicit Sync now or supported manual read action.
Opening a page, polling local progress, startup, credential setup and recurring schedules must
not initiate collection. OAuth exchanges, scoped read-only GraphQL queries, report requests,
fee estimates and rate lookups are legitimate reads even when their HTTP method is POST.
The central local transport policy rejects business mutations regardless of individual flags:
stock/price/listing publication, order/fulfillment changes, FBA creation/confirmation/charges,
and carrier label purchases or shipment operations remain off for every integration.
Local calculations, mappings, inventory drafts and planning remain available.

Use `app.local.manual-sync-only=true` with general/legacy selective scheduling, recurring
collection, polling, startup reconciliation, publication, external business writers and mail
explicitly false. Only the existing Amazon worker pollers run, constrained to bounded,
process-local run IDs registered by an explicit request; imported or previous-process queues
have no grant. Manually started finite async workers inherit the request scope. A restart
clears grants and does not resume remote work automatically. User-triggered retries retain
the provider's existing rate limits and finite job budgets.

Production release fixes may be integrated into local code without another approval when
that integration is clean. Preserve every tracked/untracked change and branch; stop and
report real content conflicts instead of overwriting work. An isolated candidate based on
production does not mean the owner's dirty main checkout has been merged.

## Verify and launch

For an authorized delivery, package the exact reviewed build without colliding with
Eclipse output, then use the established Eclipse launch. Confirm expected artifact,
local target and startup health; owner performs remaining hotfix testing. Local and
production runtime profiles/safety settings remain separate; account credentials
come from the approved production snapshot. Owner clarification (2026-10-29):
Amazon-to-local listing/order/recipient/status synchronization and its scoped local
persistence/reconciliation require the explicit manual actions above. Keep outbound Amazon regular/sale-price
updates and inventory-quantity submissions fail-closed. Inbound-read authorization
does not itself authorize charged label purchases, live shipment confirmations,
new roles, production changes or unrelated marketplace mutations. Preserve manual
mapping rules and existing Shopify UAT audit/idempotency when enabling inbound work.

## Release gate

No staging gate. Follow [DEPLOYMENT.md](../DEPLOYMENT.md) for scoped production approval,
artifact identity, actual backup behavior and compatible rollback. Higher-risk migration/
financial/inventory changes keep their recovery requirements; if required recovery
proof cannot fit the existing environment safely, present that specific target/window
decision rather than creating a second local database or overwriting owner data.
Local test records/bootstrap datasets are not production migrations or production data.
