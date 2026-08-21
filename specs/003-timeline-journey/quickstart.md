# Quickstart & Validation: Guest Timeline & Attributed Decisions

**Feature**: 003-timeline-journey
**Contracts**: [contracts/openapi.yaml](contracts/openapi.yaml) (new endpoints; slice-1/2 surface unchanged)
**Model**: [data-model.md](data-model.md) · **Research**: [research.md](research.md)

## Prerequisites

As slices 1–2: JDK 25, Docker, `./mvnw`. Local run: `./mvnw spring-boot:run
-Dspring-boot.run.profiles=local` (tenant `demo` / key `demo-key`).

After the migration lands, a stale local volume will fail Flyway's checksum check:
`docker compose down -v`, then re-run.

## Run the test suite (primary validation)

```bash
./mvnw verify
./scripts/regen-er.sh   # must produce no diff — CI's er-drift job re-runs it
```

Expected green, including the new suites:

- `timeline/AssociationDeriverTest` — pure JVM, table-driven: current vs ended, successor naming,
  dedup of one guest twice in a role, out-of-order versions, ordering and fallback (SC-001, SC-002)
- `integration/TimelineApiTest` — the four US1/US2 walks end to end, incl. the removal case
- `integration/ActorAttributionTest` — system / human / agent attribution, credential-type refusal,
  unattributed rendering of pre-slice-3 events, rule lift preserving both actors (SC-005)
- `integration/SourceObjectApiTest` — roster + full observation history across a reassignment (SC-004)
- slice-1 and slice-2 suites unchanged and green — submitters sending no `sourceObject` see no
  behavioural change (SC-008)
- `contract/OpenApiConformanceTest` — now unions three feature contracts with no test change

## End-to-end smoke walk (maps to the spec's user stories)

Base `http://localhost:8080/api/v1`, headers `X-API-Key: demo-key`,
`Content-Type: application/json`. `$B` and `$H` as in the slice-2 quickstart.

### US1 — a booking appears once, showing its latest state

1. Ingest three versions of reservation `R1`'s primary guest, each with a later
   `sourceObject.version` and `recordTimestamp`, all describing Anna.
2. `GET $B/guests/{annaId}/timeline` → exactly one entry: `objectId: R1`,
   `role: PRIMARY_GUEST`, `status: CURRENT`, `observationCount: 3`, showing v3's data.
   *Confirms SC-001.*
3. `GET $B/guests/{annaId}/records` → still three records, contract unchanged (FR-005).

### US2 — reassignment, and removal

4. Ingest `R1` v4 naming Bruno as primary guest.
   - `GET $B/guests/{brunoId}/timeline` → `R1` CURRENT.
   - `GET $B/guests/{annaId}/timeline` → `R1` absent.
   - `GET $B/guests/{annaId}/timeline?includePast=true` → `R1` with `status: ENDED` and
     `successorGuestId` = Bruno. *Confirms SC-002.*
5. Ingest reservation `R2` v1 with two additional guests (Yara, Zoe), then v2 carrying only Zoe.
   - Yara's timeline: `R2` absent; with `includePast=true`, ENDED with `successorGuestId: null`
     — she was removed, not replaced.
   - Zoe's entry is unchanged and inherits nothing. *This is the case positional slots got wrong.*
6. `GET $B/source-objects/opera-pms/reservation/R1` → current roster naming Bruno, plus all four
   observations in version order, including Anna's. *Confirms SC-004 — nothing was lost.*
7. Re-ingest `R1` v2 (an out-of-order late delivery). Timelines are unchanged: an older version
   never displaces a newer roster.

### US3 — every decision names its actor

8. Trigger an automatic merge at ingest, then `GET $B/guests/{id}/explain` → the event shows
   `actor: {type: SYSTEM, id: <matcher>}`.
9. Confirm a pending review with a human-operated key plus `X-Actor-Id: rob@example.com` →
   decision records `{type: HUMAN, id: rob@example.com}`, visible in explain.
10. Repeat with an agent-registered key → `{type: AGENT, id: <agent name>}`.
11. Send `X-Actor-Type: HUMAN` on the agent key → RFC 9457 400, and nothing is recorded.
    *Confirms SC-005.*
12. Unmerge with a human key, then `GET $B/negative-rules` → the rule names its creating actor.
13. `DELETE $B/negative-rules/{ruleId}` → the rule stops gating but is still listed, now showing
    `liftedAt` and the lifting actor. Split the same pair again → a second rule is created
    alongside the lifted one (the partial unique index exists for exactly this).

### US4 — connector observation contract

14. Submit one `R3` version carrying three persons → three observations, no duplicate-key
    collision (role + position distinguish them).
15. Resubmit that version verbatim → `DUPLICATE_IGNORED` for each, no new observation, no
    timeline change. *Confirms SC-007 for a backfill replay.*
16. Submit a version with `sourceObject.version` set to a non-instant → the record is stored,
    `needsReview: true` with a reason, and it appears in `/records` but in no roster (FR-024).
17. Submit booking-level agency contact data inside `sourceObject` rather than the person fields
    → no guest identifier is created from it and it merges nothing.

## Success-criteria spot checks

| Criterion | Check |
|---|---|
| SC-003 | One `GET /guests/{id}/timeline` answers "what does this guest currently have" — no client-side post-processing of `/records`. |
| SC-006 | Seed a guest with 500 associations; first page under 1 s. Assert in `TimelineApiTest`. |
| SC-007 | Replay an unchanged month of submissions: zero new observations, zero timeline changes, zero new merge events. |
| SC-008 | Slice-1/2 suites pass unmodified; a record submitted without `sourceObject` behaves exactly as before. |
