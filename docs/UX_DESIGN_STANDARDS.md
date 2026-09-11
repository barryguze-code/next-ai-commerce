# Next AI Commerce UX and interaction standards

Next AI Commerce is a data-dense operations platform, but it must never feel like an engineering console. Every workflow must be understandable without training.

## Non-negotiable orientation rule

Whenever a user enters a multi-step process, the interface must answer these questions immediately:

1. Where am I in the process?
2. Why is this step required?
3. What information do I need now?
4. What will the platform do after I continue?
5. What remains before the process is complete?

The current step must be visually distinct. Completed steps use a clear success state. Future steps remain visible but visually secondary. A dialog title such as "Add vendor" is not sufficient by itself.

## Action and field language

- Buttons describe the outcome: "Save vendor and continue", not merely "Save".
- Required and optional fields are explicit before submission.
- Help text explains the business purpose of a field, not its database meaning.
- Technical terms, internal identifiers, encryption details, and framework errors stay out of client-facing text.
- Prerequisites are explained before the user reaches a blocker.

## Workflow continuity

- Successful prerequisite steps advance the user to the next logical action.
- The platform preserves context when moving between related dialogs or pages.
- A user must not have to rediscover what to click after completing a setup step.
- Long-running work shows progress, current activity, completed checks, and whether it is safe to leave.

## Error recovery

- Never expose Whitelabel pages, stack traces, SQL, or framework terminology.
- State what was not changed or what remains safely saved.
- Identify the affected file or record when possible.
- Tell the user exactly what to correct and where to continue.
- Log the technical exception with sufficient server-side context.

## Visual standard

- Use Apple system fonts and compact, readable controls suitable for high-volume commerce data.
- Preserve hierarchy through spacing, alignment, weight, and color rather than oversized text.
- Keep light and dark themes equally legible.
- Design desktop and mobile behavior together.
- Use marketplace, account, country, and status identity consistently.

## Table widget standard

- Every ordinary data table supports sorting from its column headings with mouse or keyboard.
- The active direction is visible and exposed through `aria-sort`.
- Large server-paginated tables sort in the database; smaller tables sort in the browser.
- Search belongs in the table toolbar and remains available on mobile.
- Workflow tables whose rows contain live forms, grouped batches, or dependent actions opt out of
  generic sorting until a workflow-safe server sort is available.

## Required review before merging a workflow

- Current step is visible.
- Prerequisite and business reason are clear.
- Required versus optional input is clear.
- Primary action states its result.
- Success advances or clearly points to the next action.
- Empty, loading, success, validation, permission, and unexpected-error states are designed.
- Keyboard, mobile, light-theme, and dark-theme behavior are considered.
- Client-facing text contains no internal implementation language.
