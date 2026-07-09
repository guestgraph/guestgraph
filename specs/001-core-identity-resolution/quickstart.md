# Quickstart & Validation: Core Identity Resolution Service

**Feature**: 001-core-identity-resolution
**Contracts**: [contracts/openapi.yaml](contracts/openapi.yaml) · **Model**: [data-model.md](data-model.md)

## Prerequisites

- JDK 25 (`java -version` → 25)
- Docker (for local Postgres and Testcontainers)
- No local Maven needed — use the wrapper `./mvnw`

## Run the test suite (primary validation)

```bash
./mvnw verify
```

Expected: all green, including

- `resolution/*` — table-driven engine scenarios (pure JVM, no DB): shared identifiers,
  transitive merges, shared family email → review, unmerge-then-reingest
- `integration/*` — Testcontainers-backed end-to-end API tests (Docker required)
- `contract/*` — responses conform to `contracts/openapi.yaml`

## Run the service locally

```bash
./mvnw spring-boot:run    # starts Postgres via compose.yaml (Boot Docker Compose support)
```

Dev seed (local profile only) provisions tenant `demo` with API key `demo-key`.

## End-to-end smoke walk (maps to the spec's user stories)

Set once: `H='-H "X-API-Key: demo-key" -H "Content-Type: application/json"'` — base URL
`http://localhost:8080/api/v1`.

1. **US1 — register + ingest + resolve**

   ```bash
   curl -X POST $H localhost:8080/api/v1/source-systems -d '{"code":"opera-pms","name":"Opera PMS"}'
   curl -X POST $H localhost:8080/api/v1/records -d '{
     "sourceSystem":"opera-pms","externalKey":"r-1",
     "payload":{"firstName":"Anna","lastName":"Muster","email":"Anna.Muster@Example.com"}}'
   ```

   Expect: `status: CREATED_GUEST` and a `guestId`.
   Ingest `r-2` from a second source system with email `anna.muster@example.com` →
   `status: ATTACHED`, same `guestId`. Ingest `r-3` sharing r-2's phone and a *new* email
   already on another guest → `status: MERGED`.

2. **US2 — query & lookup**

   ```bash
   curl $H "localhost:8080/api/v1/guests/{guestId}"            # golden profile + identifiers
   curl $H "localhost:8080/api/v1/guests/{guestId}/records"    # originals, verbatim
   curl $H "localhost:8080/api/v1/guests?identifier=ANNA.MUSTER@EXAMPLE.COM"
   ```

   Expect: lookup normalizes and returns the same guest; unknown identifier → `{"guests":[]}`.

3. **US3 — explain & unmerge**

   ```bash
   curl $H "localhost:8080/api/v1/guests/{guestId}/explain"
   curl -X POST $H "localhost:8080/api/v1/guests/{guestId}/unmerge" \
        -d '{"sourceRecordIds":["<wrong-record-id>"]}'
   ```

   Expect: explain lists every event with `matcherName` + `confidence` (1.0) + timestamp;
   after unmerge the detached record sits on a different guest, and re-ingesting the same
   record does not silently rejoin it. Explain now shows the UNMERGE event.

4. **US4 — review queue**

   Lower the demo tenant's threshold (seed sets `review_threshold`), ingest records sharing
   one email beyond it, then:

   ```bash
   curl $H "localhost:8080/api/v1/match-reviews"                       # PENDING entries
   curl -X POST $H "localhost:8080/api/v1/match-reviews/{id}" -d '{"decision":"CONFIRM"}'
   curl -X POST $H "localhost:8080/api/v1/match-reviews/{id}" -d '{"decision":"REJECT"}'   # on another
   ```

   Expect: no auto-merge happened; CONFIRM merges + records a REVIEW_CONFIRM event; a second
   decision on the same review → 409 problem details.

5. **Error shape** — any bad request, e.g. unregistered source system:

   ```bash
   curl -X POST $H localhost:8080/api/v1/records -d '{"sourceSystem":"nope","externalKey":"x","payload":{}}'
   ```

   Expect: `Content-Type: application/problem+json` with `type`, `title`, `status`, `detail`
   (RFC 9457). A record with an unusable email but valid payload → stored with
   `needsReview: true` beside its resolution status, never dropped. A request without
   `X-API-Key` → 401 problem details.

## Success-criteria spot checks

| Spec SC | How to verify |
|---|---|
| SC-001 grouping correctness | `./mvnw test -Dtest='ResolutionScenario*'` — reference corpus green |
| SC-002 sync ingest < 1 s | single-record curl above returns guestId in-request |
| SC-003/004 explain & reversibility | walk 3 above; originals still byte-identical via `/records` |
| SC-005 no parseable loss | step 5: flagged record retrievable via its guest |
| SC-006 tenant isolation | integration test suite `TenantIsolation*`; cross-tenant guest fetch → 404 |
| SC-007 threshold → queue | walk 4 above |
