# Feature Specification: Guest Timeline & Attributed Decisions

**Feature Branch**: `003-timeline-journey`

**Created**: 2026-08-21

**Status**: Draft

**Input**: User description: "Slice 3 — Timeline / journey. Consumes roadmap-notes R3-1 (source objects, reservations first, become first-class events on resolved guests: group observations by business-object identity + role slot, later observations of the same (object, slot) supersede earlier ones so the event moves to the guest of the latest observation, A→B reassignment query contract, full observation history stays reachable per Constitution II), plus two folded-in items: (a) actor identity on decisions — `decided_by` distinguishing human user vs named agent on review decisions/unmerge/merge events (roadmap R5-1 prerequisite 1, explicitly a slice-3/4 candidate); (b) R4-1 externalKey convention for mutable multi-person source objects (`{reservationId}:{personRole}:{entityModifiedTimestamp}`), including recordTimestamp = same modified value, emit-only-on-person-change hashing rule, and the field-mapping rule that non-personal reservation-level contact data must not be extracted as guest identifiers."

## Clarifications

### Session 2026-08-21

- Q: When a booking's list of additional guests changes, how does the system tell which person each remaining entry belongs to? → A: It does not track persons across versions at all. The object version is the unit of supersession: the newest version's complete person roster defines current membership, position is display-only metadata, and the connector emit rule becomes roster-complete (every person whenever person data or the roster changed).
- Q: What kind of value is a business object's version, and how are two versions compared? → A: An instant — the source object's own last-modified timestamp — compared chronologically. A version that is absent or not a parseable instant is flagged per FR-024, never guessed.
- Q: What date should a guest's timeline be sorted by? → A: The ingest contract gains optional business-start and business-end fields alongside object identity; the timeline sorts by business start and falls back to the observation timestamp when it is absent. The payload stays opaque — the service never reads stay dates out of it.
- Q: The spec called a guest's booking association an "event", colliding with the existing `merge_event` audit entry — what should it be called? → A: Association. The timeline returns associations; the audit trail keeps merge events. No collision with the existing schema.
- Q: How fast should a guest's timeline page come back, as a testable number? → A: The first page returns in under 1 second for a guest holding 500 associations, matching the shape of the slice-2 performance criterion.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - What Does This Guest Currently Have? (Priority: P1)

Slice 1 answers *"what did we ever observe about this person"* — an append-only list of observations. It cannot answer the question every hotelier actually asks: *"what reservations does this guest have?"* A reservation edited three times shows up as three unrelated records with no indication that they describe one booking; nothing tells the steward which of them is current.

This slice makes source business objects — reservations first — first-class **associations** on the resolved guest. Each observation states which business object it describes, which role the person plays in it (primary guest, additional guest, booker), and which version of that object it reflects. The newest version's observations together form that object's current **roster**: the complete statement of who is on the booking now. A guest's timeline lists the objects whose current roster includes them, each with its business dates and a pointer to the full observation history behind it.

Nothing is matched from one version to the next — each version is a fresh statement of fact and the newest one wins whole. That is deliberate: sources such as Apaleo carry entity-less persons with no id to track across edits, so any scheme that tried to follow a person from version to version would have to guess, and would fabricate reassignments whenever a booking's guest list shrank.

**Why this priority**: It is the slice's reason to exist. Without it the graph resolves identities but cannot say what those identities are associated with, which is the first thing any consumer of the API needs.

**Independent Test**: Ingest several versions of one reservation and a second reservation with two persons; read the guests' timelines and verify each booking appears once per guest and role, showing the newest version's state.

**Acceptance Scenarios**:

1. **Given** three successive observations of reservation R1's primary guest, all resolving to guest A, **When** guest A's timeline is read, **Then** R1 appears exactly once, showing the latest version's data and the number of observations behind it.
2. **Given** a reservation carrying a primary guest and one additional guest who resolve to two different guests, **When** both timelines are read, **Then** the same reservation appears on both, each entry naming the role that person plays in it.
3. **Given** a guest timeline entry, **When** it is read, **Then** it exposes the source system, the business-object type and id, the role, the position within that role where the source gives one, the current observation, the observation count, and the object's business start and end where supplied.
4. **Given** a guest whose records include a loyalty-database person row that describes no business object, **When** the timeline is read, **Then** that record is not a timeline entry — while the existing records list still shows it unchanged.
5. **Given** a guest with no business-object observations at all, **When** the timeline is read, **Then** an empty list is returned, not an error.
6. **Given** a guest with more timeline entries than one page, **When** the timeline is read, **Then** results are paged and ordered deterministically.
7. **Given** a guest holding one booking arriving next week and one that arrived last year but was edited yesterday, **When** the timeline is read, **Then** the two are ordered by their stay dates, not by which was edited most recently.
8. **Given** a business object submitted without business dates, **When** the timeline is read, **Then** it is ordered by its observation timestamp and takes its place among the dated entries without error.

