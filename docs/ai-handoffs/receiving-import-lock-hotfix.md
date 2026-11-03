# Receiving upload hotfix 1.11.2 — 2026-11-03

Status: deployed and startup/authenticated Receiving verification passed on 2026-11-03.

## Confirmed causes and scope

The reported text originates from ReceivingRepository.addDocument acquiring the tenant inventory advisory lock with an immediate try-lock. Even brief contention rejects the entire upload before validation. The isolated two-connection test reproduces the rejection. A sampled production read did not find an Ibcore holder, so the precise original competing operation is not established.

A second defect prevented this specific file from working once the lock was available: all 21 products already exist under exact Ibcore account SKUs, but no Etolia vendor offers exist for those source SKU strings. The importer did not consult that account identity and instead attempted new catalogue creation. The file contains ASIN-shaped values under UPC / EAN; these must not become invented GTINs.

## Implementation

Document imports retain the same tenant lock and may wait up to five seconds. A PostgreSQL 55P03 timeout becomes the existing retry message and rolls back the outer import batch; other database errors remain errors. The prior 750ms timeout is restored after acquisition. Other inventory operations retain their existing lock behavior.

After an existing vendor binding is checked, an explicit SKU or Account SKU may match an exact active account SKU in the selected tenant. Case and leading zeros are preserved. Ambiguous, inactive, contradictory or conflicting numeric-barcode evidence fails safely. UPC / EAN is recognized for barcode conflict checks. The matched path reuses the existing item and does not create or rewrite a global product, identifier, vendor offer, mapping or inventory record. Original source fields remain in document raw data. Zero-quantity rows retain their skip behavior.

## Evidence

Read-only production commands c033e46f-b96d-4540-9952-0c2adaf125f7, 01d9c12a-604d-49a6-95eb-acf73aa99b24 and 32ba9790-bb57-49b2-9963-ee5dcea9f6e0 establish account/vendor identity, all 21 exact catalogue matches, all catalogue/global statuses active, and case sizes of one. The 21 source quantities sum to 487. The two listings without marketplace mappings do not block warehouse catalogue identity. No API writes or receiving submission was performed.

Focused local verification: 22 reported tests, zero failures/errors, one skip. Tests use disposable PostgreSQL and include the exact private CSV with account-only fixtures mirroring production. Red-capable tests reproduced immediate lock rejection and missing account lookup. Green tests cover brief contention, bounded timeout, complete rollback, other-tenant independence, retry/duplicates, account-only headers, leading zeros/case, inactive/unknown/conflicting identities, source-field retention and zero stock posting. Existing vendor-precedence, receiving controller/parser and atomic receipt checks remain intact. Independent source and deployment-script reviews passed.

Final revision 7002a3bec129a7619e433a55aaec4e24de89c14a, normal CI 41684242110 passed:1,414 Java tests reported,zero failures/errors,93 skips;540 browser cases,539 passes,zero failures,one skip. The final guard rejects letter-containing or unsupported-length barcodes before creating an unmatched product, while existing matches preserve the raw source values. Earlier f4a9427/CI41682850346 passed but did not contain this final safety guard and was not deployed. Earlier lock-only revision 3c8988a passed CI but is not the final candidate and must not be deployed. Customer CSV is private and excluded from Git/release artifacts. No schema changes; all 129 packaged migrations remain byte-identical to the deployed baseline.

## Operational boundary and rollback

Deploy only the final CI artifact after successful normal CI and SHA verification. Pin existing 1.11.1 package and launcher digests; preserve all database, encryption, inventory publication, scheduling and marketplace settings. Only the public build label changes; the existing startup-generated legacy admin-password value is validated under the previously reviewed narrow comparison exception. Retain old immutable package and private launcher copy. On failed startup/config verification restore both atomically and verify old health, artifact, launcher, command line, public label and protected settings.

Do not upload the client's packing list during deployment verification. The client can retry after verified deployment; duplicate detection stays enabled. If a real update holds the lock beyond five seconds, the operation still fails safely and may be retried. No stored stock or historical records are migrated.

Unrelated pending collaboration icon/email changes and all other user work are excluded from this release.


## Deployment receipt and final verification

Exact CI artifact86,438,016bytes, SHA25609923d5db854d3ab5267d6b6a7de1134244e0a29317e88b9d1e00c41c62e9595. Published build-7002a3bec129a7619e433a55aaec4e24de89c14a prerelease; GitHub asset digest matches the downloaded CI package. SSMdfc3fe85-6910-490c-b4fd-a25fc6891848 completed HEALTHY on1.11.2 with pinned artifact, launcher, process and protected-setting verification. Schema129 unchanged, zero migrations applied. Prior1.11.1 immutable artifact retained with private launcher recovery material under /opt/next-ai-commerce/backups/v1.11.2-7002a3bec129-verified1.

Authenticated Barry Chrome: Ibcore Receiving renders version1.11.2, the existing15-document list and upload dialog are available, Etolia supplier and Packing list options are present. Cancelled without selecting/submitting a file. No live receiving import, stock edit or marketplace submission was performed by verification. The client should retry the existing file with Etolia and Packing list selected. All21 catalogue/global records were active at the read-only check; case sizes were all1 and source quantities total487. Production lock holder at the original failed attempt remains unidentified; the error path and brief-contention failure were reproduced independently. Busy updates lasting beyond five seconds still fail safely with full rollback.

Private evidence: .local/packing-hotfix/deployment-success.png, production-receiving-1112.png, ci-final.log, artifact.json; customer CSV remains outside the repository/release assets. All unrelated work, including the pending collaboration corrections, remains preserved and excluded from this hotfix. Local Eclipse services were not started or restarted; verification used disposable test PostgreSQL and the authorized production service restart.
