# How GuestGraph decides two records are the same person

This is the reference for the matching rules: what makes two records candidates, how a
candidate is scored, and what happens to the score. It is written for stewards reading a
review entry, operators tuning thresholds, and contributors adding a matcher.

**Keyed by matcher version.** Every merge event permanently records the `matcherName` that
decided it, and merge events are never deleted. So this document is organised by matcher
version and is append-only: when `fuzzy-rules-v2` lands, it gets its own section and the
`fuzzy-rules-v1` section stays, because events from 2026 must remain interpretable in 2030.

---

## The model: layered confidence

The goal is not to replace deterministic matching with something cleverer. It is a **layered
confidence model**, where each layer decides only what it is entitled to decide and hands the
rest upward — and where every layer's decisions are explainable and reversible.

| Layer | Decides | Confidence | Reversible by |
|---|---|---|---|
| **Deterministic identifiers** | shared email, phone, loyalty id, ID document → merge | 1.0 | unmerge |
| **Probabilistic scoring** | merges only above a threshold the tenant chose; otherwise queues | < 1.0, always | unmerge |
| **An agent** *(planned)* | the queue's high-confidence cases; escalates the rest | its own score | unmerge, and a human's split |
| **A human steward** | the final word | — | their splits stick |

Two properties hold across every layer, and they are why the layers can be added safely:

- **Every merge writes a `MergeEvent`** carrying the deciding matcher, its confidence, and the
  evidence — so `GET /guests/{id}/explain` can always answer "why are these one guest?"
- **Every merge can be undone**, and an unmerge writes a persistent do-not-merge rule, so the
  correction survives new evidence rather than being silently re-merged.

That ordering is deliberate. The safety machinery — review queue, unmerge, negative rules,
confidence metadata — shipped in slice 1, *before* any probabilistic decision existed,
precisely so nothing uncertain could ever be trusted without it. Each new layer is "another
imperfect matcher" gated by machinery that already works.

**What is not the goal:** a single model that swallows the whole problem. Deterministic
identifier matching is not a weaker version of fuzzy matching — it is a different kind of
claim, and it keeps confidence 1.0 because it deserves it.

---

## Layer 1 — deterministic identifiers

Two records that share a normalized strong identifier resolve to the same guest, at confidence
1.0. Identifiers are normalized before comparison: emails lowercased and trimmed, phones to
E.164, ID documents hashed (never stored in plaintext).

Two things can stop a deterministic merge:

- **A do-not-merge rule** between the two record clusters, written by an earlier unmerge or
  rejection. The merge is downgraded to a review entry citing the rule. No silent merge ever
  crosses a rule.
- **An identifier quality rule** — per tenant, and shipped with built-in defaults:

  | Effect | Meaning |
  |---|---|
  | `IGNORE` | the identifier connects nothing (a shared agency phone, a property email) |
  | `PERFECT_MATCH` | may connect guests only when the names agree exactly, otherwise review |
  | `MASKED_ALIAS` | OTA relay addresses (`…@guest.booking.com`) — never merges on its own |

There is also a **sharing threshold** (`review_threshold`, default 10): an identifier appearing
on unusually many records is suspicious rather than conclusive, and further records carrying it
are parked for review instead of merged.

---

## Layer 2 — `fuzzy-rules-v1`

Rule-based. **There is no machine learning in this matcher** — no model, no training, no learned
weights. The name says so on purpose, and it is recorded on every event it decides.

It runs in two stages that use two different algorithms, because the stages need different
*shapes* of answer.

### Stage 1: blocking — which pairs get scored at all

You cannot ask a database index for "rows similar to this", only "rows equal to this". So
candidate discovery needs a function that collapses similar records onto the *same* string. At
ingest each record derives blocking keys, stored immutably beside it:

| Key | Derivation | Catches |
|---|---|---|
| `NAME_PHONETIC_BIRTHYEAR` | Double Metaphone of the last name + birth **year** | spelling variants: Müller / Mueller / Miller → `MLR:1985` |
| `NAME_INITIALS_BIRTHDATE` | sorted initials + full birthdate | swapped first/last name order |
| `PHONE_SUFFIX7` | last 7 digits of an E.164 phone | differing country/area prefixes |
| `EMAIL_LOCALPART` | text before the `@`, real emails only | same person across providers |
| `EMAIL_MASKED` | the whole relay address | repeat bookings behind one OTA alias |

**Blocking is a recall filter, not a decision.** Sharing a key makes two records candidates; it
proves nothing on its own.

### Stage 2: scoring — how good a candidate is

Jaro-Winkler string similarity on diacritic-folded text. **Not phonetic** — phonetics decided
*which pairs to look at*; scoring needs a gradient, and a phonetic code is only ever equal or
not.

The name signal takes the better of the two name orderings, so "Anna Müller" and "Mueller Anna"
score as the same person.

| Signal | Weight | How it is measured |
|---|---|---|
| name | 0.45 | Jaro-Winkler, diacritic-folded, max over normal and swapped order |
| birthdate | 0.25 | exact match: 1.0 or 0.0 |
| phone suffix | 0.15 | last-7 digits equal: 1.0 or 0.0 |
| email | 0.10 | Jaro-Winkler on real (non-masked) addresses |
| address | 0.05 | city equal after folding: 1.0 or 0.0 |
| masked alias | **0.00** | a shared OTA relay: contributes candidacy, never score |

Then, in order:

```
weightedAvg   = Σ(value × weight) / Σ(weight)          # only signals both sides have
coverage      = 0.85 + 0.15 × Σ(weight)                # sparse agreement is damped
score         = weightedAvg × coverage
if birthdates conflict: score × 0.4                     # hard penalty
score         = min(score, 0.999)                       # never certain
```

