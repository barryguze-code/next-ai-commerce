# Collaboration teammate hotfix 1.11.12

Status: implemented in isolated worktree; publication and deployment pending.

Includes approved 1.11.11 shared finalization correction, which is published but not deployed. Production remains 1.11.10.

## Confirmed causes and correction

- Open rooms retained an obsolete ROOM_PEOPLE list after PRESENCE changed. A deterministic browser regression failed on the old code. Refresh the authorized room roster when presence changes and when opening @ suggestions. Do not substitute the broader global directory for room eligibility.
- Three-person server/client caps prevented adding a fourth. Owner approved five total, including self. Enforce five server-side and four invitees in initial selection. At capacity, existing members remain mentionable; additional invite actions explain the limit.
- Replace native footer select with the existing styled teammate list directly from Add teammate. Preserve focus across asynchronous roster replacement, Escape dismissal and composer draft. Use one existing document handler instead of retained per-room listeners.

## Evidence and limits

On 2026-11-04, authenticated production Barry directory and Online picker both included ibcorellc. Owner identified bguzelordu@gmail.com and barry.guze@gmail.com as affected viewers. Earlier missing-user state was not reproduced; do not claim a membership or identity correction. Self is intentionally omitted from own picker. Shared-account/room authorization is unchanged.

## Verification

13 focused Java service/WebSocket checks passed: five-person creation, sixth rejected with original room intact, exact selection, cross-account visibility and revocation. Four browser cases passed: direct list, scoped refresh after presence, mentions at capacity, focus restoration, existing picker and finalization regression paths. Tests use synthetic browser fixtures, not live message submission. CD approved the narrow reuse direction; independent engineering re-review passed after focus/lifecycle corrections.

No schema, inventory, marketplace integration or account-grant changes. No database backup, migration or production mutation performed for this follow-on yet. Use focused finalization CI including the new presence regression, exact artifact verification and startup smoke. Keep previous receipt/performance changes intact. Roll back the complete app artifact if startup fails; no data rollback required by this delta.
