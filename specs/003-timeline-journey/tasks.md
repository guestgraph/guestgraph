---

description: "Task list for 003-timeline-journey"
---

# Tasks: Guest Timeline & Attributed Decisions

**Input**: Design documents from `/specs/003-timeline-journey/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml)

**Tests**: Test tasks are included and are not optional here. Constitution Principle VI makes TDD
mandatory for engine work (US3 touches `ResolutionEngine`, `UnmergeOperation`, and
`ReviewDecisionOperation`), and the plan additionally schedules the association deriver test-first
because its rules — current vs ended, successor naming, dedup, ordering — are where the subtle
bugs live. Tasks marked ⚠ MUST be written and seen failing before the implementation task that
follows them.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1–US4, mapping to the spec's prioritised stories
- ⚠: failing-test task — run it, watch it fail, then implement

## Path Conventions

Single Maven module. Main code under `src/main/java/io/guestgraph/`, tests under
`src/test/java/io/guestgraph/`, migrations under `src/main/resources/db/migration/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The pure value types every later phase refers to. No new dependencies — the slice
needs no library the repository does not already carry.

- [ ] T001 [P] Create domain types in `src/main/java/io/guestgraph/domain/`: `ObjectRole` enum (PRIMARY_GUEST, ADDITIONAL_GUEST, BOOKER), `ActorType` enum (SYSTEM, HUMAN, AGENT), `Actor` record (type + id, with a `system(matcherName)` factory), `RecordObject` record per [data-model.md](data-model.md)
- [ ] T002 [P] Create pure timeline types in `src/main/java/io/guestgraph/timeline/`: `Association`, `AssociationStatus` (CURRENT, ENDED), `ObjectObservation` — plain records, no Spring and no JPA imports (the ArchUnit `onlyPersistenceDependsOnJpa` rule covers this package)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema and test-harness changes every story below depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete. T005 in particular is
not optional — the harness truncates an explicit table list, so a missing entry fails *every*
integration test on FK truncate errors.

- [ ] T003 Write additive migration `src/main/resources/db/migration/V3__timeline_and_actors.sql` per [data-model.md](data-model.md): `record_object` table (tenant-scoped, `object_version timestamptz NOT NULL`, role CHECK, denormalised `source_system_id`, roster and record indexes); `actor_type`/`actor_id` nullable columns on `merge_event` and `negative_match_rule`; `actor_type` NOT NULL DEFAULT 'HUMAN' + `actor_name` on `api_key`, backfilled from `label` before `SET NOT NULL`; `lifted_at`/`lifted_actor_type`/`lifted_actor_id` on `negative_match_rule`, replacing its `UNIQUE (tenant_id, record_a, record_b)` with a partial unique index `WHERE lifted_at IS NULL` so a pair may be split, lifted, and split again. No edits to V1 or V2
- [ ] T004 Update both `api_key` INSERT sites for the new NOT NULL column — `seedTenant` in `src/test/java/io/guestgraph/integration/PostgresIntegrationTest.java` and `src/main/java/io/guestgraph/config/LocalDevSeeder.java` — to supply `actor_name` (and `actor_type` where a non-default is wanted). Both use explicit column lists, so without this every integration test and local dev startup fails on a fresh database against V3
- [ ] T005 Add `record_object` to the `TRUNCATE` list in `src/test/java/io/guestgraph/integration/PostgresIntegrationTest.java` `resetDatabase`
- [ ] T006 Run `./scripts/regen-er.sh` and commit the regenerated `docs/er-schema.mmd` — CI's er-drift job re-runs it and fails on any difference
- [ ] T007 Establish the green baseline after the migration: `docker compose down -v` to clear the stale local volume (Flyway checksum mismatch otherwise), then `./mvnw verify` — the slice-1 and slice-2 suites must pass untouched against V3 before any story work starts (SC-008). V3 is additive except for the `negative_match_rule` unique constraint that T003 replaces with a partial index; that swap is what this baseline is really checking. The Phase 7 paging migration is the only later change to those suites, and it is deliberate

**Checkpoint**: Schema in place, harness green, story work can begin.

---

## Phase 3: User Story 1 - What Does This Guest Currently Have? (Priority: P1) 🎯 MVP

**Goal**: A resolved guest's business objects appear as associations — one entry per object and
role, showing the newest version's state, ordered by business dates.

**Independent Test**: Ingest three versions of one reservation and a second reservation with two
persons; read the timelines and verify each booking appears once per guest and role with the
newest version's data, while `GET /guests/{id}/records` is unchanged.

