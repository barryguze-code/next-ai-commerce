# Collaboration finalization hotfix 1.11.11

Status: implemented and reviewed; scoped publication pending. Baseline exactdeployed1.11.10 revision10b166981452d1dbc8efba4ebb2b705733224f42. Existingwork preserved in isolated worktree.

## Changes

Empty X reproduced with real brand/tooltip cascade: icon helper adds title, tooltips turn this into data-tooltip suppressing the standard pseudo-element strokes; SVG inherits transparent currentColor from dialog-close. Huddle close controls now use the shared glyph/class without SVG/title-tooltip conflict.

Finalization reuses the actual contextual-thread dialog and its subject/pen and draft assignment/date controls via a scoped adapter. Header starts in subject editing mode; direct collaborations start with empty title, record collaborations use source label. Existing creator-only save gate retained. Assignment choices limited to current room participants. Cancel/X/Escape preserve title/assignees/date and return to room; no LEAVE action. Existing transcript/files stay in room until successful SAVE. Floating manual popover remains hidden while modal is active, including room updates, preventing composer focus frame from overlapping the modal. Legitimate focused-control outline retained. Due editor collapses after choosing date.

SAVE adds optional assigneeIds; absent values preserve actor default. Existing conversion validates active parent membership of whole room and selected IDs must be room participants before task creation. No new schema, inventory logic or marketplace operation. Non-durable legacy conversion explicitly rejects unsupported custom assignment instead of silently ignoring it.

Save stays pending until matching HUDDLE_SAVED. HUDDLE_ENDED does not confirm save. Correlated SAVE errors and disconnection retain draft;20s acknowledgment timeout allows safe idempotent retry. Timeout indicates unknown outcome: if already committed, retry retrieves original task; later edits cannot amend that committed task.

## Verification

13focusedJava tests passed, including disposable database conversion proving chosen assignee, outsider rejection, due date, transcript/file preservation and idempotence. Browser fixture uses real shared fragment/cascade/runtime; checks visible X, Cancel draft preservation, actual title/assignee/date payload, pending state, unrelated errors ignored, save error/retry, deterministic timeout/retry, success acknowledgment, ended-room rejection, minimize/send and picker. CD reviewed compact fixture; date label corrected to stay on one line. Production runtime checks pending publication.

Focused HUDDLE_FINALIZATION CI mode preserves other lanes and normalCI. No database backup/migration or broadUAT cycle. ExactCIartifact startup smoke and prior application rollback retained. This save-behavior change is not presentation-only.
