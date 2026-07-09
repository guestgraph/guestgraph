<!--
Sync Impact Report
- Version change: (template) → 1.0.0 (initial ratification)
- Modified principles: n/a — first concrete version, derived from
  docs/superpowers/specs/2026-07-09-guestgraph-core-design.md ("Decisions (fixed)" table)
- Added sections:
  - Core Principles I–VI
  - Technology & Architecture Constraints
  - Compliance & Data Protection
  - Development Workflow & Quality Gates
  - Governance
- Removed sections: none (all template placeholders filled)
- Templates:
  - ✅ .specify/templates/plan-template.md — Constitution Check gates made concrete
  - ✅ .specify/templates/tasks-template.md — tests mandatory for resolution-engine work
  - ✅ .specify/templates/spec-template.md — no changes required (structure already aligns)
  - ✅ README.md — already consistent with these principles
- Deferred TODOs: none
-->

# GuestGraph Constitution

## Core Principles

### I. Tenant Isolation (NON-NEGOTIABLE)

Every persisted row, query, uniqueness constraint, lock, and API operation MUST be scoped by
`tenant_id`. No code path may read or write data across tenants. Resolution, lookup, and merge
operations operate strictly within a single tenant; concurrency controls (e.g. Postgres advisory
locks around merges) are per-tenant.

**Rationale:** Tenancy is cheap on day 1 and brutal to retrofit. The managed SaaS runs identical
code, and hotel groups use tenants per brand/property. A single cross-tenant leak in an identity
graph is a severe privacy incident.

### II. Immutable Source Records

Source records are stored exactly as received (raw JSON payload plus extracted normalized fields)
and are NEVER mutated or deleted by application logic. The Guest golden profile is *derived* from
its source records via survivorship rules (e.g. most recent non-null wins per field) and can always
be recomputed. Corrections arrive as new records, not edits.

**Rationale:** The original data is the ground truth that makes explain, unmerge, and resolution
replay possible. Once an original is altered, trust in the graph cannot be re-established.
(Lawful GDPR erasure is the sole exception — see Compliance & Data Protection.)

### III. Never Drop Parseable Data

Data loss is the cardinal sin. Malformed-but-parseable records MUST be stored and flagged
`needs_review` rather than rejected or silently dropped. Only requests that cannot be parsed at
all are refused, and the refusal MUST be an RFC 9457 problem-details response that tells the
caller exactly what was wrong.

**Rationale:** Source systems in hospitality are dirty by nature. A record discarded at ingest is
a guest interaction lost forever; a flagged record can be repaired and re-resolved.

### IV. Explainable, Reversible Resolution

Every merge decision MUST be recorded as a MergeEvent carrying: which records/guests merged, the
deciding matcher's name, its confidence score (deterministic = 1.0), and when. `explain` MUST be
able to return the full MergeEvent chain for any guest; `unmerge` MUST be able to remove an
offending link and replay resolution. Uncertain or suspicious matches (e.g. one identifier shared
by unusually many records) MUST go to the MatchReview queue instead of auto-merging, with the
threshold configurable per tenant. The `ResolutionStrategy` interface MUST remain a
candidate-scoring contract (candidates in → scored match decisions out), so the slice-2
probabilistic matcher is a new implementation, not a redesign, and lands without schema migration.

**Rationale:** Wrong merges are privacy incidents. The safety machinery — explainability,
reversibility, review queue, confidence metadata — must exist before any probabilistic decision
can be trusted, which is why it ships in v1 even though v1 matching is deterministic.

### V. API-First

Every capability of the engine MUST be reachable through the versioned REST API
(`/api/v1/...`). There are no engine features accessible only via internal calls, jobs, or the
database. All error responses MUST conform to RFC 9457 (problem details). Authentication in the
core is per-tenant API keys; SaaS-grade auth (OIDC, SSO) belongs to the commercial layer.

**Rationale:** The SaaS later hosts this exact service, and integrators build on the API from day
one. An API-first surface is the product contract.

### VI. Test-First on the Resolution Engine (NON-NEGOTIABLE)

