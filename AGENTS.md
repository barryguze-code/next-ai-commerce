# AI engineering governance

These instructions apply throughout Next AI Commerce. Read applicable nested
`AGENTS.md` files before working in their directories.

## Protect the workspace

- Inspect the current branch, `git status`, relevant diffs, documentation, and
  existing implementation before editing. Treat tracked and untracked changes
  as valuable work owned by the user or other contributors.
- Do not reset, stash, commit, delete, overwrite, or revert existing uncommitted
  work without explicit authorization. Do not clean the workspace to simplify a
  task. Make narrow edits; if they would conflict with existing work, ask first.
- Stay within the requested scope. Do not perform unrelated cleanup or silently
  switch branches.

## Route work by responsibility

- Before substantial work, consult the [AI model policy](docs/AI_MODEL_POLICY.md)
  for model/effort recommendations, escalation and review requirements; it does
  not switch models or expand task authorization.
- For UX, UI, interaction, visual design, and usability tasks, read and apply
  [Creative Director standards](docs/ai-roles/creative-director.md).
- For implementation, debugging, refactoring, integration, and verification,
  read and apply [Principal Engineer standards](docs/ai-roles/principal-engineer.md).
- Tasks spanning design and implementation require both standards. These roles
  define responsibilities; they do not authorize additional scope or delegation.
- Preserve existing application architecture, domain boundaries, business rules,
  tenant isolation, permissions, security controls, and integration safeguards.
  Propose significant architectural changes for approval before implementing.

## Design authority and approval

Use the existing design sources as the shared reference rather than duplicating
their detailed rules:

- [UX and interaction standards](docs/UX_DESIGN_STANDARDS.md)
- [Shared table controls](docs/table-widget.md)
- [Shared platform icons](docs/platform-icons.md)

Existing UI limitations are open to challenge. Before implementing a substantial
redesign, obtain explicit product-owner approval of a concrete proposal explaining
the affected workflows, proposed experience, benefits, compatibility implications,
and verification criteria. This includes broad navigation, shared design-language,
table-interaction, or multi-screen workflow changes. Exploration and proposals may
proceed; implementation must wait for approval. Routine improvements within an
authorized task and established standards do not need a separate redesign approval.

If documentation conflicts with the task or with another source, identify the
conflict and seek a decision before implementing a consequential deviation. Do
not silently rewrite standards or treat existing implementation as design approval.

## Local services and Eclipse

Preserve all existing Eclipse `.launch` configurations and workflows. Follow the
required Eclipse workflow in [local development](docs/local-development.md): use
`Local PostgreSQL - Start.launch`, then `Local UAT - Start Application.launch`,
with logs visible in Eclipse. Use the provided Status and Stop launchers as needed.
Inspect existing listeners and configuration targets before starting or stopping
services; never stop unrelated processes. Do not substitute hidden terminal
services. If Eclipse GUI launch is unavailable, ask the user to launch the required
configurations and wait for confirmation before dependent runtime verification.
Never use production deployment workflows for local verification.

## Evidence before completion

Verify the result against the requested scope and acceptance criteria before
claiming completion. Use relevant existing tests and, for UI behavior, inspect the
affected workflows and states. Follow documented environment safeguards. For
documentation-only work, verify content, references, and the final change scope;
application tests are unnecessary when application behavior is unchanged.

Review the final diff and status to ensure unrelated work remains intact. Report
what changed, what was actually checked, results, unresolved conflicts, and any
verification blockers. Never claim a test, runtime check, or approval occurred
without evidence.
