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

1. Develop on a branch and review the change, schema migrations and data-governance impact. Never use the production database for automated tests.
2. Run `mvn --batch-mode verify` against the disposable PostgreSQL test cluster plus the JavaScript regression tests. Complete receiving/inventory and both-theme browser UAT.
3. Build once from the approved revision. Record the version, revision, JAR SHA-256 and test results. Keep the artifact in an approved private release store; this change does not automatically upload it.
4. Deploy that exact artifact to staging with its own database, secrets and disabled publishing. Apply Flyway there, verify restore from backup and measure cold/warm catalogue and search latency with production-like data and the real non-superuser role.
5. Obtain explicit production approval. Confirm migration compatibility, a current recoverable database backup and the previous JAR. V64 is additive, but index creation still needs a reviewed maintenance/lock budget.
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
The current server entry point builds the pinned tested Git revision on the server;
it does not yet consume the exact CI-built artifact described in the preferred
build-once procedure above. Never upload local database copies, credentials,
customer files or unrestricted build outputs.

## Version and user-facing history

For each approved release, increment the patch version in `pom.xml`, the default
`app.build-version`, and the deployment workflow version argument. Add the release
at the start of `src/main/resources/releases/history.json` and create the matching
`docs/releases/V<version>.md`. Write functionality and benefits, not implementation
details. `/app/releases` displays up to five entries, selected from a dropdown;
unknown version requests show the latest entry. Only add versions being released,
not unapproved development iterations. Local launches keep their branch marker
and their enforced read-only Amazon access. Tag the successfully deployed revision.

The catalogue display cache is per instance. Before deploying multiple instances,
disable it or add shared invalidation; see [cache boundaries](docs/performance-and-cache.md).

## Rollback

Stop the service, restore the prior JAR from `/opt/next-ai-commerce/releases/`, and start the service again. Database migrations are forward-only; review migration compatibility before rolling application code backward.
