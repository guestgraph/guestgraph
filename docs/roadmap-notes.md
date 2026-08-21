# Roadmap notes — requirements captured for future slices

Requirements that surfaced during slice 1 but belong to later slices. Each slice's
`/speckit-specify` run MUST consume its section here.

## Cross-slice — capability parity with mutable-record identity systems

Identity services built on mutable rows (one row per source object, upserted in place)
offer conveniences that immutability removes; each needs an audit-preserving
replacement:

- **R-X1 Steward corrections (was: `PATCH /guest-identities`)** — corrections enter as
  ordinary immutable records via a built-in `manual-corrections` source system, so
  survivorship surfaces them and the audit trail shows who corrected what and when.
  Later: a first-class steward endpoint that writes such records.
- **R-X2 Correction protection / "our data wins" (was: invalid-email guard)** —
  recency-only survivorship lets the next source update overwrite a steward correction.
  Survivorship v2 needs per-source trust ranking (manual-corrections > PMS > channel
  manager) and/or steward field-pinning; also suppress values whose extraction was
  flagged invalid from the golden profile (today a malformed email still appears in
  `extracted`, only flagged).
- **R-X3 Forced manual attach/merge (was: `PATCH /persons` re-link)** — detach exists
  (unmerge) and queued conflicts exist (match-review), but a steward cannot yet merge
  two guests without a pending review. Add an audited manual-merge operation
  (REVIEW_CONFIRM-style event, matcher `manual-merge`).
- **R-X4 Storage growth** — append-per-observation grows where the old upsert did not.
  R4-1's emit-on-change rule removes most noise; if growth ever matters, add a
  retention/compaction policy for superseded observations that preserves the
  MergeEvent audit chain.
- **R-X5 The guest id as an external reference (was: a stable primary key)** — the point
  of a golden profile is that other systems can hold its `guestId` as *the* authoritative
  reference for a person. Today they cannot: a merge deletes the absorbed guest
  (`ResolutionEngine.execute` → `deleteGuest`) and `GET /guests/{absorbedId}` then returns a
  bare 404. The reference breaks on exactly the event this product exists to produce. An
  unmerge that empties a guest retires an id the same way (`UnmergeOperation`), so the
  mapping is not always 1:1.

  Observed, not inferred: ingest two records that resolve separately, then one carrying both
  identifiers. The merge reports `MERGED`, and `GET /guests/{absorbedId}` answers
  `404 {"detail":"No guest … in this tenant"}` — as if the person had never existed.

  Nothing is lost — `merge_event` is append-only and records both the survivor (`guest_id`)
  and `absorbed_guest_ids`, so the answer is already stored. What is missing is a query that
  walks it. Needed: retired ids resolve instead of 404ing, e.g. `GET /guests/{id}` returning
  `200 {"status":"MERGED","currentGuestId":…,"mergedAt":…}` — an HTTP-shaped redirect for
  identity — following merge chains transitively (X→Y→Z), and returning the several current
  ids when an unmerge fanned one out. Until this exists, integrators must be told plainly
  that a stored `guestId` can dangle, because the failure is silent and their foreign key
  looks fine right up until it doesn't.

  Cheap, self-contained, and it changes what GuestGraph *is* to an integrator: not a tool
  that de-duplicates a report, but the system of record for identity across the estate.

## Slice 2 — Probabilistic matching (additions) — ✅ consumed by specs/002-probabilistic-matching

- **R2-1 Negative match rules (persistent do-not-merge)** — ✅ delivered in slice 2 — v1 unmerge exclusions bind
  only the replay of the detached records; a *fresh* record carrying the shared
  identifier legitimately re-merges the guests (visibly, via a MERGE event). When a
  steward has explicitly split two people, matchers should be able to consult a
  persistent negative rule (e.g. suppressed identifier↔guest or guest-pair edges,
  written by unmerge/reject decisions) so the correction survives new evidence unless a
  human confirms otherwise. Natural companion to the review queue; per-tenant
  perfect-match/affiliate-style identifier quality rules belong to the same family.

## Slice 5 — Commercial layer / MCP

### R5-1: AI agent as merge steward (real goal, not a nice-to-have)

The review queue is deliberately agent-ready: entries carry per-signal score breakdowns,
`explain` + `/records` expose full evidence, decisions are exactly-once and reversible,
and `merge_event.matcher_name`/`evidence` already accommodate an agent identity and its
rationale. An AI steward is structurally "another imperfect matcher" — the same safety
machinery (review bands, unmerge, negative rules) that gates probabilistic scores gates
the agent.

**Operating model**: three-tier stewardship — rules decide the clear cases, the agent
(over MCP tools mapping 1:1 to the REST surface: list/decide reviews, explain, records)
decides high-confidence reviews and escalates ambiguous ones to a human with a
summarized recommendation.

**Prerequisites to build (small, some earlier than slice 5):**

1. ~~**Actor identity** on decisions~~ — ✅ delivered in slice 3. Credentials are registered as
   human- or agent-operated and carry a name; that type is the ceiling a request can never widen,
   though a request may name the individual behind a shared credential. Merge events, review
   decisions, unmerges, and do-not-merge rules all record it. Rules are now *lifted* rather than
   deleted, so prerequisite 3 below has the data it needs: the actor who overrode a split sits
   beside the actor who made it.
2. **Scoped API credentials** — a review-only key: read + decide reviews, but no
   unmerge, no config changes, no lifting of negative rules.
3. **FR-011 carve-out** — confirming across a do-not-merge rule lifts the rule; for
   agents this must be restricted: an agent never overrides a *human's* explicit split.
   Now enforceable: slice 3 records both the creating and the lifting actor on the same rule row,
   so the check is a single-row comparison. Deliberately not enforced yet — it belongs with the
   scoped credentials in (2).
