# 1.0.0 release-candidate verification — 2026-09-11

## Completed

- Clean isolated Maven package succeeded with database integration tests excluded.
- Java tests: 142 discovered, 141 passed, one parser-fixture test skipped; zero failures/errors.
- CSV/preferences JavaScript tests: 10 passed.
- JavaScript syntax checks and Git whitespace checks passed.
- Browser checks: light/dark location picker, in-drawer location creation form
  (opened/closed without saving), standard marketplace shortcuts, all-item
  history default and retry, catalogue/marketplace contained horizontal scrolling.
- Synthetic browser fixture: paging, search, CSV download and completion cards
  checked in both themes. Fixture server stopped and test tab closed.
- Read-only SQL checks against PostgreSQL 18.6 with the actual RLS-constrained app
  role: first-page rows 32 ms; search-page rows 104–153 ms; search count 99 ms;
  sampled item history 1.7 ms. See the performance document for measurement limits.

Local packaged artifact:
`.local/verification-build/next-ai-commerce-1.0.0.jar`

SHA-256:
`e6c0d268a7669d1134ba2ed3c4ce83c2caefffeb2787b382d2a008be70e02534`

This artifact has **not** been promoted or deployed.

## Pending release gates

1. Start workspace-local PostgreSQL 18.6 from a normal Terminal. The agent
   sandbox denies the shared-memory operation required by PostgreSQL.
2. Obtain an administrator-authorized PostgreSQL 18 custom-format backup.
   The existing app role cannot make a full backup through enforced RLS; no
   security settings were weakened and no partial copy is presented as complete.
3. Restore into the empty local UAT database; verify role ownership and row counts.
4. Run the complete database suite on the separate test database, including
   receiving governance, expired counts, rollback/commit behavior and 20,000-item
   benchmark fixtures.
5. Restart the preview with the explicit local profile and finish authenticated
   end-to-end checks and latency measurements. The existing preview still runs
   the old Java backend; its history-loading error cannot be signed off until
   this restart and retest.
6. Complete staging/UAT and obtain explicit production deployment approval.

The standalone headless-browser suite could not launch Chrome inside the
sandbox. Browser checks above used the supported connected browser instead;
they do not claim that the entire headless suite passed.

Production data/schema, roles and integrations were not changed. Only bounded
read-only SQL diagnostics ran against the existing source. Automatic artifact
upload was not enabled following safety review; builds remain local.
