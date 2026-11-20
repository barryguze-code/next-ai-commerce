# Local Users & Access draft

Production is not deployed by this change. The current branch retains the v1.3.14 invitation-login fix.

- Header order on Users & Access: Invite user, Huddle, selected account/store.
- Huddle is positioned immediately before the selected-store picker on other pages.
- Overview owns the user portrait, standard picture pencil, collaboration, and access controls. No duplicate initial beside the name.
- Granted stores show compact names, marketplace, and connection status; the separate store count remains.
- Manage access changes ADMIN / OPERATOR / VIEWER and explicit grants within the selected account.
- Revoke account access removes only that account membership and its cascading store grants. The login and other memberships survive.
- Owners, platform administrators, and one's own membership cannot be changed through this dialog. The last active account administrator cannot be removed/demoted.
- Changes are transactional and audited. A tenant row lock serializes concurrent administrator changes.
- A request filter re-reads current membership before URL authorization, rejecting revoked account/store sessions and using the selected account's current role.
- Legacy administrators keep implicit access until their membership is explicitly managed. New invitations use their chosen grants. Migration V85 adds this distinction and USER account artwork.

Verification uses synthetic records in a disposable schema of `127.0.0.1:55432/next_ai_commerce_test`, never the local UAT or production database. Restart the application through Eclipse's Local UAT launcher after Java/schema changes.

2026-10-01 verification: full Java suite — 354 tests, zero failures/errors, one skipped. JavaScript syntax and whitespace checks passed. The inventory shared-controls source test was updated to inspect its extracted drawer fragment and script. Live visual verification is pending local browser sign-in after the restart; no real user's grants were changed during testing.

## Follow-up: compact stores and global profile pictures

- Each granted store occupies one compact unwrapped line: channel logo, store, marketplace, status dot with tooltip, and Revoke. Revocation requires confirmation, is tenant-scoped and audited, preserves other stores/accounts, and removes the membership if its last store is revoked. Protected/self and last-admin safeguards remain.
- Active platform administrators appear in every account's user list as protected SUPER ADMIN users, including when they have no tenant membership.
- V86 adds global user portraits and preserves visible existing account portraits. The sidebar pencil lets any active user upload their own picture, including viewers and platform administrators. Account administrators can edit ordinary members' portraits; ordinary administrators cannot edit a platform administrator's portrait. Reads of others' portraits require account access plus shared membership or a platform administrator target.
- The supplied Amazon Seller bitmap replaces Amazon channel icons in logged-in views. The Next AI Commerce brand is unchanged.
- Collaborate is a permanent sidebar link to `/app/collaboration`; the top Huddle icon remains next to the store picker.
- Follow-up verification: 33 targeted Java tests passed, including direct store revocation, protected platform-admin projection, global self portraits, and existing account/photo guards. Local application restart and visual verification remain necessary.
