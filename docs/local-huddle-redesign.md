# Local collaboration redesign

Local development only; do not deploy with the v1.3.12 production hotfix.

- Shared header huddle icon: hover or click for online collaborators across accepted accounts shared with the signed-in user, irrespective of the account selected in either browser.
- Presence includes active OWNER/ADMIN/OPERATOR/VIEWER members; active platform administrators are eligible in every active tenant. Persistent task edits retain their separate role checks.
- A member can receive and reply to an originating tenant's huddle while browsing a different tenant. The server resolves the room's tenant from its ID and rechecks participant membership and tenant permissions before actions and deliveries.
- Heartbeats every 25 seconds, 90-second stale cutoff, logout-session cleanup, and deduplication across browser sessions.
- Floating temporary chats open without an answer step. Pin keeps a window expanded while working; unpinned chats minimize on outside interaction. No transcript is stored in browser storage. Server-side messages expire after 45 idle minutes or application restart.
- Up to three participants. Add-person choices come from the room's originating tenant, not the browser's selected tenant. Access is validated again when adding a person.
- Record dialogs contain Collaborate and Private Note only. The header owns live huddles. Snapshot cards and duplicate huddle controls have been removed.
- Each saved conversation retains its source URL. New conversations store the actual originating table and a search term; legacy conversations infer a source from their record type when necessary. The source link opens that table with a matching search.
- Conversation rows have a separate message-count decoration path so the general record-summary loader cannot overwrite their count or assignment color.
- One reusable inline assignment choice list supports multiple assignees, offline members, and clearing all. Updates use the existing audited assignment service and queued email notifications without reloading the entire table.
- Presence broadcasts are sent only when the online list changes. Delivery revalidates permissions once per distinct recipient, including when multiple browser tabs are open.
- Local template caching is disabled and versioned local assets use no-store. Shared asset versions were advanced together to avoid combining old and new scripts. Production versioned-asset caching is preserved.

Verification on 2026-10-01: 38 tests passed across MemberAccessDatabaseTest (isolated test database), HuddleWebSocketHandlerTest, CollaborationMentionServiceTest, CollaborationEmailContentTest, CollaborationPrivacyContractTest, and TemplateRenderingTest. JavaScript syntax and diff whitespace checks passed.

Two signed-in local Chrome sessions verified Barry in The Keto Pantry and Ibcore in Ibcore could see each other and exchange temporary messages in both directions. An incoming message reopened Barry's minimized huddle. The inline assignment list displayed all eligible teammates (including offline members), and saving the existing assignment succeeded without changing it. The simplified record dialog displayed no snapshot or huddle controls; its source link opened Orders filtered to the exact order, showing one result. Local outbound email remains disabled; actual email delivery was not exercised.

Current implementation is process-local. A multi-instance deployment would require a shared presence/pub-sub layer before rollout.

## 2026-11-01 approved local feature — implementation contract for review

Status: owner authorized local implementation; the following persistence and permission
contract is awaiting PM review before consequential migrations. Earlier paragraphs
describe the old implementation, not acceptance criteria for this change. Production,
inventory, marketplace writes, and deployment are outside scope. Existing artwork and
compact-layout changes remain intact.

### Parent hierarchy amendment — owner decision received 2026-11-01

The owner selected adding parent tenants with multiple accounts now. This supersedes
the narrower account-only recommendation below. No hierarchy migration has been
applied. The account-scoped V125 queue source is an uninstalled foundation prototype,
not the final parent-queue model; it must not be shipped as the complete feature.

Proposed minimal boundary for PM review:

- Keep `tenants`, `tenant_memberships`, all account IDs and current account/store RLS
  unchanged. Add `parent_tenants`, `parent_tenant_memberships` (OWNER/ADMIN/MEMBER),
  and `parent_tenant_accounts` (one parent per account), with explicit audit entries.
  These are administrative groupings, not marketplace credentials or inventory scope.
- No automatic cross-account grouping or membership inferred from common users,
  names, email domains, or account access. Existing ungrouped accounts continue to
  use their existing discussions and huddles until explicitly configured. Provide
  an owner-selectable setup using the existing platform Accounts administration
  and member picker. Platform administrator creates a named parent, explicitly
  selects child accounts and parent members; confirmation lists exactly those
  changes. Parent membership grants collaboration only, never child-account access.
- A parent-level task cannot safely be stored in `collaboration_reviews.tenant_id`
  by choosing an arbitrary child account. Use additive parent-owned task storage
  (`collaboration_tasks`, participants, messages, files and audit), with nullable
  `account_id` and optional store/record references. Reuse existing thread UI,
  attachment validation and collaboration services where contracts match; keep
  global-product admin discussions and account discussions intact. Connected
  discussion-to-task conversion retains an explicit origin reference and copies
  only authorized shared content, never another user's private note.
- Queue owner is the parent tenant, covering its Open/In-progress tasks across
  accounts and parent-general tasks; one lock/revision and contiguous position
  sequence per parent. Account, marketplace and @Me views never renumber positions.
  Account attribution is explicit on cards. Parent-global tasks stay present in
  marketplace-filtered views only for their authorized audience.
- Parent task visibility requires active parent membership plus explicit task
  audience, including account-scoped task discussions. Linked account/store/record
  data requires existing account/store/domain authorization and is redacted
  otherwise; task access alone never grants it. Mentions cannot grant parent/account membership implicitly:
  the picker only offers eligible people and an invalid mention never adds access.
- Parent/account association changes are explicit admin operations. For the first
  local release, reject moving an account with existing parent-owned task/chat
  content instead of silently transferring its conversations. Removing parent
  membership denies new requests/downloads/websocket deliveries immediately; removing
  account membership redacts that account's linked context without deleting the
  explicitly shared task or its history.
  Removing store access redacts protected record context. Reassignment is not an
  automatic grant. Current clients clear affected cached responses after denial.
- Setup is optional and defaults empty, keeping legacy accounts safe. An unconfigured
  account shows a clear setup requirement for the new parent task board; existing
  discussions remain usable. The owner can select the actual parent name, accounts
  and people locally rather than the engineer guessing Ibcore/Keto Pantry membership.
- Proposed migration split: first additive parent identity/association and parent
  task queue with forced parent-scoped RLS; then durable parent huddles and atomic
  saved-task source identity. Reserve versions only after replacing the uninstalled
  account-queue prototype with the reviewed final model. No destructive account
  rewrite, inventory change, private-note transfer or production application.

PM review requested on the parent-owned task namespace and explicit empty-default
setup before implementing dependent storage. Existing queue transaction and client
continuity algorithms remain reusable with the parent as the scope key.

### Confirmed implementation gaps

- V1 defines `tenants` as business accounts with memberships; no parent tenant or
  organization-to-account hierarchy exists. Shared membership in several accounts is
  not authority to combine those accounts into one tenant.
