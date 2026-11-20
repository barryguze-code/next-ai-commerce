# AI model selection and escalation

Last reviewed: 2026-10-23 (America/Los_Angeles).
Owner: Governance Coordinator; product owner approves policy changes.
Review when available models/settings change or project results show repeated
defects or unnecessary cost. This policy supplements [AGENTS.md](../AGENTS.md).

## Purpose and limits

Choose model capability and reasoning effort for task complexity, ambiguity and
consequences. Use the lightest setting that meets the project's quality bar;
neither a stronger model nor more reasoning guarantees correctness.

This Markdown file guides decisions; it does not change app settings, select a
model automatically, start another agent/chat, or authorize implementation,
operational actions or additional scope. Respect the user's explicit model choice.
Recommend a change when justified; the user normally selects it in the app.
Never claim a model/effort change occurred without confirmation from the runtime
or user. If the actual setting is unavailable, say so rather than guessing.

Model and effort are separate choices. “Sol Light” is not assumed to identify an
exact model version or map to Low effort. Use the actual selector labels when
known. Availability and supported efforts vary by product/model; verify them
before prescribing a specific version or setting. Do not silently upgrade models.

## Project starting recommendations

These are project heuristics to evaluate, not fixed vendor guarantees. “Sol” means
the user's available Sol version; changing versions is a separate recommendation.
Medium is the proposed everyday baseline for engineering requiring judgment.

| Task | Starting recommendation | Review emphasis |
| --- | --- | --- |
| Small wording, links, formatting or mechanical edits with clear scope | Sol Low/Light, if that effort is confirmed | Exact scope, meaning and references |
| Read-only monitoring, factual extraction and routine backlog checks | Sol Low | Approval provenance; escalate contradictions |
| Routine implementation using established patterns and clear acceptance criteria | Sol Medium | Relevant tests, integration and preserved behavior |
| Multi-file changes, shared components, difficult debugging or conflicting handoffs | Sol High | Root cause, affected consumers and regression evidence |
| Significant UX direction, platform standardization or architecture tradeoffs | Sol High; consider Extra High or Astra Medium for unresolved ambiguity | Alternatives, rendered evidence, contracts and scoped approval |
| Inventory, financial calculations, permissions, tenant isolation, migrations or integration safety | Sol High or Astra Medium/High | Explicit invariants, focused regression evidence and independent review |

Reserve Extra High/Max for tasks where representative results justify the extra
time/usage. A routine task does not need the strongest setting merely because it
belongs to an important project. A small diff may still be high impact.

Before substantial work, give one brief recommendation with model/effort, reason,
verification approach and escalation trigger. Do not add approval ceremonies to
minor work or repeat recommendations when the task and selected setting are stable.

Example: “Recommend Sol High: this shared table change affects preferences and
exports. Verify those contracts and rendered phone/desktop layouts; escalate if
the compatibility decision remains unresolved.” A recommendation is not a switch.

## Escalation and error handling

Reassess when requirements or standards conflict, a business invariant is unclear,
scope spreads across shared components, or a correction fails to resolve the same
problem. Do not repeat speculative patches: after two unsuccessful correction
attempts, return to evidence and recommend a stronger review if still unresolved.
Escalate immediately for an unexplained permission, financial or inventory risk.

First check missing context, stale handoffs, source/runtime mismatch, incomplete
reproduction and missing verification. Higher effort cannot supply absent evidence
or decide unapproved business rules. Narrow the task or resolve the actual blocker;
do not buy more reasoning merely to continue guessing.

When recommending escalation, preserve a concise handoff: task ID/scope, current
evidence, attempts/results, affected files, unresolved question and next check.
Do useful authorized independent work while awaiting a model decision, but do not
proceed with dependent high-impact changes without the required evidence/approval.
If the user keeps the current model, respect that choice and report limitations.

## Review and verification

Follow [Principal Engineer standards](ai-roles/principal-engineer.md) for technical
verification and [Creative Director standards](ai-roles/creative-director.md) for
UX proposals and actual rendered review. Model choice does not transfer ownership
between roles or replace product-owner approval.

Require a separate review pass for high-impact work affecting calculations,
inventory eligibility, tenant/permission boundaries, migrations or marketplace
safety, and for broad shared UX/architecture changes. Prefer an independent
reviewer, with a stronger model when ambiguity warrants it. If independent review
is unavailable, report that gap before acceptance; self-review must not be labelled
independent. This requirement does not authorize spawning agents or messaging other
chats; use only separately authorized coordination.

Review the requirements and actual diff/evidence, not just the implementer's
summary. Relevant tests remain necessary regardless of model; UI claims require
rendered checks. Record unverified states and blockers honestly. Approval,
implementation, technical verification, rendered review, owner acceptance and
deployment remain separate facts. Do not rerun unrelated checks merely to satisfy
a model tier, or waive a required check because a stronger model was used.

## Learn from project results

For substantial tasks, add a compact entry to the existing task handoff when that
document update is within scope: actual model/version and effort if known, task
type, verification result, defects/rework, and time/usage if available. Do not
invent missing telemetry or create extra logs for minor tasks. Read-only monitoring
does not gain permission to edit handoffs from this policy.

Compare representative task outcomes before changing defaults. Prefer fewer
escaped defects and less total rework over fast first responses. Update these
recommendations from observed results and user feedback, with policy approval;
do not change defaults automatically or retroactively label past work verified.

## Official reference basis

OpenAI's [model-selection guidance](https://developers.openai.com/api/docs/guides/model-selection)
recommends evaluating model/effort tradeoffs on representative tasks. Its
[reasoning guidance](https://developers.openai.com/api/docs/guides/reasoning)
explains that supported effort settings depend on the model and trade speed/token
usage against depth of reasoning. The project table above is our starting policy,
not a claim about account access, pricing or the exact meaning of an app preset.

## Owner-approved presentation hotfixes

For explicitly classified Tier 1 presentation hotfixes, use the manually dispatched
`Presentation hotfix` workflow: focused unit regressions, build/package, exact artifact
verification and production startup smoke only. Browser CI is not a smoke test and
is not a deployment gate for this lane. The owner performs UAT. Keep browser tests
and normal feature/release CI intact. Inventory, schema, authorization, calculations
and integration behavior changes are ineligible; use their normal verification path.
Do not escalate model/effort or add backup, UAT or broad test cycles unless the owner asks.
