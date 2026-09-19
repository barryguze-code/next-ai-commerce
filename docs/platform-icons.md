# Shared platform icons

The SVGs under `src/main/resources/static/images/platform/` are the supplied platform visual library. Reuse these assets rather than drawing replacements for the same meanings. Preserve their colors and aspect ratios; provide accessible labels on the enclosing control and empty alt text on decorative icons.

- `01-pending.svg`: pending order.
- `02-unshipped-truck.svg`: unshipped order.
- `03-waiting-for-pickup.svg`: pickup readiness (keep platform readiness distinct from marketplace status).
- `04-shipped.svg`: shipped order.
- `05-unlocked.svg`, `06-locked.svg`: inactive/active identifier filters, not security permissions.
- `07-seller-central.svg`: Seller Central link.
- `collaboration.svg`: existing collaboration control; counts retain their existing meaning, not invented unread counts.
- `edit-image.svg`: product image editing.
- `human-ai.svg`: product actions launcher; does not imply an AI service call.
- `sku-product-mapping.svg`: marketplace SKU/catalogue mapping.

Orders uses `order-design.js` and `order-design.css` for the six-column default layout. Extra stock, mapping, shipping and sales-history columns remain available through the shared Columns control. The table layout revision resets old column preferences once; subsequent preferences persist. Decorations are idempotent because live order refresh replaces row DOM. Preserve existing action handlers, tenant-scoped endpoints, and the table widget's numeric-column mapping when changing the layout.

Item sales excludes shipping; a separate `+ currency amount Shipping` line is shown only for positive customer shipping charges. Lock controls change the existing `q` filter; copying an identifier is a separate action.
