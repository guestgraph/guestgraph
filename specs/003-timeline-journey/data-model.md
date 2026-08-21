# Data Model: Guest Timeline & Attributed Decisions

**Feature**: `003-timeline-journey` | **Date**: 2026-08-21 | Migration: `V3__timeline_and_actors.sql`

Strictly additive. No slice-1 or slice-2 table changes shape; every new column is nullable or
defaulted, so existing rows and existing submitters are unaffected (SC-008).

---

## New table: `record_object`

An optional immutable companion of `source_record`, following the pattern already set by
`record_identifier` and `record_block_key`: derived at ingest, never updated, deleted only under
lawful erasure.

| Column | Type | Notes |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL — Constitution I |
| source_record_id | uuid | NOT NULL, UNIQUE, FK → `source_record` (1:1 optional) |
| source_system_id | uuid | NOT NULL, FK → `source_system` — denormalised from the parent so the roster lookup is a single-table index scan; the object namespace includes it |
| object_type | text | NOT NULL — `reservation` first; a value, not an enum, so further types need no migration |
| object_id | text | NOT NULL — the source's own id for the object |
| object_role | text | NOT NULL CHECK IN (`PRIMARY_GUEST`, `ADDITIONAL_GUEST`, `BOOKER`) |
| object_position | int | NULL — the position the source listed this person at. Descriptive only; confers no identity across versions (FR-003) |
| object_version | timestamptz | NOT NULL — the instant the source object itself records as last modified. Chronological comparison decides the current roster (FR-008, FR-019) |
| business_start | timestamptz | NULL — e.g. arrival. Primary timeline sort key (FR-004a) |
| business_end | timestamptz | NULL — e.g. departure. Stored and displayed, never validated against `business_start` |
| created_at | timestamptz | NOT NULL DEFAULT now() |

**Indexes**

- `record_object_roster_idx (tenant_id, source_system_id, object_type, object_id, object_version DESC)`
  — the roster lookup: all observations of one object, newest version first.
- `record_object_record_idx (tenant_id, source_record_id)` — join back from a guest's links.

**Constraints and rules**

- Rows are inserted once and never updated: no repository exposes an update path, matching
  `record_identifier` and `record_block_key`, which likewise carry no append-only DELETE guard —
  that trigger is reserved for `source_record` and `merge_event`, the rows the audit trail
  depends on. Companions are removed with their parent along the lawful-erasure path.
- A record whose submitted `object_version` is absent or unparseable gets **no row here** — the
  `source_record` is still stored and carries a `needs_review` reason (FR-024). This keeps
  `object_version NOT NULL` honest and keeps the deriver free of a null-version branch.
- No CHECK relating `business_end` to `business_start`: the service records the source's calendar,
  it does not police it.

---

## Changed table: `merge_event` (additive)

| Column | Type | Notes |
|---|---|---|
| actor_type | text | NULL CHECK IN (`SYSTEM`, `HUMAN`, `AGENT`) — NULL means recorded before this slice; renders as unattributed (FR-015) |
| actor_id | text | NULL — the credential's actor name, or the individual named by `X-Actor-Id` |

Append-only and immutable as before. Automatic resolution always writes `SYSTEM` plus the matcher
name already carried in `matcher_name` (FR-012).

## Changed table: `negative_match_rule`

| Column | Type | Notes |
|---|---|---|
| actor_type | text | NULL CHECK IN (`SYSTEM`, `HUMAN`, `AGENT`) — who created the split |
| actor_id | text | NULL |
| lifted_at | timestamptz | NULL — set when the rule stops gating. NULL means active |
| lifted_actor_type | text | NULL CHECK IN (`SYSTEM`, `HUMAN`, `AGENT`) — who lifted it |
| lifted_actor_id | text | NULL |

**Rules are lifted, never deleted** (FR-016a). Both the explicit deletion endpoint and the
automatic lift that follows confirming a match across a rule stamp `lifted_at` plus the lifting
actor; neither removes the row. The gate predicate gains `lifted_at IS NULL`.

This is the one place V3 is not purely additive: the existing
`UNIQUE (tenant_id, record_a, record_b)` is replaced by a partial unique index

