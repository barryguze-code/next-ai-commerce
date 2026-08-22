# Next AI Commerce

The new multi-tenant commerce operations platform. This first slice deliberately contains only the secure platform foundation: authentication, tenants, memberships, marketplace connections, an application shell, and tenant-isolation tests. Existing workflows are migration inputs, not architectural constraints.

## Local start

Prerequisites: Java 21+ and Docker.

```bash
docker compose up -d
mvn spring-boot:run
```

Open `http://localhost:8080`. For local development only, sign in with `admin@nextaicommerce.local` / `change-me-local`.

Environment variables override the local defaults: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `APP_ADMIN_EMAIL`, and `APP_ADMIN_PASSWORD`.

## Non-negotiable tenant rule

Every tenant-owned row includes `tenant_id`. The request layer must resolve an authenticated membership, application queries must include the tenant, and PostgreSQL row-level security provides the second boundary. No marketplace credential is ever sent to the browser.
