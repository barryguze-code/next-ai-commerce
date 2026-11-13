# Parent presentation and initial rendering — 1.14.13

Prepared for owner-authorized production deployment; not deployed yet.

After committed Amazon listing imports, bounded existing relationship jobs read exact seller SKUs using the existing Listings Items client and source archive. Explicit marketplace-scoped parentage is sufficient to hide the parent listing and exclude it from counts. Reciprocal family verification remains separate; children are not hidden because they have a parent ASIN. No inventory or mapping mutation.

The new parent-read flag defaults OFF. Production activation is planned for `app.amazon.variation.parent-read-enabled=true`; Local UAT stays OFF. No startup enqueue. Reads are scoped by tenant/connection/marketplace/exact SKU, limited to 5,000 identities and six hours per job, with lease fencing, duplicate prevention, authorization-stop and existing Retry-After/rate handling. Classification refresh is eligible after seven days. Failed reads retain explicit evidence; listing ASIN must still match. Larger stores require subsequent imports for coverage.

Orders/Marketplace essential styles move to the document head with their existing order. Sidebar toggle/artwork is present initially and reused by JavaScript. Versioned assets remain; no inventory/permission caching added. Saved sidebar width/collapse still applies at DOM readiness, so this is not a guarantee of zero layout shift.

Verification: 39 focused mocked relationship acquisition/provider checks pass in disposable schemas; three first-paint browser checks pass; package builds. CD isolated light/dark Chromium fixture passes title/profit framing and sidebar artwork without app JavaScript. Populated-runtime account-switch performance remains owner UAT. Migration contents unchanged. No live Amazon calls or inventory depletion performed for testing.

Scoped source changes copied to the canonical local source; existing local version/history and unrelated browser-test changes preserved.
