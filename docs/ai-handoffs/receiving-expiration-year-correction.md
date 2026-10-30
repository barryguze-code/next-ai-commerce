# Receiving expiration-year correction — October 29, 2026

Status: source prevention implemented and independently reviewed; 14 focused Java and 11 JavaScript tests pass. Source patch not yet deployed. Exact two-record production repair completed and verified separately.

## Cause and prevention

Production receipts stored 0027-07-14 and 0027-08-10. Expiry calculations used the stored year correctly; two-digit labels hid the century. Receiving accepted ancient years, and the shared date helper did not reject loaded/programmatic/native-picker ancient values. The historical entry mechanism is not proven. Ordinary short input 07/14/27 correctly becomes 2027-07-14.

The patch enforces the existing entry contract, years 2000–2099, before receiving, target expiry corrections, positive adjustments and physical-count inputs write data. Missing dates remain subject to existing requirements. Genuine expired dates retain their meaning. Old malformed source dates and negative cleanup remain usable for explicit correction. No automatic century conversion, FEFO/quantity algorithm, schema or integration changes.

Inventory, ledger, Receiving and date-input labels expose anomalous years in full; ordinary valid-date appearance is unchanged. Changed script cache references advance. Tests reproduced the failure before correction: 14 Java and 11 JavaScript tests now pass, including actual write boundaries before JDBC, Thymeleaf expressions and input DOM fixtures. Fixtures are not actual Chrome rendering of the new runtime. No local services restarted.

## Exact production correction

Owner explicitly confirmed these two dates mean 2027. Independently reviewed read-only prerequisites: documents open, false credits OPEN and unsubmitted, untouched batches, no active reservations, original costs/currency/packs verified. Supported Receipts / Undo then Receive actions retained original receipts and ledger history with audited reversals; no direct SQL writes.

| Item | Invoice | Correct date | Quantity | Cost / each | Replacement receipt |
| --- | --- | --- | --- | --- | --- |
| 440818 Earth Balance | 6013551 | 2027-07-14 | 6 | USD 12.4600 | 5e9a8279-d8e2-4218-9557-00acdad70fb5 |
| 94018 Old Croc | 6909522 | 2027-08-10 | 12 | USD 4.1700 | d06e4e2b-c9fd-4ad4-b8ff-3d00ebbc928f |

Both false EXPIRED credits CANCELLED, original quantities retained in history. Physical totals remain 6 and 36. Existing valid Old Croc August batch stays intact; separate corrected receipt makes that date position 24. MAIN location, case packs 6/12, zero fees, currency and provisional costs preserved. No catalogue defaults changed. No other ancient dates repaired.

Read-only prerequisite command 6287db4d-6ec3-4c8a-8708-9ddb95a386fd; first correction verified by 64c41602-b97f-4388-856c-6518a90433f4 before the second; final verification e9e600b2-2e7f-4d77-a278-67ab1838155b. Evidence: .local/expiry-review/, /tmp/receiving-two-corrections-verified.txt, /tmp/earth-balance-expiry-corrected.png, /tmp/old-croc-expiry-corrected.png. First Undo attempt reported lock contention/no change; fresh read then one retry succeeded. Undo and Receive were separate transactions, not claimed atomic together.

## Delivery authority and recovery

PM relayed direct owner standing instructions: “Ok, Let's start hotfixes in quicker way, we can deploy without back up, even leave me the UAT to me.” and “remind hotfix standards, no back up, no UAT etc, just a deploy with unit tests”. Current owner request: “Could you also fix these, while receiving the items ending with 27 which is the year 2027 shows expired, there must be a glitch there to save expiration date at receiving”. Interpretation: deliver this narrow reviewed production fix using required CI/artifact/health checks without a new backup or local UAT cycle. Exact data repair was separately reviewed. Unrelated work/settings remain protected.

Candidate starts at deployed 1.10.0 revision 6f6556790c39ba3c867d401447a42dd032a800d5. Preserve its rollback artifact and configuration, no schema or flag change. Code rollback must not undo corrected data. Reversing a replacement receipt requires fresh downstream-use/reservation checks and forward review; never blanket restore.

Shopify publication and order inventory activation remain OFF; owner-managed Egglife remains Unmapped. Existing Amazon publisher is preserved and may publish corrected eligibility under its existing policy. No manual publication triggered; downstream acknowledgement is not claimed.
