# Retained catalogue product removal

Deployed as 1.14.14 on 2026-11-13. Both catalogue removal reviews distinguish already archived/inactive products with dependencies from unused products. Unused products offer permanent red Delete product confirmation. Retained products with dependencies return BLOCKED instead of repeating Archive/Inactive, with a visible reason. Unverifiable dependency checks block deletion with a retry message. Submission rechecks eligibility.

CD reviewed rendered DELETE and BLOCKED dialogs in light and dark themes and passed both. The existing compact dialog is preserved; Cancel remains enabled.

Verification: all 19 focused Java database/access checks passed in the isolated release and CI. JavaScript syntax, packaging and startup smoke passed. Production serves the exact versioned JavaScript and CSS from the verified CI artifact. No real product deletion was performed. No database backup or migration was performed; schema fingerprint remained unchanged.

Release revision: 86443a8766b734a1e5ff47dc48ddf40a98cc237f
CI: https://github.com/barryguze-code/next-ai-commerce/actions/runs/43532796933
Artifact SHA256: bd64c67b279a16a1430df77060d3b369fbda2a5c5b7a41a6245ebfcd65b203b8
Deployment command: d662d56b-79db-4eb1-9a11-0d38081916d4 (Success)
Production PID: 1699559

Owner performs production UAT. Existing production integration settings and prior fixes were preserved.

## Prepared 1.14.15 correction

The 1.14.14 dependency probe requested row_security=off, which fails under the non-bypass production role. The correction reads through existing scope policies across accounts/parents, restoring context with a savepoint, and fails closed for unrecognized policies or missing permissions. The existing internal collaboration queue context is used only for the read-only dependency check. Parent-directory coverage requires the caller to be an active platform administrator and an active member of each parent; no other user identity is substituted. Role-inapplicable, restrictive directory and altered-GUC policies fail closed. No inventory, mappings or history are rewritten.

CD passed 16 rendered catalogue photo cases: both catalogues, themes, landscape/portrait and editable/view-only. Square 80px frames contain the full product artwork. This release starts from deployed 1.14.14 and preserves its features. Production deployment pending scoped publication approval. No migration or database backup planned.

Verification for 1.14.15: 22 focused Java/database/access checks passed, including the fully migrated schema with a restricted runtime role, a hidden polymorphic task dependency, context restoration, altered-GUC policies and non-inherited policy grants. CD rendered review passed for both themes and catalogues. No real product deletion or production inventory mutation was tested.
