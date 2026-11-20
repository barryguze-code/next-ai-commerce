# Exact-artifact production promotion

This scoped change registers the reviewed manual promoter without changing the
application or the existing default-branch CI workflow. Merging it enables manual
registration; it does not deploy, change production configuration, or grant approval.

## Validated source and adoption boundary

The production-aligned parallel CI implementation is on
`codex/deployment-speed-20261119`, revision
`887deef7f216ee2160aaa0ded7566c2ef90685d8`. Its normal hosted run
[44667570930](https://github.com/barryguze-code/next-ai-commerce/actions/runs/44667570930)
and required final `test` job passed: Java 2,013 checks with zero failures and 95
skips; browser 926 checks with 925 passes, zero failures and one skip; explicit
browser fixtures 36 passes. Java and browser ran separately in parallel.

Use that production-aligned implementation as the CI base for future release
candidates. The four executable files in this change are byte-identical to that
reviewed revision. This PR does not synchronize the older `main` application,
replace its CI, or claim parallel CI is globally active. Reconcile application
branches separately before broader adoption.

Successful job reuse binds all applicable source, tests, dependencies, workflows
and actual runner/runtime inputs. The browser-only readiness correction preserved
Java and fixture source keys, but hosted runner images and Maven versions changed,
so the final hosted run correctly performed fresh verification. Actual warm
hosted reuse was not observed; the matching-input reuse contracts passed focused
checks. Do not weaken runtime identity to obtain a cache hit.

## Operator sequence

1. Select a successful normal CI run and its exact source revision and verified
   JAR SHA-256. The run must have the successful required final `test` job and
   `verified-release-<revision>` artifact. Fork/PR results cannot authorize release.
2. Prepare and approve the application-only baseline/recovery JSON required by
   `scripts/ci/promote_application.py`. It binds the current JAR, launcher, policy,
   schema history, compatible retained rollback JAR, exact candidate byte count,
   and operation digest. No credentials belong in the plan.
3. Through the existing release approval, set protected production variable
   `PRODUCTION_MANIFEST_SHA256` to the SHA-256 of the canonical reviewed JSON.
   Confirm existing production environment protections and deployment permissions.
   This PR does not set variables, secrets, permissions, or environment settings.
4. Dispatch **Deploy production** from the protected default branch with
   `source_run`, `revision`, `package_sha256`, and `release_plan`. Obtain the existing
   production environment approval. Never dispatch from an arbitrary candidate ref.
5. Retain the workflow output and server receipt. The workflow verifies original
   producer jobs and exact artifact bytes, publishes those same bytes, creates a
   fresh bounded backup, checks compatible application recovery, preserves existing
   integration policy, and performs startup smoke. It does not rebuild or retest
   the application.

The promoter is intentionally application-only: packaged migration entries and
bytes must match the current application, and schema history must remain unchanged.
Schema/configuration/integration-policy changes need their existing reviewed release
path. It cannot use an older incompatible rollback, migrate/restore a database,
activate marketplace writers, or cancel an ongoing production deployment.
