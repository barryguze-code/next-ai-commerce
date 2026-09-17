# Local development and UAT — 1.0.0

## Required Eclipse startup workflow

Use the repository's `Local PostgreSQL - Start.launch` and then
`Local UAT - Start Application.launch` through Eclipse External Tools. Keep
startup and runtime logs visible in Eclipse. Do not start replacement local
application/database services in hidden terminal sessions. If GUI launch is
unavailable, ask the user to start these configurations and wait for confirmation.
Check existing listeners first; stop only identified obsolete services, not
unrelated Java or PostgreSQL processes. Restart the application after Java/model
changes: refreshing templates alone can mix new views with old loaded classes.

The 2026-09-16 blank Receiving page was caused by a v1.2.1 background process
reading newer templates that called `Document.statusTone()`. Current source
already contained that method; restarting with current compiled classes is the
repair, not removing the template's status styling.

Local UAT, automated tests and production are separate databases. Flyway versions
the schema; it is not a test database or an environment-isolation mechanism.

| Environment | Database | Integrations |
| --- | --- | --- |
| Local UAT | 127.0.0.1:55432 / next_ai_commerce_local | Amazon reads allowed; Amazon writes and mail disabled |
| Automated database tests | 127.0.0.1:55432 / next_ai_commerce_test, disposable test schema | Synthetic records only |
| CI | Disposable embedded PostgreSQL 18.6 | No production credentials |
| Production | Existing managed deployment | Explicit production configuration |

The existing Eclipse launch points through port 15432 to AWS. **Do not relaunch
that configuration to test migrations.** Use the explicit local profile below.
The source was inspected read-only on 2026-09-11: PostgreSQL 18.6, with a
non-superuser application role and enforced row-level security. That role is
not a complete-backup role. Do not disable RLS or elevate it for UAT.

## PostgreSQL installation (macOS)

The workspace-local PostgreSQL 18.6 package is installed under
`.local/postgres18/pgsql`. It does not install a system service or modify an
existing database. The upstream archive is
[EDB PostgreSQL 18.6 macOS binaries](https://get.enterprisedb.com/postgresql/postgresql-18.6-1-osx-binaries.zip).
The downloaded archive SHA-256 is
`2a6739fccbbc36474cb2446e4e7b4f377abb8471653d3a294e4e5092271e4796`.
This is a recorded local checksum, not an independently signed vendor attestation.

For another Mac, download this archive, verify the checksum, and extract
`pgsql/bin`, `pgsql/lib` and `pgsql/share` to `.local/postgres18`.
Do not overlay a different major version on an existing data directory.

From the project directory, in a normal Terminal:

```sh
node scripts/local-database.cjs start
```

In Eclipse, refresh the project and use **Run > External Tools**. The included
**Local PostgreSQL - Start**, **Local PostgreSQL - Stop**, and **Local
PostgreSQL - Status** launchers run the same safe lifecycle commands without
leaving Eclipse.

Use **Local UAT - Start Application** from the same Eclipse menu to start the
application with the local profile. Its output appears in Eclipse's Console;
use the red stop button there when you are done.

The agent sandbox cannot initialize PostgreSQL shared memory on this Mac.
The normal Terminal startup is required once; afterward tests can connect over
loopback. The script creates random credentials with owner-only file access,
a non-superuser application role and two databases. It refuses nonempty
uninitialized data and incompatible major versions. Stop/status commands are
`node scripts/local-database.cjs stop` and `node scripts/local-database.cjs status`.
Stopping preserves all data.

## Copy existing application data

An authorized database administrator must supply a **trusted PostgreSQL 18
custom-format backup** of the application database using the existing approved
backup process. Copying account data is approved; weakening production RLS,
changing production roles or exporting private application artifacts is not.
Keep passwords out of chat and source control.

Store the backup locally with owner-only access, then run:

```sh
node scripts/copy-local-uat.cjs /absolute/path/to/approved-backup.dump
```

The restore grants `BYPASSRLS` only to the local application role so developers
can inspect all copied tenants without setting session variables. Production
roles and row-level-security policies are never changed. Marketplace credentials
and shipping-label artifacts remain excluded from the local copy.

The restore script has a fixed loopback destination and no source connection.
It validates the archive, refuses a nonempty destination, restores in one
transaction and omits marketplace credentials and shipping-label file data.
The application role owns the restored objects; RLS policies remain intact.
No `--clean`, source update, source role change or source migration is performed.
Only accept a trusted backup: restores can execute database functions.

The original backup is preserved. The restore selection is saved to
`.local/uat-restore.list`; a second restore requires deliberate review rather
than overwriting that file. All local data, credentials and builds are ignored
by Git. Local copies may contain customer/business data and login password hashes;
protect the computer and do not publish these files.

## Verify and launch

```sh
mvn -Pisolated-build -DlocalTestDatabase=true clean verify
node --test src/test/js/table-preferences.test.cjs src/test/js/table-csv.test.cjs
java -jar .local/verification-build/next-ai-commerce-1.0.0.jar --spring.profiles.active=local
```

The isolated Maven output avoids collisions with Eclipse's live `target`
directory. Always use a clean verification build: duplicate generated `.class`
files have appeared after repeated builds in this workspace and can invalidate
test discovery. Run the Java command from the project directory so the local
properties file is found. Stop the existing port-8080 preview first, only after
tests pass. Do not pass the old Eclipse environment or production secrets.
Flyway then migrates **only the local UAT copy**.

Local startup rejects a nonlocal database URL, Amazon write access, email, or an
email provider other than disabled. A central Amazon HTTP guard allows GET plus
the report, pricing-query and shipping-quote POST operations required to read
data, while rejecting listing patches, inventory/price changes, label purchases,
refunds and every unrecognized mutation. Local testing never publishes prices,
inventory or labels and never sends email.

Database tests use synthetic fixtures in a uniquely named disposable schema
inside the **test** database. They never use the UAT copy or deployment DB_URL.
Without `localTestDatabase=true`, CI/tests start an isolated embedded cluster.

## Release gate

The source backup, local restore, full database suite, catalogue benchmarks and
restarted-preview smoke tests must pass before declaring 1.0.0 ready. Code and
unit checks alone are not production sign-off.