```sql
CREATE UNIQUE INDEX negative_match_rule_active_pair_idx
    ON negative_match_rule (tenant_id, record_a, record_b) WHERE lifted_at IS NULL;
```

so a pair that is split, lifted, and split again can carry a second rule while the lifted one
stays readable. Safe here because nothing is released and no consumer depends on the constraint.

Recording the lifting actor is what makes the future carve-out — an agent may not override a
human's explicit split — a single-row comparison rather than a cross-table join (FR-016; enforced
in a later slice). It also closes an audit gap slice 2 left open: the automatic lift on confirm
currently deletes the rule outright, so today nothing records that a split was ever overridden.

## Changed table: `api_key` (additive)

| Column | Type | Notes |
|---|---|---|
| actor_type | text | NOT NULL DEFAULT `HUMAN` CHECK IN (`HUMAN`, `AGENT`) — the trust boundary: a request can never record a type its credential does not grant (FR-014) |
| actor_name | text | NOT NULL, backfilled once from `label`, then independent — `label` is key administration, `actor_name` is who the key acts as |

Existing credentials therefore become human-operated, which is the documented assumption and
leaves no decision unattributed.

---

## Derived (not persisted)

### Roster

All `record_object` rows sharing (tenant, source system, object type, object id) at one
`object_version`. The highest version present is the **current roster** and alone determines who is
on the object (FR-002). Never stored — see research R1.

### Association (timeline entry)

Derived per guest by `AssociationDeriver`:

| Field | Derivation |
|---|---|
| sourceSystem, objectType, objectId | from the observation's `record_object` |
| role, position | from the current-roster observation for this guest |
| status | `CURRENT` when the current roster contains an observation with this role resolving to this guest; `ENDED` when the guest appears only in older versions (FR-003, FR-007) |
| successorGuestId | for `ENDED` only, and only when exactly one guest holds that role in the current roster; otherwise null (FR-007) |
| currentObservation | the current-roster observation for `CURRENT`; the guest's newest own observation for `ENDED` |
| observationCount | observations of this object and role across all versions and guests |
| businessStart, businessEnd | from the observation the entry shows: the current-roster one while `CURRENT`, the guest's own newest one once `ENDED` — an ended entry reports the dates as they stood when that guest held the booking |
| ordering | `business_start`, falling back to the observation's `record_timestamp`/`received_at`; ties broken by objectId for stable paging (FR-004a) |

**Invariants** the deriver must hold, each a scenario test:

- A guest holds an association for a given (object, role) at most once, even when two persons on
  one version resolve to the same guest.
- An observation of an older version never displaces a newer roster, whatever order it arrived in.
- A record with no `record_object` row never becomes an association (FR-005, FR-024).
- Every field is recomputable from `record_object` + `resolution_link` alone (FR-010).

### Actor

| Field | Source |
|---|---|
| type | the authenticated credential's `actor_type`; `SYSTEM` for engine-initiated events |
| id | `X-Actor-Id` header when present, else the credential's `actor_name` |

A request carrying `X-Actor-Type` that disagrees with its credential is refused with RFC 9457 400
and records nothing (FR-014).

---

## Ingest contract additions

`POST /api/v1/records` gains an optional `sourceObject` block. `externalKey` semantics are
unchanged — it stays an opaque duplicate-detection token the service never parses (FR-001a); the
`{objectId}:{role}:{version}` convention remains the connector-side recipe for producing a unique
one (FR-018).

| Field | Required within the block | Maps to |
|---|---|---|
| type | yes | `object_type` |
| id | yes | `object_id` |
| role | yes | `object_role` |
| position | no | `object_position` |
| version | yes | `object_version` |
| businessStart / businessEnd | no | `business_start` / `business_end` |

`recordTimestamp` MUST equal `sourceObject.version` (FR-020); a mismatch is a `needs_review` reason,
not a rejection.

---

## Test-harness follow-ons (mandatory)

- Add `record_object` to the `TRUNCATE` list in `PostgresIntegrationTest.resetDatabase`, or every
  integration test fails on FK truncate errors.
- Run `./scripts/regen-er.sh` in the same change — CI's er-drift job compares the committed
  diagram against a fresh render.
