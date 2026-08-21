# Phase 0 Research: Guest Timeline & Attributed Decisions

**Feature**: `003-timeline-journey` | **Date**: 2026-08-21

All five spec-level unknowns were settled in `/speckit-clarify` and are recorded in the spec's
Clarifications section. This document resolves the *technical* unknowns those answers created.

---

## R1 — Derived on read, or materialised at ingest?

**Decision**: Associations are derived on read. Nothing about an association is persisted.

**Rationale**: FR-010 requires association state to be recomputable from the immutable observations
and their resolution links, and the cheapest way to guarantee that is to have no other state at
all. A materialised table would need maintaining on four separate triggers — ingest, merge,
unmerge, and review decisions — each an opportunity for the stored view to drift from the records
it claims to summarise. Derivation also makes FR-010a (partial roster during delivery) free: a
roster read mid-delivery simply reflects the observations that have landed, and self-heals with no
convergence logic, because nothing was written down to be wrong.

SC-006 (first page under 1 s at 500 associations) is the constraint that could have forced
materialisation. Sizing: 500 associations over objects averaging ~5 versions each is ~2,500
observation rows, fetched by two indexed queries. That is comfortably inside the budget.

**Alternatives considered**:

- *Materialised `guest_association` table maintained at ingest and on every graph mutation.*
  Rejected: four maintenance points, drift risk against Constitution II's "derived and
  recomputable" stance, and unnecessary at this scale. Recorded as a scale lever below.
- *Materialised `object_current_version` pointer only (a much smaller table).* Rejected for now
  for the same reason — it is the natural first step if profiling ever demands one, and it can be
  added without changing the API.

**Scale lever (not now)**: if a tenant appears with guests holding thousands of associations,
introduce a `source_object_current` projection keyed by (tenant, source system, object type,
object id) holding the max version, maintained at ingest. The read path collapses to one join and
the derivation rules stay where they are.

This is why the timeline pages by **keyset cursor**. For the timeline itself the cursor buys no
performance today — derivation happens in memory over the guest's full set, so seeking and
skipping cost the same. It is a bet on that scale lever: once paging moves into SQL, a keyset seek
avoids the scan-and-discard that `OFFSET` pays on deep pages, and because the cursor is opaque
that change needs no API version. Raw offsets would have been a contract commitment that
foreclosed it.

For consistency the two endpoints that already paged by raw offset — `/match-reviews` and
`/negative-rules` — migrate to the same cursor in this slice (R9).

---

## R2 — Where do the business-object fields live?

**Decision**: A new companion table `record_object`, one optional row per source record, carrying
the object identity, role, position, version, and business dates. Not columns on `source_record`.

**Rationale**: The repository already has two immutable companions of `source_record` —
`record_identifier` and `record_block_key` — created for exactly this shape of data: derived-at-
ingest facts about a record that not every record has. Following the precedent keeps
`source_record` and, importantly, its `source_record_immutable()` trigger untouched; adding seven
nullable columns there would mean extending that trigger's explicit column list, where an omission
silently makes a field mutable and violates Constitution II without any test noticing.

The table denormalises `source_system_id` from its parent record. That is deliberate: the object
namespace is (tenant, source system, object type, object id), so carrying the source system makes
the current-roster lookup a single-table index scan instead of a join back to `source_record`.

**Alternatives considered**:

- *Nullable columns on `source_record`.* Rejected: trigger-extension hazard above, and it widens
  the hottest table in the schema for a field set most records will not have.
- *A jsonb `object` field on `source_record`.* Rejected: the roster query needs indexed equality
  and ordering on these fields; hiding them in jsonb trades a clean index for expression indexes
  and loses the CHECK constraints.

---

## R3 — How is "current roster" computed, and where do the rules live?

**Decision**: Two tenant-scoped JPQL queries feed one pure-JVM deriver.

1. Query A: the distinct objects this guest has any observation of (via `resolution_link` →
   `record_object`).
2. Query B: every observation of those objects — regardless of which guest it resolved to —
   joined to its resolution link.
3. `AssociationDeriver` (pure, in `io.guestgraph.timeline`) groups by object, takes the highest
   `object_version` as the current roster, emits one association per (object, role, guest) present
   in it, marks guests present only in older versions as past, resolves the successor when exactly
   one guest holds that role now, deduplicates a guest appearing twice in one role, and orders by
   business start with the observation timestamp as fallback.

**Rationale**: Query B must span guests — the whole point of the roster is that a *different*
guest's observation at a newer version removes this guest from the booking — so a guest-scoped
query alone cannot answer it. Keeping the rules in Java rather than SQL means one implementation,
directly unit-testable on fixtures without a database, which matters because these rules (current
vs past, successor naming, dedup, ordering) are exactly where the subtle bugs live. Pushing them
into a correlated-subquery SQL statement would make them untestable without Testcontainers and
would tempt a second, divergent implementation for the in-memory tests.

