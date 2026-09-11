# Next AI Commerce 1.0.0

## Local release candidate — verification in progress

- Shared Seller Central/Amazon icon shortcuts now cover inventory details and Account Catalogue SKU references.
- Inventory history defaults to all item movements, supports paged loading and retry, and removes the full-ledger detour.
- Destination creation is available through a blue plus beside the location field.
- One keyboard-accessible location picker provides compact two-letter table badges, full-name tooltips, white selection menus and dark-theme styling.
- Catalogue and Marketplace SKU tables preserve readable minimum widths with contained horizontal scrolling.
- Download progress uses the standard task card with clear running, complete and failed states.
- Catalogue SQL selects the page before enrichment, aligns counts with search results and fixes item-specific ASIN matching.
- Added bounded tenant-scoped catalogue display caching with after-commit invalidation; inventory, receiving and authorization remain database-backed.
- Local PostgreSQL 18.6 UAT/test isolation, disabled external integrations, restore safeguards, database regression fixtures and CI JavaScript checks are prepared.
- Full database tests, authorized source backup/restore, performance measurements and local preview restart remain release gates. No production migration or deployment has occurred.

## Previous release — 0.9.8

## UAT iteration — badge spacing and future AI teammate

- Shared row controls separate conversation-count badges from action warnings.
- The collaboration popover always starts with a branded, locked Next AI Commerce AI row. Expanding it explains that the feature is coming soon; it does not start a subscription, activate AI, send data, or place a call.
- Existing same-account teammate presence and huddle permissions are unchanged.

## Previous release — 0.9.7

## UAT iteration — discoverable row actions

- Shared row actions now use a bordered ellipsis/dropdown control in both themes. A separate amber badge indicates detected warnings without hiding the action-menu affordance.
- Receiving hides and disables the selected-document menu action when no documents are checked. Fixed a shared action style that incorrectly overrode the hidden state.
- Selection counts, single-document review, and inventory safety validations are unchanged. No Amazon publication.

## Previous release — 0.9.6

## UAT iteration — consistent action menus and clear shelf-life rules

- Orders and receiving use compact icon-and-description action rows with a record heading, warning summary, close control, and dark-theme treatment.
- Receiving preserves selection-aware actions and explicitly separates single-document navigation from receiving checked documents together.
- Shelf-life settings say “Save rules,” identify their account-wide scope, explain automatic daily application to current and future dated stock, and distinguish cutoff batches from other eligible stock mapped to the same SKU.
- The summary now respects the automatic exclusion checkbox. Physical inventory remains visible, and Amazon publishing remains paused.
- A per-product cutoff override is not implemented in this iteration; existing discount overrides are distinct from account-wide shelf-life cutoff rules.

## Previous release — 0.9.5

## UAT iteration — receiving selection and shared row actions

- Receiving highlights selected documents and provides a high-contrast primary receiving action in both themes. Clearing selection is secondary and disabled when nothing is selected.
- Document actions move into the shared first-column menu. Menus explicitly distinguish opening one document from receiving the checked documents together; opening one does not discard the selection.
- Closed and fully received documents cannot be checked for bulk receiving. The server rejects mixed multi-document workspaces containing completed documents, including stale selections. Existing receipt locks, remaining-quantity checks, and explicit overage confirmation remain authoritative.
- Shared column sizing gives source documents room to wrap, while selection stays compact.
- Includes the preceding UAT fixes for public login scripts, safe post-login destinations, browser-local table preferences, inventory actions, and scroll containment.
- Continue incrementing 0.9.x for each delivered UAT iteration; reserve 1.0.0 for user approval. No Amazon inventory publication or cloud deployment is enabled by this release.

## Previous release — 0.9.4

## Shelf-life operations, ready for final review

- Orders no longer expose empty row shells while Amazon is still downloading item details; totals and rows advance only after readable order items are committed.
- Order rows now use the same compact Seller Central and Amazon product shortcuts as Marketplace SKUs, with consistent icons, sizing, tooltips, and theme behavior.
- Marketplace SKU mapping now turns an empty catalogue search into an in-flow creation path: a compact right panel can add a catalogue-only item, attach an existing vendor, or atomically create a new vendor and product, then selects the result in the original mapping without losing context.
- Marketplace SKUs and Orders now show the current Amazon Buy Box price in a shared read-only column. Pricing resolves by ASIN even when the seller offer is inactive or waiting for local sellable inventory; a dedicated background refresh keeps values warm in Amazon-supported batches with no row-by-row page calls, repricing, or Amazon mutation.
- Shared data tables now use one continuous page scroll instead of a competing nested vertical scrollbar, preventing clipped final rows and empty overscroll space while preserving horizontal table scrolling.
- Shelf-life rules are now a compact account policy: warning and marketplace stop-selling windows plus a default 10% warning-stage sale plan live in one consistent dialog.
- Sale plans load related Marketplace SKUs only when opened, support account defaults, product defaults, and per-SKU discount exceptions, and remain local until marketplace publishing is explicitly enabled.
- Hold, donation, disposal, return, and physical-removal planning now share a deliberate confirmation flow. Every expiration batch—including expired stock—remains visible until a real ledger movement removes its units.
- Seller-fulfilled Marketplace SKU availability now excludes cutoff, held, and removal-planned batches in one set-based calculation while preserving the physical on-hand view. Amazon publishing remains disabled for version 1 testing.
- Inventory adjustments use clear Increase / Decrease choices, validate against unreserved stock, save inline without losing the user’s current table view, and update the row immediately.
- Current plans now clear through an explanatory confirmation instead of an unexplained menu post. Local plan flags also appear beside mapped Marketplace SKUs.
- Amazon listing quantity and price mutations are disabled by default behind `AMAZON_LISTING_ACTIONS_ENABLED`; version 0.9.4 cannot publish them unless the setting is deliberately enabled.

