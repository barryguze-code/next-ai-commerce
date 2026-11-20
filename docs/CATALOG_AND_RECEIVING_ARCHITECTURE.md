# Catalogue, purchasing, and receiving architecture

## Business boundary

Next AI Commerce separates product identity from account-owned commercial information.

- **Global catalogue** identifies a physical product. Vendor plus vendor item code is first-class identity evidence; UPC/EAN/GTIN/MPN confirms the physical product when available. The catalogue also holds canonical name, brand, pack/unit information, dimensions, weight, and whether expiration dates must be captured.
- **Account catalogue** links an account to a global product and stores the account SKU, preferred vendor, activation state, and account-specific product settings.
- **Vendor offers** belong to one account. A product may have multiple vendor offers with different item codes, list prices, discounts, minimum quantities, and currencies. Freight and duty never belong to the vendor offer.
- **Cost history** is append-only. Changing today's default cost never rewrites a historical receiving transaction.
- **Marketplace SKU mappings** connect Amazon or Walmart SKUs to one or many account catalogue items.
  Component quantities support multipacks and bundles without inventing a separate physical product.

Only a platform Super Admin can open Global Catalogue. Account roles can see only their selected account's catalogue. PostgreSQL row-level security is enforced on every account-owned catalogue, vendor, import, SKU mapping, cost-history, and inventory-ledger table.

## Catalogue import workflow

Imports are staged and never write directly into the live catalogue.

1. Choose or create the vendor.
2. Upload CSV, TSV, XLS, XLSX, or a text-based PDF using the original vendor file.
3. Map the vendor's headings to Next AI Commerce fields.
4. Validate every row and show valid, warning, duplicate, and rejected counts.
5. Let the user review new global products, identifier matches, and cost changes.
6. Require explicit approval.
7. Create missing global products, account catalogue links, vendor offers, and cost-history entries in one controlled process.

### Mandatory fields

- Product name
- Vendor item code
- List/unit cost
- Currency (may default from vendor)

UPC, EAN, GTIN, manufacturer part number, brand, account SKU, category, description,
weight/dimensions, minimum order quantity, discount rate, unit of measure, units per case,
and expiration-date requirement are optional. Missing pack data safely begins at one each per case
and is confirmed during receiving.

The vendor item code is evaluated before UPC/EAN. A vendor identity is stored globally as
`vendor_key + normalized_item_code`, while the price remains in the account-owned vendor offer.
This prevents a catalogue import from creating duplicate global products merely because a UPC is
absent, and it never exposes one account's buying terms to another account.

The database tables `catalog_imports` and `catalog_import_rows` preserve the uploaded file identity, mapping, original row, normalized row, validation results, approver, and final product links. This makes imports resumable and auditable.

## Buying cost and receiving cost

The catalogue buying-unit-cost model is:

`list price × (1 − discount rate / 100)`

Freight is always a currency amount, never a percentage. A vendor may hold a **default freight amount** solely as a convenience to prefill a new receiving session. The user can replace or clear it, and the actual freight amount is preserved on the receiving session. Duty/import and other shared charges belong only to receiving. The final landed unit cost is calculated only after all documents in a receiving session are ready.

A receiving session can contain several invoices and packing lists at the same time. One session-level freight, duty/import, and other-cost amount can be allocated across all received lines by value, quantity, weight, or manual allocation. A document-level override remains available for an exceptional invoice that has its own charge.

The first upload can accept up to 12 documents from one vendor, and later vendor batches can be added to the same receiving workspace. Each upload batch is atomic: all selected files are validated and saved together, or none of that batch is saved. Vendor selection is required before intake. Missing, invalid, inactive, or cross-tenant vendors produce a clear correction message rather than a database or framework error. Files are limited to 20 MB each and 80 MB per batch; oversized, empty, unsupported, duplicate, and unreadable documents preserve the existing receiving work and return user-facing guidance.

## Invoice-first receiving workflow

Receiving is allowed only after a source line is matched to an account catalogue item. The workflow will:

1. Start by selecting a vendor and uploading an invoice or packing list. The platform creates the receiving session automatically.
2. Require a vendor for each uploaded invoice or packing list and review all documents and lines together without switching screens.
3. Parse the files into draft purchase orders and PO items; an upload never changes inventory directly.
4. Match each line first by vendor item code, then UPC/EAN/GTIN, and then account catalogue identity.
5. Create a missing global product, account catalogue item, and vendor offer in the same controlled transaction. The invoice unit cost becomes the current vendor price.
6. Capture cases, units per case, and loose eaches. KEHE `ShipQuantity` is interpreted as eaches while `PackSize` provides the case conversion. When no pack can be detected, the receiving line starts at one unit per case; an older catalogue pack never silently overrides the source document.
7. Capture an expiration date when required. Each Receive action is one expiration batch: the receiver adjusts the cases/eaches for the first date, saves it, then the same partial row resets to the remaining quantity and advances to the next batch number. This repeats for every physical expiration date without duplicating the PO item. Lot numbers are intentionally out of scope.
8. Continue receiving until each line is complete, or classify quantities as short shipped, damaged, mispicked, soon expired, expired, or another explained discrepancy. Those quantities create vendor-credit evidence and never enter sellable inventory.
9. Create a vendor credit request for non-sellable receipts and explained shortages, preserving a receiving-session discrepancy report.
10. Enter freight, duty/import, and other shared costs once for the session. These amounts are receiving costs, not vendor percentages.
11. Enable **Post inventory** only when every line is physically received or its remainder is explained.
12. Split an over-shipped receipt into the expected quantity at its normal landed cost and only the extra quantity at zero cost. The zero-cost overage remains visibly identified while still entering usable inventory.
13. Post sellable and zero-cost overage quantities as immutable inventory-ledger entries with an idempotency key, so retries cannot duplicate inventory.
14. Preserve the invoice cost and allocated landed unit cost for inventory valuation.

