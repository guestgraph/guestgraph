# Research: Probabilistic Matching

**Feature**: 002-probabilistic-matching | **Date**: 2026-07-10

No `NEEDS CLARIFICATION` items — approach, policy, and scope are fixed by the approved
design doc (docs/superpowers/specs/2026-07-10-probabilistic-matching-design.md).
Research resolves implementation-level choices.

## R2-1. Similarity & phonetics libraries

- **Decision**: `commons-text` `JaroWinklerSimilarity` for name similarity;
  `commons-codec` `DoubleMetaphone` for the phonetic block key; diacritic folding via
  `java.text.Normalizer` (NFD + strip combining marks) in a shared `NameNormalizer`.
- **Rationale**: both are tiny, battle-tested, Apache-2.0, in-JVM — no new
  infrastructure. Jaro-Winkler favors common-prefix agreement, which fits person names;
  Double Metaphone handles Müller/Mueller/Miller-class variance for *blocking* (recall),
  while Jaro-Winkler does *scoring* (precision).
- **Alternatives considered**: Levenshtein (worse for names, no prefix bias); Soundex
  (English-centric, coarser than Double Metaphone); dedicated matching engines
  (JedAI, Zingg — out of scope with the ML slice).

## R2-2. Candidate model: origins, features, and the composite strategy

- **Decision**: `MatchCandidate` gains an origin — `IDENTIFIER(type, value)` (exact) or
  `BLOCK_KEY(type, value)` (fuzzy) — and block-key candidates carry the guest's golden
  profile snapshot for scoring. The engine builds one deduplicated candidate set from
  both sources; `CompositeStrategy implements ResolutionStrategy` runs
  `DeterministicMatcher` over identifier candidates first, then `FuzzyMatcher` over
  block-key candidates for guests determinism didn't already decide. The
  `ResolutionStrategy` *interface* is unchanged (candidates in → scored decisions out).
- **Rationale**: keeps the slice-1 promise (new strategy = new implementation, no
  redesign); per-decision `matcherName` already tells explain which matcher decided.
  Fuzzy scores **record.extracted vs. the guest's golden profile** — the profile is the
  best current view of the person, one comparison per candidate instead of per record.
- **Alternatives considered**: scoring against every linked record (more precise on
  conflicting histories, O(records) per candidate — the profile already encodes
  survivorship's answer); separate resolve pass after deterministic (two transactions —
  needless complexity under the tenant lock).

## R2-3. Scoring model and default weights

