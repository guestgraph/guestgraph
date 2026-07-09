# Data Model: Core Identity Resolution Service

**Feature**: 001-core-identity-resolution | **Date**: 2026-07-09
**Store**: PostgreSQL (Flyway-managed schema). All ids are `uuid` (v7 preferred for index
locality). All tables carry `tenant_id` and every uniqueness constraint is composite with it
(Constitution I). Timestamps are `timestamptz`.

## Entity overview

```
tenant 1──n api_key
tenant 1──n source_system 1──n source_record n──1 guest      (via resolution_link)
tenant 1──n guest 1──n identifier
tenant 1──n merge_event                                       (audit, append-only)
tenant 1──n match_review
source_record 1──n record_identifier                          (what the record contributed)
```

## tenant

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| slug | text | UNIQUE, lowercase kebab |
| name | text | NOT NULL |
| review_threshold | int | NOT NULL DEFAULT 10 — max records sharing an identifier before a match is suspicious (FR-017, R9) |
| created_at | timestamptz | NOT NULL |

## api_key

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | FK → tenant, NOT NULL |
| key_hash | text | NOT NULL, UNIQUE — SHA-256 of the key; plaintext never stored (R6) |
| label | text | NOT NULL |
| created_at | timestamptz | NOT NULL |
| revoked_at | timestamptz | NULL — revoked keys fail auth |

## source_system

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | FK → tenant, NOT NULL |
| code | text | NOT NULL — e.g. `opera-pms`; UNIQUE (tenant_id, code) |
| name | text | NOT NULL |
| created_at | timestamptz | NOT NULL |

## source_record  *(immutable — Constitution II)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | FK → tenant, NOT NULL |
| source_system_id | uuid | FK → source_system, NOT NULL |
| external_key | text | NOT NULL — record id in the source; UNIQUE (tenant_id, source_system_id, external_key) = ingest dedup key |
| payload | jsonb | NOT NULL — original as received; UPDATE-guard trigger (R2) |
| extracted | jsonb | NOT NULL — normalized profile fields parsed from the payload |
| record_timestamp | timestamptz | NULL — source-provided; survivorship falls back to received_at (R7) |
| needs_review | boolean | NOT NULL DEFAULT false (FR-006) |
| needs_review_reasons | jsonb | NOT NULL DEFAULT `[]` — e.g. `["email: unparseable"]` |
| received_at | timestamptz | NOT NULL |

**Immutability rule**: rows are inserted once; `payload`, `external_key`, ids and timestamps
never change. Trigger `source_record_immutable` rejects UPDATEs touching those columns.
Only `needs_review`/`needs_review_reasons` may be cleared by a future review flow.

## record_identifier

What a record contributed to matching — survives unmerge, drives threshold counting.

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| source_record_id | uuid | FK → source_record, NOT NULL |
| type | text | NOT NULL — enum: EMAIL, PHONE, LOYALTY_ID, ID_DOCUMENT, EXTERNAL_KEY |
| value_normalized | text | NOT NULL — E.164 / lowercased email / SHA-256 hash for ID_DOCUMENT (R5) |

UNIQUE (source_record_id, type, value_normalized).
INDEX (tenant_id, type, value_normalized) — candidate lookup + threshold count (R9).

## guest

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | FK → tenant, NOT NULL |
| profile | jsonb | NOT NULL — derived golden profile (R7); recomputed on every link change, never hand-edited |
| created_at | timestamptz | NOT NULL |
| updated_at | timestamptz | NOT NULL |

Guests with zero remaining links after unmerge are deleted (their records re-resolve);
merge losers are deleted after their links/identifiers move to the survivor — MergeEvent
retains their ids for the audit trail.

## identifier  *(guest-level, drives matching)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| guest_id | uuid | FK → guest, NOT NULL |
| type | text | NOT NULL — same enum as record_identifier |
| value_normalized | text | NOT NULL |

