-- Exchange clearing of EUA futures (ADR 0046): clearing accounts at a clearing house and the daily variation margin
-- per account, computed from the official settlement prices into immutable, versioned runs.

CREATE TABLE clearing_account (
    id                UUID        NOT NULL PRIMARY KEY,
    code              TEXT        NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_-]+$'),
    legal_entity_id   UUID        NOT NULL,
    clearing_house_id UUID        NOT NULL,
    clearing_member   TEXT        NOT NULL,
    currency          CHAR(3)     NOT NULL,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL
);
-- One account per entity and clearing house: futures cleared there are margined through it.
CREATE UNIQUE INDEX clearing_account_house_uq ON clearing_account (legal_entity_id, clearing_house_id) WHERE active;

CREATE TABLE margin_run (
    id               UUID           NOT NULL PRIMARY KEY,
    account_id       UUID           NOT NULL REFERENCES clearing_account (id),
    legal_entity_id  UUID           NOT NULL,
    business_date    DATE           NOT NULL,
    version          INT            NOT NULL,
    currency         CHAR(3)        NOT NULL,
    variation_margin NUMERIC(18, 2) NOT NULL,
    inputs_hash      TEXT           NOT NULL,
    computed_by      TEXT           NOT NULL,
    computed_at      TIMESTAMPTZ    NOT NULL,
    superseded_at    TIMESTAMPTZ,
    CONSTRAINT margin_run_version_uq UNIQUE (account_id, business_date, version)
);
CREATE UNIQUE INDEX margin_run_current_uq ON margin_run (account_id, business_date) WHERE superseded_at IS NULL;

CREATE TABLE margin_line (
    run_id                    UUID           NOT NULL REFERENCES margin_run (id),
    contract_code             TEXT           NOT NULL,
    position_before           NUMERIC(18, 3) NOT NULL,
    traded_quantity           NUMERIC(18, 3) NOT NULL,
    position_after            NUMERIC(18, 3) NOT NULL,
    settlement_price          NUMERIC(18, 6) NOT NULL,
    previous_settlement_price NUMERIC(18, 6),
    variation_margin          NUMERIC(18, 2) NOT NULL,
    PRIMARY KEY (run_id, contract_code),
    CONSTRAINT margin_line_position_ck CHECK (position_after = position_before + traded_quantity)
);

CREATE OR REPLACE FUNCTION margin_line_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'margin lines are immutable; compute a new version';
END;
$$;

CREATE TRIGGER margin_line_append_only
    BEFORE UPDATE OR DELETE ON margin_line
    FOR EACH ROW EXECUTE FUNCTION margin_line_append_only();
