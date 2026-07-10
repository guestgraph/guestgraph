-- Probabilistic matching (feature 002) — strictly additive; slice-1 tables untouched.

-- Blocking keys: similarity-oriented derivatives of a record, the means of finding
-- fuzzy candidates. Immutable companions of source_record, like record_identifier.
CREATE TABLE record_block_key
(
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL,
    source_record_id uuid NOT NULL REFERENCES source_record (id),
    type             text NOT NULL CHECK (type IN
                                          ('NAME_PHONETIC_BIRTHYEAR', 'NAME_INITIALS_BIRTHDATE',
                                           'PHONE_SUFFIX7', 'EMAIL_LOCALPART', 'EMAIL_MASKED')),
    value_normalized text NOT NULL,
    UNIQUE (source_record_id, type, value_normalized)
);

CREATE INDEX record_block_key_lookup_idx ON record_block_key (tenant_id, type, value_normalized);

-- Steward splits that stick: the clusters containing record_a and record_b must never
-- be silently merged. Record ids are immutable and survive merges; guest ids do not.
CREATE TABLE negative_match_rule
(
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL,
    record_a   uuid NOT NULL REFERENCES source_record (id),
    record_b   uuid NOT NULL REFERENCES source_record (id),
    origin     text NOT NULL CHECK (origin IN ('UNMERGE', 'REVIEW_REJECT', 'MANUAL')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (record_a < record_b),
    UNIQUE (tenant_id, record_a, record_b)
);

CREATE INDEX negative_match_rule_a_idx ON negative_match_rule (tenant_id, record_a);
CREATE INDEX negative_match_rule_b_idx ON negative_match_rule (tenant_id, record_b);

-- Per-tenant identifier trust. Built-in OTA relay-domain defaults are code constants,
-- merged at evaluation time — not rows.
CREATE TABLE identifier_quality_rule
(
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant (id),
    identifier_type  text NOT NULL CHECK (identifier_type IN
                                          ('EMAIL', 'PHONE', 'LOYALTY_ID', 'ID_DOCUMENT', 'EXTERNAL_KEY')),
    match_kind       text NOT NULL CHECK (match_kind IN ('EXACT', 'EMAIL_DOMAIN')),
    value_normalized text NOT NULL,
    rule             text NOT NULL CHECK (rule IN ('IGNORE', 'PERFECT_MATCH', 'MASKED_ALIAS')),
    note             text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, identifier_type, match_kind, value_normalized)
);

CREATE INDEX identifier_quality_rule_tenant_idx ON identifier_quality_rule (tenant_id);

-- Score bands (FR-005/006): auto-merge ships OFF (threshold 1.0); review floor 0.75.
ALTER TABLE tenant
    ADD COLUMN auto_merge_threshold numeric(4, 3) NOT NULL DEFAULT 1.000,
    ADD COLUMN review_floor         numeric(4, 3) NOT NULL DEFAULT 0.750,
    ADD CONSTRAINT tenant_band_order_check CHECK (review_floor <= auto_merge_threshold),
    ADD CONSTRAINT tenant_band_range_check CHECK (
        auto_merge_threshold >= 0 AND auto_merge_threshold <= 1
            AND review_floor >= 0 AND review_floor <= 1);
