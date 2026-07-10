# GuestGraph — Probabilistic Matching: Design

**Date:** 2026-07-10
**Status:** Approved
**Scope:** Slice 2 of the GuestGraph roadmap — fuzzy matching behind the slice-1 strategy
interface, plus the steward controls that make it safe.

## Goal

Slice 1 resolves only on exact strong identifiers; real guest data is dirtier than that
(typos, name variants, missing identifiers, per-channel aliases). Slice 2 adds a
**rule-based fuzzy matcher** that scores candidate matches into the existing
confidence/review machinery — wrong merges stay impossible to make silently, and every
score is explainable.

Consumes from `docs/roadmap-notes.md`: **R2-1** (negative match rules) and the
identifier-quality-rules family.

## Decisions (fixed)

| Decision | Choice | Rationale |
|---|---|---|
| Matching approach | Rule-based fuzzy scoring, in-JVM (`fuzzy-rules-v1`) | No new infrastructure or training data; fully explainable scores; ML replaces it later behind the same `ResolutionStrategy` interface |
| Merge policy | Three score bands per tenant: ≥ `auto_merge_threshold` auto-merge, ≥ `review_floor` review queue, below ignored. **Auto-merge band ships empty** (`auto_merge_threshold = 1.0`) | Wrong merges are privacy incidents; tenants opt into automation per config once they trust the scores. Review queue is the primary channel, as slice 1 planned |
| Candidate generation | Blocking keys in Postgres (`record_block_key`, exact index lookups), not pg_trgm or tenant scans | Same shape as slice-1 identifier lookup: tenant-scoped, indexed, explainable ("candidate because shared PHONE_SUFFIX7"). Known trade-off: blocking can miss candidates; the deferred backfill scan is the future recall-catcher |
| Negative rules | Persistent do-not-merge pairs keyed by **source-record ids** (immutable), written automatically by unmerge and review-reject; they downgrade *any* would-be merge — including deterministic — to review | A human split stays split until a human confirms otherwise (R2-1); record ids survive merges, guest ids do not |
| Identifier quality rules | Per-tenant `IGNORE` / `PERFECT_MATCH` / `MASKED_ALIAS` rules with `EXACT` or `EMAIL_DOMAIN` matching; GuestGraph ships built-in `MASKED_ALIAS` defaults for known OTA relay domains | The legacy-proven config (shared agency emails, affiliate identifiers), generalized to one mechanism; domain rules handle masked OTA emails without per-alias maintenance |
| Masked OTA emails | Recognize by domain → never an EMAIL identifier (no deterministic merge), but a block key + weak fuzzy signal (review-only) → never overwrite a real email in survivorship | Masking schemes can reuse one address across different people (silent stranger-merge risk) and relay addresses expire; recognition + demotion + profile guard close all three holes |
| Config surface | Per-tenant matching config via REST (`/api/v1/config/...`) | Constitution V: everything reachable via the API |
| Out of scope | ML/sidecar matcher, duplicate-scan backfill over the existing graph, trusted-stable alias domains, survivorship trust ranking beyond the masked-email guard (rest of R-X2) | Each lands behind interfaces this slice puts in place |

## Data model (additive migration V2)

```
record_block_key        like record_identifier: (tenant_id, source_record_id,
                        type, value_normalized); INDEX (tenant_id, type, value).
                        Types v1: NAME_PHONETIC_BIRTHYEAR (double-metaphone(lastName)
                        + birth year), NAME_INITIALS_BIRTHDATE, PHONE_SUFFIX7,
                        EMAIL_LOCALPART, EMAIL_MASKED (full masked address).
                        Computed at ingest; immutable.

negative_match_rule     (tenant_id, record_a, record_b, origin, created_at) with
                        record_a < record_b. origin: UNMERGE | REVIEW_REJECT | MANUAL.
                        Semantics: the cluster containing record_a must never be
                        silently merged with the cluster containing record_b.

identifier_quality_rule (tenant_id, identifier_type, match_kind EXACT|EMAIL_DOMAIN,
                        value_normalized, rule IGNORE|PERFECT_MATCH|MASKED_ALIAS, note).
                        IGNORE: no candidates, no merges (agency/shared identifiers).
                        PERFECT_MATCH: merge only with exact name agreement, else review.
                        MASKED_ALIAS (email domains): no EMAIL identifier, but an
                        EMAIL_MASKED block key (weak fuzzy signal, review-only) and the
                        survivorship guard. Built-in defaults (all tenants): MASKED_ALIAS
                        for known OTA relay domains (guest.booking.com,
                        guest.airbnb.com, Expedia/agoda relays, ...).

tenant                  + auto_merge_threshold numeric NOT NULL DEFAULT 1.0
                        + review_floor        numeric NOT NULL DEFAULT 0.75
```

