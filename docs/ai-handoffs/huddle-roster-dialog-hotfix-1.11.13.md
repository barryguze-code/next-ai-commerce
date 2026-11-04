# Collaboration roster and finalization follow-up 1.11.13

Status: local source/fixture correction, no publication or deployment yet. Production remains 1.11.12. Owner confirmed account eligibility must remain enforced.

## Confirmed evidence

Production read-only diagnostic command `24603046-a089-4acf-8f38-48d4f8650fe2` confirmed bguzelordu has KARACA ADMIN membership only. Existing Barry/ibcorellc/ecem room belongs to Ibcore LLC. Global teammate presence uses shared accounts, while room PEOPLE and INVITE require room-account eligibility. Thus the named missing teammate is an account-boundary difference, not solely roster staleness. Do not bypass room authorization. Owner confirmed only teammates with access to the collaboration account should be available. No cross-account bypass is authorized.

## UI correction

Every Add teammate click keeps the direct list open and sends PEOPLE again. Native select remains removed. Explicit roster reads and invitations now revalidate current account access for each distinct live user instead of waiting for that user’s heartbeat cache. A deterministic access-grant-before-heartbeat regression failed before the fix; immediate revocation exclusion is tested. Authorization requirements remain unchanged.

Shared assignment popover now uses the browser top layer, following existing table overlay convention, fixed positioning with viewport clamp and internal scrolling. No arbitrary z-index increase. Shared draft and existing conversation management both initialize it. Closing dialog closes the overlay; Escape returns focus to assignment summary. Conversation history uses existing escaped message renderer read-only. Hide composers only during finalization. One primary Save in the header; Cancel returns to existing live room/draft.

## Verification

Four focused browser cases passed, including history visibility, assignment overlay at1280x500, five-person limit, direct refresh, draft/save/error/timeout and existing picker paths. History regression failed on previous implementation before the fix. CD reviewed1280x500 fixture and approved layout, with metadata spacing correction applied. Browser tests use deterministic fixtures and no real messages or API writes. CD visual review and independent source review passed. Publication remains pending owner scoped commit approval. No schema, inventory or marketplace integration changes.

## Non-save close path

CD selected Leave without saving as an explicit secondary footer action, with clear copy that others may continue. It uses existing LEAVE for the actor only, not END or SAVE. Cancel/X/Escape return to the room. Wait for joined-participant removal acknowledgement; matching error, offline or timeout retains recoverable dialog. Existing creator-only save and server leave retention policies remain unchanged. No messages or historical records are deleted.