### Tests for User Story 1 ⚠

- [ ] T008 [P] [US1] ⚠ Write failing pure-JVM table-driven tests `src/test/java/io/guestgraph/timeline/AssociationDeriverTest.java`: one entry per (object, role) at the newest version; `observationCount` spans all versions; ordering by business start with the observation timestamp as fallback and a deterministic tie-break for stable paging; records with no object identity produce no association; a guest appearing twice in one role on one version yields one entry; a guest with no business-object observations yields an empty list; a version whose roster is still partial (2 of 3 persons landed) derives from what is present and invents nothing (FR-010a); the ordering key `(businessStart, objectId)` is total, so it can serve as a keyset cursor

### Implementation for User Story 1

- [ ] T009 [US1] Implement `src/main/java/io/guestgraph/timeline/AssociationDeriver.java` — roster grouping, current-entry emission, count, ordering — until T008 is green
- [ ] T010 [P] [US1] Create persistence for the new table in `src/main/java/io/guestgraph/persistence/`: `entity/RecordObjectEntity`, `repo/RecordObjectRepo` (`@Query`-only, every method tenant-scoped, no `JdbcClient` — the ArchUnit guardrails apply), MapStruct mapping. Queries: objects this guest has any observation of, and all observations of a given object set joined to their resolution links
- [ ] T011 [US1] Implement `src/main/java/io/guestgraph/persistence/TimelineQueryService.java`: the two tenant-scoped reads feeding `AssociationDeriver`, plus keyset cursor paging over the derived, ordered result. Put the encode/decode in a shared `src/main/java/io/guestgraph/api/Cursor.java` — T036 and T037 reuse it, so the three paged endpoints share one cursor format rather than hand-rolling three. The cursor encodes the last-seen ordering key, never an offset, so the read can later move into SQL without an API change (research R1 scale lever)
- [ ] T012 [US1] Extend ingest for object identity: `sourceObject` block on `IngestDtos.IngestRecordRequest` in `src/main/java/io/guestgraph/api/IngestDtos.java`; parsing and version validation in `src/main/java/io/guestgraph/ingest/RecordExtractor.java` (unparseable or absent version and a `recordTimestamp` that disagrees with it each add a `needs_review` reason, never a rejection); `record_object` insertion in `src/main/java/io/guestgraph/persistence/SourceRecordStore.java`
- [ ] T013 [US1] Implement `GET /api/v1/guests/{guestId}/timeline` in `src/main/java/io/guestgraph/api/TimelineController.java` per [contracts/openapi.yaml](contracts/openapi.yaml), RFC 9457 errors
- [ ] T014 [US1] Write integration tests `src/test/java/io/guestgraph/integration/TimelineApiTest.java` covering spec US1 acceptance scenarios 1–8 end to end, including that `GET /guests/{id}/records` keeps its slice-1 contract (FR-005), plus three invariant cases:
  - **Convergence (FR-010a)**: ingest 1 of 3 persons of a version, read the timeline (the roster shows what landed, with no error and no placeholder for the absent person), ingest the other two, read again — the roster is complete with no convergence step of its own
  - **Reads write nothing (FR-010, FR-010a)**: snapshot `merge_event`, `resolution_link`, and `record_object` row counts, perform a timeline read and a source-object read, assert all three are unchanged — the operational form of "no association state exists that could not be recomputed"
  - **Multi-page traversal (FR-004)**: seed a guest past one page, walk the cursor to exhaustion, assert every association appears exactly once, none is skipped, and ordering holds across page boundaries

**Checkpoint**: US1 is fully functional — a guest's current bookings are readable in one request.

---

## Phase 4: User Story 2 - A Booking's Current Guests Are the Newest Version's Guests (Priority: P2)

**Goal**: The newest roster decides membership: reassignment moves the association, removal ends
it without fabricating a transfer, and the full observation history stays reachable on the object.

**Independent Test**: Ingest v1 naming Anna and v2 naming Bruno for one reservation and role, then
a two-person version followed by a one-person version; verify the association is current on Bruno
only, that the dropped guest leaves without the remaining guest inheriting anything, and that both
histories are retrievable.

### Tests for User Story 2 ⚠

- [ ] T015 [P] [US2] ⚠ Extend `src/test/java/io/guestgraph/timeline/AssociationDeriverTest.java` with failing supersession scenarios: reassignment marks the previous holder ENDED and the new holder CURRENT; `successorGuestId` is named only when exactly one guest holds that role now and is null on a plain removal; an older version arriving late never displaces a newer roster; a revert restores the original holder; after a merge the surviving guest holds the association once; after an unmerge the association follows the resolution link

