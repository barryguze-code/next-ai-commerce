# Receiving and Inventory usability hotfix 1.11.15

Status: implemented in isolated worktree; scoped publication pending owner approval. Production remains 1.11.14.

## Scope and causes
- Receipt refresh dispatched the shared filter event (resetting pagination) and focused search with scrolling. A non-filter refresh now retains the page, and successful receipt completion restores ancestor/page scroll with non-scrolling search focus. Existing save/idempotency/error handling remains intact.
- Shared row menus stop click propagation. The selected-document receiving command now binds directly to its moved menu controls. The command stays visible for single selection and preserves exact checked document IDs, existing limit, and server authorization.
- Available Inventory previously exported presentation text. Its local adapter expands visible column groups into product, brand, account SKU, vendor item code, source barcode identifier, ASIN, location code/name, exact on-hand/reserved/available quantities, ISO expiration, shelf status, cost/currency. Missing values are blank. Physical availability preserves the existing nonnegative quantity-minus-reserved definition. Barcode type is not guessed. Text formula protection remains; validated typed numeric fields remain numeric.
- Shared inventory detail dates now use bold theme-readable text with the existing policy-derived red/amber status colors. History-panel responses carry the existing shelf-life status for each position, without changing policy or stock. Inventory, Orders, and Replenishment consume this shared presentation.

## Verification
- 23 focused browser checks passed: receipt scroll and second-page retention; selected-document menu propagation; atomic CSV including leading zeros, fractional quantities, formula protection and all matching pages; existing table/pagination/export behaviors; receiving focus/delivery; shared drawer at desktop/phone widths and light/dark date colors.
- 32 Java/template checks passed: InventoryControllerSafetyTest (5), InventoryShelfLifeTest (4), TemplateRenderingTest (23), including exact decimal export attributes.
- Java 21 package completed successfully. Local candidate SHA-256: 36d857e41ba7aac8c1d244f4bdca889f3806edfd42027cf90fcb614d8e28eeac.
- Independent engineering source review passed after correcting rounded export quantities and single-selection menu visibility. CD specified existing inline bold semantic colors; no redesign.
- No migration, stock write, marketplace API call, service restart, commit, or production deployment performed for this batch. Original dirty workspace preserved by using an isolated worktree based on production commit 64b8f8c7b1dc151ae38bd6bab10d4241a7a01898.

## Release and rollback
Publish only this scoped batch after owner commit approval, run applicable focused CI/package and production startup smoke, preserve application artifact/settings rollback. No database backup or migration is needed. Roll back the application artifact to 1.11.14 if required; no data rollback.
Authenticated runtime UAT and production smoke have not yet been performed for this candidate. Owner UAT should receive a row on page 2, open selected documents from another row menu, inspect downloaded CSV, and open inventory details from each existing entry point.