- `CollaborationRepository.VISIBLE_THREAD` admits tenant team messages broadly.
  Task audience must become explicit across lists, badges, messages, attachments,
  mutations, websocket delivery and notifications, not merely the board query.
- Existing Shopify thread authorization couples task and linked-store access. The
  new contract separates permission to converse from permission to see the linked
  record; labels, images, snapshots and URLs also need redaction, not only links.
- `CollaborationMentionService.queue` currently adds every mentioned person as an
  assignee, including replies. Creation defaults and later mentions must be separated.
- Existing statuses are ACTIVE/CLOSED; no shared position, progress state or queue
  revision exists. The page fetches at most 250 rows before a Java store filter.
- Huddle messages and files are process-memory only. Both expire after 45 minutes;
  `saveHuddle` copies text but not file bytes. Removing the timer alone would neither
  meet retention requirements nor bound memory safely.

### Domain decision required

Recommend the smallest local scope: one existing account/tenant, private general
tasks spanning that account's stores, plus store/record tasks. Label scope "Account"
and "All marketplaces"; do not claim a parent-tenant scope. A true queue spanning
Ibcore and My Keto Pantry requires a separately designed organization hierarchy,
membership and delegation model. PM must confirm whether this narrower scope meets
the owner's intended tenant-wide use before implementation depends on it.

### Permissions and audience

Reuse reviews, messages, assignments, mentions, attachments and read receipts.
Add an explicit participant relation keyed by `(tenant_id, review_id, user_id)`.
Creator, assignees, invited people and valid mentions gain task access; active
membership is checked on every request and delivery. A platform role alone does
not silently make all private tasks visible. Existing private notes remain
author-only, including their files. Audience removal and tenant access revocation
must invalidate client data immediately on the next request.

Backfill legacy audience only from creator, assignees, actual team-message authors
and mentions; never from every account member or private-note content. Missing
creator identities are retained as unresolved legacy records for admin recovery,
not made public. Task access does not grant linked-record permission. Render a
neutral restricted-record marker instead of protected labels/snapshots/images/URLs.
Attachments inherit task or private-note audience; files deliberately shared into
a task are not a way to fetch an arbitrary linked-record attachment.

At creation, explicit assignees win; otherwise the first valid textual mention is
the assignee, falling back to the creator. Reply mentions grant audience and notify
but do not assign. Explicit multiple assignees share one task. Self-removal leaves
audience intact; last removal falls back to creator. Existing operator assignment
permission remains for assigning others; audience members may reorder, and may
remove themselves even when they cannot assign others. Assignment emails occur
only for newly assigned people when the resulting set has more than one person.
Reorder never emails. Agent verification keeps outbound mail disabled.

### Proposed additive storage and queue transaction

Next available migration is V125 in this worktree (recheck canonical before creating).
Propose `V125__collaboration_private_queue.sql`: participant table with tenant RLS
and composite review FK; review `task_state` OPEN/IN_PROGRESS/DONE, nullable positive
`queue_position`; a per-tenant queue-state row with monotonic revision; structured
audit rows for status, audience, assignment and ordering. Preserve ACTIVE/CLOSED
compatibility for existing record dialogs and badges. Tasks alone enter the queue;
record discussions remain discussions until explicitly converted to a task.

Unique `(tenant_id, queue_position)` is deferrable, allowing transactional shifts;
Done has null position. Every queue mutation first locks the tenant queue-state
row, validates expected revision and audience, then shifts the affected interval,
writes one audit event and increments revision. Create appends; Done closes gap;
reopen inserts at 1; Open/In progress share one contiguous queue. Numeric position
and accessible move controls are authoritative; drag can call the same operation.
Filtered-out tasks retain relative order; hidden titles/IDs are never returned.
Stale revision returns a conflict and refreshed authorized slice, not silent retry.
All existing close/reopen routes must use this transaction for tasks.

Propose `V126__collaboration_durable_huddles.sql`: tenant-scoped rooms, participants,
messages and files with composite FKs and forced RLS; reuse attachment validation
and bounded download response. Persist before websocket broadcast. No idle expiry;
creator alone ends the chat for everyone. X changes only that user's window;
leave does not end the room or remove historical authorship. Save as task uses a
room lock and unique source-room identity so retries produce one task; transcript,
file bytes, audience, record context, title and optional deadline transfer atomically.
Keep existing upload byte/type limits and bounded message paging; no unlimited
memory retention. Remove the attachment-loss warning only after retained files pass
download/privacy tests. These two migrations remain proposals, not created/applied.

### Board, paging, time and continuity

Reuse the CD-approved platform header, toolbar, preferences, assignment, due-date,
context menus and thread components. Three columns: Open / In progress / Done.
@Me means assigned, not mentioned. Default active cards 5 per column; preferences
allow 2/6/10 and bounded custom values. Done always starts with latest 5.
General tasks remain visible when filtering marketplaces, subject to audience.
All marketplaces is the default; explicit store filters are independent of the
shell's selected store and validated server-side against current access.

Serve bounded per-column queries with cursor pagination and explicit store/search/
audience predicates in SQL; return queue positions without renumbering subsets.
Done cursor orders `(closed_at, id)` and pins an as-of instant for pagination.
Exclude latest-five IDs from older buckets. Use the existing browser IANA timezone
with America/Los_Angeles fallback; propose Monday week start. Buckets apply in
order: This week, Last week, Earlier this month, Last month, Older, subtracting
prior ranges so month/week overlaps never duplicate cards. Freeze boundaries for
the loaded view; refreshing explicitly begins a new as-of snapshot.

Browser preferences and position state are namespaced by authenticated user and
tenant. Persist filters, expanded groups, column choices, anchor card ID and offset;
never message/file bodies. Keyed DOM updates preserve card and control focus.
Capture anchor before filter/update, restore surviving anchor or nearest visible
neighbor without jumping to page top; changed result scope does not force focus.
Out-of-order fetches are cancelled or discarded by request generation. Revalidate
saved store choices before use; switching user/tenant cannot restore private data.

### Performance and cache contract

Previously measured authenticated local navigation plus browser/AX overhead:
inbox 409/236 ms, teammate directory 122/108 ms. These are not SQL/TTFB or cold-cache
measurements; no slow query was reproduced. Capture comparable post-change timings
and query count for bounded fixtures, including many private tasks.

Use no shared task-body cache initially. Responses remain private/no-store. Reuse
in-flight deduplication only within user/tenant/query/generation. Mutation success
invalidates affected board/detail/member data after commit; access changes clear
stored response data and reauthorize. Queue revision is a conflict fence, not an
authorization cache key. Browser storage holds preferences only. No Redis/framework.

### Expected files, checks and rollout boundaries

Modify existing collaboration controller/repository/mention service, huddle service,
websocket handler and file controller; add narrowly scoped queue/durable-room
repositories only where transactions warrant separation. Update collaboration
template, shared thread/runtime fragments and existing collaboration/filter/
assignment/huddle JavaScript and CSS. Extend existing collaboration and huddle
Java/JS tests; add queue concurrency/database tests and bounded board endpoint tests.

