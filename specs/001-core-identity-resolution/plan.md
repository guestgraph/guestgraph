# Implementation Plan: Core Identity Resolution Service

**Branch**: `001-core-identity-resolution` | **Date**: 2026-07-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/001-core-identity-resolution/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Build the GuestGraph core service: a single Spring Boot application that ingests raw guest
records per tenant, stores them immutably, resolves identities deterministically on normalized
strong identifiers (with transitive merging, MergeEvent audit, explain/unmerge, and a
match-review queue for suspicious matches), derives golden Guest profiles via survivorship
rules, and exposes everything through a tenant-scoped `/api/v1` REST API with RFC 9457 errors
and per-tenant API-key auth. The resolution engine is built TDD-first behind a candidate-scoring
`ResolutionStrategy` interface so slice-2 probabilistic matching lands without redesign or
schema migration.

## Technical Context

**Language/Version**: Java 25 (virtual threads enabled: `spring.threads.virtual.enabled=true`)

**Primary Dependencies**: Spring Boot 4 (web, validation, data-jdbc), Flyway (schema
migrations), libphonenumber (E.164 phone normalization), Jackson (JSON payload handling)

**Storage**: PostgreSQL 18 (always latest released major) — relational schema + `jsonb` for immutable raw payloads;
per-tenant advisory locks (`pg_advisory_xact_lock`) around merge operations

**Testing**: JUnit 5 + AssertJ; resolution engine via table-driven scenario tests (TDD);
Testcontainers (PostgreSQL) for repository and API integration tests; Spring Boot Test +
MockMvc/RestTestClient for the API layer

**Target Platform**: Linux server (JVM 25); local dev via Docker Compose (Postgres)

**Project Type**: web-service — single Spring Boot service, single Maven module

**Performance Goals**: single-record ingest resolves synchronously in < 1 s under normal load
(SC-002); batch ingest processes records independently without one failure blocking others

**Constraints**: every table/query/endpoint tenant-scoped; source records immutable after
insert; no parseable data dropped (`needs_review` instead); all errors RFC 9457; merge
concurrency serialized per tenant via Postgres advisory locks; data model probabilistic-ready
(confidence, matcher metadata, review queue)

**Scale/Scope**: OSS core, single-instance deployments initially; design for ~10⁶ source
records / ~10⁵ guests per tenant; 9 REST endpoints, 8 core entities

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*
*Source: `.specify/memory/constitution.md` v1.0.0*

- [x] **Tenant isolation (I)**: every table carries `tenant_id` (see data-model.md); all
      uniqueness constraints are composite with `tenant_id`; tenant is resolved from the API
      key and injected into every repository call; advisory locks are keyed per tenant.
- [x] **Immutable source records (II)**: `source_record.payload` is insert-only (no UPDATE
      path in code; DB trigger guards payload mutation); golden profile fields on `guest` are
      derived and recomputed from records on every link change.
- [x] **No silent data loss (III)**: parseable-but-malformed records stored with
      `needs_review = true` + reasons; only unparseable requests rejected, as RFC 9457
      problem details via Spring's `ProblemDetail`.
- [x] **Explainable & reversible resolution (IV)**: every CREATE/ATTACH/MERGE decision writes
      a `merge_event` (matcher name, confidence, timestamp); `explain` walks the event chain;
      `unmerge` removes a `resolution_link` and replays resolution, recording an UNMERGE
      event; over-threshold matches create `match_review` rows instead of merging;
      `ResolutionStrategy` is candidates-in → scored-decisions-out.
- [x] **API-first (V)**: all nine capabilities exposed under `/api/v1/...`
      (contracts/openapi.yaml); errors RFC 9457; auth is per-tenant API keys (`X-API-Key`).
- [x] **TDD on the resolution engine (VI)**: engine built from failing table-driven scenario
      tests first (record sets in → expected clusters out), incl. shared family emails,
      transitive merges, unmerge-then-reingest; Testcontainers-backed integration tests.
- [x] **Stack & shape**: Java 25 + Spring Boot 4 + virtual threads + PostgreSQL + Maven;
      one service, one Maven module. No deviations.
- [x] **Open-core boundary**: no SaaS/console/billing/OIDC concerns in this repo; auth stays
      at API-key level.
- [x] **GDPR readiness**: all guest-linked data reachable via `tenant_id` + `guest_id`
      foreign keys, so future per-guest export/erasure can enumerate it; ID documents stored
      hashed only.

**Post-design re-check (after Phase 1)**: PASS — data-model.md and contracts/openapi.yaml
introduce no violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-core-identity-resolution/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/
│   └── openapi.yaml     # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
pom.xml
compose.yaml                          # local Postgres for dev
src/
├── main/
│   ├── java/io/guestgraph/
│   │   ├── GuestGraphApplication.java
│   │   ├── api/                      # REST controllers, request/response DTOs,
│   │   │   ├── SourceSystemController.java
│   │   │   ├── RecordIngestController.java
│   │   │   ├── GuestController.java
│   │   │   ├── MatchReviewController.java
│   │   │   └── ProblemDetailsExceptionHandler.java   # RFC 9457
│   │   ├── auth/                     # API-key filter, TenantContext
│   │   ├── domain/                   # entities + value types (Guest, SourceRecord,
│   │   │                             #   Identifier, MergeEvent, MatchReview, ...)
│   │   ├── ingest/                   # payload parsing, field extraction, needs_review
│   │   ├── normalize/                # email/phone/id-document normalizers
│   │   ├── resolution/               # the engine: ResolutionStrategy (candidate scoring),
│   │   │                             #   DeterministicMatcher, MergeService, UnmergeService,
│   │   │                             #   ExplainService, advisory-lock guard
│   │   ├── survivorship/             # golden-profile derivation rules
│   │   └── persistence/              # JdbcClient DAOs, explicit SQL (all tenant-scoped)
│   └── resources/
│       ├── application.yaml
│       └── db/migration/             # Flyway V1__*.sql ...
└── test/
    └── java/io/guestgraph/
        ├── resolution/               # TDD table-driven scenario tests (pure, no DB)
        ├── normalize/                # normalizer unit tests
        ├── integration/              # Testcontainers: repository + end-to-end API tests
        └── contract/                 # API ↔ openapi.yaml conformance tests
```

**Structure Decision**: Single Maven module at the repository root (constitution: "single
module until slice 2 forces modularization — do not pre-modularize"). Packages, not modules,
separate the engine (`resolution`, `normalize`, `survivorship`) from transport (`api`, `auth`)
and storage (`persistence`), keeping the engine unit-testable without Spring or a database.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty.
