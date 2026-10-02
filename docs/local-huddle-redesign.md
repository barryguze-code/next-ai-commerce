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