Required evidence: nonparticipant denial on every read/write/file route; cross-tenant
and restricted-record redaction; private notes/files; mention versus assignment;
last-assignee fallback; concurrent create/move/close/reopen and stale revision;
filtered hidden-order stability; exact Done buckets across DST/month boundaries;
durable chat restart/save idempotency/file retention; state restoration after
filter and mutation; no stale response or access leak; bounded query counts.

Sequence: (1) PM reviews this single contract and resolves account/tenant meaning;
(2) private audience and queue foundation with focused tests; (3) durable chat and
atomic task conversion; (4) bounded board and continuity; (5) local recovery point,
reviewed additive migrations on the existing local database only, guarded runtime
package and minimal rendered verification. Preserve all current changes and the
same local database. No full CI, production operation or external inventory submit.
Rollback application package only if compatible with the new private-audience
rules; old broad-visibility code is not a safe rollback after new private tasks
exist. Otherwise stop local app and restore the explicit pre-change local recovery
point with owner approval. Never downgrade privacy silently.

### Independent foundation verification (2026-11-01)

Added `CollaborationCompletionWindow` and its three passing isolated Java tests:
disjoint week/month windows, DST calendar boundaries, and viewer timezone handling.
Added `collaboration-board-state.js` and one passing mocked Playwright component
test covering anchor/focus preservation, user/tenant preference separation, private
body exclusion from storage, cancellation and stale-response invalidation. These
helpers are not yet connected to the running page; no rendered feature completion
or runtime change is claimed. `git diff --check` passes. Existing local runtime,
database, global-product discussions and all inventory behavior remain unchanged.

Queue database fixture attempt: the existing full migration chain fails at V115
because `public.app_users` is hard-coded while the fixture uses a disposable named
schema. No queue test executed in that attempt; no passing migration/RLS/concurrency
claim is made. The disposable schema cleanup ran. Resolve the isolated fixture
without modifying UAT or rewriting production migration history before acceptance.

### Local implementation checkpoint (2026-11-01)

The parent-private task board is available at `/app/collaboration/board` on local
build `1.10.7-collaboration-board-local`. Parent setup is explicit. Existing
account access is evaluated independently of the parent and task audience.

The completed focused suites report 24 passing Java tests and two passing
JavaScript tests. Eight of the Java tests exercise PostgreSQL queue, audience,
concurrency and chat conversion behavior. The disposable fixture used the existing
history through V114 plus the two new SQL scripts. Separately, Flyway validated
the packaged history against the existing local V124 database. A fresh custom
archive was created and its contents listed before applying exactly V125 and V126.
The local application started and the board route required sign-in in Barry Chrome.

Still open for acceptance are the authenticated browser walkthrough with an
owner-selected parent, a populated-board rendering check and final candidate CI.
Current chat creation uses an account context. Record-discussion conversion and
direct opening of a task from a notification are outstanding.

### Follow-up candidate and runtime distinction

Barry Chrome reached authenticated parent setup. Account associations and people
are owner selections. The setup form was inspected without submitting it.

The follow-up candidate adds Create follow-up task on shared discussions,
audience-checked opening of a task notification, and cold-cache file lookup across
a participant’s authorized origin accounts. Conversion locks the source, reuses
the creator’s prior conversion, and copies only team messages and their files.
The focused database test checked repeat conversion, exclusion of private content
and denial of the task title to another parent member. An unresolved legacy sender
cannot be copied into a task requiring an identified author; the transaction rolls
back. Source records are retained.

Candidate JAR SHA-256:
`b42e6e307dc9d17d058ae58ff1d781ee3fe4969d323234d42183abfc1fdeef88`.
Local runtime still serves the earlier board preview. The clean feature verification
attempt exposed named-schema V115 failures and Fee Variance browser timeouts.
Template artwork, shared platform includes, and the migration-version expectation
were corrected and focused checks passed. The Global Catalogue browser fixture
test passed after its template fixture was generated. Full feature verification
remains red. Hosted CI has not been run on this uncommitted source.

### Installed follow-up candidate

The owner’s authenticated tab returned from setup to the empty board before the
runtime switch. The local launcher now serves `1.10.7-collaboration-board-local.2`
at `http://localhost:8080/app/collaboration/board`. The installed JAR matches
the follow-up candidate hash above. The served board script matches the worktree.
Parent association and membership require the owner’s selections.

Baseline reproduction used an archive of the untouched HEAD `c5b3e35`.
It reproduced the named-schema V115 error, the snapshot test’s DST error, and
the missing Fee Variance button. Full-run evidence is in
`/tmp/collaborate-exact-verify.log` and `/tmp/collaborate-exact-node.log`.
Focused evidence is in `/tmp/collaborate-conversion-package.log`,
`/tmp/collaborate-fixture-db-fix.log` and `/tmp/collaborate-fee-fixture-fix.log`.

The local named-schema fixtures now qualify V115’s explicit references to the
fixture’s own schema in a temporary test copy. Embedded public-schema fixtures
continue to use packaged migrations directly. The CSV concurrent-snapshot fixture
uses transaction-local UTC. The exporter itself still rejects calendar windows
crossing the Los Angeles fall-back transition (169 hours in seven calendar days).
All 15 fee interaction tests passed with a visible-variance fixture response.

The affected database suites completed with 354 passes across all 19 formerly
blocked classes. All nine inventory relocation browser fixtures passed after
switching to their current per-location controls; the prior selector also
failed on the HEAD archive. The rendered-board browser fixture passed a phone-size
notification deep link, message display and Escape dismissal.

The original browser run recorded 53 failures, including two workers stopped
after over 52 minutes. Subsequent targeted results cover the fixture corrections
above. Other failures include Marketplace responsive layouts, thumbnail and
asset assertions and operator controls. Their baseline status was not established.
The local build is available for owner setup/UAT; full verification remains red.

### November 1 local tenant hierarchy preview

Local UAT serves `1.10.7-collaborate-tenant-local.3` with V127. The complete packaged JAR has SHA-256 `9f37af229d370fff8893c6175c71c3660fb4d10a091f10d084782b6e504bf06d`. The previous V126 database dump has a readable 1,518-entry recovery index. The migration preflight validated history and found only V127 pending. The independent inspection covered the privilege delta only.

PeraCommerce owns The Keto Pantry locally. Meryem is its verified home administrator. Ibcore LLC and Karaca each have a separate tenant and keep their existing accounts and stores. Existing direct permissions remain in place. No guest or unverified home manager was added. No invitation was sent.

Focused checks passed for scoped guest acceptance after store revocation, inherited authority revocation, preservation of direct access, stale platform staff sessions, queue privacy and filtered drag using the saved queue position. Local startup returned HTTP 200 at login. The previous authenticated browser session expired during restart. The actual signed-in overall page remains to be verified. Marketplace write guards match the previous local runtime. Production was untouched.