4. **PII/data-residency posture** — review evidence is personal data; an MCP-connected
   agent ships it to a model provider. Needs tenant consent surface and likely an
   on-prem/EU-residency model option in the commercial offering.

## Cross-cutting decisions taken in later slices

- **One paging idiom.** Slice 3 moved `/match-reviews` and `/negative-rules` off raw
  `limit`/`offset` onto the same opaque keyset cursor the timeline uses. Offsets are a contract
  commitment that foreclose moving a read into SQL or changing an ordering; a cursor keeps that
  replaceable, and seeks rather than scanning-and-discarding on deep pages. Any new paged endpoint
  uses `api/Cursor.java`.

## Scale levers (when volume demands, not before)

- `ResolutionEngine.rebuildGuest` is O(records-on-guest) per ingest and loads full rows
  including jsonb payloads; for crowded guests, first switch to a projection without
  `payload` (survivorship never reads it), then incremental profile update.
- Per-tenant advisory lock serializes ingest within a tenant (~30–100 records/s); bulk
  backfills of millions per tenant want a bulk-import mode that pre-partitions records
  by identifier cluster.

## Slice 3 — Timeline / journey

### R3-1: "What reservations does this guest have?" — ✅ consumed by specs/003-timeline-journey

Slice 1 answers *"what did we observe about this person"* (`GET /guests/{id}/records`);
it deliberately does not answer *"what does this person currently have"*. Multiple
observations of the same source object (e.g. Apaleo reservation `R1` whose guest was
edited from person A to person B) live as independent immutable records on different
guests — both guests' record lists reference R1, with no supersession link.

Slice 3 made source objects (reservation first) first-class **associations** on resolved guests.
Note what changed from the sketch below: rather than grouping by `(object, role slot)` and
superseding slot by slot, **the object version became the unit of supersession** — the newest
version's complete person roster determines who is on the object, and persons are never matched
across versions. Sources carrying entity-less persons give no id to follow across edits, so slot
tracking would have to guess, and would report a reassignment every time a guest list shrank. The
roster model also makes *removal* detectable, which no slot scheme handles honestly.

The original sketch:

- Group observations by business-object identity (reservation id from the payload) and
  role slot (primaryGuest / additionalGuests[n] / booker).
- Later observations of the same `(object, slot)` supersede earlier ones: the event moves
  to the guest of the latest observation.
- Query contract: for the A→B reassignment case, guest B's timeline returns R1;
  guest A returns nothing for R1 (or an explicitly closed/transferred association —
  spec decision). **Decided**: omitted by default, returnable with `includePast=true` marked
  ENDED, naming a successor only for a genuine one-to-one handover of the role.
- The full observation history stays reachable (Constitution II — nothing is lost,
  supersession is a view, not a deletion).

## Slice 4 — Connectors

**Consume R-X5 with this slice.** Connectors are the first real holders of a `guestId`: writing
one back into a PMS or CRM is what makes GuestGraph the system of record rather than a report.
That write-back is unsafe until a retired id resolves instead of 404ing, because the reference
breaks precisely when a merge happens — and a connector cannot tell that it broke. Either build
the resolution endpoint in this slice, or state the constraint in the connector contract so no
integrator stores an id believing it is stable.


### R4-1: externalKey convention for mutable, multi-person source objects (Apaleo pattern) — contract published by specs/003-timeline-journey; connectors remain slice 4

`externalKey` identifies an *observation*, not the source object (see slice-1 API
contract). For PMS reservations carrying entity-less persons the convention is:

```
{reservationId}:{personRole}:{entityModifiedTimestamp}
e.g. XPGMSXGF-1:primaryGuest:2026-07-09T14:30:00Z
     XPGMSXGF-1:additionalGuests[0]:2026-07-09T14:30:00Z
```

- **One record per person per version** — a reservation version with 3 persons emits 3
  records; the role segment prevents dedup-key collisions.
- **Version discriminator = the entity's own `modified` timestamp**, not the webhook
  event id: derivable from source state alone, therefore idempotent across webhook
  retries, duplicate change-pings that fetch the same final state, and full
  backfills/re-syncs. Two edits within timestamp granularity collapse to one
  observation (acceptable — the later state wins anyway).
- `recordTimestamp` = the same `modified` value, so survivorship and slice-3
  supersession order observations identically.
- **Emit the complete roster, only when people changed** *(amended by slice 3 — was
  "emit only on person-data change")*: a version carrying only the person who changed would read
  as a booking that lost its other guests, because the newest version's roster **is** the answer
  to who is on the object. So when any person's data or the guest list changed, emit every person
  on that version. Edits touching no person at all still emit nothing, which is where essentially
  all of the noise reduction lives. The original rationale, unchanged: the source bumps `modified`
  on *any* reservation edit (dates, room, price). Emitting person records for every edit is
  identity-neutral
  (they just re-attach) but pollutes the observation history and inflates the
  records-per-identifier count that feeds the review threshold — a chatty reservation
  could push a normal guest's email over the threshold and cause false review parkings.
  The connector MUST hash the extracted person fields per `(reservation, role)` slot and
  emit only when the hash changed. Stateless alternative: content-derived key
  `{reservationId}:{role}:{hash(personFields)}` lets the server dedup via
  DUPLICATE_IGNORED — but an A→B→A revert then reuses A's original key/timestamp, which
  breaks slice-3 "latest observation wins" ordering; prefer the stateful variant.
- **Field-mapping rule**: reservation-level contact data that is not personal (agency
  phone, property email, shared office numbers) MUST NOT be extracted as guest
  identifiers — persistent non-personal identifiers on a reassigned reservation would
  transitively merge different people (slice-1 review threshold is the backstop, not
  the fix).