## Huddles that follow the user

- Incoming huddle invitations now use an always-visible floating platform notice instead of depending on native modal stacking. After joining, messages received while working elsewhere produce a compact blue live-huddle indicator that opens the exact room.
- Leaving, finishing, closing, pressing Escape, or clicking outside a temporary huddle now ends or leaves it immediately without asking anyone to save. Persistence occurs only through the explicit **Save for follow-up** control.
- Typing `@` inside a live huddle now suggests only the people participating in that room, with the same keyboard-friendly, fully readable people picker used by persistent collaboration.
- Inventory toolbars now wrap from the card’s usable space, and long product identities remain clipped inside their table column instead of overlapping neighboring operational data.
- Every operational table now uses one consistent collaboration icon: quiet when no history exists, filled blue when conversations exist, and automatically opening the newest-first dated picker when a record has multiple conversations. The duplicate collaboration ellipsis has been removed.
- Inventory conversations now use stable ISO-dated record keys. Existing short-date keys are migrated in place so their row icons activate correctly without losing discussion history.

# Next AI Commerce 0.9.3

## Huddle delivery and split-screen clarity

- Quick Huddle delivery is now explicitly account-scoped, stays connected in background tabs, and re-sends the authoritative active-room list whenever a browser reconnects. This prevents missed invitations between separate signed-in browser profiles and removes stale client-only rooms after an application restart.
- Starting the same record huddle again can add a newly selected online teammate, while delivery logs report how many active browser sessions received each invitation without recording message content.
- The teammate picker now uses compact circular person icons with an online indicator and concise description. Available Inventory preserves readable table columns at narrow and split-screen widths, scrolling horizontally rather than allowing text to overlap.

# Next AI Commerce 0.9.2

## Reliable live collaboration

- Quick Huddle invitations now appear above an open collaboration dialog and always join the exact invited room. The UI distinguishes invited teammates from teammates who have actually joined.
- Live and persistent chat use the standard Enter-to-send behavior, with Shift+Enter reserved for a new line.
- The Collaboration Hub removes redundant active-status cells, fixes participant initials, adds standard search, improves filter readability, and uses conversation-specific finish/reopen icons.
- Conversation headers now provide a permanent link back to the originating inventory, catalogue, order, shipment, or label record. An inline Huddle control exposes online teammates directly from the message composer.

# Next AI Commerce 0.9.1

## Collaboration row and mention experience

- Available Inventory now loads conversation state for all visible positions with one set-based database query. Quiet rows show a frameless gray chat outline; active rows show a blue filled conversation with a visible count, while the history menu remains a compact circular control.
- The `@` people picker is compact, high contrast, keyboard navigable, and opens beside the typed mention. Mentioned handles are emphasized in conversation messages.
- Collaboration Hub rows open the conversation directly. The Action column now uses small icon-only controls to complete or reopen a conversation.

# Next AI Commerce 0.9.0

## Quick Huddles for live operational decisions

Online teammates can now open a temporary Quick Huddle from any contextual collaboration popup or start a
general huddle from the Collaboration page. The live transcript stays in application memory, expires after 45
idle minutes, and does not write to PostgreSQL unless a participant explicitly saves it. On exit, the emphasized
choice saves the transcript as completed historical context; users can instead save it as an active Team Chat for
follow-up or discard it completely.

Huddles are tenant-isolated, authenticated, limited to three people and 200 short messages, reconnect safely after
brief navigation or network interruption, and release background-tab connections after 30 seconds. This keeps the
feature outside normal table queries and page rendering, preserving the platform's interactive performance budget.

# Next AI Commerce 0.8.0

## Contextual collaboration across the operation

Collaboration is now a lightweight messaging layer attached to inventory positions, catalogue products,
marketplace SKUs, Amazon orders, and receiving shipments. The row control stays quiet gray when unused and
becomes blue, lined, and counted when a live conversation exists; its adjacent conversation picker exposes
multiple active and historical discussions by date without mixing them into normal record actions.

The shared conversation popup adds Team Chat, private notes, `@` teammate suggestions, file attachments,
immutable creation-time record snapshots, record links, and a simple Active/Closed lifecycle. The global
Collaboration inbox adds All active, @Mentions, My private notes, Closed history, and record-type filters on the
standard table widget. Private-note ownership is enforced in repository queries and PostgreSQL row-level
security, attachment downloads use the same visibility boundary, and attachment bytes stay out of inbox/feed
metadata queries for predictable interactive performance.

