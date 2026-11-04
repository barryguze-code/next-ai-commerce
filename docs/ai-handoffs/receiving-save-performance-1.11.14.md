# Receiving save performance — 1.11.14

Status: implemented and independently source-reviewed; local preview healthy. Not committed, published or deployed.

## Scope and causes

- Expiration focus targeted the hidden native date field after ShortDateInput created its visible text input. Focus the visible shared control, retaining native fallback. Bump the receiving script cache key.
- Production generic prepared-plan comparison reproduced the severe slowdown: the original query exceeded a 15-second statement limit; the corrected query executed in 60.816 ms (planning 19.177 ms). This uses force_generic_plan to model repeated prepared calls and omits row locking for read-only execution. No production configuration setting changed.
- Production read-only sampling found inventory-publication candidate selection holding the tenant inventory advisory lock for 1–8 seconds, with other writers waiting behind it. The same publication query hash was identified from pg_stat_activity; no client records or credentials were exported.
- Receiving document and 76-line invoice reads took approximately 3 ms and 6 ms in read-only plans, respectively. A small isolated receipt write took 8–21 ms. Those results do not establish end-to-end production save latency.
- The browser waits for a committed save and subsequent checklist refresh. Do not close optimistically or relax idempotency to mask lock contention.

## Correction and safety

Materialize request parameters, eligible Amazon connections and account-level Shopify freshness once per candidate-selection statement. The former query repeated store freshness scans per listing (298 loops in the sampled plan). Keep tenant/store joins, exact SKU and allowlists, startup and import checks, dirty-event exclusion, all positive-publication safety predicates, ordering, row locks and exact pre-send current-revision checks.

No schema, inventory math, reservation allocation, marketplace request body, retry or historical-record changes. No cross-request inventory cache. Tenant advisory locks remain in place. Network calls still occur under the existing publication transaction; this patch does not redesign that safety boundary, so latency under external-service delays remains a limitation.

Batch reservations already use product + location + expiration identity. A new real-database regression proves a reservation on one expiration batch does not prevent reducing another unreserved batch, and still rejects reducing the reserved batch below its reservation floor. Existing receipts remain intact. Do not weaken this rule or treat invoice-line identities as independent physical stock when they share the same position.

## Verification

- 148 Java/database tests passed, zero failures: ReceivingWorkflowDatabaseTest (67), ReceivingWorkflowControllerTest (5), InventoryPublicationDatabaseTest (41), InventoryPublicationWorkerTest (14), InventoryPublicationSimulationTest (2), ShopifyPublicationDatabaseTest (19, explicitly enabled with CI=true in a disposable embedded cluster).
- Two focused browser tests passed: receiving-focus and receiving-delivery. The visible-date focus test failed before the fix and passed afterward. It loads the actual shared date widget; prior delivery coverage omitted that widget.
- Source review by separate reviewer passed; cache-key correction incorporated.
- Bounded read-only production-data EXPLAIN comparisons, with row-lock clause omitted and no mutation: original 426.363 ms vs corrected 76.528 ms; freshness scans reduced from 298 to one. These were nearby snapshots, not identical frozen datasets; parameter values were equivalent.
- The isolated generic prepared-plan regression includes 300 Amazon listings and a managed Shopify store. It failed against the old query with 303 freshness scan loops (required <=2), then passed with the corrected query. All 41 Amazon publication database cases passed again. The earlier small fixture without a managed Shopify store was insufficient and was corrected.
- Local package built successfully; SHA-256 536722cf1aeaa7272bebefdc6a71e47c3f6102ab50fb13c75c32b64d03db0b6c.
- Eclipse External Tools stopped only Local UAT PID 84304 and launched the prepared package as PID 89733. Loopback 8080 /login returns 200. All runtime properties are byte-identical except the build label. Amazon/Shopify inventory writes and migrations remain off. Existing read/scheduling settings were retained.
- No production receipt was created or adjusted for verification. No actual authenticated production receipt timing or timeout recovery is claimed.

## Files

- src/main/java/com/nextaicommerce/platform/sync/InventoryPublicationRepository.java
- src/main/resources/static/js/receiving-work.js
- src/main/resources/templates/receiving-work.html
- src/test/java/com/nextaicommerce/platform/receiving/ReceivingWorkflowDatabaseTest.java
- src/test/java/com/nextaicommerce/platform/sync/InventoryPublicationDatabaseTest.java
- src/test/js/receiving-focus.test.cjs
- pom.xml
- .github/workflows/presentation-hotfix.yml
- this handoff

## Release and rollback

This is not a Tier 1 presentation-only change. The proposed focused CI classification executes the complete affected receiving and publication suites plus browser checks. Obtain explicit owner approval for the scoped commit/publication and this focused exception before dispatch; otherwise use normal release CI. Existing normal CI remains unchanged. No migration or backup is part of this patch.

Production remains 1.11.13 (891b2ad52dfaefc9b68bc58f203a3c28eb4442f4). Deploy only the exact CI-produced artifact after approval, retain its current runtime for application rollback, preserve production settings, verify startup/build/hash and migration version 129, and observe real receipt response/lock timing without test stock mutations. Revert application artifact only if needed; no database rollback is required.

Local prior JAR and settings retained in .local/receiving-save-latency/local-runtime-prior. Restore through the same Eclipse stop/start workflow if required. Original shared working-tree changes are preserved; task work is isolated on codex/receiving-save-latency-hotfix based on production 1.11.13.
