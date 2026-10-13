-- Cash flow hedge accounting (ADR 0045): relationships between hedging trades and a forecast purchase or sale,
-- designated and discontinued under four eyes, and their effectiveness measured on every official valuation run.

CREATE SEQUENCE hedge_number_seq;

CREATE TABLE hedge_relationship (
    id                     UUID           NOT NULL PRIMARY KEY,
    number                 TEXT           NOT NULL UNIQUE,
    legal_entity_id        UUID           NOT NULL,
    direction              TEXT           NOT NULL CHECK (direction IN ('PURCHASE', 'SALE')),
    product_family         TEXT           NOT NULL CHECK (product_family IN ('EMISSION_ALLOWANCE', 'GUARANTEE_OF_ORIGIN')),
    allowance_product_code TEXT,
    bucket_code            TEXT,
    production_year        INT,
    quantity               NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    expected_date          DATE           NOT NULL,
    currency               CHAR(3)        NOT NULL,
    objective              TEXT           NOT NULL,
    hedge_ratio            NUMERIC(18, 6) NOT NULL,
    checks                 JSONB          NOT NULL DEFAULT '[]'::jsonb,
    status                 TEXT           NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'DISCONTINUED')),
    requested_by           TEXT           NOT NULL,
    requested_at           TIMESTAMPTZ    NOT NULL,
    decided_by             TEXT,
    decided_at             TIMESTAMPTZ,
    designation_date       DATE,
    designation_price      NUMERIC(18, 6),
    instrument_baseline    NUMERIC(18, 2),
    document_id            UUID,
    discontinuation_reason TEXT,
    still_expected         BOOLEAN,
    discontinuation_by     TEXT,
    discontinuation_at     TIMESTAMPTZ,
    discontinued_by        TEXT,
    discontinued_at        TIMESTAMPTZ,
    frozen_reserve         NUMERIC(18, 2),
    CONSTRAINT hedge_item_ck CHECK (
        (product_family = 'EMISSION_ALLOWANCE' AND allowance_product_code IS NOT NULL)
        OR (product_family = 'GUARANTEE_OF_ORIGIN' AND bucket_code IS NOT NULL)
    ),
    CONSTRAINT hedge_four_eyes_ck CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT hedge_discontinuation_four_eyes_ck CHECK (discontinued_by IS NULL OR discontinued_by <> discontinuation_by),
    CONSTRAINT hedge_active_ck CHECK (status NOT IN ('ACTIVE', 'DISCONTINUED') OR (designation_price IS NOT NULL AND decided_by IS NOT NULL))
);
CREATE INDEX hedge_relationship_entity_idx ON hedge_relationship (legal_entity_id, status);

CREATE TABLE hedge_instrument (
    relationship_id UUID           NOT NULL REFERENCES hedge_relationship (id),
    trade_id        UUID           NOT NULL,
    trade_number    TEXT           NOT NULL,
    proportion      NUMERIC(9, 6)  NOT NULL CHECK (proportion > 0 AND proportion <= 1),
    quantity        NUMERIC(18, 3) NOT NULL,
    PRIMARY KEY (relationship_id, trade_id)
);
CREATE INDEX hedge_instrument_trade_idx ON hedge_instrument (trade_id);

CREATE TABLE hedge_measurement (
    relationship_id    UUID           NOT NULL REFERENCES hedge_relationship (id),
    valuation_run_id   UUID           NOT NULL,
    valuation_date     DATE           NOT NULL,
    instrument_value   NUMERIC(18, 2) NOT NULL,
    instrument_change  NUMERIC(18, 2) NOT NULL,
    hypothetical_value NUMERIC(18, 2) NOT NULL,
    effective          NUMERIC(18, 2) NOT NULL,
    ineffective        NUMERIC(18, 2) NOT NULL,
    offset_ratio       NUMERIC(18, 1),
    measured_at        TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (relationship_id, valuation_run_id),
    CONSTRAINT hedge_measurement_split_ck CHECK (effective + ineffective = instrument_value)
);

CREATE OR REPLACE FUNCTION hedge_measurement_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'hedge measurements are immutable';
END;
$$;

CREATE TRIGGER hedge_measurement_append_only
    BEFORE UPDATE OR DELETE ON hedge_measurement
    FOR EACH ROW EXECUTE FUNCTION hedge_measurement_append_only();