### Personal table restoration verification — 2026-11-01

Local runtime `1.10.7-collaborate-local.15` serves the personal queue at
`/app/collaboration`. The rejected independent column layout was replaced with
the existing collaboration table styles and shared table widget. Priority is the
first column. Shared normalization now receives the row image and the marked
collaboration icon, preventing a second placeholder thumbnail or duplicate chat
icon. One table contains the Active and Closed sections, with one main search
and column control. Automatic client pagination is suppressed for this page
because expansion owns the row extent.

Populated Barry Chrome verification found ten aligned header/body cells, one
record image and one chat image per inspected row, no subject images, one main
search, and no pagination controls. Screenshot evidence is stored locally in
`.local/personal-table-restored-local15.png`.

Focused verification passed 13 queue database tests, three completion-window
tests, and the Chrome drag regression. Search queries all authorized assigned
sources and team-message bodies before applying the visible row extent. Private
legacy note bodies are excluded. V129 was already applied locally after review;
this presentation restoration introduced no migration. Production was untouched.

Remaining parity work: parent-task rows still use minimal task metadata, and
personal-row assignee/due values are currently read-only; edits are available
through the source conversation rather than inline on the personal table. These controls
need account-safe adaptation before full workflow parity can be claimed.

### Unified tabs and confirmation correction — 2026-11-02

Local runtime `1.10.7-collaborate-local.18` retains the same table and header
while Active, @Me, My private notes and Closed request their authorized datasets.
The main search and column control remain in place. Assigned-only personal ranks
are retained across tabs; other accessible records show no invented priority.
Private search examines only the current author's private-note bodies.

The shared conversation management code no longer attaches a native browser
confirmation. It uses the existing platform confirmation dialog and describes
the existing notification settings without promising universal email delivery.
Personal-row cancellation restores focus to the Actions trigger. Verification
canceled the confirmation without submitting a close or sending mail.

Personal moves now accept explicit BEFORE/AFTER placement. A theme-token insertion
line and leading circle mark the target boundary without changing row geometry.
The styled Chrome fixture exercised a real mouse drag, checked the insertion
marker, persisted order, retained the header across tab changes, and canceled
the shared confirmation without a state request. The focused database suite
passed 14 tests, including insert-after placement, assigned versus audience
visibility, cross-user ordering, missed reopen and message-body search.
The rendered-template regression also passed.

Actual Barry Chrome verification inspected populated datasets across all four
tabs, a single search and identical headers at the same main URL. Runtime .18
confirmation cancellation returned focus to the Actions trigger. Local screenshot
evidence is `.local/personal-unified-tabs18.png`; the shared dialog render was
also captured as `.local/personal-confirm17.png`. Production was untouched.

### Local barcode warning correction — 2026-11-02

The warning at 00:28:04 is preceded by the SP-API client's explicit
'Amazon credentials are not connected' diagnostic. Barcode enrichment selected
an ACTIVE connection solely from copied listing data and repeatedly attempted
credential resolution for its ASINs. The candidate query now requires an existing
credential envelope belonging to that exact tenant and connection before
reserving a per-ASIN retry.

The disconnected-store regression was observed failing before the change.
Five focused tests passed afterward, including other-store credential isolation,
preservation of disconnected listing retry state, and retained retry behavior
for a connected store with a mocked request failure. No real Amazon request
was made by these tests. Local runtime .19 contains this correction. Credentials,
inventory, existing mappings and schema were not changed; production was untouched.

### Owner-narrowed table foundation — 2026-11-02

The latest owner scope supersedes the priority presentation described above.
Local runtime `1.10.7-collaborate-local.24` restores the original Record,
Subject, Conversation, People, Created, Last activity, Assigned to and Due date
columns. Priority/drag presentation is deferred; its backend, tables and stored
orders remain intact. The shared widget consumes the action column into the
Record controls, avoiding an empty extra Actions column.

The header uses the existing context-switcher markup/classes with a
collaboration-only filter fragment. All accounts defaults to the NextAI logo;
permitted accounts/stores are supplied by the existing access repository.
Selection is held in this page's view state and submits no global workspace
selection. The current huddle icon is retained. Create task explicitly selects
its account inside the existing dialog; its endpoint checks that account's
operation permission and the chosen store before writing.

The title/Create task/tab strip and single standard search/Filters/Columns/
download toolbar are retained. Record type is inside Filters. Initial row count
is inside the existing Columns panel rather than an exposed Preferences control.
Five rows show immediately by default. Remaining records are grouped in the same
table by the existing half-open date windows: This week, Last week, This month,
Last month and Older. Creation date is used consistently for ordering and
grouping, so edits and reopen actions do not move items between buckets. Initial
IDs are excluded from every date group. Search, account/store and record-type
filters are applied before the initial extent/group counts. View-specific
expansion and anchor state are preserved.

Active and @Me now exclude closed records before search; @Me remains assigned
only. Legacy team-message search and own-private-note search use the corresponding
visibility predicates. A regression covers closed legacy records, full authorized
message search, and exclusion of another author's private notes. General parent
tasks carry their actual/general subject classification for record-type filters,
without exposing restricted linked-record context.

Existing assignment and date editors are reused with row-authorized account
endpoints. Their shared handlers accept explicit personal-page endpoints; cached
script URLs were refreshed after the actual task dialog exposed an older handler.
Populated verification opened/canceled the assignment/date/task dialogs without
submitting edits or notifications.

Seventeen focused Java tests passed (15 queue/status/search/database, one
rendered-template, one account/store write-authorization test), plus the styled
Chrome foundation test for shared header, five initial rows, disjoint expansion,
one search/no pager, and canceling confirmation without a state write.

Chrome became unresponsive during verification. The owner approved its restart;
Barry's profile and browser-control connection were recovered. The final served
populated screen was checked at `/app/collaboration`, with five initial rows,
eight aligned headers, one search and no pagination. Evidence is stored locally
at `.local/collaborate-foundation24.png`. Existing global workspace selection,
inventory, production and database structures were not changed by this pass.

### Accepted-design refinements — 2026-11-02

Local runtime `1.10.7-collaborate-local.25` preserves the accepted table,
Overview controls, header, toolbar, columns and fonts.

- Create task now mirrors the current collaboration scope name and logo on open.
  All accounts shows NextAI and still requires an explicit permitted account
  before save. A selected account prefills its actual account ID; no parent-ID
  substitution or multi-account creation occurs.
- The account field uses the existing `data-standard-choice` /
  `NextAiPlatformControls` list component. Native select values still back form
  validation, but the visible picker uses the platform's styled list and keyboard
  controls. Account changes invalidate stale member-list responses.
