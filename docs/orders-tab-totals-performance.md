# Orders tab totals query verification

Database-first verification on 2026-10-11, before deployment.

## Change

`OrderTabTotalsQuery` aggregates item units and normalizes status once per order,
computes qualifying Buy Box Lost SKUs once, and joins the resulting order set.
It preserves the existing inventory availability rules and excludes orders without
items, as the original query did. No inventory cache, schema migration, new index,
or server-wide JIT setting is introduced.

## Production database measurements

Read-only, repeatable-read transactions used the application role `nextcommerce`
and tenant context, with a 20-second statement timeout and 1-second lock timeout.
Measurements use `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`.

| Query / store | Execution | Planning |
| --- | ---: | ---: |
| Original / Ibcore | 5,148.045 ms | 14.448 ms |
| Optimized final query / Ibcore | 147.275 ms | 13.404 ms |
| Optimized final query / Karaca | 123.248 ms | 13.579 ms |

The original plan spent 4,160.126 ms on JIT compilation and repeated the expensive
Buy Box predicate for both order and unit totals. The optimized measured plans did
not invoke JIT. These are observed database timings, not browser latency promises
or cold-cache guarantees.

All 14 totals matched the original query in the same database snapshot for Ibcore,
Karaca, and Keto Pantry. Production data was not modified.

## Regression coverage

`OrderTabTotalsDatabaseTest` covers status normalization, item aggregation,
tenant/store isolation, latest FBA snapshots, currency and marketplace matching,
bundle limiting components, expired inventory, reservations, shipment holds, and
legacy mappings. It compares results with the original SQL.

A disposable, analyzed fixture with 4,500 orders and 9,000 items completed the
optimized repository call in 63 ms (one-second budget); its legacy query took
181 ms with the same fixture statistics. The earlier unanalyzed fixture reproduced
a 4,619 ms legacy call. Fixture statistics matter and are not a production change.
Four database tests and three repository tests passed.

The SQL export test is opt-in via `-DexportOrderTabsSql=true` and writes only ignored
`.local` files. The active FedEx feature workspace is untouched. Production rollout
and browser lock/sort verification remain separate steps.
