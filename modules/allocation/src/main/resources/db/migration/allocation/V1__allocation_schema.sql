-- Allocation: obligations to deliver or cancel certificates, allocation plans that assign inventory lots to them,
-- soft reservations held by proposed plans, and bucket marks used for scoring until the valuation module exists.

CREATE TABLE obligation (
    id                 UUID        NOT NULL PRIMARY KEY,
    kind               TEXT        NOT NULL CHECK (kind IN ('SALE', 'INTERCOMPANY', 'DISCLOSURE', 'OWN_CONSUMPTION')),
    holder_entity_id   UUID        NOT NULL,
    book_id            UUID,
    counterparty_id    UUID,
    trade_ref          TEXT,
    source_ref         UUID,
    criteria_json      JSONB       NOT NULL,
    criteria_summary   TEXT        NOT NULL,
    quantity_mwh       BIGINT      NOT NULL CHECK (quantity_mwh > 0),
    fulfilled_mwh      BIGINT      NOT NULL DEFAULT 0 CHECK (fulfilled_mwh >= 0 AND fulfilled_mwh <= quantity_mwh),
    delivery_window    DATERANGE   NOT NULL,
    delivery_date      DATE        NOT NULL,
    target_registry_code TEXT,
    target_account_ref TEXT,
    cancellation       JSONB,
    priority_class     INT         NOT NULL CHECK (priority_class BETWEEN 1 AND 9),
    constraints        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status             TEXT        NOT NULL CHECK (status IN ('OPEN', 'FULFILLED', 'CLOSED')),
    version            INT         NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX obligation_open_idx ON obligation (holder_entity_id, delivery_date) WHERE status = 'OPEN';
CREATE INDEX obligation_source_idx ON obligation (source_ref) WHERE source_ref IS NOT NULL;

CREATE TABLE allocation_plan (
    id                      UUID        NOT NULL PRIMARY KEY,
    idempotency_key         TEXT        NOT NULL UNIQUE,
    holder_entity_id        UUID        NOT NULL,
    snapshot_seq            BIGINT      NOT NULL,
    solver                  TEXT        NOT NULL CHECK (solver IN ('GREEDY', 'EXACT')),
    status                  TEXT        NOT NULL CHECK (status IN (
                                'PROPOSED', 'APPROVED', 'RESERVED', 'INSTRUCTED', 'CONFIRMED', 'PARTIALLY_FAILED', 'CLOSED',
                                'REJECTED', 'EXPIRED', 'STALE', 'CANCELLED')),
    proposed_by             TEXT        NOT NULL,
    proposed_at             TIMESTAMPTZ NOT NULL,
    approved_by             TEXT,
    approved_at             TIMESTAMPTZ,
    decided_by              TEXT,
    decision_reason         TEXT,
    reservation_expires_at  TIMESTAMPTZ NOT NULL,
    objective               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    coverage_report         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version                 INT         NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT allocation_plan_four_eyes_ck CHECK (approved_by IS NULL OR approved_by IS DISTINCT FROM proposed_by)
);
CREATE INDEX allocation_plan_entity_status_idx ON allocation_plan (holder_entity_id, status);
CREATE INDEX allocation_plan_soft_expiry_idx ON allocation_plan (reservation_expires_at) WHERE status = 'PROPOSED';

CREATE TABLE allocation_line (
    id               UUID           NOT NULL PRIMARY KEY,
    plan_id          UUID           NOT NULL REFERENCES allocation_plan (id) ON DELETE CASCADE,
    line_no          INT            NOT NULL,
    obligation_id    UUID           NOT NULL REFERENCES obligation (id),
    lot_id           UUID           NOT NULL,
    lot_class_key    TEXT           NOT NULL,
    quantity_mwh     BIGINT         NOT NULL CHECK (quantity_mwh > 0),
    score            NUMERIC(14, 6) NOT NULL,
    score_breakdown  JSONB          NOT NULL DEFAULT '{}'::jsonb,
    status           TEXT           NOT NULL CHECK (status IN ('PROPOSED', 'RESERVED', 'INSTRUCTED', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    reserved_lot_id  UUID,
    instruction_id   UUID,
    external_ref     TEXT,
    failure_reason   TEXT,
    CONSTRAINT allocation_line_uq UNIQUE (plan_id, line_no)
);
CREATE INDEX allocation_line_obligation_idx ON allocation_line (obligation_id);
CREATE INDEX allocation_line_lot_idx ON allocation_line (lot_id) WHERE status IN ('PROPOSED', 'RESERVED', 'INSTRUCTED');
CREATE INDEX allocation_line_instruction_idx ON allocation_line (instruction_id) WHERE instruction_id IS NOT NULL;

-- Soft reservations: lots (or parts) claimed by a PROPOSED plan; excluded from other proposals until approval or expiry.
CREATE TABLE soft_reservation (
    plan_id      UUID   NOT NULL REFERENCES allocation_plan (id) ON DELETE CASCADE,
    lot_id       UUID   NOT NULL,
    quantity_mwh BIGINT NOT NULL CHECK (quantity_mwh > 0),
    expires_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (plan_id, lot_id)
);
CREATE INDEX soft_reservation_lot_idx ON soft_reservation (lot_id, expires_at);

-- Bucket marks (EUR/MWh) by production year, maintained by hand until the valuation module supplies curves.
CREATE TABLE bucket_mark (
    bucket_code     TEXT           NOT NULL,
    production_year INT            NOT NULL,
    mark_eur_per_mwh NUMERIC(12, 4) NOT NULL,
    as_of           DATE           NOT NULL,
    PRIMARY KEY (bucket_code, production_year)
);