---

### User Story 2 - A Booking's Current Guests Are the Newest Version's Guests (Priority: P2)

A reservation is booked for Anna; a day later the front desk corrects the guest to Bruno. Today both persons' record lists reference that reservation and neither can be called wrong, because observations never supersede one another. Operationally that is a defect: Anna appears to hold a booking she does not have.

With business-object associations, the newest version's roster is the answer: version 2 says the primary guest is Bruno, so Bruno holds the booking and Anna does not. Anna keeps every record she ever generated — nothing is deleted, nothing is rewritten — but her *current* associations no longer include that reservation. The same rule covers a guest simply being dropped from a booking, which no per-person tracking scheme handles honestly.

**Why this priority**: It is what turns the timeline from a nicer record list into a trustworthy answer. Without supersession the timeline would repeat slice 1's ambiguity in a new shape.

**Independent Test**: Ingest version 1 naming Anna and version 2 naming Bruno for the same reservation and role; verify the association is current on Bruno only, that Anna's record list is untouched, and that both observations remain retrievable. Then ingest a two-person version followed by a one-person version and verify the dropped guest leaves the booking without the remaining guest inheriting anything.

**Acceptance Scenarios**:

1. **Given** reservation R1's primary guest observed first as Anna, then as Bruno, **When** Bruno's timeline is read, **Then** R1 is a current association on Bruno; **When** Anna's timeline is read, **Then** R1 is not a current association on Anna.
2. **Given** the same reassignment, **When** Anna's timeline is read with past associations included, **Then** R1 is returned marked as no longer on the booking, naming Bruno as the successor because the role had one occupant before and has one now — a genuine handover; **When** her timeline is read without that option, **Then** R1 is absent.
3. **Given** the same reassignment, **When** Anna's records list is read, **Then** her original observation is still present and byte-for-byte unchanged (Constitution II).
4. **Given** version 3 of a reservation arrives before version 2 (out-of-order delivery), **When** version 2 is ingested, **Then** version 3 remains the current roster — supersession follows the source object's version, never arrival order.
5. **Given** the reservation is later corrected back to Anna in a newer version, **When** the timelines are read, **Then** the association is current on Anna again and no longer current on Bruno.
6. **Given** guests A and B are merged after such a reassignment, **When** the surviving guest's timeline is read, **Then** the association appears exactly once.
7. **Given** a steward unmerges the guest that holds an association's current observation, **When** the timelines are read, **Then** the association follows the guest that observation is now linked to.
8. **Given** a reservation whose version 1 roster held two additional guests and whose version 2 roster holds only the second of them, **When** the timelines are read, **Then** the dropped guest no longer holds the booking, the remaining guest's entry is unchanged, and no association is reported as transferred between them.
9. **Given** any association, **When** its observation history is requested, **Then** every observation of that object and role is returned in version order, including superseded ones and those belonging to guests no longer on the booking.

---

### User Story 3 - Every Decision Names Who Made It (Priority: P3)

The audit trail records what was decided, which matcher decided it, and how confident it was — but not *who*. The credential identifies a tenant, not a person. A steward reviewing last month's merges cannot tell an automated decision from a colleague's judgement call, and the planned three-tier stewardship model (rules → agent → human) has nowhere to record that an agent, rather than a human, confirmed a match.

Every decision this slice touches — automatic resolution, review confirm and reject, unmerge, and do-not-merge rule creation and lifting — records an actor: whether it was the system acting on its rules, a named human steward, or a named agent, and which one. Explain surfaces it alongside the matcher and confidence already there.

**Why this priority**: It is small, self-contained, and unblocks the roadmap's agent-stewardship work; it also improves the human audit story immediately. It is deliberately ranked below the timeline because the timeline is what this slice is for.

**Independent Test**: Perform one automatic ingest merge, one human review confirmation, and one agent review confirmation; verify each resulting event names the right actor type and identity, and that explain shows them.

