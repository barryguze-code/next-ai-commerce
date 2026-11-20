# Code and data release checklist

A successful Java deployment does not copy local database contents to production.

Before approving a release, record:

1. Code revision, application version, successful unit/browser tests.
2. New Flyway schema migrations and migration tests against an existing schema. Never edit an applied migration.
3. Required reference-data imports, exact source checksum, tenant/store, expected valid and skipped counts, and conflict policy.
4. A verified production backup and rollback plan. Application rollback does not automatically undo schema or data changes.
5. Post-deployment checks: deployed revision, schema version, import counts, sample lookups, and marketplace synchronization health.

Business data imports must remain private; never commit customer files or database exports to the public repository or release assets. Import using explicit tenant/store identifiers, validation, bounded transactions, and a stable source marker. Preserve user edits with insert-only conflict handling. Rerunning an import must be safe. Reconcile inserted plus preserved records against the validated source count before calling a release complete.

Keep encryption keys stable across deployments and separate local/production credentials. A connection is not healthy merely because encrypted credential rows exist. Credential replacement requires successful authorization and a read-only SP-API check; sync success must be verified separately.

Local-only bootstrap classes are test conveniences, not production data migrations. Every required bootstrap dataset needs an explicit production import step and verification evidence in the release checklist.
