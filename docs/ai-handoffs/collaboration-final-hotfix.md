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