**Acceptance Scenarios**:

1. **Given** a steward confirms a pending review, **When** the resulting event is inspected via explain, **Then** it records a human actor and that actor's identity.
2. **Given** records resolving automatically at ingest, **When** the resulting event is inspected, **Then** it records the system as actor with the deciding matcher's name — no human or agent identity is invented.
3. **Given** a credential registered as belonging to an agent, **When** it decides a review, **Then** the event records an agent actor and the agent's name.
4. **Given** a credential registered as human-operated, **When** a request supplies an identifier naming the individual steward behind it, **Then** the decision records a human actor with that individual's identifier; **When** the same request instead claims an actor type its credential does not grant, **Then** the request is refused with a problem-details error and no decision is recorded.
5. **Given** a steward unmerges a guest, **When** the resulting do-not-merge rule is listed, **Then** it names the actor that created it, so a later policy can distinguish human splits from automated ones.
6. **Given** events recorded before this slice shipped, **When** explain is read, **Then** they render as unattributed and no request fails.
7. **Given** a decision recorded with an actor, **When** the actor's identity is inspected, **Then** it is scoped to the tenant like every other record (Constitution I).
8. **Given** a do-not-merge rule created by a human steward, **When** it is lifted — by an explicit deletion or by confirming a match across it — **Then** the rule stops gating merges but remains listed, showing who created it, who lifted it, and when; and the same pair may be split again afterwards.

---

### User Story 4 - Connectors Emit Observations That Order Correctly (Priority: P4)

The timeline is only as good as the keys the connectors send. A PMS reservation is a mutable object carrying several entity-less persons; every edit produces a new version, and webhooks retry. This story turns the roadmap's connector convention into part of the published ingest contract: one observation per person per version, a version discriminator derived from the source object's own state so retries and backfills are naturally idempotent, an observation timestamp that matches that version so survivorship and supersession order identically, roster-complete emission whenever person data or the guest list changed, and a field-mapping rule keeping non-personal booking contact data out of the person fields.

Roster-complete emission amends the roadmap's per-person emit-on-change rule, which the version-roster decision requires: a version that carried only the one person who changed would read as a booking with one guest. Emitting every person of a changed version keeps each version a complete statement, while edits that touch no person at all — room, rate, dates — still emit nothing, which is where the rule's noise reduction actually comes from.

**Why this priority**: Slice 4 builds the connectors, but the contract they must satisfy is decided here, because the timeline's correctness depends on it. Shipping the timeline without publishing this contract would invite connectors that break supersession ordering on their first retry.

**Independent Test**: Submit a three-person reservation version, resubmit it unchanged, then submit a version whose only change is non-person data; verify three observations exist, the resubmission is absorbed as a duplicate, and no guest identifier was created from booking-level contact data.

**Acceptance Scenarios**:

1. **Given** one reservation version carrying three persons, **When** it is submitted per the convention, **Then** three distinct observations are stored — the role distinguishes them and no two collide on the duplicate key.
2. **Given** an already-stored object version, **When** the identical version is delivered again (webhook retry, duplicate change ping, or full backfill), **Then** it is absorbed as a duplicate with no new observation, no new association, and no change to any guest.
3. **Given** a reservation edit that changed only non-person data such as room or price, **When** the submitter applies the emission rule, **Then** no observation is emitted and the guests' observation counts — which feed the identifier-sharing review threshold — are not inflated.
4. **Given** a reservation edit that changed one guest's name, **When** the submitter applies the emission rule, **Then** an observation is emitted for every person on that version, including the unchanged co-travellers, so the version is a complete roster.
5. **Given** a submission carrying business-object identity whose version discriminator is missing or unusable, **When** it is ingested, **Then** the record is still stored and flagged for review with a reason — never rejected, never silently dropped (Constitution III).
6. **Given** booking-level contact data that belongs to an agency, a property, or a shared office rather than to the guest, **When** it is submitted as object-level metadata per the contract, **Then** it produces no guest identifier and can merge no guests.
7. **Given** the same business-object id used by two different source systems, **When** both are ingested, **Then** they remain two distinct associations — object identity is namespaced by source system.

---

### Edge Cases