- Active's existing authorization/status query was retained: it includes
  authorized unassigned/other-assignee conversations, while @Me requires
  assignment. Both exclude closed records. Private audience restrictions remain.
- Date headings retain their name/count as content expands beneath them. The
  trigger regains focus and scrolls into view with sticky-header allowance.
  The standard secondary-button component is labelled Load more.
- People and Assigned to use the existing compact initials stack. Full names
  remain in hover/focus tooltip text, accessible labels and named selection
  options. Saving personal-table assignments refreshes the source view without
  temporarily replacing the initials trigger with inline names.

Focused verification passed two database regressions for Active/@Me,
non-assignee audience, closed status and private-note search, plus two styled
Chrome regressions covering five-row/date-group behavior, stable heading
placement, Load more, compact initials, dialog scope, styled choice lists and
account-required save protection. No broad suite was run for these refinements.

Populated Barry Chrome verification confirmed that Active included an item
assigned only to another teammate (40 authorized active items versus 19 assigned
to this actor). @Me was restored with five initial rows and All accounts.
Last month's heading retained its count while expansion and Load more added
unique rows below it. All/NextAI and selected-account/actual-ID dialog states
were inspected; dialogs were canceled without saving or sending notifications.
Evidence: `.local/collaborate-refinements25.png` and
`.local/collaborate-task-choice25.png`. No schema or production changes.

### Owner-requested @Me priority and scope imagery — local .26 (2026-11-02)

Implemented within the accepted table layout. Priority is pinned immediately after Record only on @Me. A grip supports row drag/drop with before/after insertion markers; Alt+Up/Down provides keyboard reordering. The existing authenticated personal-position endpoint persists rank with revision checks. No shared task status, source timestamps, assignments, or another user's order is changed. Active, Private notes and Closed retain their original columns and have no draggable rows. Filters retain global personal rank numbers rather than renumbering a filtered subset. Failed/stale writes refresh authoritative state without silently retrying a move.

The collaboration header reuses platform account/store styling and imagery: NextAI for All accounts, account logo (initials fallback) for a selected account, and Amazon/Shopify/Walmart artwork plus marketplace/country caption for stores. This is a collaboration filter; it does not switch session account authority.

Files touched for this increment: personal-collaboration.js, collaboration-board.css, personal-collaboration.html, fragments/context-switcher.html, table-widget.js (opt-in priority pinning), fragments/sidebar-user.html (matching asset version), brand.css (scoped hidden identity elements), personal-collaboration-drag.test.cjs, and this handoff. All earlier tracked/untracked work preserved. No migration or new backend change for this increment.

Verification: two browser fixture tests passed, including actual mouse drag/drop, keyboard move, authoritative reload/persistence, per-tab drag/column boundaries, retained groups and task-dialog choices. Three isolated CollaborationQueueDatabaseTest cases passed (personal order isolation, shared source preservation, reopen, AFTER placement, audience/message privacy), and CollaborationBoardTemplateTest passed. Isolated package succeeded; git diff --check passed. Actual authenticated Barry Chrome local .26 inspected: @Me Priority after Record; Active original eight columns and zero draggable rows; account/store picker logos and selected Shopify identity. Light and dark inspected; dark retains existing low-contrast date/group/hover styling outside this increment, so a complete dark-theme acceptance is not asserted.

Local UAT restarted through Eclipse External Tools with the tested artifact; migrations and Amazon/Shopify inventory publication remain disabled. Existing read/scheduling configuration preserved. No production action, commit or deployment. Evidence: .local/collaborate-priority26.png, .local/collaborate-scope26.png, .local/collaborate-priority-dark26.png. Actual user's personal order was not changed during rendered verification; reordering exercised with deterministic browser fixtures and isolated database records.

### Task pictures, inline details and account-scope clarification — local .29 (2026-11-02)

Owner requested local corrections, with production unchanged. Reproduced the converted-task presentation defect with PersonalCollaborationPresentationTest: TASK rows with a source review produced empty review/artwork maps. Presentation now resolves only authorized linked reviews and keys their metadata/pictures by the displayed task key. Uploaded catalogue pictures use a review-authorized account-scoped image endpoint rather than the session-selected account. Missing source artwork keeps the existing initials fallback; no fabricated images or source identities.

The personal template previously included only the dialog head assets, not its body. Row subjects and collaboration icons now open the shared conversation dialog in place in Active, @Me, Private and Closed. Converted tasks use their source review; native parent tasks use their existing board message/file endpoints in a local detail dialog. The personal dialog hides the visibility-mode switch; Private notes remain a separate personal tab, with its existing privacy rules. Existing source-link artwork is retained, titles use the available header width, and popup loads/replies use an explicitly authorized review context without switching the session account. Existing reply, assignment, due-date, read-receipt, attachment, completion and reopen domain methods are reused. Source navigation alone deliberately selects the authorized account/store through a CSRF-protected action. Receiving-source links use the existing receiving-session route; the prior opaque-ID search returned no matching invoices.

Account investigation on authenticated local data: All accounts/@Me contains 19 assigned conversations. Ibcore's four tasks have global priorities 11,17,18,19 and therefore appear after the initial five, within date groups. Keto Pantry has one account-wide assignment and five active account-wide conversations; its Amazon store filter has no assigned conversations. Account-wide and store-specific scopes are intentionally distinct. Added a total/initial-row explanation and a styled in-table empty state, including All stores guidance when a store filter excludes account-wide records. Empty tables no longer expose a stray Actions header. No assignment or audience policy was broadened.

Persistence: queue ranks remain database-backed in collaboration_personal_queue_entries and the per-user revision, never sessionStorage. Added a fresh-repository reload assertion to the isolated database test; saved order remains identical across repository recreation. Actual local restarts preserved the existing personal priorities. No real user task priority/status/assignment/message was changed during screen verification; opening conversations may acknowledge visible messages under the existing read-receipt behavior.

Changed for this increment: PersonalCollaborationController.java; PersonalCollaborationActions.java; new PersonalCollaborationThreadController.java; CollaborationController.java (reuse message-type normalization only); SecurityConfig.java (exact source-navigation POST retains Viewer read access with CSRF); personal-collaboration.js; collaboration.js; collaboration-read-receipts.js; collaboration-board.css; personal-collaboration.html; fragments/collaboration-runtime.html and fragments/sidebar-user.html (asset versions); PersonalCollaborationPresentationTest.java; PersonalCollaborationThreadControllerTest.java; CollaborationQueueDatabaseTest.java; CollaborationBoardTemplateTest.java; SecurityConfigTest.java; personal-collaboration-drag.test.cjs; collaboration-inline-date.test.cjs (stale fragment extraction corrected); and this handoff. Prior changes in these files and all unrelated work retained.

