# Approved local improvements — 1.14.22

Owner authorizes production deployment of all locally approved changes, including
#16–#19, with required database updates, no new database backup, no production UI
UAT and startup smoke only. Final caption change removes visible Unit text while
retaining the accessible dimension-unit label and conversion behavior.

## Scope and preservation

Built from prepared package revision 17ca0de, itself based on deployed 1.14.20
revision 2e82213. Incorporates the locally accepted PO workflow, user-defined
package management and exact product/quantity packaging matches, canonical refund
reporting and drawers. Existing production inventory/disposal, catalogue-save,
parent-SKU and fee-sync fixes remain inherited. Original local worktree is intact.

## Verification

84 focused Java checks and 80 JavaScript checks pass in the combined isolated
release checkout. Includes migration 140→142 under a restricted schema owner,
package metadata/rates, refund lifecycle deduplication, tenant/store access,
quantity recipes, PO persistence, deletion dependency checks and dialog wrapping.
The final Unit-caption fixture passes. CD approved the caption-only change;
previous actual local light/dark and 390px package/refund review passed. No new
production browser test is planned. Review of the combined release is a separate
engineering self-review pass; no independent engineering review is claimed.

## Database operation and recovery

Apply only migrations 141–144 as one grouped Flyway transaction, validating all
existing checksums, with bounded lock/statement timeouts before switching the JAR.
141 adds account-owned dimensions; 142 flattens established package aliases and
removes redundant legacy enums only where saved packages exist, retaining amounts
and source order history; 143 adds canonical refund functions/index/view without
removing source transactions; 144 adds account-owned quantity recipes with RLS.

On migration failure, PostgreSQL rolls the grouped transaction back; do not replace
the live application. After successful migration, application rollback may retain
141–144 and restore the verified 1.14.20 JAR/launcher. The old app ignores additive
objects and already supports the existing alias/cost routing and explicit package
rows. Do not reverse or delete financial/order history. Preserve the old JAR,
launcher/configuration fingerprints and deployment receipt. No backup is created.

Production preflight observed JAR SHA256
9aa83bfca4ede92fbc0a1c507cde99d3a4340da634c38cf2afb4cba5b8af165b
and schema 140 fingerprint 3f2de538fac68933a782287d1f6ce384.
Publication, exact CI artifact and deployment evidence remain pending.