### Implementation for User Story 2

- [ ] T016 [US2] Implement current/ended classification and successor resolution in `src/main/java/io/guestgraph/timeline/AssociationDeriver.java` until T015 is green
- [ ] T017 [US2] Add the object-scoped read to `src/main/java/io/guestgraph/persistence/repo/RecordObjectRepo.java` and `TimelineQueryService`: every observation of one (tenant, source system, object type, object id) in version order, joined to its resolution link
- [ ] T018 [US2] Add the `includePast` parameter to `src/main/java/io/guestgraph/api/TimelineController.java` and `TimelineQueryService` — ended associations omitted by default, returned marked on request (FR-007)
- [ ] T019 [US2] Implement `GET /api/v1/source-objects/{sourceSystem}/{objectType}/{objectId}` in `src/main/java/io/guestgraph/api/SourceObjectController.java`: current roster plus every observation in version order, including those of guests no longer on the booking (FR-006)
- [ ] T020 [US2] Write integration tests `src/test/java/io/guestgraph/integration/SourceObjectApiTest.java` and extend `TimelineApiTest`, covering spec US2 acceptance scenarios 1–9 — reassignment, the removal case, out-of-order delivery, revert, merge, unmerge, and history completeness

**Checkpoint**: US1 and US2 both work; the timeline answers correctly through reassignments.

---

## Phase 5: User Story 3 - Every Decision Names Who Made It (Priority: P3)

**Goal**: Merge events, review decisions, unmerges, and do-not-merge rules record whether the
system, a named human, or a named agent caused them.

**Independent Test**: Perform one automatic ingest merge, one human review confirmation, and one
agent confirmation; verify each records the right actor type and identity in explain, and that a
request claiming a type its credential does not grant is refused.

### Tests for User Story 3 ⚠

- [ ] T021 [P] [US3] ⚠ Extend `src/test/java/io/guestgraph/resolution/ResolutionScenarioTest.java` and `EngineFixture`/`InMemoryGraph` with failing actor scenarios: automatic resolution records SYSTEM with the matcher name and never a person (FR-012); `unmerge` and `decide` record the `Actor` passed to them; an unmerge-written negative rule records its creating actor; an event stored without actor data reads back as unattributed rather than failing (FR-015); lifting a rule — whether by explicit deletion or by the slice-2 confirm-across-a-rule path — stamps the lifting actor, stops the rule gating, and leaves the row readable with both its creating and lifting actors (FR-013, FR-016a)

### Implementation for User Story 3

- [ ] T022 [US3] Extend `MergeEvent` and `NegativeMatchRule` in `src/main/java/io/guestgraph/domain/` with the `Actor`, and persist it: `MergeEventEntity`, `NegativeMatchRuleEntity`, their mappers, and the write paths in `src/main/java/io/guestgraph/persistence/PostgresGraph.java` and `src/test/java/io/guestgraph/resolution/InMemoryGraph.java`
- [ ] T023 [US3] Thread the actor through the engine: `ResolutionEngine` records `Actor.system(matcherName)` on every event it creates and takes no actor parameter; `UnmergeOperation.unmerge` and `ReviewDecisionOperation.decide` gain an explicit `Actor` argument — until T021 is green
- [ ] T024 [US3] Convert do-not-merge rules from hard delete to lift: stamp `lifted_at` + lifting actor instead of deleting in `src/main/java/io/guestgraph/persistence/repo/NegativeMatchRuleRepo.java` (`deleteRule`, `liftBetween`) and `NegativeRuleService`; add `lifted_at IS NULL` to the gate predicate in `negativeRuleBetween`; mirror the semantics in `GraphPort`, `PostgresGraph`, and `src/test/java/io/guestgraph/resolution/InMemoryGraph.java` — until the T021 lift scenarios are green
- [ ] T025 [US3] Implement `src/main/java/io/guestgraph/auth/ActorResolver.java` and extend `ApiKeyFilter`: the credential's `actor_type`/`actor_name` bind the actor, an `X-Actor-Id` header refines the identity within that type, and an `X-Actor-Type` that disagrees with the credential is an RFC 9457 400 that records nothing (FR-014). Extend `TenantStore`/`TenantRepo` for the new `api_key` columns
- [ ] T026 [US3] Expose the actor in the API: explain output in `src/main/java/io/guestgraph/api/GuestController.java`, the decision response in `MatchReviewController.java`, and the rule listing in `NegativeRuleController.java`, which now shows each rule's creating actor, its lifted state, and its lifting actor; unattributed rows render without error
- [ ] T027 [US3] Write integration tests `src/test/java/io/guestgraph/integration/ActorAttributionTest.java` covering spec US3 acceptance scenarios 1–8, including tenant scoping of actor data, readability after the credential is revoked (FR-017), and the split → lift → split-again sequence the partial unique index exists for

