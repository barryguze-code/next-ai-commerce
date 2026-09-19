# Order collaboration context

Orders retain their existing photo dimensions, layout and row-color rules. The shared icon library includes the supplied final PNGs. Image editing is revealed by hovering the center control (or keyboard focus), not the whole tile. Identifier locks follow their text. Available precedes 4 WEEK SALES in the revised default column order.

Conversation badges count active, visible conversations. Closed threads remain in the collaboration workspace but are omitted from record pickers and badges. Origin conversations are blue; origin conversations created by, assigned to, or mentioning the current viewer are red. Related-only conversations are gray. Empty records have a gray icon without a badge. Unread artwork is available in the library; the backend does not yet maintain independent per-user read receipts, so it does not claim an unread count.

Related order conversations are resolved server-side through tenant-scoped Amazon order items and store-scoped active SKU mappings. Marketplace SKU, catalogue item and inventory position lookups include the same order thread rather than copying notes. Inventory keys resolve the catalogue item portion before `|`. Relations reflect current mappings; the original subject and parent URL stay on the thread. Private notes remain visible only to their author. No schema migration is required.

Regression coverage: `ReceivingWorkflowDatabaseTest#orderConversationsFollowMappedAssetsWithoutLeakingPrivateNotes` uses an isolated synthetic schema in the local test database. It verifies origin/related counts, author relevance, private-note filtering, tenant boundaries, retained origin URLs and closure counts.