Verification: 18 focused JS/browser checks passed, including tab-specific artwork, direct modal opening without navigation, source header, hidden private-note switch, row drag/keyboard ordering, dates and badge regressions. Three isolated queue database cases passed, including fresh repository persistence, independent user order and source preservation. Presentation, scoped-controller, actions, rendered-template and focused source-navigation CSRF/Viewer tests passed; package succeeded and diff whitespace check passed. A broader SecurityConfigTest run reported an unrelated Connections fixture error: WorkspaceAccessRepository.connectionDestinations uses a null JdbcTemplate in tenantAdminCanManageUsersAndConnections. This is separately recorded rather than called a passing full suite.

Local-only Eclipse preview .29, existing migrations and inventory-publication guards preserved. No migrations, commit, deploy, production modification or marketplace inventory submission. Evidence from authenticated local screens: .local/collaborate-task-dialog27.png, .local/collaborate-ibcore-dialog27.png, .local/collaborate-empty27.png; final .29 evidence recorded after source navigation verification. Older screenshot suffixes identify the package actually captured.

#### CD direction and final .30 visual acceptance

Owner explicitly required CD collaboration before finalizing presentation. Consulted existing CD chat 01a15065-cf49-7221-ba66-d2a5d8886a1c. CD reviewed the supplied screenshots, corrected its stale historical instruction to hide Priority, and confirmed the owner's latest approved @Me-only Priority/drag behavior remains. Implemented CD's exact restrained count in the existing status slot (full filtered total and actual rendered Showing count with singular/plural), All stores helper only for store-scoped @Me, and task-source subtitle combining authorized stored account/store names (The Keto Storehouse · Ibcore). Restored existing data-open topic styling, with browser regression asserting zero native border. No new design direction introduced.

CD independently inspected .local/collaborate-CD-table30.png, .local/collaborate-CD-dialog30.png and .local/collaborate-CD-empty30.png and returned targeted visual review PASS, no further corrections in scope. This is local visual acceptance for owner testing, not production approval. CD did not independently repeat interaction/privacy/persistence checks. Below-fold groups were verified by engineering: expanding Last month/Last week rendered all 19 unique assigned records including the four Ibcore tasks, then collapsed again. Original source was actually followed to Invoice 2465477, correct Keto Pantry account, using /app/receiving/work with its existing document; .local/collaborate-original-source29.png captures the result. No receiving or inventory action submitted.

Final running Local UAT is 1.10.7-collaborate-local.30, launched through the existing Eclipse configuration, exact tested JAR. All 18 focused JS/browser checks passed after CD changes. Earlier isolated queue persistence/isolation and focused Java source/context/CSRF checks remain passing; no business logic changed in the final presentation adjustment. Existing unrelated broader Connections fixture failure remains documented above. Final browser left at All accounts/@Me. No commit, deployment, migration or production change.

### Owner source-icon enlargement — local .32 (2026-11-02)

Owner asked to enlarge their supplied original-source icon in the dialog. CD specified 48×48 artwork inside the existing54px source slot; <=650px43px slot/38px artwork, no added frame/padding, preserve title gap and link/focus behavior. Applied only to #thread-parent-link in collaboration-thread.css, with matching stylesheet version in collaboration-runtime.html/sidebar-user.html. Supplied PNG unchanged. Removed inherited5px link padding that otherwise shrank the image. Isolated package succeeded, diff whitespace check passed, authenticated desktop screenshot and DOM measured actual48×48. Narrow responsive sizing is specified in CSS but not claimed as separately rendered verified. No new tests for this reversible CSS-only sizing change. Evidence .local/collaborate-source-icon32.png. Local Eclipse runtime .32; guards unchanged, no commits/deployment/production changes.

### Final account/channel and collaboration touches — local .34 (2026-11-02)

Owner authorized local corrections and required CD direction. CD chat 01a15065-cf49-7221-ba66-d2a5d8886a1c supplied the exact existing-component treatment: text-style account triggers; provider identity in Channel; account-wide task count wording; left-aligned Subject with reserved grip; owning logo fallback; supplied 24px completion image and 6px label gap. Owner chose Edit store/display name (not credentials) and an access-required explanation for a task participant without source-account permission.

Root causes and corrections:
- Native account-name buttons had no applied reset, and the old brand.css URL was cached. Scoped account-title styling now removes native chrome, retains shared focus/hover and business-details handler; accounts loads brand v34. Authenticated DOM verified transparent background, appearance:none, border0, padding0.
- Connections Channel displayed store name. It now shows actual Amazon/Shopify/Walmart provider with store name retained underneath; Channel required/default. Shared row menu opens Edit store name. Tenant-admin guard and parameterized tenant/connection UPDATE affect display_name only, plus the existing audit updated_at trigger; no connection identity, credentials, permissions or sync state changes.
- Ordinary terminal onboarding cards remained globally visible. Overview shows only active queued/running setup; ordinary terminal cards hidden in shared fragment. Real terminal warning remains compactly in Connections Status. Explicit fee-review results remain, preserving their existing golden contract.
- Available Inventory tabs had inflated the shared page header. Existing tabs moved below the header; Platform accounts header retains title/count/actions in one row.
- Selected marketplace filtering excluded account-owned tasks on other channels. Client filtering is now account-wide within selected account; count explicitly names that account, avoiding a false store-only claim. Read-only local investigation found ibcorellc has existing Storehouse/Pantry memberships without store restrictions, so grants were not changed.
- General tasks used initials despite account artwork; fallback now uses actual owning account logo, never current picker logo from another account. Original product/SKU images remain preferred across tabs.
- Subject inherited shared Record centering; a Subject-only rule restores aligned title/account left edges. Both @Me grips invoke the same database-backed personal reorder. Insertion marker is gray/no POST for unchanged rank and blue for actual rank changes, including filtered-out intervening ranks. Active/Closed remain without priority controls.
- Supplied collaboration-complete.png copied unchanged with PNG alpha; dialog Close conversation uses decorative24px art plus explicit label; shared row action image uses the same asset. Existing close confirmation and permission handlers remain.

Task/source separation: queue Card retains origin contextAccount/contextStore for an already-authorized native task audience, while source account/store remain unavailable when access is denied. sourceRestricted opens the authorized task conversation with an access-required explanation; source review reads/actions still deny access. Authorized task-logo endpoint returns branding only with no-store. No source permission grant, membership update, task reassignment or private-note exposure. Backend isolation tests exercise task visibility with zero source-account access and deny the original review and nonparticipant task. Independent security review has not been performed for this incremental metadata/branding path; focused tests and engineering self-review are the current evidence.

Files for this increment (preserving earlier changes): AdministrationController.java, WorkspaceAccessRepository.java, PageController.java; PersonalCollaborationQueue.java, PersonalCollaborationController.java; templates/accounts.html, connections.html, inventory.html, personal-collaboration.html, fragments/sync-onboarding.html, fragments/sidebar-user.html; static/js/connections.js, personal-collaboration.js, table-widget.js; static/css/brand.css, collaboration-board.css, chat-composer.css; new images/platform/table/collaboration-complete.png; tests ConnectionDisplayNameTest.java, MemberAccessDatabaseTest.java, CollaborationQueueDatabaseTest.java, TemplateRenderingTest.java, platform-account-dialog.test.cjs, personal-collaboration-drag.test.cjs; this handoff. No migration or launcher changes.

