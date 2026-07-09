---

description: "Task list for feature 001-core-identity-resolution"
---

# Tasks: Core Identity Resolution Service

**Input**: Design documents from `specs/001-core-identity-resolution/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/openapi.yaml, quickstart.md

**Tests**: INCLUDED — constitution Principle VI mandates TDD for the resolution engine
(failing scenario tests before implementation) and the workflow gates require integration
tests for every API endpoint. Test-first tasks are marked ⚠ and MUST be completed (and seen
failing) before their implementation tasks.

**Organization**: Tasks are grouped by user story so each story is an independently testable
increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1–US4)
- All paths are relative to the repository root (single Maven module per plan.md)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Bootable Spring Boot 4 / Java 25 project skeleton

- [X] T001 Create Maven project: `pom.xml` (Spring Boot 4 parent, Java 25; starters: web, validation, data-jdbc, flyway, postgresql driver; libphonenumber; test: spring-boot-starter-test, testcontainers-postgresql, spring-boot-testcontainers), Maven wrapper `mvnw`/`.mvn/`, and `.gitignore`
- [X] T002 Create `src/main/java/io/guestgraph/GuestGraphApplication.java` and `src/main/resources/application.yaml` (virtual threads on, `spring.mvc.problemdetails.enabled=true`, Flyway enabled, datasource via Docker Compose support)
- [X] T003 [P] Create `compose.yaml` (PostgreSQL 18) and local-dev seed for tenant `demo` + API key `demo-key` (dev profile only, e.g. `src/main/resources/application-local.yaml` + seed SQL)
- [X] T004 [P] Create CI workflow `.github/workflows/ci.yml` running `./mvnw verify` (Docker available for Testcontainers)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, domain types, tenancy/auth, error contract, test harness — everything
every story depends on

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T005 Write Flyway migration `src/main/resources/db/migration/V1__core_schema.sql` per data-model.md: all 9 tables (tenant, api_key, source_system, source_record, record_identifier, guest, identifier, resolution_link, merge_event, match_review), composite tenant-scoped unique constraints, lookup indexes, and the `source_record_immutable` UPDATE-guard trigger
- [X] T006 [P] Create domain types in `src/main/java/io/guestgraph/domain/`: entities as records plus enums `IdentifierType`, `MergeEventKind`, `ReviewStatus` per data-model.md
- [X] T007 [P] Create Testcontainers base class `src/test/java/io/guestgraph/integration/PostgresIntegrationTest.java` (shared PostgreSQL container, Flyway applied, test tenant + API key seeded)
- [X] T008 Implement API-key auth in `src/main/java/io/guestgraph/auth/`: `ApiKeyFilter` (SHA-256 hash lookup → tenant, 401 problem details when missing/unknown/revoked) and request-scoped `TenantContext`; integration test `src/test/java/io/guestgraph/integration/AuthenticationTest.java`
- [X] T009 Implement RFC 9457 error handling in `src/main/java/io/guestgraph/api/ApiExceptionHandler.java` + domain exceptions (NotFound→404, Conflict→409, Validation→400) with GuestGraph `type` URIs; integration test asserting `application/problem+json` shape in `src/test/java/io/guestgraph/integration/ProblemDetailsTest.java`
- [X] T010 Create tenant-scoped repositories in `src/main/java/io/guestgraph/persistence/` (Tenant, ApiKey, SourceSystem, SourceRecord, RecordIdentifier, Guest, Identifier, ResolutionLink, MergeEvent, MatchReview) — every method takes `tenantId`; no unscoped query methods exist
- [X] T011 Implement per-tenant advisory lock guard `src/main/java/io/guestgraph/resolution/TenantLock.java` (`pg_advisory_xact_lock(hashtext(tenant_id))`, transaction-scoped) with integration test proving serialization in `src/test/java/io/guestgraph/integration/TenantLockTest.java`

**Checkpoint**: Foundation ready — user story implementation can now begin

---

## Phase 3: User Story 1 - Ingest Records and Resolve Identities (Priority: P1) 🎯 MVP

**Goal**: Register source systems, ingest records (immutably, never dropping parseable data),
resolve deterministically on normalized strong identifiers with transitive merging, record
MergeEvents with matcher name + confidence.

**Independent Test**: Register a source system, ingest records with overlapping identifiers,
verify the expected number of distinct guests and correct record attachment (spec US1).

### Tests for User Story 1 (TDD — write first, see them FAIL) ⚠

- [ ] T012 [P] [US1] Write failing unit tests for normalizers in `src/test/java/io/guestgraph/normalize/NormalizerTest.java`: email trim+lowercase, phone→E.164 via libphonenumber (incl. unparseable → rejection outcome, never a guess), ID document → SHA-256 of `TYPE:NUMBER`, loyalty/external key trim (research R5)
- [ ] T013 [P] [US1] Write failing table-driven resolution scenario tests in `src/test/java/io/guestgraph/resolution/ResolutionScenarioTest.java` (pure JVM, in-memory fixtures: record sets in → expected guest clusters + expected MergeEvents out): new guest on 0 matches, attach on 1 match (case-insensitive email), transitive 2+ match merge, record with no valid identifiers → new guest + needs_review, duplicate source+externalKey ignored
- [ ] T014 [P] [US1] Write failing unit tests for survivorship in `src/test/java/io/guestgraph/survivorship/GoldenProfileDeriverTest.java`: per-field most-recent-non-null, recordTimestamp fallback to receivedAt, conflicting values across 3 records (spec US2/AS1 rule defined here)

### Implementation for User Story 1

- [ ] T015 [US1] Implement normalizers in `src/main/java/io/guestgraph/normalize/` (EmailNormalizer, PhoneNormalizer, IdDocumentHasher, plain trims) until T012 is green
- [ ] T016 [US1] Define `ResolutionStrategy` candidate-scoring interface (candidates in → scored `MatchDecision`s out, carrying matcher name + confidence) and `DeterministicMatcher` (confidence 1.0) in `src/main/java/io/guestgraph/resolution/`
- [ ] T017 [US1] Implement `ResolutionService` in `src/main/java/io/guestgraph/resolution/ResolutionService.java`: find candidates by shared identifiers, apply strategy, execute create/attach/merge outcomes, write MergeEvents (CREATE/ATTACH/MERGE with evidence), rebuild guest identifiers, run inside `TenantLock` — until T013 is green
- [ ] T018 [US1] Implement `GoldenProfileDeriver` pure function in `src/main/java/io/guestgraph/survivorship/GoldenProfileDeriver.java` until T014 is green; ResolutionService recomputes `guest.profile` on every link change
- [ ] T019 [US1] Implement ingest pipeline in `src/main/java/io/guestgraph/ingest/`: payload parsing, identifier extraction via normalizers, `needs_review` flagging with reasons (malformed-but-parseable is stored, never dropped — FR-006), dedup on (sourceSystem, externalKey) → DUPLICATE_IGNORED
- [ ] T020 [US1] Implement `POST /api/v1/source-systems` in `src/main/java/io/guestgraph/api/SourceSystemController.java` (201, 409 on duplicate code) + integration test `src/test/java/io/guestgraph/integration/SourceSystemApiTest.java`
- [ ] T021 [US1] Implement `POST /api/v1/records` in `src/main/java/io/guestgraph/api/RecordIngestController.java`: single or batch ≤1000, sequential processing, per-record `IngestResult` (never atomic batch failure), unparseable body → 400 problem details per contracts/openapi.yaml
- [ ] T022 [US1] Write end-to-end API integration tests in `src/test/java/io/guestgraph/integration/IngestResolutionApiTest.java` covering spec US1 acceptance scenarios 1–6, including a concurrent-ingest test (parallel requests sharing identifiers → consistent graph, FR-011)

**Checkpoint**: MVP — records in, resolved tenant-scoped guests out, full audit trail

---

## Phase 4: User Story 2 - Query Golden Profiles and Look Up Guests (Priority: P2)

**Goal**: Read the resolved graph: golden profile + identifiers, verbatim source records,
lookup by normalized identifier.

**Independent Test**: After ingesting records for a known guest, fetch profile/identifiers/
records; look up by email and phone in unnormalized form and get the same guest (spec US2).

### Implementation for User Story 2

- [ ] T023 [P] [US2] Implement `GET /api/v1/guests/{id}` (profile + identifiers, 404 outside tenant) and `GET /api/v1/guests/{id}/records` (originals verbatim) in `src/main/java/io/guestgraph/api/GuestController.java` with a read-side query service in `src/main/java/io/guestgraph/persistence/GuestQueryService.java`
- [ ] T024 [US2] Implement `GET /api/v1/guests?identifier=...&type=...` lookup in `GuestController`: normalize input first, empty list on miss (never an error), optional type filter
- [ ] T025 [US2] Write integration tests `src/test/java/io/guestgraph/integration/GuestQueryApiTest.java` covering spec US2 acceptance scenarios 1–5, plus dedicated cross-tenant isolation test `src/test/java/io/guestgraph/integration/TenantIsolationTest.java` (guest fetch/lookup/records across tenants behaves as not-found/empty — SC-006)

**Checkpoint**: Resolved graph is consumable — first full end-to-end business value

---

## Phase 5: User Story 3 - Explain and Reverse a Merge (Priority: P3)

**Goal**: Full merge-decision chain per guest; unmerge detaches records, records an UNMERGE
event with exclusions, replays resolution; originals untouched.

**Independent Test**: Trigger a merge, verify explain returns the complete chain; unmerge and
verify records regroup correctly and the wrong merge is not silently recreated (spec US3).

### Tests for User Story 3 (TDD — write first, see them FAIL) ⚠

- [ ] T026 [P] [US3] Extend `src/test/java/io/guestgraph/resolution/ResolutionScenarioTest.java` with failing unmerge/replay scenarios: detach one of three records → correct regrouping, unmerge-then-reingest-identical-record does not rejoin (exclusion list, research R8), unmerge on single-record guest → error, explain chain includes absorbed guests' events

### Implementation for User Story 3

- [ ] T027 [US3] Implement `ExplainService` in `src/main/java/io/guestgraph/resolution/ExplainService.java`: collect merge_event chain for guest + transitively for `absorbed_guest_ids`, oldest first
- [ ] T028 [US3] Implement `UnmergeService` in `src/main/java/io/guestgraph/resolution/UnmergeService.java`: validate links, delete resolution_links, write UNMERGE event with `excluded_guest_ids`, re-resolve detached records honoring exclusions, recompute remaining guest's identifiers + profile, delete emptied guests — inside `TenantLock`; until T026 green
- [ ] T029 [US3] Implement `GET /api/v1/guests/{id}/explain` and `POST /api/v1/guests/{id}/unmerge` in `GuestController` per contracts/openapi.yaml + integration tests `src/test/java/io/guestgraph/integration/ExplainUnmergeApiTest.java` covering spec US3 acceptance scenarios 1–4

**Checkpoint**: Safety machinery complete — every merge explainable and reversible

---

## Phase 6: User Story 4 - Review Uncertain Matches (Priority: P4)

**Goal**: Over-threshold identifier sharing parks candidate matches in a review queue;
stewards confirm (merge executes, recorded) or reject (records stay separate) exactly once.

**Independent Test**: With a low threshold, ingest past it, verify no auto-merge and a
PENDING review; confirm one and reject another, verify outcomes (spec US4).

### Tests for User Story 4 (TDD — write first, see them FAIL) ⚠

- [ ] T030 [P] [US4] Extend `src/test/java/io/guestgraph/resolution/ResolutionScenarioTest.java` with failing threshold scenarios: identifier shared by more than `review_threshold` records → no merge + MatchReview created + record still resolves via its other identifiers; confirm → merge + REVIEW_CONFIRM event; reject → separate guests + REVIEW_REJECT event; second decision → conflict

### Implementation for User Story 4

- [ ] T031 [US4] Add suspicious-match detection to `ResolutionService`/`DeterministicMatcher`: count records sharing the candidate identifier (record_identifier index), compare to `tenant.review_threshold` (default 10, research R9), create `match_review` rows instead of merging suspicious candidates
- [ ] T032 [US4] Implement `ReviewDecisionService` in `src/main/java/io/guestgraph/resolution/ReviewDecisionService.java`: single-transition PENDING→CONFIRMED/REJECTED (409 on repeat), confirm executes merge via ResolutionService inside `TenantLock`, links `decision_event_id`; until T030 green
- [ ] T033 [US4] Implement `GET /api/v1/match-reviews` (status filter, pagination) and `POST /api/v1/match-reviews/{id}` in `src/main/java/io/guestgraph/api/MatchReviewController.java` + integration tests `src/test/java/io/guestgraph/integration/MatchReviewApiTest.java` covering spec US4 acceptance scenarios 1–4 (incl. tenant isolation of the queue)

**Checkpoint**: All four user stories independently functional

---

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T034 [P] Write contract conformance tests in `src/test/java/io/guestgraph/contract/OpenApiConformanceTest.java` validating live responses against `specs/001-core-identity-resolution/contracts/openapi.yaml`
- [ ] T035 [P] Update `README.md` with build/run/quickstart instructions matching `specs/001-core-identity-resolution/quickstart.md`
- [ ] T036 Execute the full quickstart smoke walk (`specs/001-core-identity-resolution/quickstart.md`) against a locally running service; verify each success-criteria spot check (SC-001…SC-007), including single-record ingest responding < 1 s (SC-002)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all user stories
- **US1 (Phase 3)**: depends on Foundational; no other story dependencies
- **US2 (Phase 4)**: depends on Foundational; needs US1's data to be meaningful end-to-end (test setup can seed via ingest)
- **US3 (Phase 5)**: depends on US1 (merges must exist to explain/unmerge)
- **US4 (Phase 6)**: depends on US1 (threshold check sits in the resolution path)
- **Polish (Phase 7)**: depends on all stories

### Within Each Story (TDD ordering — constitution Principle VI)

- ⚠ test tasks MUST be written and observed FAILING before their implementation tasks
- Normalizers → strategy/matcher → resolution service → ingest pipeline → endpoints
- Engine scenario tests are pure JVM (no DB); integration tests come with the endpoints

### Parallel Opportunities

- Setup: T003, T004 in parallel after T001–T002
- Foundational: T006, T007 in parallel after T005
- US1 test-first batch: T012, T013, T014 in parallel (different test files)
- After US1: US2 (T023–T025), US3 (T026–T029), US4 (T030–T033) are largely independent story
  tracks — parallelizable across developers/agents; note T026/T030 both extend
  ResolutionScenarioTest.java and T023/T024/T029 share GuestController.java, so those specific
  tasks serialize within/across tracks
- Polish: T034, T035 in parallel

## Implementation Strategy

**MVP first**: Phases 1–3 (T001–T022) deliver the P1 story — records in, resolved guests out,
audit trail recorded. Stop, run `./mvnw verify`, validate independently, demo.

**Incremental delivery**: add US2 (readable graph — first full business value), then US3
(safety machinery), then US4 (review queue). Each checkpoint is independently testable via
its integration suite; commit after each task or logical group.
