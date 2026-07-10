---

description: "Task list for feature 002-probabilistic-matching"
---

# Tasks: Probabilistic Matching

**Input**: Design documents from `specs/002-probabilistic-matching/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, quickstart.md

**Tests**: INCLUDED — constitution Principle VI mandates TDD for resolution-engine work.
The scorer, banding, both gates, and block-key derivation are engine logic: their ⚠ test
tasks MUST be written and seen FAILING before the implementation tasks. Every new endpoint
ships with integration tests (workflow gates).

**Organization**: grouped by user story; each story is an independently testable increment.
Slice-1 behavior is regression-guarded throughout (SC-008): the existing suites must stay
green after every phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no dependency on incomplete tasks)
- **[Story]**: US1–US4 from spec.md
- Paths relative to repository root (single Maven module)

## Phase 1: Setup

- [ ] T001 Add `org.apache.commons:commons-text` and `commons-codec:commons-codec` to `pom.xml` (versions from Spring Boot BOM where managed, else latest release per project convention)

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work until this phase completes.

- [ ] T002 Write additive Flyway migration `src/main/resources/db/migration/V2__probabilistic_matching.sql` per data-model.md: `record_block_key`, `negative_match_rule` (ordered-pair CHECK, both-side indexes), `identifier_quality_rule`, `tenant.auto_merge_threshold`/`review_floor` columns with defaults and `review_floor <= auto_merge_threshold` CHECK
- [ ] T003 [P] Create domain types in `src/main/java/io/guestgraph/domain/`: `BlockKeyType`, `NegativeRuleOrigin`, `RuleMatchKind`, `RuleEffect` enums; `NegativeMatchRule`, `IdentifierQualityRule`, `MatchingConfig`, `MatchSignals` (per-signal breakdown) records; extend `resolution/MatchCandidate` with a candidate origin (IDENTIFIER vs BLOCK_KEY + key type/value + candidate-guest profile snapshot)
- [ ] T004 [P] ⚠ Write failing unit tests `src/test/java/io/guestgraph/normalize/NameNormalizerTest.java` and `BlockKeysTest.java`: diacritic folding (Müller→muller), casing; key derivations per data-model.md (phonetic+birthyear requires both, initials+birthdate, PHONE_SUFFIX7, EMAIL_LOCALPART only for real emails, EMAIL_MASKED full alias)
- [ ] T005 Implement `src/main/java/io/guestgraph/normalize/NameNormalizer.java` (Normalizer NFD + strip marks) and `BlockKeys.java` (Double Metaphone via commons-codec) until T004 is green
- [ ] T006 Create persistence for the three new tables in `src/main/java/io/guestgraph/persistence/` (entities, `@Query`-only repos passing the ArchUnit guardrails, MapStruct mappings): `RecordBlockKeyEntity/Repo`, `NegativeMatchRuleEntity/Repo` (cluster-membership query joining resolution_link, spanning-pair lift/delete), `IdentifierQualityRuleEntity/Repo`; tenant repo/query additions for the two new columns
- [ ] T007 Extend `src/main/java/io/guestgraph/resolution/GraphPort.java` + `src/test/java/io/guestgraph/resolution/InMemoryGraph.java` + `src/main/java/io/guestgraph/persistence/PostgresGraph.java`: block-key candidate lookup (guests via linked records sharing a key), guest profile fetch, matching config read, negative-rule query/create/lift, quality-rule read (tenant rules merged with built-in constants)

**Checkpoint**: schema + ports ready; `./mvnw verify` green (slice-1 suites untouched)

---

## Phase 3: User Story 1 - Likely Matches Surface for Review (Priority: P1) 🎯 MVP

**Goal**: fuzzy candidates found via block keys, scored with per-signal breakdown, routed
into three per-tenant bands with auto-merge shipped off; deterministic precedence intact.

**Independent Test**: ingest similar-but-not-identical pairs sharing no identifier →
scored review entry with breakdown, no automatic merge under defaults (spec US1).

### Tests for User Story 1 (TDD — write first, see them FAIL) ⚠

- [ ] T008 [P] [US1] ⚠ Write failing golden-pair scenario corpus `src/test/java/io/guestgraph/resolution/FuzzyScenarioTest.java` (pure JVM on InMemoryGraph): same-person variants (diacritics, name-order swap, typo, missing birthdate) surface as REVIEW; different-person near-misses (family sharing phone, common name different birthdate) score below floor or park, never auto-merge; band routing incl. at-threshold-≥ semantics; birthdate-conflict hard penalty; no candidate without name + one other signal; **invariant: no auto-merge below the configured threshold**; deterministic exact match wins with no parallel fuzzy review (US1-AS6)

### Implementation for User Story 1

- [ ] T009 [US1] Implement `src/main/java/io/guestgraph/resolution/FuzzyMatcher.java` (`fuzzy-rules-v1`) + `MatchSignals` scoring: weighted average (name .45 / birthdate .25 / phone .15 / email .10 / address .05) with renormalization over available signals, birthdate-conflict penalty, masked-alias bonus capped below auto-merge — until T008 scorer cases are green
- [ ] T010 [US1] Implement `src/main/java/io/guestgraph/resolution/CompositeStrategy.java` and `MatchingPolicy.java`; extend `ResolutionEngine` candidate collection with deduplicated block-key candidates (profile snapshots attached) — deterministic decides first, fuzzy scores the rest, bands route MATCH/REVIEW/discard — until T008 is fully green
- [ ] T011 [US1] Extend `src/main/java/io/guestgraph/ingest/RecordExtractor.java` + `persistence/SourceRecordStore.java` to derive and insert block keys at ingest (birthdate parsed from payload ISO field; keys per data-model.md)
- [ ] T012 [US1] Wire the per-signal breakdown into review reasons and MergeEvent evidence (engine decision → `MatchReview.reason` JSON summary, `merge_event.evidence.signals`) so explain and the queue show why a score is what it is (FR-003)
- [ ] T013 [US1] Write integration tests `src/test/java/io/guestgraph/integration/FuzzyReviewApiTest.java` covering spec US1 acceptance scenarios 1–6 end to end (default no-auto-merge, confirm records `fuzzy-rules-v1` + score as confidence, threshold-enabled auto-merge, breakdown visible via API)

**Checkpoint**: MVP — fuzzy candidates in the queue with explainable scores, automation opt-in only

---

## Phase 4: User Story 2 - Steward Splits Stick (Priority: P2)

**Goal**: unmerge/reject write persistent do-not-merge rules; any merge crossing a rule —
fuzzy or exact — downgrades to review; confirm lifts; rules listable/deletable.

**Independent Test**: unmerge a pair, ingest connecting evidence → parked citing the rule,
never silently merged (spec US2).

### Tests for User Story 2 (TDD — write first, see them FAIL) ⚠

- [ ] T014 [P] [US2] ⚠ Extend `FuzzyScenarioTest`/`ResolutionScenarioTest` with failing rule scenarios: unmerge writes rules (detached × remaining records); fresh exact-identifier evidence across a rule → REVIEW citing the rule, not MERGE; reject writes rules and the same pair is not re-queued while pending; confirm across a rule executes the merge and lifts the spanning rules (FR-011); deleted rule → next evidence merges normally

### Implementation for User Story 2

- [ ] T015 [US2] Implement `src/main/java/io/guestgraph/resolution/NegativeRuleGate.java` applied in the engine to every decision (deterministic included); rule writers in `UnmergeOperation` and `ReviewDecisionOperation` (reject), rule lift on confirm — until T014 is green
- [ ] T016 [US2] Implement `GET/DELETE /api/v1/negative-rules` in `src/main/java/io/guestgraph/api/NegativeRuleController.java` (paging per contract) + integration tests `src/test/java/io/guestgraph/integration/NegativeRuleApiTest.java` covering spec US2 acceptance scenarios 1–4

**Checkpoint**: human splits survive all future evidence unless a human says otherwise

---

## Phase 5: User Story 3 - Shared & Masked Identifiers (Priority: P3)

**Goal**: IGNORE / PERFECT_MATCH / MASKED_ALIAS rules evaluated at matching time; built-in
OTA relay defaults; masked emails never merge alone and never displace a real profile email.

**Independent Test**: two different guests sharing one masked OTA address → two guests, at
most a review, profiles keep real emails (spec US3).

### Tests for User Story 3 (TDD — write first, see them FAIL) ⚠

- [ ] T017 [P] [US3] ⚠ Write failing scenarios (FuzzyScenarioTest + `src/test/java/io/guestgraph/survivorship/GoldenProfileDeriverTest.java`): IGNOREd identifier produces neither candidates nor merges (incl. identifiers written before the rule existed — matching-time semantics, R2-4); PERFECT_MATCH with differing names → REVIEW; shared masked alias with different persons → at most REVIEW, never merge; masked email never overwrites a real profile email and fills only when none exists (marked `emailMasked`)

### Implementation for User Story 3

- [ ] T018 [US3] Implement `src/main/java/io/guestgraph/resolution/QualityRuleGate.java` + candidate-stage filtering (IGNORE, masked-domain check for pre-existing EMAIL identifiers) + built-in OTA relay-domain constants — until T017 gate cases are green
- [ ] T019 [US3] Extend extraction + survivorship: masked-domain recognition in `RecordExtractor` (`emailMasked: true` in extracted, `EMAIL_MASKED` block key, no EMAIL identifier row) and the masked-email guard in `GoldenProfileDeriver` — until T017 is fully green
- [ ] T020 [US3] Implement `GET/POST/DELETE /api/v1/config/identifier-rules` in `src/main/java/io/guestgraph/api/IdentifierRuleController.java` (built-ins listed `builtin: true`, not deletable → 400) + integration tests `src/test/java/io/guestgraph/integration/QualityRuleApiTest.java` covering spec US3 acceptance scenarios 1–5

**Checkpoint**: the classic wrong-merge and profile-pollution sources are controlled

---

## Phase 6: User Story 4 - Tenant Tunes Matching (Priority: P4)

**Goal**: one API resource for the three thresholds; validated, transactional, effective on
the next resolution, tenant-isolated.

**Independent Test**: read defaults, change bands, next ingest routes accordingly (spec US4).

### Implementation for User Story 4

- [ ] T021 [P] [US4] Implement `GET/PUT /api/v1/config/matching` in `src/main/java/io/guestgraph/api/MatchingConfigController.java` with validation (0 ≤ floor ≤ auto ≤ 1, sharing ≥ 1 → RFC 9457 400, previous config untouched) backed by tenant repo updates
- [ ] T022 [US4] Write integration tests `src/test/java/io/guestgraph/integration/MatchingConfigApiTest.java` covering spec US4 acceptance scenarios 1–4 (defaults, live band change on next ingest, invalid combos → 400, tenant isolation)

**Checkpoint**: all four user stories independently functional

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T023 [P] Extend `src/test/java/io/guestgraph/contract/OpenApiConformanceTest.java` to union all `specs/*/contracts/openapi.yaml` files (R2-7) — two-way gate over the merged surface
- [ ] T024 [P] Update `docs/roadmap-notes.md` (mark R2-1 and identifier-quality rules as consumed by this slice) and `README.md` if the endpoint list is affected
- [ ] T025 Execute the full quickstart smoke walk (`specs/002-probabilistic-matching/quickstart.md`): all four story walks + SC spot checks, incl. SC-006 (< 1 s ingest with fuzzy active) and SC-008 (slice-1 suites green under defaults)

---

## Dependencies & Execution Order

- **Setup (1) → Foundational (2)**: blocks everything; T004⚠ before T005 (TDD)
- **US1 (3)**: after Foundational; T008⚠ before T009–T012; MVP checkpoint
- **US2 (4)**: after US1 (gate hooks into the engine paths US1 finishes); T014⚠ first
- **US3 (5)**: after US1 (gate + candidate filter build on CompositeStrategy); T017⚠ first;
  independent of US2 — US2/US3 can run in parallel tracks *except* both extend
  `FuzzyScenarioTest` (serialize edits to that file)
- **US4 (6)**: after Foundational only; parallel to US2/US3 (distinct files); note US1's
  T013 auto-merge case needs T021's PUT — schedule T021 before or beside T013 if pulled forward
- **Polish (7)**: last; T023/T024 parallel

### Parallel opportunities

- T003, T004 after T002 · T008 while T006/T007 finish (pure JVM, no DB) · US2/US3/US4
  tracks after US1 (file-sharing caveat above) · T023/T024

## Implementation Strategy

MVP first: Phases 1–3 (T001–T013) deliver P1 — fuzzy candidates with explainable scores in
the review queue, automation off. Stop, `./mvnw verify`, validate, then US2 → US3 → US4
in priority order. Commit after each task or logical group; slice-1 suites must stay green
at every checkpoint (SC-008).
