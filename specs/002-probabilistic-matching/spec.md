# Feature Specification: Probabilistic Matching

**Feature Branch**: `002-probabilistic-matching`

**Created**: 2026-07-10

**Status**: Draft

**Input**: User description: "Add probabilistic (fuzzy) matching to GuestGraph per the approved design doc at docs/superpowers/specs/2026-07-10-probabilistic-matching-design.md (slice 2). Rule-based fuzzy matcher behind the existing strategy interface; blocking-key candidates; three per-tenant score bands with auto-merge shipped off; per-signal score breakdowns; persistent negative match rules; identifier quality rules incl. masked OTA emails; per-tenant matching config API. Existing contracts unchanged."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Likely Matches Surface for Review (Priority: P1)

Slice 1 only connects records that share an exact strong identifier. Real guest data is dirtier: "Anna Müller, born 12.03.1985, +41 79 …" from the PMS and "Anna Mueller, 12.03.1985" from the wifi portal share no identifier, so today they remain two guests forever. With probabilistic matching, the system finds such likely-same-person candidates, scores the similarity of their names, birthdates, phones, emails, and addresses, and — unless the tenant has explicitly enabled automatic merging above a confidence threshold — parks them in the existing review queue. The steward sees *why*: a per-signal breakdown ("name similarity 0.94, same birthdate, no conflicting data → score 0.88") and confirms or rejects as in slice 1.

**Why this priority**: This is the slice's reason to exist — recovering the duplicates that exact matching cannot see, without ever risking a silent wrong merge.

**Independent Test**: Ingest record pairs with similar-but-not-identical data and no shared identifier; verify a scored review entry appears with a signal breakdown, and that no merge happens automatically under default configuration.

**Acceptance Scenarios**:

1. **Given** a guest "Anna Müller" with birthdate 1985-03-12, **When** a record "Anna Mueller" with the same birthdate and no shared identifier is ingested, **Then** no automatic merge occurs and a review entry is created whose score and per-signal breakdown are visible to the steward.
2. **Given** a pending fuzzy-match review, **When** the steward confirms it, **Then** the guests merge and the decision is recorded with the fuzzy matcher's name and its score as confidence.
3. **Given** two records with a weakly similar name and nothing else in common, **When** the second is ingested, **Then** the score falls below the review floor and no review entry is created — the queue is not flooded with noise.
4. **Given** a tenant that has raised its automation trust and set an auto-merge threshold below 1.0, **When** a candidate scores at or above that threshold, **Then** the merge executes automatically and its event records the fuzzy matcher name, the score as confidence, and the per-signal breakdown.
5. **Given** default configuration (auto-merge off), **When** any candidate scores 0.99, **Then** it still goes to review — no fuzzy merge ever happens without explicit tenant opt-in.
6. **Given** records whose exact identifiers already match, **When** they are ingested, **Then** resolution behaves exactly as in slice 1 (deterministic merge, confidence 1.0) — fuzzy scoring only considers what determinism left undecided.

---

### User Story 2 - Steward Splits Stick (Priority: P2)

In slice 1, when a steward unmerges a wrong merge or rejects a queued match, nothing stops the next piece of shared evidence from silently re-merging the pair. Now every unmerge and every rejection automatically writes a persistent "do not merge" rule between the affected records' clusters. Any future would-be merge across such a rule — even one backed by an exact shared identifier — is downgraded to a review entry instead. The steward's decision stands until a human explicitly confirms otherwise or lifts the rule.

**Why this priority**: Without it, fuzzy matching amplifies the existing weakness — the queue re-suggests pairs a human already rejected, and corrections silently evaporate. This closes roadmap requirement R2-1.

**Independent Test**: Unmerge a pair, ingest fresh evidence connecting them, verify the connection is parked for review instead of merged; reject a review, verify the same pair is not re-queued by the same evidence.

**Acceptance Scenarios**:

