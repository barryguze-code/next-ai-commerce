# Shopify inventory activation — October 30, 2026

Status: candidate 1.10.2 in review; production remains 1.10.1. No inventory activation or Shopify publication performed.

## Confirmed baseline

Read-only production command `d17afb76-8e47-41c2-9de4-39bb2a88ff20` confirmed schema124, healthy1.10.1, Shopify inventory handling/polling/write/publication runtime flags OFF. Existing Amazon publication remains LIVE and must be preserved.

Read-only command `0633e17d-9d16-4f8e-8bda-b5ac1e1b77d2` at20:42:47UTC confirmed exactly one Shopify connection across production: Ibcore (`d018e963-2c60-4b41-9331-a6232e1981b2`), The Keto Storehouse (`ee351413-5770-4860-8a26-1c2098465a94`). All17 approved bins are ACTIVE: MAIN,F1,F2,F3,F4,F5,F6,F7,F8,F9,F10,F11,F13,F14,FX,R1,R2. There are no Shopify destination/source bindings or publications yet.

Order#1022 is complete PAID merchant evidence, mapped to Beecher's item111663 with8 each per sold unit. It remains REPORTING_ONLY because inventory handling was disabled. Current physical stock16each; existing Amazon reservation8; available8. Expected after verified remaining-only cutover: physical16 unchanged, Amazon reservation8 unchanged, Shopify reservation8, available0. No shipment deduction until physical handoff or supported fulfillment evidence.

## Corrections

- Runtime inventory activation now requires both master enablement and exact tenant/connection membership in `SHOPIFY_ORDER_INVENTORY_SCOPES`, default empty. Enforce at automatic activation, cutoff, shipment, reservation release/allocation, reconciliation, bootstrap, and guarded local test entry points. New stores do not inherit inventory activation.
- The ordinary incremental scan starts at previous completion minus5minutes, which misses unchanged#1022 last updated17:54UTC. For an explicitly approved inventory AND bootstrap scope, extend the new fenced read window to the earliest currently mapped open reporting-only order revision minus5minutes. Retain the existing59-day limit; never rewrite job cursors/history or infer cancellation from absence. Unmapped#1009 does not trigger repeated catch-up scans.
- Existing bootstrap still requires current same-job archived evidence, exact mapping identity, complete merchant evidence, no prior handoff, and a completed lease-fenced scan. Frozen prior fulfillment checkpoints and append-only cutover prevent replay.
- Pre-activation orders may be packed only when every outstanding physical line has an exact verified cutover, unchanged frozen composition, valid current evidence, merchant ownership, matching remaining quantity, READY state, and actual complete eligible reservations. No blanket history exemption.
- Shopify Orders uses America/Los_Angeles with PDT/PST and date rollover.
- Available Inventory's shared overflow wrapper now receives available flex width. All/Healthy precede red Cannot sell and amber Act soon; selected status and badge retain semantic colors in both themes. No filter handlers or calculations changed.

## Reviewed activation proposal (not executed)

After independent review and exact CI-tested artifact verification, change only:

```
SHOPIFY_ORDER_INVENTORY_ENABLED=true
SHOPIFY_ORDER_INVENTORY_SCOPES=d018e963-2c60-4b41-9331-a6232e1981b2/ee351413-5770-4860-8a26-1c2098465a94
SHOPIFY_ORDER_BOOTSTRAP_SCOPES=d018e963-2c60-4b41-9331-a6232e1981b2/ee351413-5770-4860-8a26-1c2098465a94
SHOPIFY_ORDER_POLLING_ENABLED=true
```

Shopify publication will be enabled only for the exact approved tenant/store and verified destination, using all17 approved source bins. Initial `SHOPIFY_INVENTORY_PUBLICATION_VARIANTS` is `d018e963-2c60-4b41-9331-a6232e1981b2/ee351413-5770-4860-8a26-1c2098465a94/c9a30e30-4195-4c85-9f55-d4cc7beaf262` (Beecher's). This restricts plan, claim and pre-submit checks; already-attempted VERIFYING operations can still receive read-only confirmation. Empty optional variant list restores the separately approved store policy after canary acknowledgement/readback review, with the same artifact. Exact operational command remains subject to independent review; not executed.

Preserve all Amazon settings, other integrations, nginx, database and other accounts; migrations remain OFF. Preserve prior artifact/configuration. Do not automatically roll back or rewrite reservations on failure: pause publication and inspect retained checkpoints under owner direction.

The first complete scoped scan must freshly include#1022, authorize baseline control, append one exact-line cutover, and reserve8each. Repeat scan must preserve one cutover and total8Shopify reservation, with no historical ledger mutation. Verify unchanged Amazon8reservation, physical16, available0, shared publication invalidation, and Amazon FBM provider acknowledgement/readback of mapped quantity0. Never manufacture a real shipment merely to test.

## Shared stock protection

#1009 Southwest remains deliberately Unmapped. Read-only audit `4075a95f-6a27-4fc3-b85d-f8d21c146427` found retained inactive mapping component `a5cbe721-3331-4b20-b02e-d1a671b7e4c4`, quantity2, distinct from Beecher's `e057c78d-979f-4574-bbc5-f22803529364`. The shared positive-publication guard unions frozen and retained mapping components for unresolved commitments and holds overlapping Amazon FBM/Shopify offers. Unknown identity/ownership, incomplete evidence and stale order windows remain fail-closed. A truly never-managed known variant stays unmanaged; no guessed mapping or inventory takeover. Existing managed targets may safely reach zero. Current checks repeat immediately before mutation; already-inflight provider requests cannot be recalled.

Historical waiting-for-pickup flags alone no longer permit packing reprints. A real post-cutover local handoff with shipped reservation and matching ledger evidence is required; repeated handoff remains idempotent.

## Verification

- Pacific formatter regression failed on UTC before correction;24 focused page/lifecycle/allocation tests passed after correction.
- Inventory layout failed at wide width before correction. Full production stylesheet ordering exposed the later shared blue selection override; semantic variables correct it. Focused Chromium fixture covers actual inventory template and widget,1440px/320px resize, arrow visibility, both themes, and semantic selected badges. This is isolated rendered evidence, not a deployed-screen claim.
- Existing publication database suite:13 tests passed in its disposable embedded cluster before scope changes. It applied migrations only to that ephemeral test cluster, which shut down. UAT/production untouched. PM subsequently directed no further local test-cluster provisioning; updated database fixtures will be verified in CI only.
- Final non-database Shopify and Amazon publication-worker selection passed; updated cutover, component quarantine, canary and historical handoff database cases await CI. No local database was provisioned for those new cases. Exact CI evidence pending.
- No schema migration, inventory data repair, mapping change, FBA write, pricing change, or local reset included.