# Next AI Commerce 0.7.6

## Smart row conversations and operational history

Collaboration now reads like part of the work instead of a separate task system. A quiet gray chat icon means
there is no active conversation; an active conversation turns the icon blue, and multiple active discussions
open a dated participant chooser. Completed discussions leave the row quiet while remaining one click away in
a record-filtered history. The Collaboration menu now uses the platform's sticky, configurable table with
context, participants, start time, latest activity, status, and direct open/complete actions. Completing a
conversation durably queues an email for every active account participant involved, excluding the person who
completed it.

# Next AI Commerce 0.7.5

## Collaboration is a conversation, not an action

Every Available Inventory row now carries a quiet gray chat control, whether or not a conversation already
exists. The same compact popup starts the first message or displays the complete open history and accepts a
reply. Inventory actions remain separate. Typing `@` suggests active members of the selected business account;
mentions are stored tenant-safely and delivered by email outside the interactive request, with durable retry
status so a mail outage never loses the conversation itself. Closing removes the row count while preserving
the conversation in Collaboration history.

# Next AI Commerce 0.7.4

## Conversations visible where work happens

Available Inventory rows now show a small counted conversation mark only while an attached review remains open.
It opens the full discussion in place, supports a reply, Waiting state, or Close review action, and removes the
row signal immediately after closure while retaining the complete history in Collaboration.

# Next AI Commerce 0.7.3

## Lightweight collaboration foundation

Collaboration is now a tenant-safe operational layer, not a separate task manager. The new Collaboration
menu gives the account a fast, indexed overview of open discussions, and the Available Inventory actions menu
can start a review attached to the exact item and expiration position. A review carries its creation time,
author, optional follow-up reminder, action context, and conversation history schema; no inventory action is
performed merely by opening a review.

# Next AI Commerce 0.7.2

## Workspace brand visibility

Company logos now load for every signed-in member who can access that account, while remaining unavailable to
members of other accounts. Previously the logo image URL was accidentally treated as a Super Admin-only page.

# Next AI Commerce 0.7.1

## Visual mapping components

The shared mapping popup now matches the wider reference design and shows each selected catalogue product’s
picture in its component row. Products without an uploaded image receive a clear letter placeholder.

# Next AI Commerce 0.7.0

## Mapping studio as a focused popup

All mapping workflows now open in one centered, spacious modal instead of a side panel. The popup keeps the
clear component rows and bundle guidance while making long marketplace SKUs and multiple components easier to read.

# Next AI Commerce 0.6.99

## Consistent mapping studio

Marketplace SKU mappings now use the same larger, readable slide-over studio as Orders. Every mapping flow
uses the same product context, component rows, quantity controls, bundle guidance, and backdrop-close behavior.

# Next AI Commerce 0.6.98

## One mapping experience from Orders

The Orders mapping control now opens a polished right-side drawer using the same catalogue-mapping language as
Marketplace SKUs. It loads the saved component mapping for review, makes bundle quantities easy to understand,
and keeps the order table in view behind the drawer.

# Next AI Commerce 0.6.97

## Bundle SKU availability follows every component

Seller-fulfilled mapped SKUs now calculate availability from local sellable inventory. For bundles, the
lowest component availability divided by its required quantity sets the SKU quantity. A bundle with any
unavailable component is shown as out of stock rather than retaining a stale Amazon listing quantity.

# Next AI Commerce 0.6.96

## Search by brand and ASIN

Available Inventory and movement history now find products by brand and Amazon ASIN as well as product name,
SKU, item code, UPC, and other identifiers. Catalogue lookups use the same ASIN-aware search.

# Next AI Commerce 0.6.95

## Cleaner order actions

The mapping column now contains one direct mapping control with its included catalogue item code and quantity.
The duplicate Mapping action is removed, and Buy Shipping uses a printer icon with a clear tooltip.

## Reserved demand remains visible when stock is zero

Available Inventory now retains a position for open-order reservations even when no on-hand stock remains.
It shows the reserved quantity, `0 each` available, and a clear Out of stock / Receive inventory prompt.

# Next AI Commerce 0.6.92

## Physical-count snapshots remain authoritative

When Amazon reports an already-shipped order after a physical count was uploaded, the platform now
recognizes that the count already includes the shipment. It removes the late duplicate shipment
posting instead of reducing the freshly counted inventory a second time.

# Next AI Commerce 0.6.91

## Verified physical-count imports

Physical-count uploads now recognize AppSheet's `Def Loc` column as a batch location. Most importantly, before an
import can be marked complete, every resolved item, location, and expiry-batch count is reread from the inventory
ledger and compared with the uploaded count. Any mismatch fails the transaction and leaves inventory unchanged;
no individual upload row can be silently omitted.

# Next AI Commerce 0.6.90

## Clear out-of-stock search results