The two queries are bounded by the guest's own object count, so the row volume is proportional to
what is being displayed, not to tenant size.

**Alternatives considered**:

- *One SQL statement with a correlated `MAX(object_version)` subquery.* Rejected: the derivation
  rules become SQL, so the only way to test them is against Postgres, and the past-association
  view needs a second, structurally different statement anyway.
- *Loading the guest's records and asking the engine.* Rejected: associations are a read model and
  make no resolution decisions; routing them through `GraphPort` would grow the engine seam for
  something the engine never consults.

---

## R4 — Threading actor identity into decisions

**Decision**: An `Actor` value (type + id) is an explicit parameter on the steward operations, not
ambient state in the engine. The credential supplies the type; the request may name the individual.

- `api_key` gains `actor_type` (`HUMAN` | `AGENT`, default `HUMAN`) and `actor_name`.
- `merge_event` and `negative_match_rule` gain `actor_type` (`SYSTEM` | `HUMAN` | `AGENT`) and
  `actor_id`, both nullable so pre-existing rows read as unattributed (FR-015).
- `ApiKeyFilter` resolves the credential's actor and binds it beside the tenant; the optional
  `X-Actor-Id` header refines the identity, and an `X-Actor-Type` that disagrees with the
  credential is a 400 (FR-014).
- `ResolutionEngine` always records `Actor.system(matcherName)` — it takes no actor parameter, so
  there is no path by which automatic resolution can be attributed to a person (FR-012).
- `UnmergeOperation.unmerge` and `ReviewDecisionOperation.decide` take an `Actor` argument.

**Rationale**: The engine is pure JVM behind `GraphPort` and its scenario tests construct it
directly; a `ThreadLocal` actor would either leak request scope into that purity or default
silently to something wrong under test. An explicit parameter makes the attribution visible at
every call site and impossible to forget. Binding type to the credential rather than the request
is the trust boundary the spec settled on: the header names a person, it authorises nothing.

**Alternatives considered**:

- *`ActorContext` ThreadLocal mirroring `TenantContext`.* Rejected for the engine's sake, above.
  `TenantContext` is justified because every code path needs the tenant; only three call sites
  need the actor.
- *Reusing `api_key.label` as the actor name.* Rejected: `label` is operator-facing key
  administration ("laptop key, rotated March"), a separate concern from who the key acts as.
  Existing rows backfill `actor_name` from `label` once, then the two diverge freely.

---

## R5 — Version as an instant, and the unusable-version path

**Decision**: `object_version` is `timestamptz NOT NULL` on `record_object`, compared
chronologically. A submission carrying object identity whose version is absent or unparseable
stores the `source_record` normally, adds a `needs_review` reason, and writes **no**
`record_object` row.

**Rationale**: FR-024 requires such a record to be stored and flagged yet to participate in no
roster. Omitting the companion row achieves both with no nullable-version special case in the
deriver, and it keeps `object_version NOT NULL` so the index stays dense. The record remains fully
visible through `GET /guests/{id}/records`, so nothing is lost (Constitution III).

Ties inside timestamp granularity are already absorbed by the ingest duplicate key when the
observation key matches, and where they do not, the spec accepts that the stored state stands.

---

## R6 — API surface

**Decision**: Two new endpoints plus additive fields on existing ones.

- `GET /api/v1/guests/{guestId}/timeline` — the guest's associations, paged, `includePast` to
  return ended ones marked with their successor.
- `GET /api/v1/source-objects/{sourceSystem}/{objectType}/{objectId}` — the object itself: its
  current roster and every observation in version order (FR-006).
- `POST /api/v1/records` gains an optional `sourceObject` block.
- Explain, review-decision, and negative-rule responses gain `actor`.

**Rationale**: Observation history belongs to the *object*, not to a guest — after a reassignment
the history spans two guests, and hanging it off one of them would make the other's copy
misleading. A dedicated object resource states that directly. `OpenApiConformanceTest` already
unions every `specs/*/contracts/openapi.yaml`, so a contract file for this slice is enrolled in the
two-way drift gate automatically, with no test change.

**Alternatives considered**:

- *`GET /guests/{id}/timeline/{objectId}/observations`.* Rejected: implies the history is the
  guest's, which is exactly the confusion this slice exists to remove.
- *Folding associations into the existing `GET /guests/{id}/records`.* Rejected: FR-005 requires
  that endpoint's contract to stay as it is, and the two answer genuinely different questions.

---

## R8 — Do-not-merge rules are lifted, not deleted