The resolution engine MUST be developed with TDD: tests written first, seen failing, then
implemented. It receives the heaviest coverage in the codebase, as table-driven scenario tests
(record sets in → expected guest clusters out) including shared family emails, transitive merges,
and unmerge-then-reingest. Integration tests run against real PostgreSQL via Testcontainers, with
API integration tests on top.

**Rationale:** The resolution engine is the hard, defensible core of the product, and its failure
mode (a wrong merge) is a privacy incident. Correctness here cannot be retrofitted.

## Technology & Architecture Constraints

- **Stack (fixed):** Java 25 with virtual threads (Loom), Spring Boot 4, PostgreSQL, Maven.
  No alternative languages, frameworks, or datastores in the core without a constitution
  amendment. (Slice 2 MAY introduce a probabilistic-matcher sidecar, e.g. Python/ONNX, behind
  the `ResolutionStrategy` contract.)
- **Shape:** a single Spring Boot service exposing the REST API. Single Maven module until
  slice 2 forces modularization — do not pre-modularize.
- **Concurrency model:** I/O-heavy work runs on virtual threads; blocking code in request
  handling is idiomatic here. Resolution runs synchronously within the ingest request.
  Merge operations are serialized with per-tenant Postgres advisory locks.
- **License & open-core boundary:** everything in this repository is Apache 2.0. Commercial
  features (managed SaaS, hosting, MCP server, console, billing, OIDC/SSO) live in the private
  `guestgraph/cloud` repository. The core MUST NOT depend on commercial code, and commercial
  concerns MUST NOT gate or degrade core functionality.

## Compliance & Data Protection

- **GDPR deletion and export are known future obligations.** They are out of scope for v1 but
  the design MUST NOT preclude them: per-guest erasure and export must remain implementable
  across Guest, SourceRecord, Identifier, MergeEvent, and MatchReview data. Lawful erasure is
  the single sanctioned exception to Principle II (immutability).
- **Sensitive identifiers are minimized:** ID documents are stored hashed, never in plaintext.
  Identifiers are normalized (lowercased/trimmed email, E.164 phone) before storage and matching.
- All personal data access flows through tenant-scoped APIs (Principles I and V); there is no
  cross-tenant administrative read path in the core.

## Development Workflow & Quality Gates

- **Spec-driven delivery:** each roadmap slice runs the spec-kit cycle —
  constitution → `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`.
  Approved design docs live in `docs/`; feature specs in `specs/`.
- **Constitution Check:** every implementation plan MUST pass the Constitution Check gate
  (see plan template) before research and again after design. Violations require an explicit
  entry in the plan's Complexity Tracking table with the rejected simpler alternative.
- **Testing gates:** resolution-engine changes follow TDD (Principle VI) and MUST NOT merge
  without their scenario tests. All tests pass before merge. New API endpoints require
  integration tests and RFC 9457-compliant error behavior.
- **Data-safety review:** any change touching merge, unmerge, survivorship, or ingest MUST be
  reviewed against Principles II–IV (no mutation of originals, no silent data loss, full
  MergeEvent audit metadata).

## Governance

- This constitution supersedes all other development practices in this repository. Where a
  template, plan, or convention conflicts with it, the constitution wins and the conflicting
  artifact MUST be updated.
- **Amendments** are made by pull request that (a) edits this file, (b) states the rationale,
  (c) updates the Sync Impact Report comment, and (d) propagates changes to dependent templates
  (`.specify/templates/*.md`) and guidance docs in the same change. Amendments to items marked
  NON-NEGOTIABLE or to the fixed stack/license require explicit maintainer approval.
- **Versioning** follows semantic versioning: MAJOR for backward-incompatible removals or
  redefinitions of principles, MINOR for new principles or materially expanded guidance, PATCH
  for clarifications and wording fixes.
- **Compliance review:** every PR review verifies conformance with the Core Principles; every
  `/speckit-plan` run re-evaluates the Constitution Check. Complexity beyond the fixed shape
  (extra modules, services, datastores) must be justified in Complexity Tracking or rejected.

**Version**: 1.0.0 | **Ratified**: 2026-07-09 | **Last Amended**: 2026-07-09
