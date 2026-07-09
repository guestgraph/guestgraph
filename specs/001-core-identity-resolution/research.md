# Research: Core Identity Resolution Service

**Feature**: 001-core-identity-resolution | **Date**: 2026-07-09

The Technical Context contains no `NEEDS CLARIFICATION` items — language, framework,
datastore, build tool, and testing approach are fixed by the constitution and the approved
design doc. Research therefore resolves the remaining *implementation-level* choices.

## R1. Persistence approach: JPA + MapStruct with guardrails

- **Decision**: JPA entities + Spring Data repositories + MapStruct, constrained so the
  original safety goals survive:
  - Repositories extend only the `Repository<T, ID>` **marker interface** — never
    `CrudRepository`/`JpaRepository`, whose `findById(id)` has no tenant parameter.
  - **Every repository method is an explicit `@Query`** (JPQL) with the tenant predicate
    visible in the query text; no derived query methods. The rare tenant-less lookup
    (e.g. API-key → tenant) carries `@TenantAgnostic` with a justification.
  - `@Immutable` on append-only entities (`source_record`, `record_identifier`,
    `merge_event`, `tenant`) — Hibernate refuses UPDATEs; the DB trigger (R2) backstops.
  - MapStruct (`unmappedTargetPolicy = ERROR`) generates entity → domain-record mapping;
    a forgotten field fails the build. Writes construct immutable entities directly.
  - **ArchUnit tests enforce all of the above** at build time (`PersistenceRulesTest`):
    no CrudRepository scaffolding; tenantId on every repo method; JPA confined to the
    persistence package.
  - `JdbcClient` remains for the explicit-SQL corner: per-tenant advisory locks
    (`TenantLock`) and jsonb-specific statements.
- **Rationale**: The first implementation used hand-written `JdbcClient` DAOs with explicit
  SQL everywhere. That maximized reviewability but cost ~750 lines of mapper/CRUD
  boilerplate and put a maintenance and contribution barrier around routine persistence.
  The risks that motivated it — accidental UPDATEs on immutable data, tenant-predicate
  omission — are mechanically enforced instead (marker interface + `@Immutable` +
  ArchUnit), keeping the guarantees while restoring the persistence dialect enterprise
  Java contributors expect.
- **Alternatives considered**: JdbcClient DAOs everywhere (first implementation, replaced:
  boilerplate without added safety once guardrails are mechanical); jOOQ (rejected: extra
  codegen + license complexity for an Apache-2.0 OSS core, overkill for ~8 tables);
  unconstrained JPA/Spring Data (rejected: `CrudRepository` scaffolding and derived
  queries bypass tenant scoping; dirty checking invites accidental UPDATEs).

## R2. Raw payload storage: `jsonb` column, insert-only, DB-level guard