**Decision**: `DELETE /negative-rules/{id}` and the automatic lift on confirm both stamp
`lifted_at` plus the lifting actor instead of removing the row. The gate predicate gains
`lifted_at IS NULL`, and the pair uniqueness constraint becomes a partial index over active rules.

**Rationale**: FR-013 requires the actor on rule *deletion* as well as creation, and a deleted row
has nowhere to record it. Lifting is the same move this slice already makes for observations —
supersession as a view over a preserved record rather than destruction — so it introduces no new
concept and sits naturally under Constitution II.

It also puts both actors on one row, which is exactly the comparison the roadmap's agent carve-out
needs (R5-1 prerequisite 3: an agent never overrides a human's explicit split). Any design that
records the lift elsewhere turns that check into a join across tables whose rows may not
correspond. And it closes a gap slice 2 left: `NegativeMatchRuleRepo.liftBetween` is a hard delete
today, so confirming across a rule currently destroys the evidence that a split ever existed.

**Cost, stated plainly**: this is the only part of V3 that is not purely additive — the existing
`UNIQUE (tenant_id, record_a, record_b)` must be dropped for a partial unique index, or a pair
could never be split again after a lift. Acceptable because nothing is released and no consumer
depends on it; it would not be acceptable after tagging.

**Alternatives considered**:

- *Narrow FR-013 to rule creation only.* Rejected: lifting a rule **is** overriding a human's
  split, the precise action the actor data exists to gate later. Recording the creator but not the
  lifter would hollow out the feature's purpose.
- *Record lifts as a new `merge_event` kind.* Rejected: `merge_event.guest_id` is NOT NULL while a
  rule spans a record *pair*, so the event would need a synthesised guest id and would surface in
  one arbitrary guest's explain chain.
- *A separate `negative_rule_lift` audit table.* Rejected: a second table for one row's lifecycle,
  and the gate would still need to consult it on every decision.

---

## R9 — One paging idiom across the API

**Decision**: `/match-reviews` and `/negative-rules` migrate from raw `limit`/`offset` to the same
keyset cursor the timeline uses, sharing one encoder (`api/Cursor.java`). Done in this slice, not
deferred.

**Rationale**: Introducing the timeline with a cursor while two neighbouring endpoints expose
offsets would leave one versioned API with two paging idioms, and the longer that stands the more
expensive it is to undo — offsets are a contract commitment, and a released consumer paging by
offset cannot be migrated without a version bump. Nothing is released, so the change is free now
and never will be again.

It is not only cosmetic. Both endpoints are single-table native queries over ordered indexes
(`match_review_queue_idx`, `negative_match_rule` by `created_at DESC, id`), so a keyset seek
replaces `OFFSET`'s scan-and-discard — a real improvement on a long review queue, unlike the
timeline where derivation is in memory. It also fixes a correctness wart: under offsets, reviews
decided during a traversal shift the remaining rows and the client silently skips entries. Keyset
paging does not.

**Cost**: this modifies two earlier slices' endpoints, contracts, and API test suites, so SC-008's
"slice-1 and slice-2 suites pass unmodified" is narrowed to resolution behaviour with paging named
as the deliberate exception. `specs/001-.../contracts/openapi.yaml` and
`specs/002-.../contracts/openapi.yaml` are edited by the migration tasks in lockstep with the
controllers, not in advance — the conformance gate checks operations rather than parameters, so
nothing would catch a contract that described a surface the server does not serve.

**Alternatives considered**:

- *Regress the timeline to offsets for consistency.* Rejected: it would spread the weaker idiom
  and foreclose the R1 scale lever.
- *Leave two idioms and migrate later.* Rejected: "later" is after release, when it is a breaking
  change with consumers attached.

---

## R7 — Migration and test-harness consequences

**Decision**: One additive migration `V3__timeline_and_actors.sql`. No edits to V1 or V2.

The repository is untagged, so editing earlier migrations in place is still technically permitted,
but it would force every developer and CI worker to `docker compose down -v`. Additive is free
here because nothing in slices 1–2 needs changing — every new column is nullable or defaulted.

Two harness follow-ons are mandatory and easy to forget:

- `PostgresIntegrationTest.resetDatabase` truncates an explicit table list; `record_object` must
  be added or every integration test fails on FK truncate errors.
- `api_key.actor_name` is NOT NULL, and both INSERT sites for that table —
  `PostgresIntegrationTest.seedTenant` and `LocalDevSeeder` — use explicit column lists that omit
  it. Both must be updated in the same change, or every integration test and local dev startup
  fails on a fresh database. The migration's one-time backfill from `label` covers existing rows
  (needed before `SET NOT NULL` succeeds on a developer's non-empty local volume); it does nothing
  for future inserts from those two call sites.
- `./scripts/regen-er.sh` must run in the same change — CI's er-drift job fails otherwise.