- **Decision**: weighted average over available signals, renormalized when a signal is
  absent (missing data lowers evidence, not the score directly). Starting weights,
  tuned against the golden corpus during TDD: name 0.45, birthdate 0.25, phone-suffix
  0.15, email-similarity 0.10, address-locality 0.05. Guards: no candidate is even
  scored without a name signal plus at least one other signal (edge case "no name, no
  birthdate → no candidate"); conflicting birthdates apply a hard penalty
  (different person evidence outweighs name similarity). `EMAIL_MASKED` alias agreement
  contributes a small fixed bonus and **caps the result below the auto-merge
  threshold** (review-only by construction).
- **Rationale**: transparent, explainable arithmetic (each signal's contribution *is*
  the breakdown); renormalization avoids punishing sparse-but-consistent records;
  the corpus, not intuition, fixes the final numbers — weights are constants shipped
  with `fuzzy-rules-v1`, not tenant config (only thresholds are per-tenant, per spec).
- **As tuned during TDD** (refinements over the sketch above, all in `FuzzyMatcher`):
  renormalized scores are damped by evidence coverage (`score ×= 0.85 + 0.15·Σweights`)
  so sparse agreement reads as good evidence, not certainty; all fuzzy scores are
  **capped at 0.999** — certainty is reserved for deterministic identifiers, which makes
  `auto_merge_threshold = 1.0` structurally mean "off" (FR-006) instead of relying on
  float behavior; the masked-alias signal is a weight-0 contribution (it explains
  candidacy in the breakdown without inflating the score) and masked-origin candidates
  are additionally capped to the review band in `CompositeStrategy`.
- **Alternatives considered**: Fellegi–Sunter log-likelihood weights (statistically
  principled but needs match/non-match frequency estimates — that's the ML slice);
  rule cascade without scores (loses the band policy and breakdown).

## R2-4. Rule evaluation point: matching time, not extraction time

- **Decision**: IGNORE / PERFECT_MATCH / MASKED_ALIAS rules are evaluated **at matching
  time** (candidate filtering + engine gate), reading the rule set fresh per resolution.
  Extraction changes only for MASKED_ALIAS recognition: a masked email is stored in
  `extracted` with `emailMasked: true`, emits an `EMAIL_MASKED` block key, and emits
  no `EMAIL` record-identifier row. Built-in OTA relay domains live as code constants
  merged with tenant rules at evaluation (and surfaced read-only by the rules API).
- **Rationale**: matching-time evaluation makes new rules effective immediately for
  *all* data — an exact-value IGNORE added today silences an identifier written last
  year, no backfill needed (record_identifier rows stay complete per FR-016/III).
  Masked recognition must also happen at extraction because writing an EMAIL identifier
  for a relay address would create wrong deterministic merges before any gate runs;
  the matching-time domain check additionally covers EMAIL identifiers written *before*
  a domain became known — belt and braces.
- **Alternatives considered**: extraction-time only (stale rules problem, needs
  backfill); a rules cache with TTL (premature — rule reads are two indexed queries
  per resolution; add caching when measured).

## R2-5. Negative-rule storage and the cluster-membership check

- **Decision**: rules store ordered record-id pairs (`record_a < record_b`). Enforcement
  asks: "does any rule have one record in cluster A and the other in cluster B?" —
  one indexed query joining `resolution_link`, with the being-resolved record treated
  as its own single-record cluster (it has no link yet during resolution). New
  `GraphPort` methods: `negativeRuleBetween(tenantId, recordIds|guestId, guestId)`,
  `createNegativeRule(...)`, `liftNegativeRulesBetween(...)`. Writers: unmerge (each
  detached record × one representative record per remaining... **all** remaining
  records — pairs are cheap and unambiguous), review-reject (reviewed record × candidate
  guest's records at decision time). Confirm across a rule lifts the spanning rules
  (FR-011).
- **Rationale**: record ids are immutable and survive merges; enumerating full pairs
  (not representatives) keeps enforcement a pure membership test with no re-derivation
  when clusters later reshuffle. Volume stays trivial (unmerges are rare; a 5×10 split
  writes 50 rows).
- **Known narrow gap (accepted)**: the gate downgrades would-be *merges*; a fuzzy
  decision already in the review band that happens to cross a rule is queued as an
  ordinary fuzzy review without citing the rule. Reachable only via unmerge-replay of a
  previously rejected record; the split itself always holds. Annotating those reviews
  is a candidate refinement for slice 2.x.
- **Alternatives considered**: guest-id pairs (break on merge/delete); identifier-value
  suppression (blocks the identifier for everyone, not the pair — different feature,
  that's what quality rules are for).

## R2-6. Config storage and API shape

- **Decision**: thresholds as `tenant` columns (`auto_merge_threshold` DEFAULT 1.0,
  `review_floor` DEFAULT 0.75) joining slice-1's `review_threshold`; one
  `GET/PUT /api/v1/config/matching` resource for all three. Identifier rules and
  negative rules as plain CRUD-lite endpoints per the design doc. Validation: 0 ≤
  floor ≤ auto-threshold ≤ 1, sharing threshold ≥ 1; violations → RFC 9457 400,
  previous config untouched (PUT is transactional).
- **Rationale**: tenant columns match slice-1's `review_threshold` precedent and are
  read in the same query the engine already makes; a config table adds indirection for
  three scalars.
- **Alternatives considered**: JSON config blob on tenant (unvalidatable drift);
  dedicated config table (when config grows past scalars — not yet).

## R2-7. OpenAPI conformance across feature contracts

- **Decision**: `specs/002-probabilistic-matching/contracts/openapi.yaml` documents only
  the new endpoints; `OpenApiConformanceTest` changes to union the paths of **all**
  `specs/*/contracts/openapi.yaml` files and check the two-way gate against the merged
  surface.
- **Rationale**: keeps each feature's contract with its spec (spec-kit convention held
  from slice 1) while preserving the drift gate's guarantee over the whole served API.
- **Alternatives considered**: single canonical root contract (breaks the
  spec-per-feature layout; a future "publish merged contract" step can generate one for
  consumers when needed).

## R2-8. Ingest-path cost and SC-006

- **Decision**: block keys are computed in the extractor (pure string work) and
  inserted alongside record identifiers; candidate discovery adds one indexed lookup
  per key type (~5) plus one profile fetch per distinct fuzzy candidate (typically
  0–3); gates add two indexed rule queries. No new locks — everything runs inside the
  existing per-tenant advisory lock and transaction.
- **Rationale**: same access pattern and indexes as slice-1 identifier matching;
  the < 1 s budget (measured 173 ms with headroom) absorbs it. The quickstart smoke
  walk re-verifies SC-006 with fuzzy active.
