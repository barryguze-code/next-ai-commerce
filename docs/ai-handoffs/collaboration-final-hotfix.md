# Collaboration final UAT hotfix —1.11.6

Status: implemented; focused tests/review and local rendered verification in progress. Production remains1.11.5.

Owner authorized completion emails plus Older-only25paging, hideemptydategroups, stableEnter-to-send, and truthful defaultassignment warning. Existing explicitassignment wins; otherwise existing validmention recipients are initialassignees, falling back to creator only when there are no recipients. Replies do not reassign.

CD directed inlineaudiencehint with existingAddpeoplementionpicker, existingmanagementrow, no newmodal. Recentdatebuckets showfullcontents; onlyOlderloads25more. FirstNprioritystrip andsavedordering stayunchanged.

Completion: existingrecordnotificationqueue includes creator/assignees/teamwriters/mentionedparticipants; excludesprivate-onlyusers; serializesconcurrentclose, suppressesrepeatclose. NativeDONE notifiesparticipants once, perrecipientfailuredoesnotstopothers; nativeeventdelivery remainsnon-durableaftercommit.

Independentreview found and corrected mentionoverride/duplicatealerts, hiddenprivate-threadreply, delayedreplydraftreset, and membership-directoryrace. Addedfocusedregressions. No schema/inventory/calculation/integrationchanges. Existingreceipt/performancefixes preservedasancestors.

FinalCI/artifact/deployment/localruntime evidence to follow.

Focused verification:91 Java/template/security reports zero failures/errors/skips;23 focused browser tests passed. Separate final4-case UAT browser rerun passed. MockMvc confirms existing reply URL negotiates JSON instead of redirect; isolatedDB validates creator fallback, explicit-over-mention assignment and single-notification kinds, ownprivate versus foreignprivate writes, concurrent closes and completeparticipant audience. Independent source review PASS after corrections. No real email sent in tests.

Local preview started directly through Eclipse, PID46840,buildlabel1.11.6-collaboration-final-local, previewJAR SHA25653ed46b94e69769ff9a212986cbdd68350316542a93794e2e9712888a14b8ab9. Launchers and existingintegrationguards preserved. Final exactCIartifact will replace preview after publication.

CD actuallocalreview PASS in light/dark: truthfulself/mention/explicitassignmentpreview, existingAdd@picker, compactnotice, emptygroupremoval, complete25-rowrecentbucket/noLoadmore, Older10rows/noLoadmore. Onlyautomatedfixtureexercised25/50/58Olderincrements. Screenshots `.local/collaboration-final/cd-local.png` and`cd-local-dark.png`. No realtask/message/emailwrites. Theme restoredlight. Productionread-onlypreflight confirms1.11.5 healthyHTTP200,schema129|0,exactbaselineartifact;22GBfree.

Owner explicitly approved scopedcommit/publication viaasyncquestion on2026-11-03. Publication/CI/deployment evidence pending. Existingdirtyworkspace preserved; allcombinedapplicationchanges isolated oncodex/collaboration-final-hotfix atopexactproduction1115.

## 1.11.7 workspace identity hotfix — prepared locally

Owner requested a larger title pen beside the title, item-code copy beside its lock, and consistent account/store identity without navigation silently changing the workspace.

Implemented in isolated `codex/workspace-identity-hotfix` from production 1.11.6. The shared picker now uses the selected account logo and concrete store identity on warehouse pages, without changing warehouse ownership or quantity queries. Ordinary picker accounts with connected stores are grouping headings; store changes use the existing authorized POST. Accounts without stores remain selectable as warehouse accounts. Collaboration alone adds account All stores and All accessible accounts as view-only scopes; choosing a specific store updates the concrete workspace through the existing selection endpoint. The collaboration client now actually filters by store and invalidates stale local scope when server workspace identity changes. Receiving detail navigation safely returns to the Receiving list when the workspace changes.

Saved-title pen: 28px graphic, 36px control, measured 8px after title on authenticated Local UAT. Item-code copy remains beside lock; mapping-link and lock-filter actions retained. Changed asset references bumped to 1.11.7.

Verification: 30 focused Java/template tests passed (WarehouseWorkspaceTest 3, WorkspaceModelAdviceTest 3, TemplateRenderingTest 23, CollaborationBoardTemplateTest 1). Six existing focused browser tests passed; two new workspace/icon regressions passed. Isolated package built and launched using Eclipse External Tools; localhost login returned 200. Existing local runtime configuration preserved except public version label; prior JAR/properties retained under `.local/workspace-final/runtime-before`. No schema, migration, inventory, receiving, performance-query, or integration source changes.

Owner approved scoped commit/publication. Independent engineering review passed. Actual CD review passed: Inventory → Orders → Marketplace SKUs → Collaborate retained Ibcore/Amazon US; Collaboration All accessible scope did not clear the concrete workspace on Orders. Screenshots: `.local/workspace-final/cd-inventory-picker.png`, `cd-title.png`. Normal CI and production deployment remain pending. No production change has been made for 1.11.7.

## 1.11.8 client feedback hotfix — prepared

Owner requested fast focused CI/startup smoke, no database backup or broad UAT cycle. Isolated source is `codex/collaboration-invite-hotfix` based on exact production 1.11.7 revision `229cfe76da9ac868efa5809151321ae34531a25d`. Original working-tree changes are preserved.

Root causes: invitation form never looked up effective existing grants and binary channel markup rendered Shopify as Walmart; teammate row started a one-recipient chat immediately; shared conversation dismissal unconditionally cleared drafts. New read-only invitation lookup requires current account-administrator authority, reuses effective account membership/store-grant projection and returns only granted store IDs with no-store caching. Existing grant/write policy is unchanged. Client generation checks reject stale email responses and fail closed on failed/malformed lookup. Already-granted boxes remain checked and disabled; only additional stores are submitted, and existing acceptance already unions grants. No-store account invitation behavior remains available.

Huddle picker selects up to two teammates before one explicit Start chat action, preserving the existing three-person server maximum and common-account authorization. Selection survives search/presence refreshes and server errors. Existing @ invite action now identifies non-members as Add to chat; no permission or room-membership expansion occurs without the existing server validation. Shared draft dismissal checks body/files/subject/explicit initial audience/due date, blocks close during save, and uses existing Keep editing/Discard draft confirmation on X/Done/backdrop/Escape. Shared sidebar loads the singleton confirmation helper so Overview and other consumers work too.

Verification: 43 focused Java tests passed, 15 browser tests passed plus the added late/failed lookup regression (16 total relevant scenarios). All original symptom regressions were run red before fixes. Independent source review passed after fixing missing shared confirmation dependency. Actual CD review passed for invite grant/Shopify logo, Overview unsent draft close/retain/discard, and huddle one-person selection/tab persistence. No real invitation, task, chat or message was submitted for review. Actual two-recipient/@ server interactions were verified using deterministic mocked browser tests; real multi-person operation remains owner UAT. Evidence: `.local/collaboration-invite/cd-invite.png`, `cd-draft-close.png`, `cd-huddle-selection.png`, Java/browser logs.

The existing manually dispatched hotfix workflow retains its PRESENTATION_ONLY behavior and adds a separately selected CLIENT_READ_ONLY_ACCESS_UI mode for the owner's focused-CI request: fixed relevant Java classes and browser regressions, package/checksum, startup smoke at deployment. This is not labelled Tier 1 presentation. Normal feature/release CI remains intact. No database backup, migrations, inventory calculations or marketplace-write changes are included. Publication/deployment pending scoped commit approval and exact-artifact verification.