- **Decision**: `source_record.payload jsonb NOT NULL`, written once at ingest. A Postgres
  trigger rejects any UPDATE that changes `payload` (belt-and-braces on top of "no update
  path in code"). Extracted/normalized fields live in regular columns beside it.
- **Rationale**: jsonb preserves the original faithfully while staying queryable for
  debugging; the trigger makes Principle II enforceable at the database, not just by
  convention.
- **Alternatives considered**: separate blob store (rejected: second system, no benefit at
  this scale); `json` type (rejected: jsonb indexes/operators are worth the negligible
  canonicalization — byte-exact original preservation is not required by the spec, semantic
  equivalence is; noted in data-model.md).

## R3. Merge concurrency: per-tenant Postgres advisory locks

- **Decision**: `pg_advisory_xact_lock(hashtext(tenant_id::text))` acquired at the start of
  any transaction that can create/merge/unmerge guests. Lock is transaction-scoped, so
  release is automatic on commit/rollback.
- **Rationale**: Fixed by the design doc. Serializes all graph mutations within a tenant —
  the simplest correct answer to FR-011 (concurrent ingest yields sequential-equivalent
  state); tenants don't block each other. Ingest volume per tenant makes a per-tenant
  serial section acceptable for v1.
- **Alternatives considered**: row-level `SELECT ... FOR UPDATE` on affected guests
  (rejected: deadlock-prone with transitive merges touching variable guest sets);
  SERIALIZABLE isolation with retries (rejected: retry loops complicate the sync ingest
  contract); per-identifier locks (rejected: a record with N identifiers needs N ordered
  locks — more complexity for marginal parallelism).

## R4. RFC 9457 errors: Spring's built-in ProblemDetail

- **Decision**: Use Spring Framework's native `ProblemDetail` /
  `ResponseEntityExceptionHandler` support (`spring.mvc.problemdetails.enabled=true`), with
  one `@RestControllerAdvice` mapping domain exceptions (unknown guest, cross-tenant access →
  404; already-decided review → 409; unparseable payload → 400) to problem responses with
  GuestGraph-specific `type` URIs.
- **Rationale**: First-class framework support; RFC 9457 is the successor to RFC 7807 with
  the same wire format, which Spring emits. No extra dependency.
- **Alternatives considered**: problem-spring-web library (rejected: unnecessary given
  native support); custom error envelope (rejected: constitution mandates RFC 9457).

## R5. Phone/email/ID-document normalization

- **Decision**: email → trim + lowercase (whole address, documented simplification);
  phone → Google libphonenumber, formatted E.164, with a default-region config
  (fallback: reject as malformed → `needs_review`, never guess); ID document → SHA-256 of
  `type:number` after trim/uppercase, stored hash-only; loyalty ID / external key → trim.
  *v1 simplification*: the phone default region is one application-wide property
  (`guestgraph.default-phone-region`, unset by default → only `+`-international numbers
  match); a per-tenant column is a follow-up when tenants actually span regions.
- **Rationale**: libphonenumber is the de-facto standard for E.164; hashing ID documents
  satisfies the constitution's data-minimization rule while still allowing exact matching.
- **Alternatives considered**: hand-rolled phone parsing (rejected: national formats are a
  minefield); lowercasing only the email domain per RFC 5321 strictness (rejected:
  case-sensitive local parts are vanishingly rare and would fragment identities — the
  system's purpose).

## R6. API-key authentication

- **Decision**: `X-API-Key` header → servlet filter (`OncePerRequestFilter`) that hashes the
  presented key (SHA-256), looks up the tenant, and stores it in a request-scoped
  `TenantContext`; keys provisioned by operator SQL/seed data in v1. 401 (RFC 9457) when
  missing/unknown.
- **Rationale**: Matches the design doc ("per-tenant API keys; SaaS-grade auth belongs to
  the commercial layer"). Hash-at-rest so a DB leak doesn't leak keys. A filter (rather than
  full Spring Security) keeps v1 simple; Spring Security can wrap this later without API
  change.
- **Alternatives considered**: Spring Security with a custom `AuthenticationProvider`
  (viable, heavier; revisit when roles/scopes appear); `Authorization: Bearer` (rejected:
  implies OAuth semantics we don't provide).

## R7. Golden profile derivation (survivorship)

- **Decision**: Recompute the golden profile from all linked source records whenever links
  change (ingest attach, merge, unmerge, review confirm), and persist the result as columns
  on `guest`. Rule v1: per field, most recent non-null wins; "most recent" = source-provided
  record timestamp, falling back to `received_at` (spec assumption). Derivation is a pure
  function `List<SourceRecord> → Profile` in the `survivorship` package.
- **Rationale**: Persisting keeps reads cheap and lookup simple; recompute-on-change keeps
  it derived and always reproducible from immutable records (Principle II). Pure function =
  trivially unit-testable.
- **Alternatives considered**: compute-on-read (rejected: repeated cost on the hottest read
  path, harder identifier lookup); event-sourced profile snapshots (rejected: MergeEvent
  already provides the audit trail; full ES is overdesign for v1).

## R8. Unmerge semantics ("remove the offending link and replay resolution")

- **Decision**: `POST /guests/{id}/unmerge` takes the source-record id(s) to detach. Those
  records' `resolution_link`s are deleted, an UNMERGE `merge_event` is recorded, and
  resolution replays: the detached records re-resolve *excluding* the guest they were
  detached from (an exclusion recorded on the unmerge event prevents silently recreating the
  identical wrong merge — spec US3/AS3), and the remaining guest's identifiers and profile
  are recomputed from its remaining records.
- **Rationale**: Detach-by-record is the finest-grained, least surprising contract and maps
  directly onto `resolution_link`. The exclusion list is what makes unmerge *stick* against
  identical re-ingest while remaining visible in the decision history.
- **Alternatives considered**: revert-by-merge-event (rejected for v1: reverting an event
  deep in the chain requires replaying all subsequent events — more machinery, same user
  outcome; can be added later on top of the same event log); full tenant re-resolution after
  unmerge (rejected: unbounded cost).

## R9. Suspicious-match detection (review threshold)

- **Decision**: At resolution time, for each candidate identifier match, count distinct
  source records carrying that normalized identifier value in the tenant — including the
  record being resolved. If the total > `tenant.review_threshold` (default **10**), the
  candidate is not merged; a
  `match_review` row is created (identifier, candidate record, target guest(s), reason).
  The ingested record still resolves via its *other*, non-suspicious identifiers, or gets a
  new guest.
- **Rationale**: Record-count on the identifier is the design doc's example ("one email
  shared by 40 records"); default 10 comfortably exceeds a real family/household sharing an
  email while catching agency/front-desk addresses. Per-tenant column keeps it configurable
  without a config subsystem.
- **Alternatives considered**: distinct-guest count (near-equivalent; record count chosen as
  it is observable pre-merge); no default / mandatory config (rejected: spec assumes a
  working default).

## R10. Spring Boot 4 / Java 25 specifics

- **Decision**: Spring Boot 4.x on Java 25, `spring.threads.virtual.enabled=true` so Tomcat
  request handling runs on virtual threads and synchronous resolution-in-request is cheap.
  Maven with the Boot parent POM; Flyway for migrations; Docker Compose (`compose.yaml`)
  for local Postgres via Boot's Docker Compose support.
- **Rationale**: All stack elements fixed by constitution; virtual threads are why blocking
  synchronous ingest is acceptable. Flyway over Liquibase: SQL-first migrations match the
  hand-written-SQL persistence approach.
- **Alternatives considered**: none material — stack is constitutionally fixed; Liquibase
  (rejected: XML/YAML changelogs add indirection over plain SQL).

## R11. Batch ingest contract

- **Decision**: `POST /api/v1/records` accepts one record or an array (max 1000/request).
  Records are processed sequentially within the request (per-tenant lock serializes merges
  anyway); the response reports one outcome per record (guest id, created/attached/merged/
  needs_review, or per-record problem) — a batch never fails atomically.
- **Rationale**: Per-record independence is a spec assumption and follows Principle III
  (one bad record must not sink the batch). Sequential processing is honest about the lock.
- **Alternatives considered**: async batch with polling (rejected: v1 contract is
  synchronous resolve-on-ingest); parallel in-batch processing (rejected: per-tenant lock
  makes it pointless).
