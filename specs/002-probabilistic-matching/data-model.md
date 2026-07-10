# Data Model: Probabilistic Matching

**Feature**: 002-probabilistic-matching | **Date**: 2026-07-10
**Store**: PostgreSQL, additive Flyway `V2__probabilistic_matching.sql`. All new tables
tenant-scoped; ids `uuid`; timestamps `timestamptz`. **No existing table or row changes**
except two new `tenant` columns with defaults — slice-1 data needs no migration.

## record_block_key  *(immutable companion of source_record, like record_identifier)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| source_record_id | uuid | FK → source_record, NOT NULL |
| type | text | NOT NULL — enum: NAME_PHONETIC_BIRTHYEAR, NAME_INITIALS_BIRTHDATE, PHONE_SUFFIX7, EMAIL_LOCALPART, EMAIL_MASKED |
| value_normalized | text | NOT NULL |

UNIQUE (source_record_id, type, value_normalized).
INDEX (tenant_id, type, value_normalized) — fuzzy candidate lookup, same shape as
`record_identifier_lookup_idx`. Insert-only, computed at ingest by the extractor.

Key derivations (all after `NameNormalizer` diacritic folding + lowercasing):

- `NAME_PHONETIC_BIRTHYEAR` — `doubleMetaphone(lastName) + ":" + birthYear` (both required)
- `NAME_INITIALS_BIRTHDATE` — `first(firstName) + first(lastName) + ":" + birthdate` (all required)
- `PHONE_SUFFIX7` — last 7 digits of any normalized phone
- `EMAIL_LOCALPART` — local part of a *real* (non-masked) email
- `EMAIL_MASKED` — the full normalized masked relay address

## negative_match_rule  *(steward splits that stick — R2-1/FR-009..012)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| record_a | uuid | FK → source_record, NOT NULL |
| record_b | uuid | FK → source_record, NOT NULL — CHECK (record_a < record_b) |
| origin | text | NOT NULL — enum: UNMERGE, REVIEW_REJECT, MANUAL |
| created_at | timestamptz | NOT NULL DEFAULT now() |

UNIQUE (tenant_id, record_a, record_b).
INDEX (tenant_id, record_a), INDEX (tenant_id, record_b) — cluster-membership checks
join through `resolution_link`. Semantics: the clusters containing `record_a` and
`record_b` must never be silently merged (any matcher). Lifecycle: created by unmerge
(each detached record × each remaining record) and review-reject (reviewed record ×
candidate cluster records); deleted by API or lifted automatically when a steward
confirms a review that a rule had downgraded (FR-011).

## identifier_quality_rule  *(per-tenant identifier trust — FR-013..016)*

| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| tenant_id | uuid | NOT NULL |
| identifier_type | text | NOT NULL — slice-1 IdentifierType enum (EMAIL for domain rules) |
| match_kind | text | NOT NULL — enum: EXACT, EMAIL_DOMAIN |
| value_normalized | text | NOT NULL — full value, or bare domain for EMAIL_DOMAIN |
| rule | text | NOT NULL — enum: IGNORE, PERFECT_MATCH, MASKED_ALIAS |
| note | text | NULL — steward's reason |
| created_at | timestamptz | NOT NULL DEFAULT now() |

UNIQUE (tenant_id, identifier_type, match_kind, value_normalized).
Built-in MASKED_ALIAS defaults for known OTA relay domains are **code constants**, merged
at evaluation time and listed read-only (`builtin: true`) by the API — not rows, so
product updates to the list need no migration.

Effects (evaluated at matching time, R2-4):

- `IGNORE` — the identifier produces no candidates and no merges; still stored on records.
- `PERFECT_MATCH` — merges via this identifier require exact normalized-name agreement,
  else the decision downgrades to review.
- `MASKED_ALIAS` — (email domains) no EMAIL identifier at extraction, `EMAIL_MASKED`
  block key instead, weak review-only fuzzy signal, survivorship guard
  (`emailMasked: true` in extracted; never overwrites a real email in the profile).

## tenant  *(two new columns)*

| Column | Type | Constraints |
|---|---|---|
| auto_merge_threshold | numeric(4,3) | NOT NULL DEFAULT 1.000 — fuzzy auto-merge band; 1.0 = off (FR-006) |
| review_floor | numeric(4,3) | NOT NULL DEFAULT 0.750 — below → discard |

CHECK (review_floor <= auto_merge_threshold). Slice-1 `review_threshold` (identifier
sharing count) unchanged; all three exposed by `/api/v1/config/matching`.

## Unchanged

`source_record`, `record_identifier`, `guest`, `identifier`, `resolution_link`,
`merge_event`, `match_review` — untouched. Fuzzy activity appears through existing
columns: `matcher_name = 'fuzzy-rules-v1'`, `confidence = score`, `evidence`/`reason`
carrying the per-signal breakdown, e.g.:

```json
{ "score": 0.88,
  "signals": { "name": {"value": 0.94, "weight": 0.45},
               "birthdate": {"value": 1.0, "weight": 0.25},
               "phoneSuffix": {"value": 0.0, "weight": 0.15} },
  "candidateOrigin": { "blockKey": "NAME_PHONETIC_BIRTHYEAR", "value": "MLR:1985" } }
```

## GDPR readiness

New tables reachable via `tenant_id` + `source_record_id` FKs (`record_block_key`,
`negative_match_rule`) or tenant alone (`identifier_quality_rule`); nothing stores
plaintext beyond what the record already carries. Future per-guest erasure enumerates
block keys and negative rules through the record ids exactly like record_identifier.
