# Order SKU pictures

The Orders image tile uses a hover/focus pencil and an always-visible product-actions
button. Both open the same keyboard-accessible menu; upload and Amazon sync are
shown only to editors. Mapping and inventory reuse the existing order drawers.
Conversation counts retain existing order-level meaning (not unread-message counts).

V73 stores overrides by tenant, marketplace connection and seller SKU, with enforced
RLS. They do not replace account/global catalogue images or component images in a
bundle. Uploads accept validated JPG/PNG, up to 5 MB and 25 megapixels. Amazon sync
reads the order item's exact ASIN and marketplace and stores the official MAIN image
URL. No Amazon listing write is performed. Routine listing sync cannot overwrite
these local overrides. Re-upload or explicit Amazon sync replaces the override.

POST operations check the selected account's operator membership and use existing
CSRF protection. Read and write item lookup also require the selected store.
Order queries join picture metadata once; image bytes are fetched separately.

Restart the standard Eclipse Local UAT application to apply V73 locally. Do not
start a replacement background service or deploy this change without a new request.
