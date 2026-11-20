# Production deployment

Next AI Commerce 1.0.1 runs as a Spring Boot service on Ubuntu. This document is
the release procedure, not deployment authorization. Local development and UAT
use an isolated database; see [local development](docs/local-development.md).

## Server requirements

- Ubuntu LTS
- Java 21
- PostgreSQL 18 (match the inspected production major version)
- A dedicated, non-login `nextaicommerce` service user
- `/opt/next-ai-commerce/next-ai-commerce.jar`
- `/etc/next-ai-commerce/app.env`, readable only by root and the service group

## Required environment values

```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://127.0.0.1:5432/next_ai_commerce
DB_USER=next_commerce
DB_PASSWORD=<database password>
APP_ADMIN_EMAIL=<super admin email>
APP_ADMIN_PASSWORD=<strong super admin password>
APP_CREDENTIAL_ENCRYPTION_KEY=<base64 encoded 32-byte key>
MAIL_ENABLED=true
MAIL_HOST=<SMTP server>
MAIL_PORT=587
MAIL_USERNAME=<SMTP username>
MAIL_PASSWORD=<SMTP password or app password>
MAIL_FROM=<verified sender address>
MAIL_FROM_NAME=Next AI Commerce
APP_PUBLIC_URL=https://<production hostname>
```

The encryption key must remain unchanged across deployments or previously stored marketplace credentials cannot be decrypted.

## Service

The production service runs the release JAR with Java, loads its secrets from `/etc/next-ai-commerce/app.env`, restarts automatically after a failure, and starts after PostgreSQL. Secrets are never included in the JAR or Git repository.

## Release process

### Backup policy by release type — owner approved 2026-10-23

- **Patch/hotfix:** do not create a new database backup by default. Preserve the
  existing rollback JAR, startup configuration and release evidence. This does not
  authorize deleting retained backups or skipping artifact, compatibility, health
  and rollback checks.
- A hotfix that changes schema or stored data requires a separately reviewed
  recovery plan before those changes; the no-new-backup default is not approval
  for an unrecoverable migration or data repair.
- **Major/minor release:** retain the fresh recoverable backup and recovery
  verification requirements below, unless explicitly changed by the owner.
- Retain four latest daily recovery points and two rolling beginning-of-month
  recovery points. Protect active release rollback dependencies until acceptance;
  missing historical monthly points must be reported rather than fabricated.

The current deployment script still creates a backup unconditionally. Documentation
does not change executable behavior: use a reviewed hotfix-specific path or update
and verify the workflow before deploying under the no-new-backup policy.

1. Develop on a branch and review the change, schema migrations and data-governance impact. Never use the production database for automated tests.
2. Run `mvn --batch-mode verify` against the disposable PostgreSQL test cluster plus the JavaScript regression tests. Complete receiving/inventory and both-theme browser UAT.
3. Build once from the approved revision. Record the version, revision, JAR SHA-256 and test results. Keep the artifact in an approved private release store; this change does not automatically upload it.
4. Deploy that exact artifact to staging with its own database, secrets and disabled publishing. Apply Flyway there, verify restore from backup and measure cold/warm catalogue and search latency with production-like data and the real non-superuser role.
5. Obtain explicit production approval. Confirm migration compatibility, backup/recovery readiness appropriate to the release type above, and the previous JAR. V64 is additive, but index creation still needs a reviewed maintenance/lock budget.
6. Transfer the approved artifact to a temporary server path, verify its checksum, then install and restart the service. Do not rebuild between staging and production.
7. Verify login, account isolation, catalogue search, movement history, receiving locks and background processing. Monitor errors and latency. Do not use a real inventory mutation as a smoke test.
8. Preserve the previous artifact and release evidence. Production publishing settings remain a separately controlled decision.

The existing production delivery branch is `release/v1.0.0` (the branch name is
not the application version). Pushing an approved revision there starts the
production workflow. Feature-branch CI runs Java and browser regressions without
deploying. The production workflow also runs both suites before using the existing
SSM deployment entry point. `scripts/production-release.sh` first requires a healthy
existing service, saves a full server-local database backup and the previous JAR,
and validates the backup archive. It does not change Amazon publishing policies.
The current workflow publishes the verified CI-built JAR and SHA-256, downloads and
compares the published bytes, and passes the expected digest to the server script.
The server downloads and verifies that exact artifact; it does not build beside
the live application. Never upload local database copies, credentials,
customer files or unrestricted build outputs.

