# Performance and cache decision — 1.0.0

## Decision

Keep PostgreSQL as the only operational source of truth. Use **Caffeine**, an
in-process bounded Java cache, for safe account-visible catalogue projections.
MongoDB is not needed for this cache: another database would add a network hop,
duplicate data lifecycle and another service to operate. Caffeine supports
[bounded expiry](https://github.com/ben-manes/caffeine/wiki/Eviction) and
[atomic loading](https://github.com/ben-manes/caffeine/wiki/Population).
This is a single-instance design, not a distributed-cache claim.

## Read-path review

| Area | Strategy |
| --- | --- |
| Account Catalogue | Page IDs before price, image, vendor and location enrichment; cache the completed display projection |
| Catalogue search | Count and results share one predicate; secondary identifiers and vendor codes included; mapped ASIN/SKU correlated to the correct item |
| Catalogue type-ahead | Bounded direct SQL; no cache used for receiving/product selection validation |
| Inventory positions / ledger | Fresh PostgreSQL reads; movement history scoped to tenant/item, 100 rows per request, stable ordering |
| Orders / reservations | Fresh database state; stock and reservation checks are never cache-driven |
| Receiving / physical counts | Transactional validation and ledger writes; never cache authoritative quantities or locks |
| Marketplace / pricing | Existing persisted snapshots and scheduled refresh remain authoritative; no per-row Amazon calls added |
| Vendors / locations | Catalogue-page display lists share that page cache; edit/save handlers still validate in PostgreSQL |
| Account / permission / collaboration | Fresh authorization and viewer-scoped conversation lookups; private content excluded from catalogue cache |
| Downloads / imports | Shared asynchronous feedback; no synchronous one-second promise for long jobs |

## Bounds and invalidation

`PlatformReadCache` keys include tenant UUID, area, normalized query, page, size
and generation. Defaults: 10-second expiry and 256 entries (hard cap 2048).
Concurrent identical loads coalesce. Failed loads are not cached. The caller
can disable caching with `APP_READ_CACHE_ENABLED=false`.

A successful non-read-only Spring transaction commit advances the generation.
Rollback does not invalidate. An old in-flight load cannot populate the new
generation, and invalidation does not wait for a running loader. Entries from
older generations remain bounded by expiry and capacity. Write transactions
bypass the cache entirely.

This conservatively invalidates the whole instance, including writes from
imports and background workers. External SQL writes can remain unseen until
expiry. Before adding another application instance, either disable this cache
or implement shared invalidation; do not assume local invalidation broadcasts.
Never add authorization, private chat, reservations or inventory validation to
this generic display cache.

## Database work

V64 adds tenant-aware listing lookup, preferred identifier lookup and stable
item-movement history indexes. It makes no inventory/data changes.
Catalogue page enrichment runs only for the selected IDs. Search matches
related records in a materialized set once, rather than repeatedly scanning
all mappings for every product. Movement history
no longer uses an untyped nullable UUID parameter in a PostgreSQL `IS NULL`
expression, and all-item history includes both dated and undated stock.
The UI cancels obsolete drawer loads, pages results and offers an in-place retry.

## Verification and performance budget

The database regression suite includes 20,000 synthetic products, a selective
brand search, exact count/result consistency, tenant scoping, commit/rollback
invalidation, and dated/undated movement tests. It prints cold page/search
milliseconds and warm-cache microseconds and asserts cold catalogue reads below
1,000 ms on the test setup.

### Read-only source measurements (2026-09-11)

The revised SQL was checked against the existing PostgreSQL 18.6 account with
19,417 catalogue items, using the application's non-superuser role and enforced
RLS. Each diagnostic transaction was read-only with a three-second statement
timeout. No migration, index, stock or source record was changed.

| Read | PostgreSQL execution time |
| --- | ---: |
| Previous correlated search-count shape | Exceeded 3,000 ms timeout |
| Revised Egglife search count | 99 ms |
| Revised first-page rows, no search | 32 ms |
| Revised Egglife page rows | 153 ms |
| Revised ASIN page rows | 112 ms |
| Revised no-match page rows | 104 ms |
| Revised all-item movement query (one sampled item) | 1.7 ms |

These are individual database execution measurements with warm database buffers,
not full HTTP timings, percentiles or a guarantee. They exclude network, other
page queries, rendering and cache hits. V64 was not required for these source
measurements and has not been applied there.

**Pending:** full isolated PostgreSQL regression suite, backup restoration and
cloned-data end-to-end browser benchmarks. Do not declare the whole-platform
one-second goal verified from individual SQL results.
Measure cold and warm server reads separately from end-to-end browser rendering,
under the non-superuser application role and realistic concurrent activity.
The earlier AWS-tunnel browser navigation was approximately 2.9 seconds; that
single observation is a baseline, not a representative latency percentile.
