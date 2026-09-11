# Next AI Commerce

Next AI Commerce is a multi-tenant commerce operations platform for managing business accounts, users, and marketplace stores from one secure workspace.

## Version 0.2

The current foundation provides:

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
- Account-wide Overview context and store-specific operational context
- Searchable account/store switching with Amazon and Walmart identity
- Grouped module navigation and persistent light/dark appearance
- Verified invitation email delivery and activation

Amazon synchronization, catalogs, inventory, reimbursements, distributor workflows, and shipping integrations will build on this foundation in later releases.

The database-backed Amazon onboarding and recurring-job design is documented in
[`docs/AMAZON_SYNC_ARCHITECTURE.md`](docs/AMAZON_SYNC_ARCHITECTURE.md).

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

Production invitations use Microsoft Graph with application authentication; mailbox passwords and legacy SMTP authentication are not used. Configure these values outside Git:

```text
MAIL_ENABLED=true
MAIL_PROVIDER=graph
MAIL_FROM=invitation@nextaicommerce.com
MAIL_FROM_NAME=Next AI Commerce
MICROSOFT_TENANT_ID=<Microsoft tenant ID>
MICROSOFT_CLIENT_ID=<application client ID>
MICROSOFT_CLIENT_SECRET=<application secret>
APP_PUBLIC_URL=http://localhost:8080
```

`MAIL_FROM` is the dedicated shared mailbox used as the actual Graph sender. The Entra application requires Microsoft Graph `Mail.Send` application permission with administrator consent and must be restricted to this designated mailbox. Rotate the client secret before its expiry. Production should set `APP_PUBLIC_URL` to the HTTPS application address.

## Non-negotiable tenant rule

Every tenant-owned row includes `tenant_id`. The request layer must resolve an authenticated membership, application queries must include the tenant, and PostgreSQL row-level security provides the second boundary. No marketplace credential is ever sent to the browser.

## Marketplace credential encryption

Before starting the application, set `APP_CREDENTIAL_ENCRYPTION_KEY` to a Base64-encoded 32-byte key. Generate one once with `openssl rand -base64 32`, store it outside Git, and use the same value after every restart and deployment. Marketplace credentials are verified with Amazon or Walmart, encrypted with AES-256-GCM, and only the encrypted payload is stored in PostgreSQL.

`SUPER_ADMIN` is a platform-level assignment, not a tenant role. It cannot be granted through a tenant invitation. Super Admin support actions require a reason and are written to the audit log.

## Production deployment

The Ubuntu deployment procedure, required environment values, service configuration, verification, and rollback steps are documented in [DEPLOYMENT.md](DEPLOYMENT.md). Secrets belong only in the server environment file and must never be committed to Git.

## Project documentation

- [UX and interaction standards](docs/UX_DESIGN_STANDARDS.md)
- [Catalogue and receiving architecture](docs/CATALOG_AND_RECEIVING_ARCHITECTURE.md)
- [Amazon synchronization architecture](docs/AMAZON_SYNC_ARCHITECTURE.md)
- [Recurring Amazon schedules and Walmart direction](docs/MARKETPLACE_SYNC_OPERATIONS.md)
- [Progressive order profit architecture](docs/PROFIT_ARCHITECTURE.md)
- [Live order fulfillment architecture](docs/ORDER_FULFILLMENT_ARCHITECTURE.md)
- [Version 0.6.13 release notes](docs/releases/V0.6.13.md)
- [Version 0.6.12 release notes](docs/releases/V0.6.12.md)
- [Version 0.6.11 release notes](docs/releases/V0.6.11.md)
- [Version 0.6.10 release notes](docs/releases/V0.6.10.md)
- [Version 0.6.9 release notes](docs/releases/V0.6.9.md)
- [Version 0.6.8 release notes](docs/releases/V0.6.8.md)
- [Version 0.6.7 release notes](docs/releases/V0.6.7.md)
- [Version 0.6.6 release notes](docs/releases/V0.6.6.md)
- [Version 0.6.5 release notes](docs/releases/V0.6.5.md)
- [Version 0.6.4 release notes](docs/releases/V0.6.4.md)
- [Version 0.6.3 release notes](docs/releases/V0.6.3.md)
- [Version 0.6.2 release notes](docs/releases/V0.6.2.md)
- [Version 0.6.1 release notes](docs/releases/V0.6.1.md)
- [Version 0.6.0 release notes](docs/releases/V0.6.0.md)
- [Version 0.5.11 release notes](docs/releases/V0.5.11.md)
- [Version 0.5.10 release notes](docs/releases/V0.5.10.md)
- [Version 0.5.9 release notes](docs/releases/V0.5.9.md)
- [Version 0.5.8 release notes](docs/releases/V0.5.8.md)
- [Version 0.5.7 release notes](docs/releases/V0.5.7.md)
- [Preserved Version 0.5.6 release notes](docs/releases/V0.5.6.md)
- [Preserved Version 0.5.5 release notes](docs/releases/V0.5.5.md)
- [Preserved Version 0.5.4 release notes](docs/releases/V0.5.4.md)
- [Version 0.3 release notes](docs/releases/V0.3.0.md)
- [Preserved Version 0.2 release notes](docs/releases/V0.2.0.md)
