# Collaboration UAT refinements — 1.11.5

Status: implementation and focused verification complete; scoped publication/deployment pending.

## Owner scope and design

Owner authorized the six screenshot corrections and hotfix deployment. CD reviewed the supplied screenshots, source and authenticated local 1.11.4/1.11.5, then supplied the contract: preserve the accepted table; use actual assignees for red icons; visible user/browser saved initial count; centered replenishment-style period controls; narrow message-count column; shared subject/header/composer/assignment controls for creation and existing conversations. No new hover history retrieval. Source record identity stays independent of the editable discussion title.

## Implementation

- `Card.assignedToMe` comes from the current authorized native task participant or legacy review assignee, rather than assuming every active row belongs to the viewer. Unread mentions retain count feedback but do not turn another person's task red.
- Visible Show first tasks control defaults to five and saves immediately per user in localStorage; session view filters remain separate. This browser preference survives sign-out/restart but is not a cross-device server setting. Personal priority ordering remains database persisted and unchanged.
- Period toggles retain date grouping and state, using the shared centered chevron divider. Conversation becomes a compact count/open control; source title no longer repeats there. Subject uses reclaimed width and normal wrapping.
- Platform task creation uses the existing contextual thread dialog, composer and mention handlers. Initial header subject is focused and defaults to the source title for record conversations. Assignment, assign-self and due date are available before first save; Close stays hidden until an active conversation exists.
- Saved subjects use the supplied pen and persist `review.title`, without changing source keys, source label, store identity or task state. Authorized closed-title edits leave the conversation closed. Private-note view hides the shared subject editor.
- Initial assignment/date/files are saved within the existing repository transaction. Existing attachment validation, notifications and permission checks are reused. Title POST carries only title and CSRF, never unsent replies/files.
- Drafts/files clear on account, source, conversation or privacy transitions. Late members/subject responses are ignored; account picker is disabled during create.

## Focused evidence

- Reproduction: new browser regression failed on original deployed source because an unassigned row had `data-assigned-to-me=true`; passes after the correction.
- 22 focused browser checks passed covering actual assignee colors, saved count across fresh session, shared dialog, @mention insertion, initial controls, pen save, same-record conversation switches, attachment/text isolation, late account responses, dragging/keyboard order, themes' existing contracts and source navigation. No production message or stock writes were used for testing.
- 103 relevant Java/template/security reports currently show zero failures/errors/skips. Isolated PostgreSQL tests cover source-preserving title edits, foreign-account denial, closed state preservation, authoritative native task assignment and existing personal order/isolation behavior.
- CD authenticated local rendered review confirmed narrow Conversation (120px), centered visible chevrons, mention suggestions/insertion, initial metadata controls and existing pen. CD found initial header shrinkwrap and inherited Subject copy cap; both corrected in source. Final template browser fixture verifies initial input wider than400px; final exact runtime render remains a release smoke check.
- Based on exact production1.11.4 revision `a8ce578e2e23ebee54e29791359f38f7cf248702`. No inventory/schema/calculation/integration changes. All129migration bytes must remain identical at deployment. Prior performance and receiving fixes remain ancestors, not copied from an older release.

## Files in scope

- `.github/workflows/ci.yml`
- `pom.xml`
- `src/main/java/com/nextaicommerce/platform/collaboration/CollaborationController.java`
- `src/main/java/com/nextaicommerce/platform/collaboration/CollaborationRepository.java`
- `src/main/java/com/nextaicommerce/platform/collaboration/PersonalCollaborationActions.java`
- `src/main/java/com/nextaicommerce/platform/collaboration/PersonalCollaborationController.java`
- `src/main/java/com/nextaicommerce/platform/collaboration/PersonalCollaborationQueue.java`
- `src/main/java/com/nextaicommerce/platform/collaboration/PersonalCollaborationThreadController.java`
- `src/main/resources/application.yml`
- `src/main/resources/releases/history.json`
- `src/main/resources/static/css/collaboration-board.css`
- `src/main/resources/static/css/collaboration-thread.css`
- `src/main/resources/static/js/collaboration.js`
- `src/main/resources/static/js/general-task.js`
- `src/main/resources/static/js/personal-collaboration.js`
- `src/main/resources/templates/account-catalog.html`
- `src/main/resources/templates/action-suggestions.html`
- `src/main/resources/templates/collaboration-board.html`
- `src/main/resources/templates/collaboration.html`
- `src/main/resources/templates/fragments/collaboration-runtime.html`
- `src/main/resources/templates/fragments/collaboration-thread.html`
- `src/main/resources/templates/fragments/sidebar-user.html`
- `src/main/resources/templates/inventory-publications.html`
- `src/main/resources/templates/inventory.html`
- `src/main/resources/templates/marketplace-skus.html`
- `src/main/resources/templates/orders.html`
- `src/main/resources/templates/personal-collaboration.html`
- `src/main/resources/templates/receiving.html`
- `src/main/resources/templates/variation-coverage-operator.html`
- `src/main/resources/templates/variation-trial-operator.html`
- `src/test/java/com/nextaicommerce/platform/collaboration/CollaborationQueueDatabaseTest.java`
- `src/test/java/com/nextaicommerce/platform/collaboration/CollaborationTaskDatabaseTest.java`
- `src/test/java/com/nextaicommerce/platform/collaboration/GeneralTaskControllerTest.java`
- `src/test/java/com/nextaicommerce/platform/config/TemplateRenderingTest.java`
- `src/test/js/collaboration-badges.test.cjs`
- `src/test/js/personal-collaboration-drag.test.cjs`
- `src/test/js/collaboration-uat.test.cjs`

## Release and rollback

Use normal CI because this batch includes persisted subject/initial task behavior, not a presentation-only lane. Verify exact CI artifact/digest and preserved production settings; retain1.11.4 for package rollback. No migration or data rewrite required. Existing unrelated dirty worktree remains untouched; feature is isolated in branch `codex/collaboration-uat-hotfix`.

Final release evidence and original-worktree alignment will be appended after verification. The previous one-second page SLA remains open; this hotfix must not be presented as completing it.
