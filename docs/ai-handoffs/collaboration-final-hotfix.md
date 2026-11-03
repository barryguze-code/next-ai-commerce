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