Available Inventory now distinguishes a catalogue item with no positive inventory from an item that was not found.
Searching an item such as `467936` shows an explicit **Out of stock** result, identifies the catalogue item, and
offers a direct, preselected **Receive inventory** action. Searches with a positive position or a shelf-life filter
still retain the normal table result and filter guidance.

# Next AI Commerce 0.6.89

## Buy Shipping UAT repair

Build `0.6.89-buy-shipping-uat-repair` fixes the shipping drawer state that could leave its loading indicator
visible over an error. Order setup and Amazon rate checks now show immediate progress, stop after a clear timeout,
offer a safe retry, and log human-readable start/completion/failure messages. Cheapest and fastest rates remain the
first view while **See all rates** exposes every eligible Amazon option.

Purchased carrier labels are normalized onto a 4×6 sheet and scaled into the upper label area. Up to two compact
packing rows print on the bottom of that same sheet; additional SKUs continue on another 4×6 page. The Orders list
now uses the shared scroll-frame behavior so its column header stays visible during table scrolling.

# Next AI Commerce 0.6.88

## Resilient Shipping Desk

Build `0.6.88-resilient-shipping-desk` adds a durable Buy Shipping workspace for package-ready Amazon
orders. Operators can prepare up to 50 orders, watch store-scoped rate preparation progress, review the exact
recommended service and total, explicitly confirm the Amazon postage charge, and print the purchased labels in
an expedited-first, same-product-together sequence.

Purchased print files remain encrypted and available from a permanent label library, including after a paper
outage, printer jam, closed browser, or application restart. Package name, box tag, weight, dimensions, and
Ambient/Refrigerated/Frozen storage type remain editable and reusable by SKU. The saved cold-chain policy uses
two-business-day transit by default, blocks SurePost, permits UPS Ground within a configurable $1 premium, and
records Monday weekend holds or Friday extra-ice handoffs on the packing details. Policy decisions are frozen per
batch; purchases still require an explicit review and are never triggered automatically.

# Next AI Commerce 0.6.87

## Amazon Buy Shipping

Build `0.6.87-amazon-buy-shipping` adds a store-scoped Amazon Merchant Fulfillment workflow for
seller-fulfilled Pending and Unshipped orders. The Orders table opens a responsive shipping drawer that
remembers the default ship-from address and SKU package, supports split packages, shows Amazon delivery
deadlines, compares live rates with Cheapest and Fastest promoted, and preserves customer shipping revenue
separately from purchased postage.

The purchasing path is intentionally protected by a per-store `RATES_ONLY` default, an administrator-only
UAT switch, a second explicit charge confirmation, a background queue, and a `PURCHASE_UNKNOWN` recovery
state that prevents blind retries after timeouts. Purchased labels and composed 4×6 print files are encrypted;
the carrier page is composed into a controlled 4×6 print file and optional internal packing details use no more than two SKUs per page.
Label refunds stay separate from customer-order cancellation and inventory status remains authoritative from
the normal Amazon synchronization.

# Next AI Commerce 0.6.86

## Compact reserved-inventory drawer

Build `0.6.86-compact-reservation-drawer` condenses each reserved order into two deliberate lines. Amazon
order identity, status, order quantity, marketplace SKU, and reserved eaches stay together on the first line;
warehouse location and inventory expiration form the quiet second line. Responsive wrapping preserves the same
reading order on narrow screens.

# Next AI Commerce 0.6.85

## Buy Shipping architecture and order actions

Build `0.6.85-buy-shipping-blueprint` documents the store-scoped Amazon Merchant Fulfillment workflow,
data model, failure recovery, split-shipment costing, and production blockers before label purchasing is
enabled. The Orders table now presents SKU Mapping as a deliberate catalogue action and gives Buy Shipping
its own label icon and accessible tooltip without pretending the unfinished purchase flow is active.

# Next AI Commerce 0.6.84

## Compact reserved-order details

Build `0.6.84-compact-reservation-orders` uses the same round Amazon artwork as the workspace profile
inside Reserved Inventory. Each card keeps the Amazon order ID and status together, places the marketplace
SKU and order-quantity conversion on one compact line, and retains the prominent reserved-eaches total,
storage location, and inventory expiration date.

# Next AI Commerce 0.6.83

## Visible reservations for newly imported orders

Build `0.6.83-visible-order-reservations` fixes the inventory read path that accidentally reduced Amazon's
mixed-case `Pending` and `Unshipped` values to a single letter before filtering. Active reservations now
appear in Available Inventory, the reserved-order drawer, and the adjustment and location-move safeguards.
The automatic mapper also considers SKUs discovered directly on imported order items, so a new merchant-
fulfilled order can be mapped and reserved during the same completed refresh even when its listings snapshot
has not supplied that SKU yet.

# Next AI Commerce 0.6.82

## Reliable order reconciliation and readable sync activity

Build `0.6.82-reliable-order-reconciliation` makes the final Amazon sync stage actually rebuild local
inventory reservations after both API updates and report imports. Pending and Unshipped merchant orders
are restored even when an older internal state incorrectly said Shipped, while Amazon-fulfilled orders
remain outside local warehouse reservations. Interactive order refreshes now have a dedicated worker and
take priority over larger report imports; repeat restarts do not request another 30-day report when a full
check completed within 24 hours.

