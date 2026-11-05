# Frontend performance and operational product standard — 1.11.16

Owner authorized implementation, scoped publication and direct production deployment without another approval prompt. Work isolated from the existing dirty workspace, based on production 1.11.15 (5f17f829b3f4e516e05a6d475c39cdf2bbdd31a8).

## Changes
- Orders remains the Product Details reference; marketplace SKU is bold. Shared copy controls have the same size/color and remain beside identifier locks. Marketplace mapping lines each have exactly one copy action; clicking item code opens the existing mapping workflow. Marketplace ledger links reuse the existing drawer, including explicit bundle component selection.
- Inventory uses three-character location display only (full exact location identity is preserved). A final Actions column opens the existing account catalogue editor, scoped to the selected account. JSON submission uses the same account-editor permission and repository operation as the existing HTML form; validation leaves the dialog open. No product creation/mapping inference.
- Receiving Product reuses the same title/code/copy treatment; mapped titles open the shared ledger, and the existing row menu includes Edit catalogue item. Its checklist refresh preserves current row/page/scroll.
- Stock-editor location/date fields align on desktop and stack at the existing narrow breakpoint. Confirmed saves close the dialog. Inventory values are refreshed from the server, without client-side quantity arithmetic. Existing transaction/reservation validations remain intact.
- Successful-save refreshes are serialized so overlapping writes each get a fresh subsequent read. A failed read leaves a durable Saved/Refresh notice; retry repeats only the GET. Column identity, saved order/visibility, active shelf filter, counts, account context, page and scroll are preserved. A mismatched fetched account is never grafted into the current view. Existing POST navigation fallback restores the same view's position.
- Shared grid rendering indexes headers/columns once and avoids setting unchanged cell identity attributes. Deterministic 500-row/five-refresh regression: old implementation writes 20,000 column attributes, new implementation writes zero (about 10ms in the local fixture; not a production SLA measurement).
- All template script/style URLs use the existing buildVersion model and immutable versioned-static-asset cache. Each deployed release gets new URLs. Protected /image and /picture byte responses support private, mandatory revalidation, only after controller access/image resolution. Validator includes account, store, release and image content; changed picture/release/account invalidates it. Redirects and other downloads are outside this adapter. This saves repeat image transfer; it does not claim to remove image database reads or replace CDN cache policy.

## Verification and boundaries
- Focused Java checks: ProtectedImageCacheAdviceTest, VersionedAssetCacheFilterTest, TemplateRenderingTest, InventoryControllerSafetyTest, CatalogGlobalChoiceMvcTest; 42 passing checks locally.
- Focused browser checks include grid writes, page/scroll preservation, account mismatch, reordered columns, serialized save refresh, malformed response recovery, nested mapping copies, shared stock editor/CSRF, existing table/filter/export, receipt workflow, thumbnails, zero stock and bundle selection.
- CD inspected light/dark/390px component fixtures and four server-template/actual-asset fixtures. Final Marketplace copy and header corrections passed. These are isolated fixture checks, not authenticated live business mutations.
- Independent engineering source review passed after save recovery, account comparison and column identity corrections.
- No schema, inventory calculations, reservation policy, marketplace submissions or synchronization configuration change. No database backup requested or performed. Existing performance/receiving fixes retained.
- Local services were not started or restarted outside Eclipse. No claim of local runtime parity; exact CI artifact will be retained for local use.

## Deployment
Use FRONTEND_STANDARDIZATION focused hotfix CI, verify exact artifact/version/digest, retain prior application settings/artifact rollback and run startup smoke. No migration. Deployment result is recorded alongside the release artifact evidence. Owner performs production UAT.
