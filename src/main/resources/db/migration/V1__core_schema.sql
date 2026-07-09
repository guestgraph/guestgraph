-- GuestGraph core schema (feature 001-core-identity-resolution)
-- Every table is tenant-scoped; every uniqueness constraint is composite with tenant_id.

CREATE TABLE tenant (
    id               uuid PRIMARY KEY,
    slug             text NOT NULL UNIQUE,
    name             text NOT NULL,
    review_threshold int  NOT NULL DEFAULT 10 CHECK (review_threshold > 0),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_key (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    key_hash   text NOT NULL UNIQUE,
    label      text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);

CREATE TABLE source_system (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    code       text NOT NULL,
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE guest (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenant (id),
    profile    jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX guest_tenant_idx ON guest (tenant_id);

CREATE TABLE source_record (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenant (id),
    source_system_id     uuid NOT NULL REFERENCES source_system (id),
    external_key         text NOT NULL,
    payload              jsonb NOT NULL,
    extracted            jsonb NOT NULL DEFAULT '{}'::jsonb,
    record_timestamp     timestamptz,
    needs_review         boolean NOT NULL DEFAULT false,
    needs_review_reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
    received_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source_system_id, external_key)
);

CREATE INDEX source_record_tenant_idx ON source_record (tenant_id);

-- Constitution Principle II: source records are immutable. Only the review flag
-- may ever change; everything else is frozen at ingest.
CREATE FUNCTION source_record_immutable() RETURNS trigger AS
$$
BEGIN
    IF NEW.id <> OLD.id
        OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.source_system_id <> OLD.source_system_id
        OR NEW.external_key <> OLD.external_key
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.extracted IS DISTINCT FROM OLD.extracted
        OR NEW.record_timestamp IS DISTINCT FROM OLD.record_timestamp
        OR NEW.received_at <> OLD.received_at THEN
        RAISE EXCEPTION 'source_record is immutable (id=%)', OLD.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER source_record_immutable
    BEFORE UPDATE
    ON source_record
    FOR EACH ROW
EXECUTE FUNCTION source_record_immutable();

-- Belt-and-braces for append-only tables: application code never deletes source
-- records or merge events; lawful GDPR erasure (the constitution's sole exception)
-- goes through an explicit session setting.
CREATE FUNCTION guard_append_only() RETURNS trigger AS
$$
BEGIN
    IF current_setting('guestgraph.allow_erasure', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION '% is append-only; set guestgraph.allow_erasure = ''on'' for lawful erasure', TG_TABLE_NAME;
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER source_record_append_only
    BEFORE DELETE
    ON source_record
    FOR EACH ROW
EXECUTE FUNCTION guard_append_only();

CREATE TABLE record_identifier (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL,
    source_record_id uuid NOT NULL REFERENCES source_record (id),
    type             text NOT NULL CHECK (type IN ('EMAIL', 'PHONE', 'LOYALTY_ID', 'ID_DOCUMENT', 'EXTERNAL_KEY')),
    value_normalized text NOT NULL,
    UNIQUE (source_record_id, type, value_normalized)
);

CREATE INDEX record_identifier_lookup_idx ON record_identifier (tenant_id, type, value_normalized);

-- No FK on guest_id columns: merge/unmerge delete absorbed or emptied guests, but their
-- ids must stay referenceable in the audit trail forever.
CREATE TABLE merge_event (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenant (id),
    kind               text NOT NULL CHECK (kind IN ('CREATE', 'ATTACH', 'MERGE', 'UNMERGE', 'REVIEW_CONFIRM', 'REVIEW_REJECT')),
    guest_id           uuid NOT NULL,
    absorbed_guest_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_record_ids  jsonb NOT NULL DEFAULT '[]'::jsonb,
    matcher_name       text NOT NULL,
    confidence         numeric(4, 3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    evidence           jsonb NOT NULL DEFAULT '{}'::jsonb,
    excluded_guest_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX merge_event_guest_idx ON merge_event (tenant_id, guest_id, created_at);

CREATE TRIGGER merge_event_append_only
    BEFORE DELETE
    ON merge_event
    FOR EACH ROW
EXECUTE FUNCTION guard_append_only();

CREATE TABLE resolution_link (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL,
    source_record_id    uuid NOT NULL UNIQUE REFERENCES source_record (id),
    guest_id            uuid NOT NULL REFERENCES guest (id),
    created_by_event_id uuid NOT NULL REFERENCES merge_event (id),
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX resolution_link_guest_idx ON resolution_link (tenant_id, guest_id);

CREATE TABLE identifier (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL,
    guest_id         uuid NOT NULL REFERENCES guest (id) ON DELETE CASCADE,
    type             text NOT NULL CHECK (type IN ('EMAIL', 'PHONE', 'LOYALTY_ID', 'ID_DOCUMENT', 'EXTERNAL_KEY')),
    value_normalized text NOT NULL,
    UNIQUE (tenant_id, type, value_normalized, guest_id)
);

CREATE INDEX identifier_lookup_idx ON identifier (tenant_id, type, value_normalized);
-- Hot write path: every ingest rebuilds a guest's identifiers (delete-by-guest), and
-- guest deletion cascades here — both need this access path.
CREATE INDEX identifier_guest_idx ON identifier (tenant_id, guest_id);

CREATE TABLE match_review (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenant (id),
    status             text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    source_record_id   uuid NOT NULL REFERENCES source_record (id),
    candidate_guest_id uuid NOT NULL,
    identifier_type    text NOT NULL,
    identifier_value   text NOT NULL,
    reason             text NOT NULL,
    matcher_name       text NOT NULL,
    confidence         numeric(4, 3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    created_at         timestamptz NOT NULL DEFAULT now(),
    decided_at         timestamptz,
    decision_event_id  uuid REFERENCES merge_event (id)
);

CREATE INDEX match_review_queue_idx ON match_review (tenant_id, status, created_at);