## Version and user-facing history

For each approved release, increment the patch version in `pom.xml`, the default
`app.build-version`, and the deployment workflow version argument. Patch versions
do not create separate user-facing release notes. When the feature version changes
(for example 1.0.x to 1.1.0), add a major.minor entry to
`src/main/resources/releases/history.json` and create `docs/releases/V<major.minor>.md`.
Write functionality and benefits, not implementation details. `/app/releases`
displays up to five feature releases, selected from a dropdown;
unknown version requests show the latest entry. Only add versions being released,
not unapproved development iterations. Local launches keep their branch marker
and their enforced read-only Amazon access. Tag the successfully deployed revision.

The catalogue display cache is per instance. Before deploying multiple instances,
disable it or add shared invalidation; see [cache boundaries](docs/performance-and-cache.md).

## Rollback

Stop the service, restore the prior JAR from `/opt/next-ai-commerce/releases/`, and start the service again. Database migrations are forward-only; review migration compatibility before rolling application code backward.

### Receiving case-count emergency static hotfix — 2026-10-19

Production v1.6.9 (`91a292beddf9`) serves `/js/receiving-work.js` through an
exact nginx location pointing to
`/opt/next-ai-commerce/hotfixes/20261019-receiving-cases/receiving-work.js`.
Only nginx was reloaded; the application JAR, startup configuration, database,
and running application were unchanged. The asset was patched from the deployed
version, preserving its newer receipt-submit safety fixes. Served SHA-256:
`4c9a31bdb875fba525ad1a9e19621239fae54ce3ab18302c636ed460dfadc5a7`.
The hotfix directory contains `nginx-before.conf` and `nginx-config-path.txt`.

**Before the next normal release:** include and verify the receiving case display
change and existing production receipt-submit fixes in the release asset, then
remove this exact-location override and validate/reload nginx. Otherwise this
override will continue masking future packaged receiving JavaScript. Do not
replace newer nginx configuration wholesale with the backup during a later
release; remove only this location after reviewing the current configuration.
For immediate rollback while configuration is unchanged, restore the saved nginx
configuration, validate with `nginx -t`, then reload nginx; no app restart is needed.

Case counts are equivalents using the saved invoice `unitsPerCase`, including
receipt history and batch balances; they do not reconstruct the original physical
carton composition. Loose units and each totals remain visible. Missing or invalid
pack sizes show each totals alone. The change does not submit inventory writes.

Receiving display follow-up, 2026-10-19: the same static override now excludes
DAMAGED, MISPICKED and EXPIRED receipt quantities from the table's Received total.
Remaining balances retain server semantics and show separate exception subtotals;
shortage uses the existing discrepancy total or receipt total, without duplication.
The unsaved row preview follows the same display rule. Receipt history, inventory
writes and stored balances are unchanged. Sorting uses the displayed sellable each
quantity. The previous static asset is retained as
`receiving-work.before-exceptions.js` in the hotfix directory; restore it atomically
for rollback of this follow-up only. Current served SHA-256:
`418da63eab0b002ce664c8dede1f471b390b7aeee2c6e81f4d5f09116975fd2b`.
No application restart or nginx reload was needed for this atomic asset update.
Include both receiving hotfixes when retiring the temporary static override.


### Pending release preparation — 2026-10-23 (not deployment approval)

See [the production release candidate plan](docs/ai-handoffs/production-release-plan.md).
The proposed server script now refuses missing, duplicated or enabled release
safety settings before downloading or changing services; it does not enable startup
reconciliation. This proposed release starts with scheduling, collection, variation
operators/coverage/enforcement, writes, publication, mail and Flyway OFF. Re-enabling
existing background operations is a separate reviewed activation decision.

V104–V112 must already be applied through a separately approved maintenance step
and verified before this Flyway-disabled application launch. Disposable-schema test
success does not establish production migration readiness. The post-launch check
covers V91–V112. Existing production version and migration checksums are unverified;
no server command has been run as part of this preparation. Do not push the release
branch or dispatch its workflow while release gates remain open.