1. **Given** a steward has unmerged record B from guest A, **When** a fresh record sharing an exact identifier with both sides is ingested, **Then** the sides are not silently merged — the candidate is parked for review citing the do-not-merge rule.
2. **Given** a steward has rejected a queued match, **When** the same records would match again (fuzzy or exact), **Then** no silent merge occurs and no duplicate pending review for the same pair accumulates.
3. **Given** a parked candidate that was downgraded by a do-not-merge rule, **When** the steward confirms it, **Then** the merge executes and the rule is lifted — an explicit human confirmation supersedes the earlier split.
4. **Given** existing do-not-merge rules, **When** the steward lists them, **Then** each shows its origin (unmerge, rejection, or manual) and can be deleted; after deletion the affected pair may merge again on its next evidence.

---

### User Story 3 - Shared and Masked Identifiers Stop Causing Wrong Merges (Priority: P3)

Some identifiers are technically valid but untrustworthy. Online travel agencies mask guest emails behind relay addresses (e.g. `xyz123@guest.booking.com`) — some masking schemes reuse one address across *different* people, and relay addresses expire after the stay. Agencies and offices share booking phones and emails across many guests. The tenant can now declare identifier quality rules: *ignore* rules (the identifier never connects guests), *perfect match* rules (it may connect guests only when the names agree exactly, otherwise review), and *masked alias* recognition by email domain — shipped with a built-in list of known OTA relay domains. A masked email never merges guests on its own, contributes at most a weak "same alias" hint that routes to review, and never replaces a real email address in the golden profile.

**Why this priority**: These are the classic wrong-merge and profile-pollution sources proven in production hospitality data; fuzzy matching would make them worse without these controls.

**Independent Test**: Ingest two different guests sharing one masked OTA email; verify two guests result, at most a review entry appears, and neither profile shows the masked address over a real one.

**Acceptance Scenarios**:

1. **Given** two records with different names and birthdates sharing one masked OTA relay address, **When** both are ingested, **Then** they resolve to two guests; the shared alias alone produces at most a review entry, never a merge.
2. **Given** a guest whose profile has a real email, **When** a newer record arrives carrying only a masked relay address, **Then** the golden profile keeps the real email; the masked address is stored on the record and marked as masked.
3. **Given** a tenant ignore-rule on a shared agency phone number, **When** records carrying that phone are ingested, **Then** the phone connects nothing — records resolve as if the phone were absent (it remains stored on the records).
4. **Given** a perfect-match rule on an email, **When** a record shares that email with a guest but the names differ, **Then** the candidate is parked for review instead of merged.
5. **Given** the built-in OTA domain defaults, **When** a tenant lists its identifier quality rules, **Then** the built-ins are visible and distinguishable from tenant-added rules.

---

### User Story 4 - Tenant Tunes Matching Behavior (Priority: P4)

An operator adjusts how aggressive matching is for their tenant through the API: the review floor (below which candidates are ignored), the auto-merge threshold (above which fuzzy matches merge without review — shipped fully off), and the slice-1 identifier-sharing threshold, all readable and writable in one place. Changes apply to subsequent resolutions without restart or migration.

**Why this priority**: The three-band policy is only safe *and* useful if tenants can move the bands as trust grows; without the API the thresholds are dead configuration.

**Independent Test**: Read the default config, change thresholds via the API, verify the next ingest routes candidates according to the new bands.

**Acceptance Scenarios**:

1. **Given** a fresh tenant, **When** its matching configuration is read, **Then** it shows auto-merge off (threshold 1.0), the default review floor, and the default sharing threshold.
2. **Given** an updated review floor, **When** the next candidate scores between old and new floor, **Then** it is routed per the new value.
3. **Given** an invalid configuration (floor above auto-merge threshold, or values outside 0..1), **When** submitted, **Then** it is rejected with a problem-details error and the previous configuration stays in effect.
4. **Given** configuration changed by tenant A, **When** tenant B resolves records, **Then** tenant B's behavior is unaffected.

---

### Edge Cases

