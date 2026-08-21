# Implementation Plan: Guest Timeline & Attributed Decisions

**Branch**: `003-timeline-journey` | **Date**: 2026-08-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-timeline-journey/spec.md`

## Summary

Source business objects — reservations first — become **associations** on resolved guests, so the
graph can answer "what does this guest currently have", not only "what did we ever observe about
them". The unit of supersession is the object *version*: the newest version's complete person
roster determines who is on a booking, and persons are never matched from one version to the next.
That choice (spec Clarifications, and research R3) is what makes the Apaleo case — entity-less
persons with no id to follow across edits — answerable rather than merely tolerated; it also makes
guest *removal* detectable, which no positional-slot scheme handles honestly.

Technically: one additive migration adds `record_object`, an optional immutable companion of
`source_record` following the `record_identifier` / `record_block_key` precedent, plus actor
columns on `merge_event`, `negative_match_rule`, and `api_key`. Associations are **derived on
read** — two tenant-scoped JPQL queries feed a pure-JVM `AssociationDeriver`, so the subtle rules
(current vs ended, successor naming, dedup, ordering) are unit-testable without a database and
FR-010's recomputability is structural rather than maintained. Actor identity threads through as an
explicit parameter on the steward operations; the engine always records `SYSTEM`, so automatic
resolution has no path to being attributed to a person. Do-not-merge rules are lifted rather than
deleted, so the actor who overrides a split is recorded next to the one who made it.

## Technical Context

**Language/Version**: Java 25 (virtual threads / Loom), unchanged

**Primary Dependencies**: Spring Boot 4, Spring Data JPA + Hibernate, MapStruct, Flyway. No new
dependencies — the slice needs no library the repository does not already carry.

**Storage**: PostgreSQL. Migration `V3__timeline_and_actors.sql` — additive except for replacing
`negative_match_rule`'s pair uniqueness constraint with a partial index over active rules
(research R8); no slice-1/2 table loses a column or changes a type.

**Testing**: JUnit 5 + AssertJ; pure-JVM scenario tests for the deriver; Testcontainers-backed
integration tests; ArchUnit (`PersistenceRulesTest`); the existing `OpenApiConformanceTest`
auto-enrols this slice's contract because it unions every `specs/*/contracts/openapi.yaml`.

**Target Platform**: Linux server (single Spring Boot service), unchanged

**Project Type**: Web service — single Maven module

**Performance Goals**: SC-006 — first timeline page under 1 s for a guest holding 500
associations. Sizing behind the read-derived design: ~2,500 observation rows for such a guest,
fetched by two indexed queries (research R1).

**Constraints**: Every query tenant-scoped (Constitution I). `record_object` rows are insert-only.
No `JdbcClient` outside `TenantLock` / `LocalDevSeeder` — the ArchUnit allowlist stands, so all new
reads are `@Query` repository methods. JPA stays confined to `io.guestgraph.persistence`.

**Scale/Scope**: 2 new endpoints, 1 new table, 4 altered tables, 1 new package
(`io.guestgraph.timeline`), additive fields on ingest and on three existing responses, and a
paging migration of the two existing offset-paged endpoints (research R9).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*
*Source: `.specify/memory/constitution.md` v1.0.0*

**Initial evaluation — PASS.** **Post-design re-evaluation — PASS** (no design element changed a
verdict; notes below reflect the final design).

- [x] **Tenant isolation (I)**: `record_object` carries `tenant_id`; both timeline queries and the
      source-object query are tenant-scoped, as is the actor read. No cross-tenant path is
      introduced — the object namespace is (tenant, source system, object type, object id).
- [x] **Immutable source records (II)**: `record_object` is an insert-only companion with no
      update path, exactly as `record_identifier` and `record_block_key` are; `source_record`
      and its immutability trigger are not touched at all (research R2). Associations are derived and never persisted, so there is no
      second copy of the truth to drift (research R1).
- [x] **No silent data loss (III)**: a submission whose `sourceObject.version` is absent or
      unparseable stores the record and adds a `needs_review` reason, writing no `record_object`
      row — visible in `/records`, absent from every roster (FR-024). A `recordTimestamp` that
      disagrees with the version is likewise flagged, not rejected.
- [x] **Explainable & reversible resolution (IV)**: no merge path changes. Associations make no
      resolution decisions and are never consulted by the engine, so `MergeEvent`, explain,
      unmerge, and the review queue keep their semantics. `ResolutionStrategy` is untouched — this
      slice adds no matcher. Actor is additive audit metadata that strengthens the trail; lifting
      rules rather than deleting them (research R8) additionally makes rule removal auditable,
      which it is not today.
- [x] **API-first (V)**: both capabilities are reachable at `/api/v1/...`; errors stay RFC 9457
      (including the credential-type refusal); auth stays per-tenant API keys, extended only with
      what the key acts as.
- [x] **TDD on the resolution engine (VI)**: the deriver is developed test-first as table-driven
      pure-JVM scenarios; the actor changes to `UnmergeOperation` / `ReviewDecisionOperation` get
      failing scenario tests before implementation; Testcontainers integration tests cover the
      SQL and the API surface. Note that the timeline is a read model, not engine logic — the
      TDD obligation is met because it is cheap and the rules are subtle, not because the
      constitution compels it here.
- [x] **Stack & shape**: Java 25 + Spring Boot 4 + PostgreSQL + Maven, single module. No new
      dependency, no new service.
- [x] **Open-core boundary**: nothing commercial. FR-016 records the actor on do-not-merge rules
      but deliberately does not enforce the agent carve-out, which belongs with scoped credentials
      in a later slice.
- [x] **GDPR readiness**: `record_object` is per-record and is removed with its parent along the
      same erasure path the existing companions use; associations are derived, so erasing records
      erases the associations with no separate cleanup. Actor ids are tenant-scoped personal data subject to the same
      erasure path.

## Project Structure

### Documentation (this feature)

```text
specs/003-timeline-journey/
├── plan.md              # This file
├── spec.md              # Feature specification (with Clarifications)
├── research.md          # Phase 0 output — R1..R7
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── openapi.yaml     # Phase 1 output — 2 new operations
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 — created by /speckit-tasks, not here
```

### Source Code (repository root)

```text
src/main/java/io/guestgraph/
├── timeline/                     # NEW — pure JVM, no Spring, no JPA
│   ├── AssociationDeriver.java   # roster → associations: current/ended, successor, dedup, order
│   ├── Association.java
│   ├── AssociationStatus.java
│   └── ObjectObservation.java
├── domain/
│   ├── Actor.java                # NEW — type + id
│   ├── ActorType.java            # NEW — SYSTEM | HUMAN | AGENT
│   ├── ObjectRole.java           # NEW — PRIMARY_GUEST | ADDITIONAL_GUEST | BOOKER
│   ├── RecordObject.java         # NEW
│   ├── MergeEvent.java           # + actor
│   ├── NegativeMatchRule.java    # + actor
│   └── SourceRecord.java         # unchanged
├── persistence/
│   ├── TimelineQueryService.java # NEW — the two tenant-scoped reads
│   ├── NegativeRuleService.java  # lift instead of delete (R8)
│   ├── entity/RecordObjectEntity.java        # NEW
│   ├── entity/NegativeMatchRuleEntity.java   # + actor, lifted_at, lifting actor
│   ├── repo/RecordObjectRepo.java            # NEW — @Query only, tenant-scoped
│   ├── repo/NegativeMatchRuleRepo.java       # lift-stamp, gate predicate, keyset paging (R8, R9)
│   ├── repo/MatchReviewRepo.java             # keyset paging replaces OFFSET (R9)
│   └── mapper/…                              # MapStruct additions
├── ingest/RecordExtractor.java   # + sourceObject parsing, version validation, flag reasons
├── resolution/
│   ├── ResolutionEngine.java     # records Actor.system(matcher) on every event
│   ├── GraphPort.java            # negative-rule lift semantics (R8)
│   ├── UnmergeOperation.java     # + Actor parameter
│   └── ReviewDecisionOperation.java          # + Actor parameter
├── auth/
│   ├── ApiKeyFilter.java         # resolves credential actor; X-Actor-Id / X-Actor-Type handling
│   └── ActorResolver.java        # NEW — credential type is the ceiling (FR-014)
└── api/
    ├── TimelineController.java       # NEW — GET /guests/{id}/timeline
    ├── SourceObjectController.java   # NEW — GET /source-objects/{sys}/{type}/{id}
    ├── Cursor.java                   # NEW — one keyset cursor codec for all paged endpoints (R9)
    ├── IngestDtos.java               # + SourceObjectDto on the request
    ├── GuestController.java          # explain response + actor
    ├── MatchReviewController.java    # decision response + actor; offset → cursor (R9)
    └── NegativeRuleController.java   # listing + actors and lifted state; offset → cursor (R8, R9)

src/main/resources/db/migration/
└── V3__timeline_and_actors.sql   # NEW — additive only

src/test/java/io/guestgraph/
├── timeline/AssociationDeriverTest.java      # NEW — table-driven, pure JVM, written first
├── integration/TimelineApiTest.java          # NEW
├── integration/SourceObjectApiTest.java      # NEW
├── integration/ActorAttributionTest.java     # NEW
├── integration/PostgresIntegrationTest.java  # + record_object in TRUNCATE (mandatory); actor_name in seedTenant
├── integration/MatchReviewApiTest.java       # paging migration (R9)
├── integration/NegativeRuleApiTest.java      # paging migration + lift (R8, R9)
└── resolution/…                              # actor assertions added to existing scenarios

docs/er-schema.mmd                # regenerated by ./scripts/regen-er.sh (CI gate)
src/main/java/io/guestgraph/config/LocalDevSeeder.java   # + actor_name on the api_key insert

specs/001-core-identity-resolution/contracts/openapi.yaml  # /match-reviews offset → cursor (R9)
specs/002-probabilistic-matching/contracts/openapi.yaml    # /negative-rules offset → cursor (R9)
```

**Structure Decision**: Single Maven module, unchanged. One new package `io.guestgraph.timeline`
holding the derivation rules as pure JVM code with no Spring or JPA dependency — the same
separation the resolution engine already has behind `GraphPort`, and what makes the deriver
testable on fixtures. Persistence and API additions follow the existing package layout exactly;
the ArchUnit rules (`@Query`-only repositories, tenant-scoped methods, JPA confined to
`persistence`, `JdbcClient` allowlist) constrain the new code with no rule changes.

## Design Decisions Carried From Phase 0

| # | Decision | Where |
|---|---|---|
| R1 | Associations derived on read, never materialised | [research.md](research.md) |
| R2 | `record_object` companion table, not columns on `source_record` | [research.md](research.md) |
| R3 | Two tenant-scoped queries + pure-JVM `AssociationDeriver` | [research.md](research.md) |
| R4 | Actor as explicit parameter; credential type is the ceiling | [research.md](research.md) |
| R5 | `object_version timestamptz`; unusable version → flagged, no companion row | [research.md](research.md) |
| R6 | `/guests/{id}/timeline` + `/source-objects/{sys}/{type}/{id}` | [contracts/openapi.yaml](contracts/openapi.yaml) |
| R7 | `V3` additive but for one constraint swap; `resetDatabase`, seeder, and `regen-er.sh` follow-ons | [research.md](research.md) |
| R8 | Do-not-merge rules are lifted, not deleted — both actors on one row | [research.md](research.md) |
| R9 | One paging idiom: `/match-reviews` and `/negative-rules` migrate to keyset cursors | [research.md](research.md) |

## Complexity Tracking

No Constitution Check violations — the table is intentionally empty.

Three choices worth naming even though none is a violation:

- **Two queries plus in-memory derivation, rather than one SQL statement.** Slightly more code
  than a correlated-subquery query would be, chosen because it keeps the derivation rules in one
  unit-testable place instead of splitting them between SQL and Java, where they would drift.
- **`source_system_id` denormalised onto `record_object`.** A deliberate duplication so the roster
  lookup is a single-table index scan; the value is copied from the parent record at insert and,
  like the rest of the row, never updated.
- **Replacing `negative_match_rule`'s unique constraint with a partial index** (research R8). The
  only part of V3 that is not purely additive. Required because lifted rules stay in the table, so
  a pair split → lifted → split again needs a second row. Safe pre-release; it would need a
  different approach after tagging.
- **Migrating two earlier slices' endpoints off offset paging** (research R9). Scope beyond this
  slice's stories, taken deliberately: one API should have one paging idiom, and the change is
  free before release and breaking after it. It narrows SC-008 and edits two prior contract
  files, both stated rather than absorbed silently.

## Follow-ons Not In This Slice

- Roadmap note R4-1 must be updated when marked consumed: its per-person emit-on-change rule is
  amended by this slice to roster-complete emission (spec Assumptions).
- The FR-011 agent carve-out — an agent may not lift a human's split — is *enabled* by FR-016's
  actor data and enforced later, alongside scoped credentials (R5-1 prerequisite 2).
- A `source_object_current` projection if a tenant ever appears whose guests hold thousands of
  associations (research R1 scale lever).