Source records, guests, identifiers, links, merge events, match reviews: unchanged.
The slice-1 promise holds — no migration of existing resolution data.

## Engine

Strategy chain replaces the single matcher: **deterministic first (unchanged,
confidence 1.0), fuzzy second** over candidates the deterministic pass didn't decide.

Per candidate, `FuzzyMatcher` computes a feature vector and a weighted score in [0,1]:

- name similarity — Jaro-Winkler on diacritic-normalized full names (commons-text)
- birthdate agreement (exact / partial / missing)
- phone suffix agreement
- email similarity (real emails; masked aliases only as the weak EMAIL_MASKED signal)
- address locality hint when present

Banding: `score ≥ auto_merge_threshold` → MATCH (confidence = score);
`≥ review_floor` → REVIEW; below → dropped. The review reason and MergeEvent evidence
carry the **per-signal breakdown** — a steward sees why a pair scored 0.87
(Constitution IV).

Two gates run in the engine after scoring, before execution, against **every** decision
(deterministic included):

1. **Negative rule gate** — a rule spanning the two clusters downgrades MATCH → REVIEW.
2. **Quality rule gate** — `IGNORE`d identifiers never generated the candidate in the
   first place (removed at extraction/candidate stage); `PERFECT_MATCH` identifiers
   downgrade merges without exact normalized-name agreement to REVIEW.

Writers of negative rules: `UnmergeOperation` (detached records × remaining records)
and review REJECT (reviewed record × candidate cluster's records at decision time).
Rules are visible and deletable via the API — lifting one is a steward act.

Masked-email survivorship guard: a masked address never overwrites a real email in the
golden profile; it fills the field only when no real address exists, marked
`emailMasked: true` in the profile.

## API (v1 surface additions)

```
GET  /api/v1/config/matching            thresholds: autoMergeThreshold, reviewFloor,
PUT  /api/v1/config/matching            reviewThreshold (the slice-1 sharing count)
GET  /api/v1/config/identifier-rules    list incl. built-in defaults (flagged builtin)
POST /api/v1/config/identifier-rules    add tenant rule
DELETE /api/v1/config/identifier-rules/{id}
GET  /api/v1/negative-rules             list (origin, records, created)
DELETE /api/v1/negative-rules/{id}      lift a rule (steward act, audited)
```

Ingest, guests, explain, unmerge, match-reviews: unchanged contracts. Fuzzy shows up as
new matcher names, sub-1.0 confidences, and richer reasons/evidence — exactly what the
probabilistic-ready model reserved space for.

## Testing

TDD on all matcher and gate logic (Constitution VI), pure JVM via the slice-1
`InMemoryGraph` harness:

- **Golden-pair corpus**: same-person variants (umlauts/diacritics, name order swapped,
  typos, nickname vs full form, missing birthdate) and different-person near-misses
  (family members sharing phone/address, common names, same masked OTA alias).
- Band routing per threshold config; invariant test: **no auto-merge below the
  configured threshold, ever**.
- Negative-rule scenarios: fuzzy re-suggestion suppressed to review; deterministic
  exact-email match across a negative pair → review, not merge; rule lifted → merge
  proceeds.
- Quality-rule scenarios: IGNORE identifier generates neither candidates nor merges;
  PERFECT_MATCH mismatch routes to review; masked-OTA email never merges
  deterministically, never overwrites a real profile email.
- Integration: config API round-trips, Testcontainers; OpenAPI drift gate extended to
  the new endpoints.

## Out of scope for this slice

ML/model-based scoring (future implementation of the same interface, possibly a
sidecar), duplicate-scan backfill over existing data, trusted-stable alias domains,
per-source survivorship trust ranking (rest of R-X2), timeline (slice 3), connectors
(slice 4).

## Workflow

Spec-driven with spec-kit, as slice 1: this design feeds `/speckit-specify` →
`/speckit-plan` → `/speckit-tasks` → `/speckit-implement` on a `002-*` feature branch.