- Two records match the same guest — one via exact identifier, one via fuzzy score: determinism wins; fuzzy neither duplicates the decision nor creates a parallel review for the already-matched guest.
- A candidate's score lands exactly on a band boundary: at-threshold belongs to the higher band (≥ semantics), consistently for both thresholds.
- Names in different scripts or with diacritics ("Müller" / "Mueller" / "MULLER"): treated as equivalent for similarity scoring.
- A record with no name and no birthdate: too few signals — fuzzy produces no candidate rather than a garbage score.
- The same fuzzy pair is re-encountered while its review is still pending: no duplicate review entries accumulate.
- A do-not-merge rule references records whose clusters have since merged with others: the rule follows the records (records are permanent), not the transient guests.
- Both a masked-alias hint and a do-not-merge rule apply to the same pair: the rule wins — nothing is queued repeatedly for a pair a human already split; the pair stays split.
- A typo in the name *and* a different phone number: the pair may not be found at all (candidate generation has recall limits) — accepted for this slice; a future backfill scan is the recall catcher.
- Deleting a built-in OTA domain rule: built-ins cannot be deleted, only tenant-added rules can.

## Requirements *(mandatory)*

### Functional Requirements

**Candidate discovery & scoring**

- **FR-001**: The system MUST discover likely-same-person candidates that share no exact identifier, using similarity-oriented keys (name phonetics with birth year, name initials with birthdate, phone endings, email name-part, masked alias) computed at ingest and stored immutably with the record.
- **FR-002**: Each candidate MUST receive a similarity score between 0 and 1 combining at least: name similarity (diacritic-insensitive), birthdate agreement, phone-ending agreement, email similarity, and — when present — address hints.
- **FR-003**: Every scored decision MUST carry a per-signal breakdown (which signals contributed what), visible in review entries and recorded in the merge audit trail when a merge executes.
- **FR-004**: Deterministic exact-identifier resolution MUST remain unchanged and take precedence; fuzzy scoring only evaluates candidates determinism did not already decide.

**Banding & merge policy**

- **FR-005**: Each tenant MUST have two thresholds forming three bands: score ≥ auto-merge threshold → merge automatically; score ≥ review floor → park in the existing review queue; below → discard. At-threshold scores belong to the higher band.
- **FR-006**: The auto-merge threshold MUST ship at 1.0 for every tenant (automatic fuzzy merging off) and only take effect through explicit tenant configuration.
- **FR-007**: Automatic fuzzy merges MUST be recorded like any merge: matcher name, score as confidence, evidence with the signal breakdown — fully explainable and reversible via the slice-1 machinery.
- **FR-008**: Fuzzy candidates parked for review MUST flow through the existing review queue and confirm/reject contract unchanged.

**Negative match rules**

- **FR-009**: Every unmerge and every review rejection MUST automatically create a persistent do-not-merge rule between the affected records' clusters, keyed by permanent record identity.
- **FR-010**: A do-not-merge rule MUST downgrade any would-be merge across it — fuzzy or exact-identifier — to a review entry citing the rule; no silent merge may ever cross a rule.
- **FR-011**: Confirming a review that was downgraded by a do-not-merge rule MUST execute the merge and lift the rule (explicit human confirmation supersedes the earlier split).
- **FR-012**: Users MUST be able to list do-not-merge rules (with origin: unmerge, rejection, manual) and delete them; deletion re-enables normal matching for the pair.

**Identifier quality rules & masked aliases**

- **FR-013**: Tenants MUST be able to declare identifier quality rules matching an exact identifier value or an email domain, with three effects: IGNORE (never connects guests), PERFECT_MATCH (connects only with exact name agreement, otherwise review), MASKED_ALIAS (email domains: excluded from exact matching, weak review-only similarity hint, profile guard).
- **FR-014**: The system MUST ship with built-in MASKED_ALIAS rules for known OTA relay domains, active for every tenant, visible in rule listings, and not deletable by tenants.
- **FR-015**: A masked email MUST never connect guests on its own, and MUST never replace a real email address in the golden profile — it fills the email field only when no real address exists, marked as masked.
- **FR-016**: Records affected by quality rules MUST still be stored in full (rules change matching and profile derivation, never what is kept — no parseable data is dropped).

