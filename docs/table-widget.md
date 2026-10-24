# Shared table controls

The platform shell loads `table-preferences.js` before `table-widget.js`, then `table-data-tools.js`, and
`table-widget.css`. Data-card tables are enrolled automatically; an explicit
`data-table-widget` gives a stable preference key. Orders and the shipping-ready
grid use adapters. Receiving retains its scanning and save handlers but uses the
shared pager.

Every enrolled table has one toolbar: existing search (including server search
and domain-specific lookups), column filters, Columns, and an icon-only CSV download. Identity
and action columns cannot be hidden. Column preferences remain browser-local.

## Preference storage and recovery

Table choices use localStorage keys prefixed with `nextai.table.preferences.`;
they are never sent with HTTP requests. If browser storage is unavailable, choices
remain in memory for the current page. The shared preference loader runs on login
and application pages, migrates valid legacy `nextai-table-*` cookies, and expires
only those host-only, root-path cookies. Existing localStorage choices take priority.
Session, authentication, CSRF, theme, and unrelated cookies are untouched.

The server allows a bounded 64KB request header (`APP_MAX_REQUEST_HEADER_SIZE`)
so browsers blocked by legacy cookies can load the cleanup script. Restart the
application to apply this configuration, then refresh old tabs. This allowance
is recovery headroom, not a substitute for keeping preferences out of cookies.

## Data scope

### Row action menus

Use the shared outlined ellipsis-and-chevron trigger for row actions. An amber
exclamation badge indicates a detected warning without replacing the menu icon.
Keep the adjacent chat control and its conversation count separate. Hidden
actions must remain hidden even when action-entry layout styles use `!important`;
selection-only actions also become disabled when the selection is empty.

To consolidate an existing actions column into the first context column, mark its
heading `data-context-actions` and wrap the row's links/buttons in
`data-context-menu` with a `data-title`. The shared widget moves those existing
controls (rather than cloning their handlers), removes the empty actions column,
and uses a neutral ellipsis. A `data-row-warning` element switches the trigger
to an exclamation and includes the warning in the same menu. Keep permission
checks and domain actions in the module; the widget owns placement and styling.
Receiving menus explicitly distinguish one-document navigation from the current
cross-page selection. Opening either view does not post stock.

- Fully loaded tables: search/filter precedes pagination; 25, 50 or 100 rows.
- Server-paged tables: existing server search, sort and paging stay authoritative.
  Column filters explicitly say “on this page”; they are not server predicates.
  Render a `.table-pagination` fallback with `data-page` (zero-based),
  `data-page-max` (at least 1), and `data-page-size`. The shared pager replaces
  its controls with a left-aligned Showing [size] rows selector and total count,
  and right-aligned First, Previous, Page, Next and Last. Endpoints accept
  `page` and `size` (25, 50 or 100); changing size retains the visible row anchor
  and saves the preference per table in the browser.
- CSV: visible data columns in their chosen order, excluding action controls.
  Client tables export all matching rows, independent of the selected page.
  Server tables traverse the authenticated list's Next links from page zero,
  retaining URL search/status/sort and applying the column filters to each page.
  Exports fail rather than silently truncate if a request fails or cycles.
- Exports are UTF-8 with BOM, quoted fields, escaped quotes and formula-prefix
  protection. They do not call Amazon, sync, or action endpoints.
  The size is retained across every page to avoid duplicate or skipped rows.
  Progress appears in the shared task tray. Client CSV jobs require keeping the
  current page open until the download starts; they are not durable server jobs.

## Record context and presentation

Existing `.collaboration-row-button` controls move into the pinned
`record-context` first column. This identifier is intentionally distinct from
the Collaboration table's textual `conversation` column.
For new record tables, put `data-entity-type`, `data-entity-id`,
`data-title`, and `data-can-collaborate` on each row. Include the collaboration
runtime fragment. IDs must be stable domain IDs, never row indexes. Summary
lookups are grouped by entity type and limited to 250 IDs per request; the
existing endpoint enforces account and viewer scope.

Chat remains clickable with no history. Active conversations show a count;
closed-only history uses a distinct color without an active badge. Use
`prepareRecordCollaboration` for all badge updates. Orders show warnings below
chat, with contextual stock adjustment inside the warning panel. Additional
record warnings can use `data-row-warning`.

Semantic tables have sticky headings, proportional widths and normal scroll
chaining. Orders retain zebra order groups with compact rows. Small screens
may retain page-specific card layouts. Weekly sales use four lightweight bars
from existing aggregates, not extra pricing/sales API calls. CSV retains their
numeric values; tooltips describe rolling seven-day windows.

## Background feedback

Client task cards now use the shared icon/header/body layout with an indeterminate
progress bar, Ready/Needs attention states and dismiss control. They follow both
themes and reduced-motion preferences. Progress percentages must come from real
measured work, not timers.

`NextAiBackgroundJobs.start(title, message)` returns `update`, `complete`,
and `fail` methods for client operations. Physical counts, catalogue imports
and order refresh continue using their durable server progress endpoints.
File-upload submissions and marketplace initialization use the same tray,
without fabricated percentage progress. Upload progress does not convert a
synchronous server endpoint into a durable background job.