The Orders page now uses a lightweight version check instead of reloading its complete table every 30
seconds. Console output uses human-readable job names, short run identifiers, clear start/wait/download/
import/reconciliation/finish messages, and friendly slow-page warnings without framework thread noise.

# Next AI Commerce 0.6.81

## Physical-count upload traceability

Build `0.6.81-physical-count-traceability` adds a paged Count Uploads workspace showing the retained
filename, status, uploader, vendor, row totals, and every original parsed source row with search. New
physical-count ledger movements link back to their exact upload, making catalogue-code, quantity,
expiration, and location issues directly auditable without uploading the inventory again.

# Next AI Commerce 0.6.80

## Implicit one-each bundle mapping

Build `0.6.80-implicit-bundle-mapping` recognizes multi-product seller SKUs whose component item codes
omit individual multipliers. Each resolvable code defaults to one each, while the final `NxEA` remains
the safety check for the total bundle quantity. Existing explicit component quantities and ordinary
single-product pack mappings retain their established behavior.

# Next AI Commerce 0.6.79

## Map before reserving

Build `0.6.79-map-before-reserve` refreshes automatic marketplace-SKU mappings immediately before
the reservation pass in every Amazon order update. Newly catalogued products and newly discovered
SKUs can therefore reserve their component inventory in the same completed sync rather than waiting
for the separate listing-mapping schedule.

# Next AI Commerce 0.6.78

## Synchronized order reservations

Build `0.6.78-synchronized-order-reservations` completes inventory reservation reconciliation inside
the Amazon order-sync job, after the latest order items are imported and before the sync is reported
complete. Available Inventory therefore reflects an open order as soon as its successful sync finishes;
the periodic reconciliation remains as a recovery safeguard.

# Next AI Commerce 0.6.77

## Open-order reservation status

Build `0.6.77-open-order-reservations` treats Amazon `Unshipped` orders as open inventory commitments,
alongside `Pending` orders. Available inventory, reserved-order details, inventory movement safeguards,
and reservation allocation now use the same rule; shipped, pickup, transit, delivered, cancelled, and
returned orders remain excluded.

# Next AI Commerce 0.6.76

## Scroll-safe workspace text size

Build `0.6.76-scroll-safe-text-size` replaces whole-page zooming with true typography scaling. Compact,
Standard, and Larger still update the complete workspace immediately, while page dimensions, sticky
navigation, images, controls, and scroll boundaries retain their correct geometry at every size.

# Next AI Commerce 0.6.75

## Adjustable workspace text size

Build `0.6.75-workspace-text-size` adds an Apple-style Compact, Standard, and Larger control beside
the signed-in user. The setting reflows the complete authenticated workspace—including navigation,
tables, forms, drawers, dialogs, and background-job cards—takes effect immediately, supports light
and dark appearance, closes on outside click or Escape, and is remembered in this browser.

# Next AI Commerce 0.6.74

## Reservation lifecycle and reserved-order clarity

Build `0.6.74-reservation-lifecycle` limits warehouse reservations to pending Amazon orders. It repairs
legacy shipped orders that still have active reservations by posting their shipment depletion before
releasing the reservation. The reserved-order drawer now uses the standard Amazon artwork, readable
type, explicit order quantities, clearer SKU information, and a human-readable inventory expiration label.

# Next AI Commerce 0.6.73

## Shipped status reconciliation

Build `0.6.73-shipped-status-reconciliation` treats Amazon's combined `Shipped - Out for Delivery`
status as inventory that has left the warehouse. Reconciliation releases the active reservation and
records shipment depletion, while partially shipped orders remain open and reserved.

# Next AI Commerce 0.6.72

## Multi-product SKU auto-mapping

Build `0.6.72-bundle-auto-mapping` recognizes marketplace SKUs containing repeated quantity/item-code
components, validates their combined quantity against the trailing pack total, and writes a canonical
multi-product bundle mapping only when every component resolves uniquely in the account catalogue.

# Next AI Commerce 0.6.71

## Inventory cell polish

Build `0.6.71-inventory-cell-polish` gives reserved-order controls enough column space to render as a
complete compact card and limits detail navigation to the product name and image, leaving item codes,
UPCs, totals, and all other table content freely selectable and copyable.

# Next AI Commerce 0.6.70

## Explainable inventory reservations

Build `0.6.70-reservation-detail` makes nonzero reserved quantities actionable and opens a read-only
order allocation drawer with selected-batch and all-product scopes, marketplace order links, SKU pack
calculations, storage and expiration details, and explicit shortage warnings.

# Next AI Commerce 0.6.69

## Copyable inventory table

Build `0.6.69-copyable-inventory-table` removes row-wide navigation so table text can be selected and
copied, makes the product image and description the clear detail links, replaces FEFO/FIFO jargon with
plain warehouse instructions, and removes redundant availability helper text.

# Next AI Commerce 0.6.68

## Intuitive popup dismissal

