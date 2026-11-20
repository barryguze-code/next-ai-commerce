# Input-bound CI and exact artifact promotion

The owner-approved faster-delivery implementation separates Java verification,
explicit template-fixture generation, and browser verification. Java and fixtures
start together; browsers depend only on their portable fixture bundle. The required
`test` aggregate fails if planning or any verification job fails. Existing focused
hotfix and recovery entry points remain; they do not create a normal promotable
artifact.

## Successful result reuse

`scripts/ci/validation.py` fingerprints the tracked Git tree, including build files,
dependencies, application assets, Java tests/resources, workflows and CI tools.
Only `src/test/js/` is excluded from Java/fixture fingerprints. Browser results bind
the complete tree. Runner image/architecture, exact Temurin version, Maven version,
locked Playwright OCI image digest, and branded Chrome version/content identity are
bound to their respective results. Changed relevant inputs invalidate that result.
Each job fingerprints its own actual hosted runtime; GitHub may assign different
Ubuntu image builds to parallel jobs in the same run. Contracts are joined only
after verifying their source/key identities, rather than assuming one shared VM.
The browser result also binds the exact verified fixture manifest and payload
digests it consumes. A different host or fixture invalidates the affected reuse
lookup, while other independently successful job results remain eligible.

An artifact name/cache hit is never a passing check. Reuse verifies GitHub's actual
artifact producer repository/run/head revision, its upload timestamp within the
successful original job attempt, the workflow path and event, recomputed original
Git input fingerprint, archive digest, exact payload names and payload digests.
A successful Java job can survive a different failed browser job in that run.
Expired, unavailable, malformed or mismatched prior evidence causes fresh checks.
Fork/PR artifacts cannot authorize production promotion. A current PR can verify
its own fresh merge-ref results without making them eligible for promotion.

The final `verified-release-<validated revision>` contains the exact JAR from the
Java job, its checksum and `verification.json`. Provenance separately records the
validated revision and original artifact/build revision when a result is reused.
No post-verification rebuild substitutes for these bytes.

## Prepared browsers and portable fixtures

The browser job resolves the official Playwright image for the exact package-lock
version to an immutable OCI digest. It mounts the complete preinstalled GitHub
Ubuntu runner Google Chrome directory read-only and verifies the runner's Chrome
version/content identity matches planning. It launches both bundled Chromium and
the real `channel: 'chrome'` runtime before running the unchanged full browser suite.
It never aliases Chromium as Chrome or installs browser/Linux dependencies in each
job. Missing or changed runtimes fail explicitly.

Five named renderer test classes generate the required HTML. A bounded portable
bundle carries explicit workspace and `/tmp` fixtures between jobs; missing files,
unsafe paths, links and incomplete bundles fail before browser checks. The image,
Chrome installation and generated fixture HTML files are read-only runtime inputs;
their containing directories remain writable for existing screenshot paths, as does
the npm download cache. Maven/npm download caches
are dependency caches only and never count as verification evidence.

## Protected application-only promotion

`Deploy production` is manually dispatched from the protected default workflow ref,
using the existing production environment approval and existing SSM/IAM target.
Inputs identify one successful normal CI run, immutable validated revision, exact
JAR digest, and the single reviewed non-secret baseline/recovery plan. Its canonical
JSON digest must match `PRODUCTION_MANIFEST_SHA256`; this binds the existing release
approval rather than adding a second approval ceremony.

The workflow verifies the original successful jobs and package provenance, publishes
and re-downloads the exact JAR, and invokes `promote_application.py`. The plan includes
`schema: 1`, `revision`, `version`, `packageSha256`, `packageBytes`,
`baselineJarSha256`, `baselineLauncherSha256`, `policySha256`,
`schemaHistorySha256`, `rollbackJar`, `rollbackSha256`,
`rollbackSchemaHistorySha256`, and `operationSha256`. All are non-secret identities;
never put credentials or environment dumps into the plan or artifact.

Promotion requires matching live application/configuration/schema/policy, the exact
current baseline release as compatible rollback, and unchanged packaged migration
entries/bytes. It performs no DDL, restore, flag activation, collector reset, nginx
change or credential change. Schema/data/configuration releases require their
separately reviewed operation. The historical all-OFF installer is not used.

Disk/recovery/WAL reserves are checked before any candidate download or staging
directory. HTTPS streaming is bounded by the exact reviewed artifact byte count and
time; oversized/incomplete artifacts fail before service changes. A capped fresh
backup and retained baseline/configuration precede installation. The release lock,
fresh pre-stop guards, compatible application rollback, preserved integration policy,
exact artifact/version identity and basic local/public startup smoke remain.

## Verification and activation status

Focused policy tests are `python3 scripts/ci/test_policy.py`; workflow syntax is
checked with actionlint. They cover test-only reuse boundaries, producer/attempt and
payload forgery, portable fixture safety, byte/disk bounds, preserved ON/OFF policy,
unchanged-schema promotion and compatible startup-failure recovery without touching
a real service or database. Explicit renderer prerequisite tests validate the bundle.

At local implementation review, hosted Linux runtime and end-to-end Actions evidence
remain pending the first normal run on the isolated workflow branch. That run must
launch both actual browser channels and verify real artifact transport before the
workflow is described as operational. No production dispatch, environment/secret
update, default/release-branch merge or deployment is authorized by these checks.