- Two source edits fall inside the timestamp's granularity and share a version: they collapse into one observation and the stored state stands. Accepted — the alternative is a version that is not derivable from source state, which breaks retry idempotency.
- A source reports a last-modified instant that moves backwards (a clock correction at the source): the newer roster is the one with the later instant, so the corrected value takes effect only if it is later. The service trusts the source's own ordering rather than inventing one.
- A person is removed from the middle of a booking's guest list, shifting everyone behind them: nothing is fabricated, because positions carry no identity — the newest roster simply has one fewer guest, and only the removed person's association ends.
- A version's observations arrive across several submissions: the roster is briefly incomplete and a guest may momentarily appear absent from the booking. It self-heals when the remaining observations of that version land; the window is the delivery gap, and no decision is recorded from the partial state.
- A guest holds hundreds of timeline entries: the timeline is paged and ordered deterministically, and the first page stays within the SC-006 budget.
- A submitter supplies a business end earlier than the business start, or only one of the two: the values are stored and shown as given and the entry still orders by whatever start it has — the service records business dates, it does not validate the source's calendar.
- An association's current observation belongs to a record flagged `needs_review`: the association still appears — flagged data is stored and usable, never hidden (Constitution III).
- Two observations of the same object, role, and position carry the same version but different persons: one of them is a duplicate by the ingest key and is absorbed; the stored one stands.
- Two persons on the same version resolve to the same guest (the same human entered twice): that guest holds the booking once for that role, not twice.
- A business object whose persons all resolve to the same guest (a booker who is also the primary guest): the guest sees two entries for the same object, one per role, not one merged entry.
- A reservation is cancelled at the source: the status travels in the payload and is visible on the entry; the resolution engine attaches no meaning to it in this slice.
- An observation is ingested with object identity for an object whose earlier observations had none: the earlier records stay outside the timeline; only observations carrying object identity form associations.
- A booking whose primary guest moves from Anna to Bruno and back again: Anna's timeline shows it current again, and Bruno's shows it as no longer on the booking — the marker describes the association's present state, not a permanent scar.
- A guest leaves a booking with nobody taking their role, or leaves a role that several people share (a booking that drops one of two additional guests): their entry is marked as no longer on the booking and names no successor. Naming whoever else holds the role would fabricate a handover that never happened — the very failure positional slots produce.
- The per-request individual identifier is caller-asserted and unverified: it attributes a decision to a named person, it never authorises anything. Only the credential-bound actor type carries trust.
- The same pair is split, lifted, and split again: the lifted rule stays as it is and a second rule is created — rules are a record of decisions taken, so a lift is a stamp on the old one rather than room reclaimed for a new one.
- A decision arrives from a credential whose actor registration was changed after earlier decisions: past events keep the actor recorded at decision time — the audit trail is append-only.

## Requirements *(mandatory)*

### Functional Requirements

**Business-object associations**

- **FR-001**: Ingest MUST accept, optionally per record, the identity of the business object the record describes: object type, object id, the role the person plays in it, the position within that role where the source exposes one, and the object version this observation reflects, expressed as the instant the source object was last modified. It MUST also accept, optionally, the object's business start and end — a stay's arrival and departure.
- **FR-001a**: These MUST be explicit, structured fields on the submission and MUST be the sole source of business-object identity — the observation key stays an opaque duplicate-detection token that the service never parses. Records submitted without object identity MUST behave exactly as before.
- **FR-002**: Observations sharing tenant, source system, object type, object id, and object version MUST form that version's roster. The highest version observed for an object MUST be its current roster, and that roster MUST be the sole determinant of who is currently on the object.
- **FR-003**: A guest MUST hold a current association for a business object and role exactly when the object's current roster contains an observation with that role which resolves to that guest. Persons MUST NOT be tracked or matched from one object version to the next; position within a role is descriptive metadata only and MUST NOT confer identity.
- **FR-004**: Users MUST be able to read a resolved guest's current associations, paged and tenant-scoped, each exposing source system, object type, object id, role and position, the current observation's extracted data, the count of observations behind it, and the object's business start and end where supplied.
- **FR-004a**: The timeline MUST be ordered by business start, falling back to the observation's timestamp for objects supplied without one, and MUST break ties deterministically so paging is stable. The service MUST NOT read business dates out of the payload — the payload stays opaque and only the explicit fields are interpreted.
- **FR-005**: Records carrying no business-object identity MUST NOT appear as associations; the existing per-guest records list MUST continue to return them and MUST keep its current contract.
- **FR-006**: Superseded observations MUST remain stored, unmodified, and retrievable — users MUST be able to read the full observation history of an association in version order. Supersession is a view over immutable records, never a deletion or a mutation (Constitution II).
- **FR-007**: When a new current roster no longer contains a guest for a role that guest previously held, the association MUST cease to be current on that guest. The affected guest's timeline MUST omit the ended association by default and MUST be able to return it on request, marked as no longer on the booking; it MUST name a successor only for a genuine one-to-one handover of the role: the role had exactly one occupant in the version where this guest last held it, has exactly one occupant in the current roster, and they are different guests. In every other case — a role that shrank from several occupants to fewer, grew, or emptied — it MUST name none, because a guest who was dropped was not replaced by whoever else happens to hold the role.
- **FR-008**: The current roster MUST be decided by comparing object versions chronologically, not by arrival order or receipt time; observations of an earlier version MUST NOT displace a newer current roster whenever they arrive.
- **FR-009**: Merge and unmerge MUST leave associations consistent: after either operation a guest holds each association at most once, following the resolution links of the current roster's observations.
- **FR-010**: Association derivation MUST be reproducible from the stored records — no association state may exist that could not be recomputed from the immutable observations and their resolution links.
- **FR-010a**: While a version's observations are still arriving, the roster MAY be temporarily incomplete; the system MUST converge on the complete roster once they have landed and MUST NOT record any merge event or steward-visible decision from the partial state.

