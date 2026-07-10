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

## Slice 3 — Timeline / journey

### R3-1: "What reservations does this guest have?" (current associations, not observations)

Slice 1 answers *"what did we observe about this person"* (`GET /guests/{id}/records`);
it deliberately does not answer *"what does this person currently have"*. Multiple
observations of the same source object (e.g. Apaleo reservation `R1` whose guest was
edited from person A to person B) live as independent immutable records on different
guests — both guests' record lists reference R1, with no supersession link.

Slice 3 must make source objects (reservation first) first-class **events** on resolved
guests:

- Group observations by business-object identity (reservation id from the payload) and
  role slot (primaryGuest / additionalGuests[n] / booker).
- Later observations of the same `(object, slot)` supersede earlier ones: the event moves
  to the guest of the latest observation.
- Query contract: for the A→B reassignment case, guest B's timeline returns R1;
  guest A returns nothing for R1 (or an explicitly closed/transferred association —
  spec decision).
- The full observation history stays reachable (Constitution II — nothing is lost,
  supersession is a view, not a deletion).

## Slice 4 — Connectors

### R4-1: externalKey convention for mutable, multi-person source objects (Apaleo pattern)

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
- **Emit only on person-data change**: the source bumps `modified` on *any* reservation
  edit (dates, room, price). Emitting person records for every edit is identity-neutral
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
