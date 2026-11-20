# Catalogue identity and distributor branches

Global product UUIDs remain stable foreign keys for existing account items and operational history. The authoritative business identifier is a normalized UPC/EAN/GTIN: numeric GTIN-8, UPC-A, EAN-13 and GTIN-14 representations are zero-padded to a 14-digit unique identity key. This does not expand UPC-E or infer barcodes from names. Existing known secondary identifiers remain supported; unknown identifiers never silently replace a known product identity.

Supplier codes are unique within `(supplier key, catalogue scope, normalized item code)`. A supplied DC uses `DC:<code>`; an unspecified DC uses `ACCOUNT:<tenant UUID>`. A missing barcode can only fall back to a code in that scope. Missing-DC conflicts with other scopes require review and branch selection. An explicit DC conflict is rejected, not overwritten.

Each account vendor record represents one branch. Import review offers an optional DC field, remembers the selected branch, and prevents changing branches after vendor offers exist. Add a distinct vendor entry for another DC. Migration 82 assigns only Ibcore KEHE to DC 41; other suppliers are not assigned that DC. Receipts, mappings, stock and account product IDs are unchanged.

Packaging versions are scoped by supplier/DC/code. Imports do not overwrite an existing global product's case pack. Receiving prefers the selected vendor offer's packaging version, falling back to global defaults when no branch packaging exists. Historical invoice pack sizes and costs are not rewritten.

Imports use a temporary staging table, batched writes and indexed set-based matching, not per-row global queries. Catalogue identity writers share a transaction-scoped advisory lock to prevent concurrent imports/manual creation from racing. Order syncing and inventory publishing do not take this lock. Imports are atomic; failures retain the staged upload for review. Multiple file rows resolving to one product are rejected for review because the offer model represents one product offer per vendor/date.

Rollback warning: migration 82 changes supplier-code uniqueness; do not run an older catalogue writer against the new schema. If an application rollback is needed after migration, disable catalogue writes and forward-fix, or perform a coordinated database recovery from the release backup after preserving intervening operational writes. Never blindly restore a full production database.
