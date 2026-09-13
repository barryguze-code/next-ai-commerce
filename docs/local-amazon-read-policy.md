# Local Amazon testing

Run Eclipse's Local UAT launch with the `local` Spring profile. It uses local Postgres on port 55432. Amazon reads use the same services, recurring schedules, manual sync, and guarded startup order reconciliation as production. Startup reconciliation may skip a connection with an active run or a successful reconciliation in the previous 24 hours.

Local inventory edits and imported Amazon data are saved to the local database. They must never publish listing prices, inventory quantities, shipping purchases, refunds, or other seller changes to Amazon. `app.amazon.write-enabled` and `app.amazon.listing-actions-enabled` remain false. `LocalEnvironmentSafety` refuses startup if those protections or the local database settings are overridden.

`AmazonSpApiClient` blocks write operations before obtaining credentials or sending requests. Its read-only POST exceptions are report generation, batch price queries, and eligible shipping-service queries; report generation is needed to retrieve Amazon data. New Amazon integrations must use this client and maintain its safety tests. A new read-only endpoint requires an explicit reviewed allowlist change.

Merging application code does not require changing the local profile. Production uses its own deployment configuration. Local UAT approval and production deployment are separate steps; never merge local secrets, copied database content, or Eclipse-specific credentials into a release.

Enabled schedules do not prove a successful import. Confirm a completed sync in the application and successful Amazon responses in the local console. Copied encrypted credentials must be readable with the corresponding encryption key; do not paste keys or tokens into logs or support messages.
