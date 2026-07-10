# Implementation Plan: Probabilistic Matching

**Branch**: `002-probabilistic-matching` | **Date**: 2026-07-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-probabilistic-matching/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Add a rule-based fuzzy matcher (`fuzzy-rules-v1`) behind the slice-1 `ResolutionStrategy`
seam: blocking keys computed at ingest yield candidates that share no exact identifier;
a weighted similarity score (name/birthdate/phone/email/address signals with per-signal
breakdown) routes each candidate into three per-tenant bands — auto-merge (ships off),
review queue, discard. Two engine gates apply to every decision including deterministic
ones: persistent do-not-merge rules (written by unmerge/reject, lifted by confirm or
deletion) and identifier quality rules (IGNORE / PERFECT_MATCH / MASKED_ALIAS with
built-in OTA relay-domain defaults). A per-tenant matching-config API completes the
slice. All slice-1 contracts and deterministic behavior unchanged; migration V2 is
additive only.

## Technical Context

**Language/Version**: Java 25 (virtual threads), unchanged slice-1 stack

**Primary Dependencies**: existing (Spring Boot 4.1, data-jpa, MapStruct, Flyway) plus
two small Apache-2.0 additions: commons-text (Jaro-Winkler similarity) and
commons-codec (Double Metaphone phonetics)

**Storage**: PostgreSQL 18 — three new tenant-scoped tables (`record_block_key`,
`negative_match_rule`, `identifier_quality_rule`) + two `tenant` columns, additive
Flyway V2; no changes to existing tables or rows

**Testing**: slice-1 harness unchanged — TDD golden-pair scenario corpus (pure JVM via
`InMemoryGraph`), Testcontainers integration suites, ArchUnit guardrails, OpenAPI
drift gate extended to the new endpoints

**Target Platform**: unchanged (Linux server / JVM 25, Docker Compose dev)

**Project Type**: same single Spring Boot service, single Maven module

**Performance Goals**: single-record ingest stays < 1 s (SC-006): fuzzy adds ~5 indexed
block-key lookups + in-memory scoring of a small candidate set per ingest

**Constraints**: auto-merge threshold defaults to 1.0 (off) — no fuzzy merge without
explicit tenant opt-in; no silent merge may ever cross a negative rule; deterministic
resolution takes precedence and is byte-for-byte regression-guarded (SC-008); all new
tables tenant-scoped with composite constraints (Constitution I)

**Scale/Scope**: ~5 block keys per record (~5× record_identifier row volume);
negative/quality rules O(100s) per tenant; 6 new endpoints, 3 new entities

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*
*Source: `.specify/memory/constitution.md` v1.0.0*

- [x] **Tenant isolation (I)**: all three new tables carry `tenant_id` with composite
      uniqueness/indexes; config and rules are per-tenant; built-in defaults are code
      constants applied per-tenant at evaluation time, not shared rows.
- [x] **Immutable source records (II)**: block keys are insert-only companions of the
      record (like `record_identifier`); no existing table is altered; profile stays
      derived (masked-email guard is a pure survivorship rule).
- [x] **No silent data loss (III)**: quality rules change matching and derivation only —
      records, identifiers, and payloads are stored in full regardless of rules (FR-016).
- [x] **Explainable & reversible resolution (IV)**: fuzzy decisions carry matcher name +
      score-as-confidence + per-signal breakdown in review reasons and MergeEvent
      evidence; auto-merges remain unmergeable; negative rules make reversals *stick*.
      `ResolutionStrategy` contract unchanged — fuzzy is a second implementation, as
      slice 1 promised.
- [x] **API-first (V)**: thresholds and rules manageable only via `/api/v1/config/...`
      and `/api/v1/negative-rules`; RFC 9457 errors incl. config validation (FR-018).
- [x] **TDD on the resolution engine (VI)**: scorer, banding, and both gates are engine
      logic — failing golden-pair/gate scenarios first, pure JVM, then implementation;
      invariant test "no auto-merge below threshold".
- [x] **Stack & shape**: same service, same module; only two small Apache-2.0 utility
      libraries added.
- [x] **Open-core boundary**: agent stewardship, scoped keys, OIDC stay commercial
      (roadmap R5-1); everything here is core.
- [x] **GDPR readiness**: new tables reachable via tenant_id + record ids; block keys
      derive from record data and are enumerable for future erasure.

**Post-design re-check (after Phase 1)**: PASS — data-model.md and contracts introduce
no violations; Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/002-probabilistic-matching/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── openapi.yaml     # NEW endpoints only; conformance gate unions all features
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root — additions to the slice-1 layout)

```text
src/
├── main/
│   ├── java/io/guestgraph/
│   │   ├── api/
│   │   │   ├── MatchingConfigController.java      # GET/PUT /config/matching
│   │   │   ├── IdentifierRuleController.java      # GET/POST/DELETE /config/identifier-rules
│   │   │   └── NegativeRuleController.java        # GET/DELETE /negative-rules
│   │   ├── normalize/
│   │   │   ├── NameNormalizer.java                # diacritic folding, casing
│   │   │   └── BlockKeys.java                     # phonetic/initials/suffix/localpart keys
│   │   ├── resolution/
│   │   │   ├── CompositeStrategy.java             # deterministic first, fuzzy on the rest
│   │   │   ├── FuzzyMatcher.java                  # fuzzy-rules-v1: features → score → band
│   │   │   ├── MatchSignals.java                  # per-signal breakdown value type
│   │   │   ├── NegativeRuleGate.java              # downgrades merges crossing a rule
│   │   │   ├── QualityRuleGate.java               # IGNORE / PERFECT_MATCH / MASKED_ALIAS
│   │   │   └── MatchingPolicy.java                # per-tenant bands (thresholds)
│   │   ├── survivorship/                          # masked-email guard in GoldenProfileDeriver
│   │   └── persistence/
│   │       ├── entity/ + repo/ + mapper/          # 3 new entities/repos, mappings
│   │       └── PostgresGraph.java                 # new GraphPort methods
│   └── resources/db/migration/V2__probabilistic_matching.sql
└── test/
    └── java/io/guestgraph/
        ├── resolution/                            # golden-pair corpus + gate scenarios (TDD)
        ├── normalize/                             # name/phonetic/block-key unit tests
        └── integration/                           # config API, rules, end-to-end fuzzy flows
```

**Structure Decision**: unchanged single module; fuzzy logic stays inside the pure
`resolution` package behind `GraphPort` (extended with block-key lookup, guest-profile
fetch, rule queries), so the golden-pair corpus runs on `InMemoryGraph` exactly like
slice 1's scenarios.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty.
