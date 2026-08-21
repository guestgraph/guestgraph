-- Timeline & attributed decisions (feature 003).
-- Additive but for one constraint swap on negative_match_rule (see below): no slice-1 or
-- slice-2 table loses a column or changes a type.

-- The business object a record describes. An optional immutable companion of source_record,
-- like record_identifier and record_block_key — which is why source_record and its
-- immutability trigger stay untouched.
CREATE TABLE record_object
(
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL,
    source_record_id uuid NOT NULL UNIQUE REFERENCES source_record (id),
    -- Denormalised from the parent record: the object namespace is
    -- (tenant, source system, object type, object id), so carrying it here makes the
    -- roster lookup a single-table index scan instead of a join back.
    source_system_id uuid NOT NULL REFERENCES source_system (id),
    object_type      text NOT NULL,
    object_id        text NOT NULL,
    object_role      text NOT NULL CHECK (object_role IN ('PRIMARY_GUEST', 'ADDITIONAL_GUEST', 'BOOKER')),
    -- Descriptive only: position confers no identity across versions, so a person shifting
    -- position when a guest list shrinks is not a reassignment.
    object_position  int,
    -- The instant the source object itself records as last modified. Chronological
    -- comparison decides the current roster; a record whose submitted version could not be
    -- parsed gets no row here at all, so it joins no roster while staying stored and flagged.
    object_version   timestamptz NOT NULL,
    business_start   timestamptz,
    business_end     timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- All observations of one object, newest version first: the roster lookup.
CREATE INDEX record_object_roster_idx
    ON record_object (tenant_id, source_system_id, object_type, object_id, object_version DESC);
CREATE INDEX record_object_record_idx ON record_object (tenant_id, source_record_id);

-- Who decided. NULL means recorded before this slice — rendered as unattributed, never an error.
ALTER TABLE merge_event
    ADD COLUMN actor_type text CHECK (actor_type IN ('SYSTEM', 'HUMAN', 'AGENT')),
    ADD COLUMN actor_id   text;

ALTER TABLE negative_match_rule
    ADD COLUMN actor_type        text CHECK (actor_type IN ('SYSTEM', 'HUMAN', 'AGENT')),
    ADD COLUMN actor_id          text,
    -- Rules are lifted, not deleted: lifting is the act that overrides a steward's split, so
    -- it needs an actor, and a deleted row has nowhere to keep one.
    ADD COLUMN lifted_at         timestamptz,
    ADD COLUMN lifted_actor_type text CHECK (lifted_actor_type IN ('SYSTEM', 'HUMAN', 'AGENT')),
    ADD COLUMN lifted_actor_id   text;

-- The one non-additive step. Pair uniqueness must apply to ACTIVE rules only: a pair that is
-- split, lifted, and split again needs a second row while the lifted one stays readable.
ALTER TABLE negative_match_rule
    DROP CONSTRAINT negative_match_rule_tenant_id_record_a_record_b_key;
CREATE UNIQUE INDEX negative_match_rule_active_pair_idx
    ON negative_match_rule (tenant_id, record_a, record_b) WHERE lifted_at IS NULL;

-- What a credential acts as. The type is the trust boundary: a request may name the individual
-- behind a shared credential but can never claim a type the credential does not grant.
ALTER TABLE api_key
    ADD COLUMN actor_type text NOT NULL DEFAULT 'HUMAN' CHECK (actor_type IN ('HUMAN', 'AGENT')),
    ADD COLUMN actor_name text;
-- Backfill before SET NOT NULL, so the migration also succeeds on a non-empty local volume.
-- `label` is key administration ("laptop key, rotated March"); actor_name is who the key acts
-- as. They start equal and diverge freely.
UPDATE api_key SET actor_name = label WHERE actor_name IS NULL;
ALTER TABLE api_key ALTER COLUMN actor_name SET NOT NULL;