**Actor identity on decisions**

- **FR-011**: Every merge event and every steward decision MUST record an actor comprising an actor type — system, human, or agent — and an actor identity.
- **FR-012**: Automatic resolution performed by the engine MUST record the system actor together with the deciding matcher's name; it MUST NOT attribute a decision to a human or an agent.
- **FR-013**: Explicit steward operations — review confirm, review reject, unmerge, and do-not-merge rule creation and deletion — MUST record the requesting actor.
- **FR-014**: Each credential MUST be registered as human-operated or agent-operated and MUST carry a name. The actor type recorded on a decision MUST come from the credential; a request MUST NOT be able to record an actor type its credential does not grant, and an attempt to do so MUST be refused with a problem-details error. A request MAY additionally supply an identifier naming the individual acting behind a shared credential, which refines the actor identity within the type the credential grants and never widens it.
- **FR-015**: Explain output and decision responses MUST expose the recorded actor. Events recorded before this capability existed MUST render as unattributed without error.
- **FR-016**: Do-not-merge rules MUST record the actor that created them and, once lifted, the actor that lifted them, so that a later policy can prevent an agent from overriding a human's explicit split. Enforcing that restriction is out of scope for this slice; recording both sides of it is not.
- **FR-016a**: Lifting a do-not-merge rule MUST preserve the rule rather than destroy it: a lifted rule stops gating merges but remains readable with its origin, both actors, and when it was lifted. This applies to every lift — an explicit deletion request and the automatic lift that follows confirming a match across a rule alike. A pair that is split, lifted, and split again MUST be able to carry a new rule.
- **FR-017**: Recorded actors MUST be tenant-scoped and MUST remain readable for the lifetime of the audit trail, including after the corresponding credential is revoked.

**Connector observation contract**

- **FR-018**: The published ingest contract MUST define, for mutable multi-person business objects, how observations are keyed: one observation per person per object version, with the role and its position distinguishing persons within a version so that no two collide on the ingest duplicate key.
- **FR-019**: The version MUST be the instant the source object itself records as its last modification, derivable from source state alone, so that retries, duplicate change notifications, and full re-syncs of unchanged data are idempotent. A submitter MUST NOT substitute its own clock, a delivery timestamp, or an event id.
- **FR-020**: The observation's own timestamp MUST equal the object version it reflects, so survivorship and roster supersession order observations identically. A mismatch MUST flag the record and MUST NOT suppress it: the version itself is still usable, so the observation still joins its roster. Suppressing it would erase an otherwise valid booking from every timeline over a redundant field.
- **FR-021**: Re-submission of an already-stored observation key MUST be absorbed as a duplicate with no new record, no association change, and no resolution side effects.
- **FR-022**: The contract MUST require roster-complete emission: when any person's data changed or the set of persons changed, the submitter emits an observation for every person on that version; when no person data and no roster changed, it emits nothing. The contract MUST state both consequences of ignoring it — a partial version reads as a booking that lost guests, and emitting on person-neutral edits inflates observation counts, which can push a normal guest's identifier over the sharing review threshold and cause false review parkings.
- **FR-023**: The contract MUST require that contact data belonging to the booking rather than to the person — agency phones, property emails, shared office numbers — is not submitted in the person fields, and MUST provide a place in the submission for such object-level metadata where it produces no guest identifier.
- **FR-024**: A record carrying business-object identity whose version is absent or is not a parseable instant MUST be stored and flagged for review with a specific reason, and MUST NOT participate in any roster, never rejected (Constitution III).

