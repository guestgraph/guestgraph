# Quickstart & Validation: Probabilistic Matching

**Feature**: 002-probabilistic-matching
**Contracts**: [contracts/openapi.yaml](contracts/openapi.yaml) (new endpoints; slice-1 surface unchanged)
**Model**: [data-model.md](data-model.md)

## Prerequisites

As slice 1: JDK 25, Docker, `./mvnw`. Local run: `./mvnw spring-boot:run
-Dspring-boot.run.profiles=local` (tenant `demo` / key `demo-key`).

## Run the test suite (primary validation)

```bash
./mvnw verify
```

Expected green, including the new suites:

- `resolution/FuzzyScenario*` / golden-pair corpus — same-person variants surface,
  near-misses don't; band routing; **invariant: no auto-merge below the configured
  threshold** (SC-001/002)
- gate scenarios — negative rules downgrade fuzzy *and* exact merges (SC-003);
  quality rules (IGNORE / PERFECT_MATCH / MASKED_ALIAS) (SC-004)
- slice-1 `ResolutionScenarioTest` unchanged and green under defaults (SC-008)
- integration — config API round-trips + validation 400s, rules CRUD, end-to-end
  fuzzy-review flow; OpenAPI gate over the union of both feature contracts

## End-to-end smoke walk (maps to the spec's user stories)

Base `http://localhost:8080/api/v1`, headers `X-API-Key: demo-key`,
`Content-Type: application/json`.

1. **US1 — fuzzy candidate surfaces for review**

   ```bash
   curl -X POST $H $B/records -d '{"sourceSystem":"opera-pms","externalKey":"f-1",
     "payload":{"firstName":"Anna","lastName":"Müller","birthdate":"1985-03-12","phone":"+41791234567"}}'
   curl -X POST $H $B/records -d '{"sourceSystem":"opera-pms","externalKey":"f-2",
     "payload":{"firstName":"Anna","lastName":"Mueller","birthdate":"1985-03-12"}}'
   ```

   Expect: `f-2` → `CREATED_GUEST` (no auto-merge) with a `pendingReviewIds` entry;
   `GET /match-reviews` shows matcher `fuzzy-rules-v1`, a score < 1.0, and the
   per-signal breakdown in the reason. Confirm it → guests merge; `explain` shows the
   REVIEW_CONFIRM with the breakdown in evidence.

2. **US2 — splits stick**

   Unmerge a record from step 1's guest, then ingest a fresh record sharing an exact
   email with both sides. Expect: no silent merge — a review entry citing the
   do-not-merge rule; `GET /negative-rules` lists rules with origin `UNMERGE`;
   confirming the review merges AND lifts the rule; alternatively
   `DELETE /negative-rules/{id}` re-enables matching.

3. **US3 — masked OTA email**

   ```bash
   curl -X POST $H $B/records -d '{"sourceSystem":"opera-pms","externalKey":"m-1",
     "payload":{"firstName":"Ben","lastName":"Ott","email":"x1@guest.booking.com"}}'
   curl -X POST $H $B/records -d '{"sourceSystem":"opera-pms","externalKey":"m-2",
     "payload":{"firstName":"Eva","lastName":"Roth","email":"x1@guest.booking.com"}}'
   ```

   Expect: two guests (never merged by the shared alias); at most a review entry;
   neither profile's `email` is the relay address unless no real email exists (then
   marked `emailMasked`). `GET /config/identifier-rules` lists the built-in
   MASKED_ALIAS domains with `builtin: true`.

4. **US4 — tune the bands**

   ```bash
   curl $H $B/config/matching                          # defaults: 1.0 / 0.75 / 10
   curl -X PUT $H $B/config/matching -d '{"autoMergeThreshold":0.9,"reviewFloor":0.75,"reviewThreshold":10}'
   ```

   Re-run a high-scoring pair → auto-merge with matcher `fuzzy-rules-v1` and score as
   confidence. `PUT` with `reviewFloor: 0.95, autoMergeThreshold: 0.9` → 400 problem
   details, config unchanged.

## Success-criteria spot checks

| Spec SC | How to verify |
|---|---|
| SC-001/002 corpus + zero silent merges | `./mvnw test -Dtest='FuzzyScenario*'` green incl. invariant test |
| SC-003 splits stick | walk 2 above |
| SC-004 masked emails | walk 3 above |
| SC-005 breakdown everywhere | review reason + explain evidence in walks 1–2 |
| SC-006 ingest budget | single-record curl in walk 1 returns in-request, < 1 s |
| SC-007 config live | walk 4: behavior changes on next ingest, no restart |
| SC-008 no regression | slice-1 `ResolutionScenarioTest` + `IngestResolutionApiTest` unchanged, green |