The warehouse workspace is a searchable, single-row-per-product table. It uses pending, partial, complete, discrepancy, and over-shipped color states. Expected, received, remaining, cases, eaches, and pack sizes are displayed as whole units. Product identity includes title, brand, vendor code, UPC/EAN, and the Amazon MAIN image when an Amazon catalogue match exists. Missing Amazon images are enriched gradually through the Catalog Items API at a deliberately throttled rate; receiving never waits for an image.

### Supported document behavior

- CSV, TSV, XLS, and XLSX files are read as structured tables.
- Text-based PDFs are parsed when their table text can be extracted reliably. Image-only/scanned PDFs are rejected with a clear message rather than silently importing incorrect rows; OCR is a later controlled enhancement.
- Modern KEHE CSV headings are supported directly: `ShipItem`, `UPC`, `Description`, `Brand`, `ShipQuantity`, `PackSize`, `NetEach`, `InvoiceNumber`, and `InvoiceDate`.
- The legacy four-page KEHE invoice PDF layout is also recognized. Its shipped quantity and net-each cost are derived from the printed extended-cost data. Because that PDF does not expose case pack, it begins at one unit per case and the receiver confirms the physical case size.
- Invoice number and invoice date are preserved on the receiving document. Reusing the same vendor invoice number or identical file is blocked before inventory can be duplicated.
- A failed first-document upload removes its empty draft receiving session, so the activity list is not polluted with unusable sessions.

### Catalogue and price audit

- A newly discovered UPC/EAN/GTIN creates the global identity and the selected account's catalogue item together. Brand, product description, vendor item code, pack information, and invoice cost are populated when the source provides them.
- Vendor prices are independent. Adding the same product from another vendor creates another active offer without overwriting the preferred vendor's price.
- Every invoice-derived cost change appends `INVOICE` or `PACKING_LIST` cost history with its source document number; manual prices and catalogue imports retain their own source classifications.
- Account users can open **View vendor prices** from the account catalogue to compare every current vendor offer for the product.

### Inventory flow

Inventory has two intentionally separate operational views:

- **Available Inventory** groups posted ledger quantities by product and expiration date. This is the everyday view of stock that can be allocated or sold. Dated inventory is presented in earliest-expiration order (**FEFO**); products without expiration dates follow received-time order (**FIFO**).
- **Inventory Ledger** is the immutable movement history behind those balances. Each movement identifies the product, signed quantity, expiration date, unit cost, source, business reference such as a PO or marketplace order, event time, notes, and the person or system that recorded it. Future order fulfillment, returns, transfers, and manual adjustments append new movements; they never rewrite a posted receipt.

Each account has a shelf-life policy with two simple thresholds. The default **minimum sellable life** is 10 days: expired inventory and inventory with 10 days or less is marked Cannot sell. The default **act-soon window** begins at 30 days. These states are calculated from the current date every time the page opens, so alerts advance without a destructive background update. Account administrators and operators may change the thresholds; viewers may filter and review them.

An expiration position can hold one current action plan: Discount, Donate, Hold, Remove, or Other. Planning an action is deliberately separate from executing it. A plan helps the team coordinate without silently changing a marketplace price or inventory balance. Later confirmed price, donation, or removal workflows will append their own immutable ledger movements.

Short-shipped, damaged, mispicked, expired, and soon-expired quantities remain in the discrepancy history and never increase sellable inventory. Extra over-shipped units enter inventory with an explicit zero unit cost so later profitability remains accurate. A saved sellable physical receipt creates one idempotent ledger movement immediately, making the expiration batch available while other invoice lines are still being counted. Its invoice cost is provisional until **Post inventory** allocates shared landed costs and finalizes valuation.

A received or discrepancy line remains correctable. Reopening a line appends a compensating ledger
movement for its prior available quantity, clears only that line's receiving work, and returns it to
the count workflow. The original movement remains auditable; it is never rewritten or deleted.

Shared freight, duty/import, and other receiving costs are allocated by merchandise value by default. The calculated allocation is saved in `receiving_cost_allocations`, and every sellable ledger receipt preserves its final landed unit cost. Different discrepancy reasons on one PO line remain separate vendor-credit records rather than overwriting each other.

FEFO applies when an expiration date exists. FIFO applies when it does not. Corrections are new reversing/adjusting ledger entries; posted history is never overwritten.

## Schema reference

- `global_catalog_products`, `global_product_identifiers`, `global_product_vendor_codes`
- `account_catalog_items`
- `vendors`, `vendor_catalog_offers`, `vendor_cost_history`
- `marketplace_sku_mappings`
- `marketplace_sku_mapping_components`
- `catalog_imports`, `catalog_import_rows`
- `inventory_ledger_entries`
- `receiving_sessions`, `receiving_documents`, `receiving_document_lines`, `receiving_cost_allocations`
- `purchase_orders`, `purchase_order_items`
- `receiving_line_receipts`, `vendor_credit_requests`

This foundation supports the guided import and receiving screens without a later database redesign.