Verification: isolated package and25 focused Java/database tests passed (16 queue cases, tenant-scoped rename, source presentation/thread guards, rendered templates and connection access).20 focused browser tests passed covering shared account/dialog actions, rename payload, image/popup behavior, both reorder handles, gray/no-op vs blue/changed rank, filtered gaps, denied-source task message path, date and badge regressions. Final CSS/asset correction reran rendered-template check/package. git diff --check passed. Earlier unrelated broader SecurityConfigTest fixture failure remains separately recorded above; no full-suite claim.

Authenticated Local UAT .33 inspected accounts/details Cancel, Connections/edit Cancel, Overview, Inventory header, @Me, Active and Closed. Original and owning-logo images loaded. Ibcore Active now shows34 account-wide tasks rather than8 store-only tasks; @Me18 within Storehouse vs19 All accounts for Barry. Count still defaults to5 visible rows with existing time groups. User task priority/status/assignment/messages not altered during verification (existing read acknowledgement may occur when opening a conversation). Final .34 verified account-name winning styles and completion gap6px. Existing local migrations OFF, Amazon inventory publication OFF, Shopify inventory writes OFF; other existing local read/scheduling/reservation settings preserved. Both restarts used Eclipse External Tools with visible console, exact tested local JAR. Production unchanged; no commit/deploy, marketplace submission or migration applied.

Evidence: .local/collaborate-final33.png, collaborate-account-final33.png, collaborate-dialog-final33.png, accounts-final34.png, connections-final33.png, connection-edit-final33.png, inventory-header-final33.png, collaborate-dialog-final34.png. CD accepted the .33 surfaces except account-name native styling and completion label spacing; both corrected in .34 and submitted for final targeted review. Screenshots establish rendered desktop appearance, not real-user denied-source sessions or mobile interaction coverage; those source-denial states use deterministic isolated fixtures.

Final CD review: independently inspected accounts-final34.png and collaborate-dialog-final34.png and returned targeted visual PASS; both .33 findings closed. Other .33 accepted surfaces unchanged. Owner local UAT pending; production approval not granted. Dedicated Barry Chrome local tab left at Ibcore/@Me, .34. Final workspace diff whitespace check clean; existing uncommitted work retained.

### Creation weeks and 1.11.0 release preparation (2026-11-02)

Owner approved This week/Last week before Last month by creation date, independent engineering review, and scoped GitHub publication. CD confirmed the existing group rows remain: empty requested week groups show0 without a chevron and are disabled; nonempty groups retain expand/collapse; first5 personal priorities are excluded to prevent duplicates. Existing Monday calendar boundaries and Los Angeles zone preserved. Three focused browser cases plus creation-date/DST tests passed. Authenticated local1.11.0-collaborate-local.35 shows This week0, Last week1, Last month12; evidence .local/creation-weeks35.png. This preview predates the final release security corrections described below.

Independent reviewer found and reviewed fixes for legacy source-store access and exact attachment context. The final source resolves historical null-store Amazon order/SKU records only from unique authoritative tenant source keys; restricted viewers cannot use missing/ambiguous source identity. New bound discussions persist the verified source store. Source Review metadata returns the server-resolved store for navigation. Persisted store grants protect source reads, messages, mutations, files and notification recipients. Native task participant audience remains separate from source access. Store-scoped task creation validates actor and every recipient before writing anything. Creation from missing/ambiguous Amazon source now fails explicitly rather than guessing a store; a future selected-source context adaptation can improve that limitation.

Ordinary reply mentions now use notification-only queueReply; explicit assignment/initial task creation retain their existing contract. Delivery claim rechecks active recipient and current account membership/platform authority plus source-store scope. Denied queued rows can exhaust retries without a terminal SKIPPED transition (queue hygiene limitation; no content delivered).

Reviewer source PASS followed by passing same-account storeA/storeB regression: storeB-only teammate cannot be assigned storeA task and no task remains. Tests cover Amazon creation/assignee, historical null source, missing/ambiguous source, revoked store and recipient access, exact-review file binding, authorized native-task visibility, no mention assignment.85 focused final Java/database/template/security/release tests pass.20 primary browser tests pass. A full1404-test Java attempt initially found stale fixtures/assertions: migration max126 vs129, missing real discussion source/member fixtures, removed FEFO header copy, old release version, missing user-template model value, enrichment requiring saved credentials. These were corrected in tests; the41-case replacement run passed40 with the remaining FEFO assertion subsequently corrected and covered in final85. Full538-case browser attempt had3 interaction timeouts under concurrent builds and2 stale version/inventory assertions; serial affected42-case rerun passed41 with remaining cache manifest mismatch then corrected and all4 cache cases passed. Do not describe either initial full run as passing. Normal CI on the scoped candidate remains the final full-suite gate.

Production preflight read-only via AWS SSM: current1.10.7 JAR SHA218e782f512d65b735b3cd3a13685f56088e7172a3f40be8e85ef5e91f35aa68, schema124/no failed migrations, V124 checksum338492468, PostgreSQL18.6, nextcommerce role neither superuser nor BYPASSRLS. New exact migration set125–129 remains unapplied. Database3455915711bytes, filesystem3330203648bytes free (89% used), below normal recovery-space requirement. No production app/config/database mutation or migration. Deployment held for recovery capacity and final verified release pipeline. First read-only command stopped at absent build-label grep; corrected read-only command succeeded (SSM3e902e4a-6ce1-4cfb-8826-746c7074b3aa).

#### Verified candidate and approved production capacity (2026-11-02)

Scoped release checkout .local/release111/source preserves the original dirty worktree and excludes unrelated governance edits. Owner approved review/commit/publication. Published candidate revision0f4df1bc0b6d17bd55d8ec73df2af0d45d4a68e9 on codex/collaboration-minor-1-11. Normal CI41539480265 passed:1404 Java tests reported,0 failures/errors,93 skipped;538 Node/browser cases,537 passed,1 skipped. Initial CI had one receiving quantity assertion racing recalculation; added a wait for the actual calculated value (assertion remains), focused case passed, then full CI passed. Exact CI JAR86434290bytes SHA256febf5c88f9138a71a0a2e0ea9b7483ee455a6d094128143b725035632e25ac11 matches GitHub asset digest under build-0f4df1bc0b6d17bd55d8ec73df2af0d45d4a68e9. No artifact rebuilt for production.

Owner explicitly approved deleting only oldest database dump. Verified newer1.10.0 archive index and unlinked only /opt/next-ai-commerce/backups/v1.0.1-68a08d59fdd0/database.dump (71372983logical bytes); all other backups/configuration retained. ReceiptSSMb9cf52c1-1f34-4112-b9ce-f65c1887c2fb; screenshot .local/release111/oldest-backup-deleted.png.

