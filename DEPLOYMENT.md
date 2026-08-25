# Production deployment

Next AI Commerce 0.1.0 runs as a Spring Boot service on Ubuntu and uses the PostgreSQL database installed on the same AWS instance.

## Server requirements

- Ubuntu LTS
- Java 21
- PostgreSQL 17
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
APP_PUBLIC_URL=https://<production hostname>
```

The encryption key must remain unchanged across deployments or previously stored marketplace credentials cannot be decrypted.

## Service

The production service runs the release JAR with Java, loads its secrets from `/etc/next-ai-commerce/app.env`, restarts automatically after a failure, and starts after PostgreSQL. Secrets are never included in the JAR or Git repository.

## Release process

1. Run the complete automated test suite.
2. Package the versioned JAR.
3. Back up the currently deployed JAR.
4. Upload the new JAR to a temporary server path.
5. Move the artifact into `/opt/next-ai-commerce/` and restart the service.
6. Confirm the service is healthy and `/login` responds successfully.
7. Preserve the previous JAR for immediate rollback.

## Rollback

Stop the service, restore the prior JAR from `/opt/next-ai-commerce/releases/`, and start the service again. Database migrations are forward-only; review migration compatibility before rolling application code backward.