Four rules are doing real work here, and each exists to prevent a specific wrong merge:

- **Renormalisation over present signals.** Two records that agree on name and birthdate and
  have nothing else are not punished for the fields nobody supplied.
- **The coverage damper.** But they are not treated as *certain* either. Agreement on two
  signals out of five is good evidence, not proof, so the score is scaled by how much of the
  weight was actually observed.
- **The birthdate conflict penalty.** Different birthdates are positive evidence of *different
  people*, and that outweighs a strong name match. Two people genuinely called Anna Müller are
  common; one person with two birthdates is not.
- **The 0.999 cap.** Certainty belongs to deterministic identifiers. It also makes
  `auto_merge_threshold = 1.0` genuinely mean *off* rather than *very unlikely*.

**A candidate needs a name plus at least one other signal.** A name alone yields no score at
all — not a low one. Scoring on a single common name would flood the queue with noise.

### Stage 3: banding — what happens to the score

| Band | Condition | Outcome |
|---|---|---|
| auto-merge | `score ≥ auto_merge_threshold` | merges, recorded with the score as confidence |
| review | `score ≥ review_floor` | parked in the review queue with its breakdown |
| discard | below `review_floor` | nothing happens |

At-threshold belongs to the higher band. Both thresholds are per tenant, readable and writable
at `GET|PUT /api/v1/config/matching`.

**The shipped defaults mean fuzzy matching never merges anything:**

```
auto_merge_threshold = 1.000    # and fuzzy scores cap at 0.999
review_floor         = 0.750
```

The auto-merge band is provably empty until a tenant explicitly lowers the threshold. Out of the
box, probabilistic matching is a *suggestion engine* — it finds duplicates exact matching cannot
see and shows a human why it thinks so. Lowering the threshold is an explicit act of trust, and
it is reversible.

### Worked examples

Real values, from the algorithms as implemented:

| Pair | Metaphone | Jaro-Winkler | What happens |
|---|---|---|---|
| Müller / Mueller | `MLR` = `MLR` | 0.917 | same block, scores high — same person |
| Schmidt / Schmitt | `XMT` = `XMT` | 0.943 | same block, scores high |
| Müller / **Miller** | `MLR` = `MLR` | 0.900 | same block, similar score — **the birthdate decides** |
| Catherine / Kathryn | `K0RN` = `K0RN` | 0.757 | blocking catches what string similarity nearly misses |
| Anna / **Hannah** | `AN` ≠ `HN` | 0.889 | **never scored** — different blocks |

The last two rows are the honest limits:

- **Müller / Miller** shows why blocking alone would be dangerous: identical phonetic code,
  plausibly different people. Scoring and the birthdate penalty separate them.
- **Anna / Hannah** shows the recall gap: string-similar, but they never block together, so
  they are never even compared. A typo'd name *plus* a changed phone may not be found at all.
  This is accepted — a future duplicate-scan backfill is the recall catcher, not a redesign.

### Reading a review entry

Every scored decision carries its breakdown, in the review entry and in the merge event's
evidence:

```json
{"signals": {"name":      {"value": 0.94, "weight": 0.45},
             "birthdate": {"value": 1.0,  "weight": 0.25},
             "phoneSuffix":{"value": 1.0, "weight": 0.15}},
 "score": 0.88}
```

Read it as: the names are very similar, the birthdates match exactly, the phones match — and
nothing contradicted. The score is below 1.0 because no strong identifier was shared and two of
the five signals were unobserved.

---

## Layer 3 — an agent as steward (planned)

Not built. The intent is three-tier stewardship: rules decide the clear cases, an agent decides
high-confidence reviews over MCP tools mapping 1:1 to the REST surface, and ambiguous ones
escalate to a human with a summarised recommendation.

Structurally an agent is just another imperfect matcher, gated by the same machinery. Its
prerequisites are tracked in [roadmap-notes.md](roadmap-notes.md) under R5-1; actor identity
(who decided — system, human, or a named agent) shipped in slice 3, and do-not-merge rules now
record both the actor who created a split and the actor who lifted it, so the rule *"an agent
never overrides a human's explicit split"* becomes a single-row comparison. That restriction is
enabled but not yet enforced.

## On "ML"

The roadmap says *"fuzzy/ML resolution"*. Today the ML half is a direction, not an
implementation: there is no model, no training pipeline, and no ML dependency in `pom.xml`.

What makes it feasible later is that it needs no redesign. `ResolutionStrategy` is a single
method — *candidates in, scored decisions out* — and `fuzzy-rules-v1` is already its second
implementation; a model would be the third, with bands, review queue, explain, and unmerge
unchanged. The constitution pre-authorises a sidecar (e.g. Python/ONNX) behind that contract.

The more interesting part: **every decision already persists its full feature vector**, and every
review carries a `CONFIRMED`/`REJECTED` outcome. So stewards doing ordinary work are emitting
labelled training data — feature vector plus human verdict — on the tenant's own data. Nobody
has built that pipeline, but the data is accruing in the right shape.

---

## Changing the rules

- Weights, damper, and penalty: `FuzzyMatcher` constants
- Blocking key derivations: `BlockKeys`
- Band routing: `MatchingPolicy`
- Thresholds: per tenant, `GET|PUT /api/v1/config/matching`

A change to any of the first three is a **new matcher version**, not an edit: bump the name,
add a section here, and leave the old one. Existing merge events name the matcher that decided
them, and they must stay readable.

Scenario tests are the specification of this behaviour — `FuzzyScenarioTest` and
`ResolutionScenarioTest` run pure-JVM against an in-memory graph. Per the constitution,
resolution-engine changes are test-first: the failing scenario comes before the rule.