Page-owned row visibility uses `hidden`/`style`; shared pagination uses the
separate `table-data-hidden` class. After replacing a server fragment call
`NextAiTableWidget.refresh()`; initialization is idempotent. Client filters may
dispatch `table:filter` to reset the shared pager.

## Shared marketplace and location controls

The shell loads `platform-controls.js` and `platform-controls.css` once.
Use `data-marketplace-shortcuts` with SKU, ASIN, channel and server-resolved
Amazon domain attributes. The shared renderer owns both icon links, accessible
names, encoding and the allowed-domain list. After adding dynamic cards, call
`NextAiMarketplaceShortcuts.enhance(container)`.

Storage location selects named `locationId` or `destinationLocationId`, and
`.receipt-location-select`, use one custom white/dark listbox. Native select
values remain the submitted source of truth and existing change handlers still
run. Table badges show two characters; full labels remain available via tooltip
and accessible name. Arrow keys, Home/End, Escape, Tab and disabled options are
supported. Dynamic fragments call `NextAiPlatformControls.enhance(container)`.
The inventory drawer's blue plus opens the existing in-flow location form.

Tables may declare `data-table-min-width` for an explicit readability boundary
(Account Catalogue 1200px; Marketplace SKUs 1240px). Below it, scroll inside the
table container rather than squeezing columns or widening the whole page.
All-item inventory history is the drawer default; batch history remains an
explicit option. Loading, retry and additional pages stay inside the drawer.

## Verification

`mvn test` covers server/template regressions. With Playwright installed:

```
node --test src/test/js/table-widget.test.cjs
```

Set `TEST_BROWSER_CHANNEL=chrome` to use an installed Chrome instead of the
Playwright Chromium download. This launches an isolated test profile, not a
signed-in user profile.

## Local standardization — 1.2.7-local.20260922.31

Available Inventory, Inventory Ledger, Account Catalogue, Vendors, and Receiving
opt into `platform-standard-tables`. The shared overview remains picture-left,
collaboration and Human+AI actions-right, with a compact row height. Existing
actions are moved, not cloned, so permissions and domain handlers remain intact.
A single versioned table runtime is loaded through the shell.

The shared right-side filter drawer supports text contains, numeric intervals,
and calendar-date intervals where applicable. Client collections filter before
pagination. Server-paged screens continue to label column filters “on this page”;
global search remains server-owned. This UI change does not add account-saved
smart filters to these additional tables.

Catalogue pictures use the existing account product image endpoints. Vendor and
receiving-session pictures use tenant-scoped record overrides (V75), with explicit
account access and operator checks, actual PNG/JPEG validation, and a 5 MB limit.
These non-product images offer upload only, not an unrelated Amazon sync. Product
sync retains the catalogue endpoint’s existing single-product mapping rules.


## Marketplace SKU typed CSV — implementation awaiting local activation

Marketplace SKU main listings have a narrowly enrolled typed snapshot exporter in
source; other tables retain the CSV contracts above. The [approved contract](ai-handoffs/marketplace-skus/sku006-csv-contract-proposal.md) and
[implementation evidence](ai-handoffs/marketplace-skus/sku006-implementation-evidence.md)
define scalar field groups, required exact SKU/marketplace identity, chosen visible
group order, component/currency expansion, internal null/N/A handling and existing query/sort
scope. Limits are10,000 server-query listing candidates before client column filters,
50MiB encoded output and60s. Owner amendment removes all companion value-state/data-quality columns. Unknown numeric inputs stay blank;
negative typed amounts stay numbers, while dangerous text receives formula-prefix
protection. Import identifier columns as Text to retain spreadsheet leading zeros.

Generation uses one bounded local read snapshot and publishes a fully buffered CSV
only on success. The shared control validates response/version/length before
initiating a download and reports download started, not saved. There are no Amazon
reads, sync calls or durable export jobs. This source feature is **not installed on
running UAT**; a separately authorized, migration-disabled Eclipse restart and
classpath reconciliation are required. Do not copy only its JavaScript.


### Marketplace organization amendment — local verification 2026-10-22

The owner-approved Marketplace specialization now exports mandatory `Parent SKU`
and `Parent ASIN` columns alongside canonical child listing identity. Only fresh,
verified local reciprocal evidence supplies context; unresolved values are blank.
Verified non-buyable parents and decorative family headings are not CSV rows.
Canonical filtering/count/pagination occurs before export; existing atomic typed
values, authorization, exact SKU strings and formula protection remain unchanged.
Marketplace screen and CSV default to New condition with explicit All conditions;
All/FBA/FBM filters apply consistently. This specialization is now installed in the
reviewed local runtime (the earlier not-installed note describes its earlier state).
See engineering-plan Section45 for evidence and rollout limitations.


### Product photograph frames

Follow the canonical [operational product-thumbnail standard](UX_DESIGN_STANDARDS.md#product-thumbnails-in-operational-tables).
The product-only `product-thumbnail-slot` adapter preserves existing slots and
handlers; it is not a global rule for `.table-overview-picture` or all images.