Build `0.6.68-popup-dismissal` closes every standard table-column popup on outside click or Escape,
keeps only one open at a time, and presents product-wide inventory totals as a subtle green indicator
directly in the available-inventory table.

# Next AI Commerce 0.6.67

## Complete item movement history

Build `0.6.67-item-movement-history` adds selected-batch and all-item movement views to the inventory
drawer, identifies expiration batches in the complete view, and shows total product availability across
every location and expiration position without changing the transfer scope of the selected batch.

# Next AI Commerce 0.6.66

## Receiving routing design

Build `0.6.66-receiving-routing-design` presents expiration and the preselected product location as
one clear stock-routing control, keeps the condition chooser away from those inputs, and identifies
inventory ledger entries from this workflow as vendor invoice receipts with plain-language details.

# Next AI Commerce 0.6.65

## Inventory position design

Build `0.6.65-inventory-position-design` compacts the stock-transfer card, gives its default-location
choice a properly sized control, removes redundant movement headings, and replaces fulfillment detail
with direct marketplace-SKU maintenance and Amazon product shortcuts.

# Next AI Commerce 0.6.64

## Receiving controls and inventory disposition

Build `0.6.64-receiving-control` brings the standard column selector and 20-row paging to receiving,
prefills each product's actual default storage location, replaces the native condition list with an
explanatory platform control, and posts soon-to-expire/expired stock into dated inventory. Damaged,
mispicked, and short-shipped quantities remain outside inventory; over-shipped units retain zero cost.

# Next AI Commerce 0.6.63

## Inventory table clarity

Build `0.6.63-inventory-table-clarity` shows movement topics consistently in the inventory-position
history, adds each product's total on-hand and available stock across expiration/location positions,
simplifies the Action column, and keeps the Columns panel above the table instead of clipping it.

# Next AI Commerce 0.6.62

## Inventory clarity and partial location moves

Build `0.6.62-inventory-clarity` makes vendor item codes searchable in Available Inventory, allows
users to move a chosen quantity of unreserved stock between locations, and replaces physical-count
row metadata with clear descriptions in both the position history and the paged Inventory Ledger.

# Next AI Commerce 0.6.59

## Marketplace-driven shipment depletion

Build `0.6.59-marketplace-driven-shipments` removes manual shipment confirmation from the Orders
page and its server endpoint. Synchronized shipped, pickup, transit, delivery, completion, and return
statuses now provide the only warehouse-departure signal. Amazon shipment ledger rows display and
link the Amazon order ID; internal UUID references are no longer shown to users.

# Next AI Commerce 0.6.58

## Paged inventory ledger

Build `0.6.58-inventory-ledger-pagination` moves Inventory Ledger search, summary totals, and paging
to the database. The page now uses the standard configurable table widget and loads at most 100
movement rows at once, with first, previous, direct-page, next, and last navigation.

# Next AI Commerce 0.6.33

## Related marketplace SKUs

Build `0.6.33-related-marketplace-skus` adds a live related-SKU panel to each Inventory Position.
It shows all mapped Amazon or Walmart seller SKUs, component quantity, marketplace quantity,
fulfillment channel, listing status, and ASIN. Catalogue rows retain their three-SKU summary and
expandable full list.

# Next AI Commerce 0.6.32

## Catalogue mappings, images, and count retry

Build `0.6.32-catalogue-skus-images-count-retry` lists up to three mapped marketplace SKUs per
catalogue item with an expandable remainder, enlarges and makes account product pictures directly
uploadable from Catalogue and Inventory, standardizes the search icon, and adds corrected-file upload
to physical-count review errors. Physical counts now recognize `qty_in_hand` and `expire_date`, common
US date text, ISO dates, and native Excel serial dates.

# Next AI Commerce 0.6.31

## Appearance welcome

Build `0.6.31-theme-welcome` introduces light and dark appearance choices once, immediately after a
user first enters the signed-in application. The visual chooser is never shown on the sign-in page.
The selection is saved for one year in a browser cookie and local storage, and later sidebar changes
replace that preference so the last chosen appearance returns immediately.

# Next AI Commerce 0.6.30

## Catalogue images and dark-mode audit

Build `0.6.30-catalogue-images-dark-audit` connects saved Amazon MAIN images to account catalogue
and inventory products. Only one-component mappings qualify, so single products and multipacks can
supply an image while bundles cannot. Catalogue imports now reject using the same source column for
vendor item code and Account SKU. Remaining receiving and vendor-price dark styles now use the root
theme marker consistently.

# Next AI Commerce 0.6.29

## Inventory dark appearance

Build `0.6.29-inventory-dark-mode` replaces light warning rows and the pale view switch with
low-glare navy and charcoal surfaces. Shelf-life risks retain restrained red or amber edge cues,
while table text, filters, location labels, headers, and hover states now share the platform's dark
palette.

# Next AI Commerce 0.6.28

## Inventory position locations

Build `0.6.28-inventory-position-locations` turns the location label into an action. A user can move
the selected expiration batch to another active storage location and optionally make that location
the product default for future receipts. Moves are recorded as paired ledger entries, reserved
batches are protected, and the inventory-position drawer now keeps compact quantity cards, location
controls, and movement history visible without the previous empty layout.