Owner then explicitly approved gp3 production root-volume growth30→50GiB, additional$1.92/month before tax at currentUS-West-N.California$0.096/GB-month. AWS volumevol-064e5d2f92b114514, instancei-043ea7df8377dd0a9;3000IOPS/125MiB/s unchanged. Optimizing state confirmed, then boundedgrowpart/resize2fs against verified /dev/nvme0n1p1 ext4 completed online. Root filesystem50839412736bytes,24220303360bytes free,53% used; app service active, no restart for storage. ReceiptSSM7a5a1c01-2303-43d2-a795-f2aa14aab626; .local/release111/filesystem-expanded.png. AWS regional public price index verified rate; no other AWS capacity/security settings changed.

Independent reviewer cleared operational scripts after pending-only prevalidation/exact125–129 enforcement, grouped transactional migration batch, strict postvalidation, datasource loopback5432 binding, non-BYPASS restricted role, each required CRUD privilege and both RLSflags, actual MainPID/artifact verification and healthy old-artifact rollback. Read-only production verification confirms existing exact/ws/huddles route, Upgrade header and HTTP1.1 forwarding; no nginx change needed. Actual production profile binds DB_URL/DB_USER/DB_PASSWORD from existing service environment. The deployer rechecks exact servicePID and captures only these datasource values for the standalone migration helper without shell sourcing/credential output or ambient datasource fallback. No integration settings changed. Independent source review and all final release prerequisites passed.

Production release command9de52cf2-9c20-43be-b769-c461db6ed3a2 submitted with exact reviewed payload and CI digest. It takes a fresh private recovery dump/index/hash plus app/start/mail/service copies, verifies first124 packaged migration files byte-identical to running artifact, uses standalone Flyway (no Spring jobs during DDL), then exact artifact swap/restart/health. Result and final rendering verification recorded below when available. At this entry, release command remains in progress; do not infer deployment completion.


#### Production deployment receipt (2026-11-02)

SSM9de52cf2-9c20-43be-b769-c461db6ed3a2 completed successfully at23:03:18UTC. Exact CI revision0f4df1bc0b6d17bd55d8ec73df2af0d45d4a68e9 /1.11.0 artifact SHA256febf5c88f9138a71a0a2e0ea9b7483ee455a6d094128143b725035632e25ac11 is healthy. Schema124→129 completed; fresh recovery dump478447590bytes, SHA2566e3cdb4c09170eecea00b92a83c3d6e39f8d00f11af5fce4dc066bfd0f0e2a64 retained under /opt/next-ai-commerce/backups/v1.11.0-0f4df1bc0b6d. Previous immutable1.10.7 artifact retained. Runtime configuration unchanged; no marketplace inventory publication activated by deployment. Evidence .local/release111/deployment-success.png.

Authenticated Barry Chrome production check: personal queue populated, original product pictures rendered, This week0 / Last week4 / Last month12 in requested order, original-source link present and conversation content loaded from @Me without tab navigation. No messages, assignments, priorities or marketplace quantities changed during checks. Production queue and conversation loads were noticeably slow; performance follow-up remains unresolved. Sidebar still displays1.10.7 despite independently verified running1.11.0 artifact: retained runtime build-label override is a presentation limitation, not artifact identity evidence. Do not claim it corrected. Evidence .local/release111/production-me.png.

Production responsive rendering:1440×900,1024×900 and390×844 document widths matched viewport widths (no horizontal document overflow); temporary override reset. Rendering only, no mobile interaction testing.


Final production verification exception: Closed tab failed with “This view could not be loaded.” Read-only SSM81024334-45e4-475c-8c4f-530065428a61 confirms one nginx upstream timeout for /collaboration/personal/data, and response-write IOException/HttpMessageNotWritableException; no credentials or customer messages printed. Thus deployment/startup PASSED but full feature acceptance NOT PASSED. Independent reviewer confirmed Closed unnecessarily reconciles/locks ASSIGNED queue and enumerates all authorized sources before Java status filtering; single-thread access similarly enumerates sources. Exact query-plan timing remains unmeasured; JIT/index hypotheses are not proven. Do not disable JIT globally or add speculative indexes. Recommended scoped follow-up: SQL-level closed filtering, avoid personal ranking reconciliation for unranked scopes, exact-key thread authorization with existing permissions preserved. Existing APP_BUILD_VERSION explicitly1.10.7 confirmed; artifact identity independently1.11.0. These issues were documented, not silently patched in production.


### 1.11.1 performance hotfix (2026-11-02, preparing)

Owner authorized production performance corrections, explicit two-digit expiration entry, removal of Available Inventory's three summary cards, and Inventory account-only scope. Original dirty worktree preserved; implementation isolated in codex/platform-performance-hotfix. Inventory “All accounts” aggregate versus single-account picker requires clarification and is not silently relabeled.

Read-only production comparison (SSM4a5253f6-acd3-47e1-80d7-c5938ef2e0be): directory JIT compilation12827.939ms /execution12876.769ms versus JIT-off execution9.172ms; batched details14253.246ms compilation /14315.639ms execution versus32.013ms; Inventory badges15323.678ms compilation /15358.146ms execution versus31.377ms. Same connection, role, predicates, records and transaction, SET LOCAL only, rolled back afterward. Source corroborates repeated unranked personal queue locking and per-row detail hydration.

Correction: transaction-local JIT-off at collaboration query context; no ALTER ROLE/database/global setting. ACTIVE/CLOSED/PRIVATE bypass assigned-queue reconciliation; visible reviews status-filtered in SQL; authorized task aliases collected across status before suppression. Exact-ID authorization and bounded200-ID detail batches preserve existing visibility predicates. Display-only workspace model skipped for JSON/image handlers with an established account context (personal API self-resolves account access). No cross-request permission/task/inventory cache. Existing bounded catalogue cache/commit invalidation remains unchanged.

Date correction is explicit editor input only: typing26/27 preserves the current row month/day and submits2026/2027; calendar-native0027 edit expands only on explicit change; programmatic loaded historic0027 remains visible and invalid until corrected. Reused/undated editor cannot borrow a prior row date. Controller normalizes only the newly submitted target year; original batch date/expected quantity/reservation/history path unchanged. Removed requested summary cards; retained shelf-status calculations and rules.

Red/green regression: unranked view creates queue state before fix (expected0/actual1), then passes without state creation. Focused Java reports126tests across executed relevant suites,0failures/errors;11 focused date/table browser cases pass. Independent review initially found reused-date reference and opposite-status alias cases; both corrected and regression-covered; final source PASS. Package builds. Full normal CI and production latency/render verification pending. No schema, inventory quantities, marketplace writes, integration flags or application services changed during preparation.
