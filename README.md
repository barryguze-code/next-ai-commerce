# Next AI Commerce

Next AI Commerce is a multi-tenant commerce operations platform for managing business accounts, users, and marketplace stores from one secure workspace.

## Version 0.1

The first production release provides:

- Secure sign-in and account activation
- Separate business workspaces with tenant-aware data access
- Platform-wide Super Admin account management
- Administrator, Operator, and Viewer roles
- User invitations scoped to a business and its stores
- Amazon US, UK, and Canada marketplace connections
- Walmart US connections
- Encrypted marketplace credential storage and live authorization checks
- Account and store switching foundations for multi-channel operations
- A consistent Apple system-font interface and responsive application shell

Orders, inventory, reimbursements, distributor workflows, and shipping integrations will build on this foundation in later releases.

## Local start

Prerequisites: Java 21+ and Docker.

```bash
docker compose up -d
mvn spring-boot:run
```

Open `http://localhost:8080`. Configure your own local administrator credentials before signing in.

Environment variables override the local defaults: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_ADMIN_EMAIL`, and `APP_ADMIN_PASSWORD`.

## Invitation email verification

Invitations use a single-use link that expires after seven days. New users verify their email by opening the link and creating a password. Existing users sign in before accepting access to an additional business account. Role and store permissions are applied only after verification.

For Gmail SMTP testing, enable two-step verification on the sender account and create a Google App Password. Never use or store the normal Gmail password. Configure:

```text
MAIL_ENABLED=true
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=<sender Gmail address>
MAIL_PASSWORD=<Google App Password>
MAIL_FROM=<sender Gmail address>
APP_PUBLIC_URL=http://localhost:8080
```

Keep these values outside Git. Production should set `APP_PUBLIC_URL` to the HTTPS application address.

## Non-negotiable tenant rule

Every tenant-owned row includes `tenant_id`. The request layer must resolve an authenticated membership, application queries must include the tenant, and PostgreSQL row-level security provides the second boundary. No marketplace credential is ever sent to the browser.

## Marketplace credential encryption

Before starting the application, set `APP_CREDENTIAL_ENCRYPTION_KEY` to a Base64-encoded 32-byte key. Generate one once with `openssl rand -base64 32`, store it outside Git, and use the same value after every restart and deployment. Marketplace credentials are verified with Amazon or Walmart, encrypted with AES-256-GCM, and only the encrypted payload is stored in PostgreSQL.

`SUPER_ADMIN` is a platform-level assignment, not a tenant role. It cannot be granted through a tenant invitation. Super Admin support actions require a reason and are written to the audit log.

## Production deployment

The Ubuntu deployment procedure, required environment values, service configuration, verification, and rollback steps are documented in [DEPLOYMENT.md](DEPLOYMENT.md). Secrets belong only in the server environment file and must never be committed to Git.
