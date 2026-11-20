# Receiving KEHE hotfix 1.11.9

Status: implemented and locally verified; scoped publication approval pending. Production remains 1.11.8.

## Root cause and correction

KEHE CSV exports numeric barcodes as Excel text literals (`="093966113105"`). Receiving validation rejected the wrapper before catalogue normalization. Unwrap only an exact numeric text literal of a supported GTIN length at the receiving identity-use boundary. Preserve leading zeros, raw document rows, source archive, vendor binding authority, tenant scope and catalogue check-digit validation. ASINs and arbitrary formulas remain rejected for unmatched products. No inventory posting, historical mutation, schema change or marketplace operation is introduced.

The attached invoice was inspected read-only: 82 rows, 76 shipped; all shipped barcodes pass check-digit validation after unwrapping. The client invoice is not included in source control and has not been imported into production by engineering.

## Presentation

CD contract applied: shared Vendor/Document type choice lists with correct accessible labels, styled native file chooser, aligned document metadata, concise header, removed What happens next/Safe draft panels. Vendor buying currency is retained as hidden form data with a currency symbol beside costs; no conversion or marketplace currency substitution. CD reviewed the rendered fixture and passed it. Actual served production verification follows deployment.

## Verification

22 executed Java tests passed across ReceivingCatalogIdentityTest, ReceivingInvoiceParserTest, ReceivingControllerTest, ReceivingWorkflowControllerTest and the focused ReceivingWorkflowDatabaseTest import case. One optional external-PDF parser test skipped. The new database test uses an owned disposable embedded PostgreSQL cluster and verifies original source preservation, canonical UPC creation and zero inventory ledger entries. Four negative cases reject ASIN/alphanumeric wrappers and formulas. Shared-control browser fixture passed at 390, 1024 and 1440 pixels, checking selection, vendor freight/currency defaults and file attributes. Build/package and diff checks passed.

## Release boundary

Based on exact deployed 1.11.8 revision 0975a753096a755d0d33797837d8e2fbc1807de0 in an isolated branch/worktree. Existing original-tree work untouched. New explicit RECEIVING_DOCUMENT_IMPORT focused CI mode runs receiving Java/integration and dialog checks before package creation; existing modes and normal CI retained. No database backup or migration required. Deploy only exact verified CI artifact with existing startup smoke and prior application artifact rollback. Local runtime parity update remains pending the released artifact.