### Key Entities

- **Business Object**: a mutable thing in a source system that persons are attached to — a reservation first. Identified by source system, object type, and object id; versioned by the instant the source last modified it, which advances with each edit.
- **Role**: the capacity in which a person appears on a business object (primary guest, additional guest, booker), optionally with the position the source listed them at. The position is descriptive: it is shown to users but confers no identity across versions.
- **Roster**: all observations sharing a business object and object version — the complete statement of who was on that object at that version. The highest version's roster is the current one, and it alone determines present membership.
- **Observation**: an existing immutable source record, now optionally stating which business object, role, position, and object version it reflects. Unchanged in every other respect.
- **Association (timeline entry)**: the derived link between a resolved guest and a business object in a given role, carrying the object's business start and end where the submitter supplied them. Current while the object's current roster places that guest in that role; backed by a retrievable history of every observation of that object and role. Guests the booking has moved away from, or dropped, can see it marked as no longer on the booking.
- **Actor**: who caused a decision — the system acting on its rules, a named human steward, or a named agent. Its type comes from the credential that made the request and cannot be claimed by the request itself; its identity may be refined per request to name the individual behind a shared credential. Recorded on merge events, review decisions, and both the creation and the lifting of do-not-merge rules, and retained for the life of the audit trail.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A reservation edited any number of times appears exactly once on its guest's timeline, showing its latest state.
- **SC-002**: After a booking's guest list changes, every query for who currently holds that booking returns exactly the persons on its newest version — no one who was removed, and no one who merely shifted position.
- **SC-003**: A user can answer "what does this guest currently have" for any resolved guest in a single request, without post-processing observation lists.
- **SC-004**: No observation is lost: the number of retrievable observations equals the number of accepted submissions, unchanged by any supersession, merge, or unmerge.
- **SC-005**: Every decision recorded after this slice ships names its actor; the share of decisions recorded with an unknown actor is zero.
- **SC-006**: The first page of the timeline returns in under 1 second for a guest holding 500 associations.
- **SC-007**: Replaying a full backfill of an unchanged period produces zero new observations, zero timeline changes, and zero new merge events.
- **SC-008**: Submitters that send no business-object identity observe no change in resolution behaviour: every slice-1 and slice-2 resolution, matching, and ingest test passes unmodified. The single deliberate exception is paging — the review-queue and do-not-merge-rule listings move from offsets to cursors so the API has one paging idiom, so those two API tests change with them.

## Assumptions

- Reservations are the only business-object type this slice must support end to end; the model treats the type as data rather than a fixed list, so further types need no redesign.
- Object-level status such as cancelled or checked out travels in the payload and is displayed, but the resolution engine attaches no meaning to it in this slice.
- Applying the emission rule is the submitter's responsibility — slice 4 builds the connectors that do it. This slice defines the contract and the duplicate absorption that makes retries safe regardless.
- Roadmap note R4-1 is amended by this slice: its per-person emit-on-change rule becomes roster-complete emission, because the version-roster model requires each emitted version to be a complete statement of who is on the object. The roadmap note must be updated to say so when it is marked consumed.
- Existing endpoints keep their contracts; the timeline is an addition, and business-object identity on ingest is optional. A submitter upgraded to send it sees new capability, not new obligations.
- Actor identity rides on the existing per-tenant API-key authentication: credentials gain an operator type and a name, which is the trust boundary, while the optional per-request individual identifier is caller-asserted attribution and grants nothing.
- Credentials issued before this slice are treated as human-operated by default, so existing integrations keep working and no decision is left unattributed.
- Scoped credentials — a review-only key that cannot unmerge or change configuration — remain a later concern.
- Restricting agents from lifting a human steward's split is enabled by this slice's actor data but deliberately not enforced in it; it belongs with scoped credentials.
- Timeline entries are derived, so a change to derivation rules can be rolled out by recomputation without touching the immutable observation history.
- Steward corrections still arrive as ordinary records; a first-class steward-correction endpoint (roadmap R-X1) and per-source trust ranking (R-X2) stay out of scope.