UNIQUE (tenant_id, type, value_normalized, guest_id); INDEX (tenant_id, type,
value_normalized). Note: the same identifier value MAY legitimately sit on multiple guests
(review-rejected shared email) — that is why uniqueness includes `guest_id`. Rebuilt from
linked records' `record_identifier`s on merge/unmerge.

## resolution_link

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| source_record_id | uuid | FK → source_record, NOT NULL, UNIQUE — a record belongs to exactly one guest |
| guest_id | uuid | FK → guest, NOT NULL |
| created_by_event_id | uuid | FK → merge_event, NOT NULL — which decision created this link |
| created_at | timestamptz | NOT NULL |

## merge_event  *(append-only audit — Constitution IV)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| kind | text | NOT NULL — enum: CREATE, ATTACH, MERGE, UNMERGE, REVIEW_CONFIRM, REVIEW_REJECT |
| guest_id | uuid | NOT NULL — surviving/affected guest |
| absorbed_guest_ids | jsonb | NOT NULL DEFAULT `[]` — MERGE: guests folded into survivor (uuid list) |
| source_record_ids | jsonb | NOT NULL DEFAULT `[]` — records whose links this event created/removed |
| matcher_name | text | NOT NULL — e.g. `deterministic-identifier-v1`, `manual-review`, `manual-unmerge` |
| confidence | numeric(4,3) | NOT NULL — 1.000 for deterministic (FR-009); probabilistic-ready |
| evidence | jsonb | NULL — matched identifiers (type + normalized value), threshold counts; the "why" shown by explain |
| excluded_guest_ids | jsonb | NOT NULL DEFAULT `[]` — UNMERGE: guests the detached records must not rejoin on replay (R8) |
| created_at | timestamptz | NOT NULL |

INDEX (tenant_id, guest_id, created_at) — explain chain traversal. `explain` collects events
for the guest plus, transitively, events of `absorbed_guest_ids`.

## match_review  *(Constitution IV; primary channel for slice-2 probabilistic)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| status | text | NOT NULL — PENDING → CONFIRMED \| REJECTED (one transition, enforced; FR-018) |
| source_record_id | uuid | FK → source_record, NOT NULL — the record whose match is uncertain |
| candidate_guest_id | uuid | FK → guest, NOT NULL — the guest it would merge with/attach to |
| identifier_type | text | NOT NULL |
| identifier_value | text | NOT NULL — normalized value that triggered suspicion |
| reason | text | NOT NULL — e.g. `identifier shared by 41 records (threshold 10)` |
| matcher_name | text | NOT NULL, confidence numeric(4,3) NOT NULL — probabilistic-ready |
| created_at | timestamptz | NOT NULL |
| decided_at | timestamptz | NULL |
| decision_event_id | uuid | NULL FK → merge_event — set on confirm/reject |

INDEX (tenant_id, status, created_at) — queue listing.

### State transitions

```
match_review:  PENDING ──confirm──▶ CONFIRMED   (writes REVIEW_CONFIRM merge_event, executes merge)
                        └─reject──▶ REJECTED    (writes REVIEW_REJECT merge_event, no merge)
               CONFIRMED/REJECTED are terminal; a second decision → 409.

source_record: (needs_review = true) ──future review flow──▶ (false)   [only mutable aspect]
```

## Validation rules (from FRs)

- Ingest requires an existing `source_system` (`code`) in the caller's tenant → else 400.
- Record with zero extractable valid identifiers → stored, `needs_review = true`, new guest.
- Identifier normalization failures flag the record, never drop it (FR-006).
- All reads/writes filter `tenant_id = :current` — repository layer enforces; no repository
  method exists without a tenant parameter.
- Merge/unmerge/review-confirm run inside `pg_advisory_xact_lock(hash(tenant_id))` (R3).

## GDPR readiness (Constitution — Compliance)

Every guest-linked row is reachable via `tenant_id` + (`guest_id` | `source_record_id`)
foreign keys: erasure/export can enumerate `guest → resolution_link → source_record →
record_identifier` plus `identifier`, `merge_event` (by guest ids), `match_review`. Nothing
stores plaintext ID documents (hash only). No design element precludes the future
deletion/export API.