# Next AI Commerce 0.6.27

Released: 2026-09-04

Version 0.6.27 corrects the Available Inventory table after customizable column ordering. Column
widths now follow column identities, the default nine-column view fits the card without horizontal
scrolling, stale browser column layouts are safely reset, and the initial view always starts at the
left edge.

---

# Next AI Commerce 0.6.26

Released: 2026-09-04

Version 0.6.26 standardizes operational tables and high-impact confirmations. Table headers remain
visible inside long data sets, desktop rows no longer repeat their column headings, and the Columns
panel keeps required fields visible while allowing the complete option list to scroll. Physical
counts now use a staged column-review and sample-preview step before their atomic inventory update.
Browser-native confirmation alerts are replaced by the platform dialog in shipment and receiving
workflows.

---

# Next AI Commerce 0.6.25

Released: 2026-09-04

Version 0.6.25 makes warehouse location a first-class inventory dimension. Every account product
has a default location, individual expiration batches may use other locations, receiving and
zero-cost receipts support put-away overrides, physical-count files accept location codes, and
Amazon reservations and shipment deductions remain attached to the physical location selected by
the cost layer. Physical counts continue to apply atomically and never publish marketplace stock.

---

# Next AI Commerce 0.6.5

Released: 2026-08-31

Version 0.6.5 finalizes the Orders UAT presentation: single-product orders use one compact row,
multi-product orders retain a clear order group, and imported item sales and shipping amounts are
shown alongside the Seller Central shortcut.

Detailed notes are preserved in [docs/releases/V0.6.5.md](docs/releases/V0.6.5.md).

---

# Next AI Commerce 0.6.4

Released: 2026-08-31

Version 0.6.4 rebuilds Orders as a fast, paged operational queue with batch item loading, product
imagery, raw Amazon order status, Seller Central shortcuts, current mapped availability, and honest
profit/margin/markup placeholders.

Detailed notes are preserved in [docs/releases/V0.6.4.md](docs/releases/V0.6.4.md).

---

# Next AI Commerce 0.6.3

Released: 2026-08-30

Version 0.6.3 corrects the Orders marketplace-time lookup to use the foundation schema's
`marketplace_identifier` column and adds a regression contract test.

Detailed notes are preserved in [docs/releases/V0.6.3.md](docs/releases/V0.6.3.md).

---

# Next AI Commerce 0.6.2

Released: 2026-08-30

Version 0.6.2 repairs the v0.6 fulfillment migration for existing tenant-protected marketplace
connections. Legacy rows are backfilled before the activation boundary becomes mandatory, and row
level tenant isolation is restored immediately within the same transactional migration.

Detailed notes are preserved in [docs/releases/V0.6.2.md](docs/releases/V0.6.2.md).

---

# Next AI Commerce 0.6.1

Released: 2026-08-30

Version 0.6.1 turns the live order queue into a marketplace-day order stream with quiet automatic
refresh, direct SKU-mapping actions, explicit future Buy Shipping controls, and transparent pending
profit states. The stream is a reusable Thymeleaf fragment for the future AI Overview.

Detailed notes are preserved in [docs/releases/V0.6.1.md](docs/releases/V0.6.1.md).
The v0.6.0 fulfillment foundation remains unchanged.

---

# Next AI Commerce 0.6.0

Released: 2026-08-30

Version 0.6 introduces the live FBM order queue, store activation boundary, marketplace mapping
readiness, FEFO/FIFO inventory reservations, and transactional shipment ledger posting.

Detailed notes are preserved in [docs/releases/V0.6.0.md](docs/releases/V0.6.0.md), with the complete
workflow reference in [docs/ORDER_FULFILLMENT_ARCHITECTURE.md](docs/ORDER_FULFILLMENT_ARCHITECTURE.md).
The v0.5.11 receiving release remains unchanged.

---

# Next AI Commerce 0.5.11

Released: 2026-08-29

Version 0.5.11 makes sellable physical receipts immediately available, finalizes landed cost when
the receiving is posted, and adds auditable invoice-line corrections before or after posting.

Detailed notes are preserved in [docs/releases/V0.5.11.md](docs/releases/V0.5.11.md).
The v0.5.10 release remains unchanged in [docs/releases/V0.5.10.md](docs/releases/V0.5.10.md).

---

# Next AI Commerce 0.5.10

Released: 2026-08-29

Version 0.5.10 adds a tenant-safe marketplace mapping studio for individual products and bundles,
refines the Amazon assortment presentation, and records the progressive profit truth architecture.

Detailed notes are preserved in [docs/releases/V0.5.10.md](docs/releases/V0.5.10.md).
The v0.5.9 release remains unchanged in [docs/releases/V0.5.9.md](docs/releases/V0.5.9.md).

---

# Next AI Commerce 0.5.9

Released: 2026-08-29

Version 0.5.9 makes vendor item code the primary catalogue identity, adds safe seller-SKU pack
parsing and automatic account mapping, introduces four-week sales and refined listing statuses,
and standardizes sortable table widgets.