**Configuration**

- **FR-017**: Tenants MUST be able to read and update their matching configuration (auto-merge threshold, review floor, identifier-sharing threshold) and manage identifier quality rules through the API; changes apply to subsequent resolutions without restart.
- **FR-018**: Configuration validation MUST reject inconsistent values (review floor > auto-merge threshold, values outside 0..1) with a problem-details error, leaving the previous configuration in effect.

**Isolation & compatibility**

- **FR-019**: All new data and behavior (candidates, rules, configuration) MUST be tenant-scoped like everything else; no rule or threshold of one tenant may affect another.
- **FR-020**: All existing API contracts (ingest, guests, explain, unmerge, match reviews) MUST remain unchanged; slice-1 behavior for exact matches MUST be fully preserved under default configuration.

### Key Entities

- **Similarity Key** (block key): a matching-oriented derivative of a source record (phonetic name + birth year, initials + birthdate, phone ending, email name-part, masked alias) — immutable, computed at ingest, the means of finding fuzzy candidates.
- **Do-Not-Merge Rule**: a persistent steward decision that two records' clusters must not be silently merged; carries origin (unmerge / rejection / manual) and lifecycle (created automatically, lifted by confirmation or deletion).
- **Identifier Quality Rule**: a per-tenant (or built-in) declaration about an identifier value or email domain: IGNORE, PERFECT_MATCH, or MASKED_ALIAS.
- **Matching Configuration**: per-tenant thresholds — auto-merge threshold (default: off), review floor, identifier-sharing threshold (from slice 1).
- Existing entities (Guest, Source Record, Match Review, Merge Event) are reused unchanged; fuzzy decisions appear as new matcher names, sub-1.0 confidences, and richer evidence.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a reference corpus of same-person variant pairs (diacritics, name-order swaps, typos, missing fields) sharing no exact identifier, at least 90% surface as review entries or merges; different-person near-miss pairs in the corpus produce zero automatic merges.
- **SC-002**: Zero silent merges: 100% of executed merges are backed by an exact identifier, a score at or above the tenant's explicitly configured auto-merge threshold, or a human confirmation.
- **SC-003**: 100% of steward splits stick: after any unmerge or rejection, no evidence silently re-merges the pair; every attempted re-merge appears as a review entry citing the rule.
- **SC-004**: Zero merges caused by masked OTA relay addresses alone, and zero golden profiles where a masked address replaced a real email.
- **SC-005**: 100% of fuzzy review entries and fuzzy merge events expose a per-signal score breakdown a steward can read.
- **SC-006**: Single-record ingest stays within the slice-1 budget (under 1 second under normal load) with fuzzy matching active.
- **SC-007**: Configuration changes take effect on the next resolution — no restart, no migration, no cross-tenant effect.
- **SC-008**: The full slice-1 reference corpus still passes unchanged under default slice-2 configuration (no behavioral regression for exact matching).

## Assumptions

- Signal weights and the default review floor (0.75) are fixed during implementation by tuning against the golden-pair corpus; they are system-wide in this slice (only thresholds are per-tenant).
- Candidate discovery has known recall limits (a typo'd name plus a changed phone may not be found); accepted for this slice — the deferred duplicate-scan backfill is the recall catcher.
- Name normalization targets Latin-script names with diacritic folding; non-Latin scripts pass through unscored rather than mis-scored.
- Birthdate arrives as a payload field in ISO date form where sources have it; records without it simply contribute fewer signals.
- The built-in OTA relay domain list is maintained with the product (shipped defaults); tenants can add their own domains but not remove built-ins in this slice.
- Do-not-merge rules are expected to number at most in the hundreds per tenant; no pagination beyond the existing queue conventions is needed yet.
- Manual creation of do-not-merge rules (origin MANUAL) is supported via the same storage but has no dedicated API endpoint in this slice; unmerge and rejection are the writers.
