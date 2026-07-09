# GuestGraph — Core Identity Resolution Service: Design

**Date:** 2026-07-09
**Status:** Approved
**Scope:** Slice 1 of the GuestGraph roadmap — the OSS core service.

## Vision

GuestGraph is an open-source **guest identity graph** for hospitality. Guest data is scattered across PMS, POS, booking engines, loyalty programs, wifi portals, and review platforms — each with different keys and dirty data. GuestGraph ingests guest records from all sources and resolves which records refer to the same person, producing one trustworthy, explainable golden profile per guest.

**Business model:** open-core. The core engine is Apache 2.0. Commercial layer (managed SaaS at guestgraph.io, API hosting, MCP server, console, billing) is a separate private product built on the same core.

## Roadmap (each slice = one spec → plan → implement cycle)

1. **Core service** *(this design)* — ingest, deterministic resolution, query the graph. **Probabilistic-ready from day 1**: confidence scores, matcher metadata, and review queue are in the v1 data model so slice 2 lands without migration.
2. **Probabilistic matching** — fuzzy/ML resolution behind the strategy interface from slice 1. This is the long-term differentiator; deterministic ships first only because wrong merges are privacy incidents, and the safety machinery (explain, unmerge, review queue) must exist before probabilistic decisions are trusted.
3. **Timeline/journey** — unified per-guest event timeline on resolved identities
4. **Connectors** — pull from real systems (PMS first)
5. **Commercial layer** — managed multi-tenant SaaS, MCP server, console (private repo `guestgraph/cloud`)

## Decisions (fixed)

| Decision | Choice | Rationale |
|---|---|---|
| Core concept | Identity resolution engine that emits a guest graph | The hard, defensible problem; graph store alone is a commodity; timeline depends on resolution |
| Stack | Java 25 (virtual threads/Loom), Spring Boot 4, PostgreSQL, Maven | Founder expertise; I/O-heavy workload fits Loom; enterprise-credible OSS category |
| Shape | Single Spring Boot service with REST API | API-first from day 1; SaaS later hosts the same service; single module until slice 2 forces modularization |
| License | Apache 2.0 | Adoption over protection; moat = execution, connectors, managed/MCP layer |
| Tenancy | Tenant-scoped from day 1 | Cheap now, brutal to retrofit; SaaS runs identical code; hotel groups use tenants per brand/property |
| Resolution v1 | Deterministic matcher first, but engine is **probabilistic-ready**: confidence scores, matcher metadata, review queue in the v1 model | Wrong merges are privacy incidents — safety machinery before probabilistic decisions. Fuzzy/ML is slice 2 (possibly a sidecar: Python/ONNX), landing without migration |

## Data model (Postgres)

```
Tenant
  └── SourceSystem        e.g. "opera-pms", "loyalty-db" — registered per tenant
        └── SourceRecord  raw guest record as received: immutable JSON payload
                          + extracted normalized fields (names, emails, phones, ...)
Guest                     the golden profile — merged view over its source records
  ├── has many SourceRecords (via ResolutionLink)
  └── Identifier          normalized strong identifiers: EMAIL, PHONE,
                          LOYALTY_ID, ID_DOCUMENT (hashed), EXTERNAL_KEY
MergeEvent                audit log: which records/guests merged, which matcher
                          decided it, confidence score (deterministic = 1.0),
                          when — the basis for explain & unmerge
MatchReview               review queue: uncertain or suspicious matches awaiting
                          human confirm/reject (e.g. one email shared by 40
                          records — family/agency address). Used by deterministic
                          edge cases in v1; the primary channel for probabilistic
                          matches in slice 2.
```

Principles:

- **Source records are immutable.** Originals are never mutated or lost. The Guest profile is *derived* via survivorship rules (e.g. most recent non-null wins per field).
- **Every merge is explainable** (`explain` returns the MergeEvent chain) **and reversible** (unmerge removes the offending link and replays resolution).
- **Everything carries `tenant_id`**; all queries and constraints are tenant-scoped.

## Resolution engine (deterministic v1)

On ingest:

1. Normalize identifiers (lowercase/trim email, E.164 phone).
2. Find existing guests in the tenant sharing a *strong* identifier.
3. Outcomes: **0 matches** → create new Guest. **1 match** → attach record. **2+ matches** → merge those guests (transitive identity), recording MergeEvents.

- Runs synchronously in the ingest request (virtual threads make blocking cheap).
- `ResolutionStrategy` interface designed for **candidate scoring** (candidates in → scored match decisions out), not boolean matching — so the probabilistic matcher in slice 2 is a new implementation, not a redesign.
- Every merge decision records matcher name + confidence (deterministic = 1.0).
- Suspicious deterministic matches (e.g. an identifier shared by unusually many records) go to the `MatchReview` queue instead of auto-merging; the threshold is configurable per tenant.
- Concurrency safety: per-tenant Postgres advisory locks around merge operations.

## REST API (v1 surface)

```
POST /api/v1/source-systems              register a source system
POST /api/v1/records                     ingest record(s) → returns resolved guest id
GET  /api/v1/guests/{id}                 golden profile + identifiers
GET  /api/v1/guests/{id}/records         its source records
GET  /api/v1/guests/{id}/explain         why these records are one guest
POST /api/v1/guests/{id}/unmerge         split a wrong merge
GET  /api/v1/guests?identifier=...       lookup by normalized identifier
GET  /api/v1/match-reviews               pending uncertain matches
POST /api/v1/match-reviews/{id}          confirm or reject a queued match
```

Auth v1: per-tenant API keys. SaaS-grade auth (OIDC, SSO) belongs to the commercial layer.

## Error handling

- Invalid requests → RFC 9457 problem-details responses.
- Malformed-but-parseable records are stored flagged `needs_review` rather than dropped — data loss is the cardinal sin.

## Testing

- Resolution engine gets the heaviest coverage: table-driven scenario tests (record sets in → expected guest clusters out), including shared family emails, transitive merges, unmerge-then-reingest.
- Testcontainers for Postgres; API integration tests on top.
- TDD for the resolution engine.

## Out of scope for v1

The probabilistic *matcher implementation* (slice 2 — but its data-model hooks ship in v1), timeline, connectors, webhooks/eventing, UI/console, OIDC auth, multi-region, GDPR tooling (deletion/export API is a fast-follow candidate — flag in constitution as a known obligation).

## Workflow

Spec-driven with [spec-kit](https://github.com/github/spec-kit): `/speckit.constitution` (encodes the fixed decisions above) → `/speckit.specify` (this design as input for the first feature spec) → `/speckit.plan` → `/speckit.tasks` → `/speckit.implement`.