Detailed notes are preserved in [docs/releases/V0.5.9.md](docs/releases/V0.5.9.md).
The v0.5.8 release remains unchanged in [docs/releases/V0.5.8.md](docs/releases/V0.5.8.md).

---

# Next AI Commerce 0.3.0

Released: 2026-08-26

Version 0.3 adds the Amazon US synchronization foundation, including resumable initial preparation,
normalized marketplace persistence, essential-data readiness, and database-backed recurring jobs.

Detailed notes are preserved in [docs/releases/V0.3.0.md](docs/releases/V0.3.0.md), and the full
schedule/operations reference is [docs/MARKETPLACE_SYNC_OPERATIONS.md](docs/MARKETPLACE_SYNC_OPERATIONS.md).

The v0.2 release remains unchanged below and is also preserved independently in
[docs/releases/V0.2.0.md](docs/releases/V0.2.0.md).

---

# Next AI Commerce 0.2.0

Released: 2026-08-25

Version 0.2 establishes the account/store operating model and the reusable UX foundation for commerce modules.

## Included

- Account-wide **All stores** context for aggregated Overview experiences
- Required store context for operational modules such as Orders and Inventory
- Searchable account and marketplace-store switcher with marketplace logos, flags, and readable account initials
- Compact grouped navigation for Sales, Catalog & Purchasing, Inventory, Growth & Recovery, Integrations, and Administration
- User-selectable light and dark appearance stored in the browser
- Responsive desktop and mobile layouts with Apple system typography
- Streamlined user invitations with all connected stores selected by default
- Verified email invitation and account activation workflow
- Compact sidebar identity, appearance, and sign-out controls
- Consistent NextAI favicon and updated visual hierarchy
- Initial platform information architecture for catalog, vendors, SKU mapping, receiving, inventory ledger, pricing, feedback, reimbursements, shipping, and Amazon Vendor

## Architecture decisions recorded for the next phase

- Global product identity is separate from account catalog records, vendor offers, and marketplace listings
- UPC/GTIN values are stored as text to retain leading zeros
- Vendor prices, discounts, case packs, minimum quantities, and effective dates belong to vendor offers
- Marketplace synchronization will use notification-first ingestion, incremental safety polling, and periodic reconciliation
- Operational jobs will be idempotent, tenant/store scoped, rate-limit aware, observable, and replayable
- Inventory changes will be recorded as immutable ledger movements rather than direct total replacements
- Replenishment forecasting will retain explicit out-of-stock date ranges and calculate demand velocity only from in-stock selling periods, so stockouts do not suppress forecasts
- Local workstation printer profiles will separate packing-label and 4x6 shipping-label output

## Intentionally deferred

- Amazon and Walmart production synchronization jobs
- Global and account catalog database implementation
- Vendor catalog upload and mapping
- Marketplace SKU-to-product mapping
- Receiving, lot tracking, inventory ledger, and FEFO/FIFO allocation
- Shipping-label purchase and printer-agent integration
- AI-configurable Overview, alerts, and actions

## Verification and rollback

The release passed the complete automated suite: 20 tests with zero failures. The Git tag `v0.2.0` is the rollback point for this UX and access-control foundation.

---

# Next AI Commerce 0.1.0

Released: 2026-08-24

Version 0.1 establishes the secure multi-tenant foundation for Next AI Commerce.

## Included

- Business-account creation and switching for the Super Admin
- Tenant-scoped authentication, roles, memberships, and store permissions
- Invitation and one-time account activation foundations
- Amazon and Walmart marketplace connection workflows
- Amazon US, UK, and Canada marketplace selection with country flags
- Walmart US setup without Amazon-specific Seller ID requirements
- AES-256-GCM encrypted marketplace credentials
- Live Amazon and Walmart credential verification before activation
- Next AI Commerce branded, responsive user interface using Apple system fonts
- PostgreSQL migrations, row-level tenant boundaries, and automated security tests

## Intentionally deferred

- Amazon and Walmart order synchronization
- Inventory and profitability workflows
- Reimbursements
- Multi-distributor workflows
- FedEx and UPS label printing
- Dashboard analytics
- Production email delivery for invitations

## Verification

The release passed the complete automated test suite and user acceptance testing of sign-in, account creation, account switching, marketplace creation, and marketplace credential setup.
# 0.6.60 — Unified background jobs

- Catalogue batches now return users to their workspace while product import and marketplace SKU mapping continue in a compact bottom-right progress card.
- Physical counts and catalogue imports share the same responsive light/dark job-card system, including completion, failure, and direct review actions.
- Concurrent jobs stack cleanly instead of blocking the workspace or covering one another.
# 0.6.61 — User-controlled Amazon order refresh

- Orders now distinguish fresh, actively refreshing, and update-due states instead of always claiming to be live.
- Authorized users can request a focused Amazon order check once the last completed check is five minutes old.
- Manual refreshes use the shared compact background progress card and refresh order mappings and inventory reservations without blocking the workspace.
