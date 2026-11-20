# FBA shipment read lifecycle

Applies to every Amazon account/store. Production activation is governed by the
[combined1.14.31 deployment plan](deployments/1.14.31-fba-optimization.md), which also
includes the Sync Now presentation fix; staged1.14.30 is superseded.

## Coverage and resumption

The existing tenant/store-scoped `marketplace_sync_jobs.cursor` holds the versioned
shipment read plan, fixed query dates, pagination/item evidence, coverage watermarks,
parent discovery and request budget. No new table or migration is required.

- A first complete shipment history scan includes all supported shipment states and
  complete per-shipment item membership. A selected-item refresh, parent-only job,
  unfinished pagination or generic successful job status cannot establish coverage.
- Existing legacy cursors retain their original query scope and tokens. Cumulative
  page counts above the former 1,200 limit do not force a restart or permanent failure.
- Later requests read **last updated** shipments in a frozen seven-day window.
  After an outage, the window extends to the previous successful watermark minus
  one day; it never drops the gap simply because seven days elapsed.
- Older stored nonterminal shipment IDs are checked in batches of 50 without an
  open-status filter. This observes a transition to closed/cancelled/deleted.
  Missing IDs remain unresolved and do not advance the successful watermark.
- Only `CLOSED`, `CANCELLED` and `DELETED` are terminal. `ERROR`, unknown states,
  receiving and other open states remain eligible for refresh.

Metadata/item commits and checkpoints share the existing fenced transaction. New,
missing, changed and open shipment item reads retain complete evidence before
publication. No partial changed-item response replaces complete membership.

The v0 shipment response model does not guarantee a last-updated timestamp in each
header. Matching closed headers alone cannot prove unchanged received quantities.
When a recent DATE_RANGE result omits that timestamp, its closed shipment items are
conservatively refreshed. The seven-day overlap can therefore recheck recent closed
shipments; old closed history outside the window is not rescanned.

## Verified parents

Parent history and shipment history have separate completion evidence. Only complete
supported-status discovery with exact listing/plan/child identities may seed the
parent baseline; `PARTIAL` or `UNAVAILABLE` cannot do so. Existing verified documents
remain immutable and reusable. Names, dates and destinations never invent a parent.

Parent listings use `LAST_UPDATED_TIME DESC` after historical coverage. There is no
invented date-range parameter. Unchanged fully verified plans skip repeated detail
and child reads. A whole known unchanged older page permits a cutoff only when its
ordering/timestamps are reliable and older open links are resolved. Unknown plans,
missing timestamps, inconsistent ordering and unresolved links prevent that shortcut.
Outages retain the last successful parent watermark with overlap.

## Scheduling and limits

Production's existing enabled FBA schedule changes to once daily. Shipment windows
are independent of finance windows; fee queries, charge normalization, warehouse
reservations and deduction idempotency remain on their existing contracts.

Each activation admits at most 600 requests per hourly checkpoint window. Automatic
work defers through `available_at`; a finite manual activation explicitly pauses at
its budget or worker lifetime. Paused manual work is not claimed by a generic worker.
An explicit Sync Now resumes the same job/tokens with a fresh admission budget,
retains cumulative progress, and preserves Amazon's actual retry/backoff deadline.
A rate-limited active job is distinct from an explicitly paused job.

Local UAT still permits external reads only from explicit manual requests and blocks
all external business writes. Restored production queues never acquire permission
from this feature. No live job, production credential, schedule or writer flag is
modified during local development.

## API contracts

- [Amazon getShipments](https://developer-docs.amazon/sp-api/reference/getshipments):
  `DATE_RANGE`, `LastUpdatedAfter`, `LastUpdatedBefore`; ID queries must not combine
  an open-only status filter when checking for closure.
- [Amazon listInboundPlans](https://developer-docs.amazon/sp-api/reference/listinboundplans):
  page size up to 30, optional status `ACTIVE`/`SHIPPED`/`VOIDED`, last-updated sorting
  and pagination tokens; no date-range request parameter.
- [Official v0 model](https://github.com/amzn/selling-partner-api-models/blob/main/models/fulfillment-inbound-api-model/fulfillmentInboundV0.json).
