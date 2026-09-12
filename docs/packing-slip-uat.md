# Packing-slip UAT (temporary local feature)

This local-only feature presents a 2 × 1 inch internal packing label and opens the matching Seller Central order in a separate browser tab. It never purchases shipping, changes Amazon, changes inventory, or changes pricing.

After Flyway has applied `V65`, load the supplied history once:

```bash
LOCAL_DB_PASSWORD='your-local-password' scripts/import-local-packing-history.sh /Users/barryguze/Downloads/Packaging.txt
```

The importer selects the local active Amazon store, scopes every row to that store and tenant, and ignores blank package decisions. A historical exact order-id match displays its recorded package. A non-matching order prints **Package to confirm**, so a warehouse operator is never told an unverified package is correct.

UAT acceptance: confirm one exact match, one multi-line order, an item without a reservation/expiry, and a non-matching order. Approving the feature is the separate gate for a `v1.0.1` release.