**Checkpoint**: All decisions are attributed; the agent-stewardship prerequisite is in place.

---

## Phase 6: User Story 4 - Connectors Emit Observations That Order Correctly (Priority: P4)

**Goal**: The published ingest contract states how mutable multi-person objects are keyed and
emitted, and the service behaves correctly for every case it describes.

**Independent Test**: Submit a three-person reservation version, resubmit it verbatim, then submit
a version whose only change is non-person data; verify three observations, a clean duplicate
absorption, and no guest identifier from booking-level contact data.

### Tests for User Story 4 ⚠

- [ ] T028 [P] [US4] ⚠ Write failing integration tests `src/test/java/io/guestgraph/integration/ObservationContractTest.java` covering spec US4 acceptance scenarios 1–7: three persons on one version store three observations with no duplicate-key collision; a verbatim resubmission is absorbed as DUPLICATE_IGNORED with no new observation, association, or merge event; an unparseable version stores a flagged record with no `record_object` row and no roster participation (FR-024); booking-level contact data supplied as object metadata creates no guest identifier; the same object id under two source systems yields two distinct objects

### Implementation for User Story 4

- [ ] T029 [US4] Make T028 green. Each item only if the test proves it missing: object identity namespaced by `source_system_id` in the roster key (`src/main/java/io/guestgraph/persistence/SourceRecordStore.java`, `repo/RecordObjectRepo.java`); duplicate absorption returning before any `record_object` insert so a resubmission has no association side effect (`src/main/java/io/guestgraph/ingest/IngestService.java`); `RecordExtractor` reading only the documented top-level person fields, so nested object metadata yields no identifier. If all three pass unchanged, record that in the task and move on — US4 is largely a verification-and-documentation story over behaviour US1 built, and that is not a defect
- [ ] T030 [US4] Document the connector contract in [contracts/openapi.yaml](contracts/openapi.yaml)'s ingest description and the `README.md` ingest section: one observation per person per object version keyed so roles cannot collide, the version derived from the source object's own last-modified instant (never the submitter's clock or an event id), `recordTimestamp` equal to that version, roster-complete emission whenever person data or the guest list changed, and the rule that booking-level contact data stays out of the person fields (FR-018 through FR-023). For FR-023's "place" for object-level metadata, document the one that already exists: a nested object inside `payload`, since extraction reads only specific top-level keys — do not invent a new field to satisfy the sentence

**Checkpoint**: All four stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T031 [P] Update `docs/roadmap-notes.md`: mark R3-1 consumed by this slice, mark R5-1 prerequisite 1 (actor identity) consumed, and **amend R4-1** — its per-person emit-on-change rule becomes roster-complete emission, because a sparse version would read as a booking that lost guests. Also record that all paged endpoints now use keyset cursors — `/match-reviews` and `/negative-rules` were migrated off raw offsets in this slice (T036, T037), so the API has one paging idiom rather than two
- [ ] T032 [P] Update `README.md` if the endpoint list is affected by the two new operations
- [ ] T033 Migrate `GET /api/v1/match-reviews` from offsets to keyset cursors: seek past the decoded `(created_at, id)` in the native query in `src/main/java/io/guestgraph/persistence/repo/MatchReviewRepo.java` instead of `OFFSET`, backed by the existing `match_review_queue_idx`; update `MatchReviewQueryService` and `src/main/java/io/guestgraph/api/MatchReviewController.java` (drop `offset`, accept `cursor`, return `nextCursor`) reusing `api/Cursor.java`; update `src/test/java/io/guestgraph/integration/MatchReviewApiTest.java` and `specs/001-core-identity-resolution/contracts/openapi.yaml` in the same change. Add a case asserting the behaviour this fixes: reviews decided mid-traversal no longer cause the queue to skip entries, as they do under offsets
- [ ] T034 Migrate `GET /api/v1/negative-rules` from offsets to keyset cursors the same way: `src/main/java/io/guestgraph/persistence/repo/NegativeMatchRuleRepo.java` (ordered `created_at DESC, id`), `NegativeRuleService`, `src/main/java/io/guestgraph/api/NegativeRuleController.java`, `src/test/java/io/guestgraph/integration/NegativeRuleApiTest.java`, and `specs/002-probabilistic-matching/contracts/openapi.yaml`. Sequence after T024 and T026, which also touch the rule repo and controller
- [ ] T035 Add the SC-006 assertion to `src/test/java/io/guestgraph/integration/TimelineApiTest.java`: seed a guest holding 500 associations and assert the first page returns in under 1 second. If it does not, introduce the `source_object_current` projection recorded as a scale lever in [research.md](research.md) R1 rather than loosening the criterion
- [ ] T036 Run `./mvnw spotless:apply && ./mvnw verify` — Spotless, PMD (no inline FQNs), ArchUnit (`PersistenceRulesTest`), and `OpenApiConformanceTest` over the union of all three feature contracts must all be green
- [ ] T037 Execute the full quickstart walk ([quickstart.md](quickstart.md)): all four story walks and the success-criteria spot checks, including SC-007's unchanged-backfill replay

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup; **blocks every user story**
- **US1 (Phase 3)**: depends on Foundational
- **US2 (Phase 4)**: depends on US1 — it extends the same deriver and query service
- **US3 (Phase 5)**: depends on Foundational only; **independent of US1/US2** and can run in parallel with them
- **US4 (Phase 6)**: depends on US1 (object identity must reach the database before its contract can be tested end to end)
- **Polish (Phase 7)**: depends on all stories being complete

### User Story Dependencies

- **US1 (P1)**: the MVP. No dependencies beyond Foundational.
- **US2 (P2)**: builds on US1's deriver and query service. Not independent of US1 by design — supersession is a rule *of* the derivation, not a separate mechanism.
- **US3 (P3)**: genuinely independent. Touches the engine, auth, and audit surfaces, none of which the timeline uses. A second developer can take this from the Foundational checkpoint.
- **US4 (P4)**: needs US1's ingest path; mostly hardens and documents behaviour US1 introduced.

### Within Each User Story

- ⚠ test tasks MUST be written and seen failing before the implementation tasks that follow
- Domain types before persistence, persistence before services, services before endpoints
- Story complete and its checkpoint validated before moving to the next priority

### Parallel Opportunities

- T001 and T002 in Setup
- T008 (deriver tests) and T010 (persistence) once Foundational is done — different files, no shared state
- **US3 in full alongside US1/US2** — the largest parallel win in this slice
- T031 and T032 in Polish

---

## Parallel Example: after the Foundational checkpoint

```bash
# Developer A — US1 (MVP path):
Task: "T008 failing AssociationDeriverTest in src/test/java/io/guestgraph/timeline/"
Task: "T010 RecordObjectEntity + RecordObjectRepo in src/main/java/io/guestgraph/persistence/"

# Developer B — US3, fully independent:
Task: "T021 failing actor scenarios in src/test/java/io/guestgraph/resolution/"
Task: "T022 Actor on MergeEvent + NegativeMatchRule in src/main/java/io/guestgraph/domain/"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational (T005 and T006 are the ones that bite if skipped)
2. Phase 3 US1
3. **STOP and VALIDATE**: run the US1 quickstart walk — a reservation edited three times appears
   once on its guest's timeline (SC-001)
4. This alone answers "what does this guest currently have", which is the slice's reason to exist

### Incremental Delivery

1. Setup + Foundational → schema and harness ready
2. US1 → validate → the timeline works (MVP)
3. US2 → validate → it stays correct through reassignments and removals
4. US3 → validate → every decision names its actor
5. US4 → validate → the connector contract is published and enforced
6. Polish → roadmap notes amended, performance pinned, quickstart walked

### Parallel Team Strategy

Two developers split cleanly at the Foundational checkpoint: one takes US1 → US2 → US4 (the
timeline spine), the other takes US3 (actor identity) end to end. They meet only in Polish.

---

## Notes

- Constitution II: `record_object` rows are inserted once and never updated — no repository may
  expose an update path for them
- Constitution I: every new repository method is tenant-scoped, enforced by `PersistenceRulesTest`
- `OpenApiConformanceTest` unions every `specs/*/contracts/openapi.yaml` automatically, so it will
  fail until T013 and T019 serve the documented operations — that failure is the gate working
- Commit after each task or logical group; do not auto-commit — Rob commits when he asks
