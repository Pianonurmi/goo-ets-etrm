-- Valuation runs: mark-to-market of open trades per delivery schedule against official curves, signed off by a second
-- person; P&L explain between consecutive official runs. Results are immutable once written.

CREATE TABLE valuation_run (
    id              UUID           NOT NULL PRIMARY KEY,
    legal_entity_id UUID           NOT NULL,
    valuation_date  DATE           NOT NULL,
    kind            TEXT           NOT NULL CHECK (kind IN ('EOD', 'PERIOD_END', 'ADHOC')),
    status          TEXT           NOT NULL CHECK (status IN ('DRAFT', 'PENDING_SIGN_OFF', 'OFFICIAL', 'SUPERSEDED', 'REJECTED')),
    base_currency   CHAR(3)        NOT NULL,
    total_mtm_base  NUMERIC(18, 2) NOT NULL,
    exceptions      INT            NOT NULL,
    -- Curve snapshots used, by curve code.
    inputs          JSONB          NOT NULL DEFAULT '{}'::jsonb,
    note            TEXT,
    created_by      TEXT           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    signed_off_by   TEXT,
    signed_off_at   TIMESTAMPTZ,
    sign_off_note   TEXT,
    superseded_at   TIMESTAMPTZ,
    CONSTRAINT valuation_run_four_eyes_ck CHECK (signed_off_by IS NULL OR signed_off_by <> created_by)
);
CREATE UNIQUE INDEX valuation_run_official_uq ON valuation_run (legal_entity_id, valuation_date) WHERE status = 'OFFICIAL';
CREATE INDEX valuation_run_entity_date_idx ON valuation_run (legal_entity_id, valuation_date);

CREATE TABLE valuation_result (
    run_id                  UUID           NOT NULL REFERENCES valuation_run (id),
    schedule_id             UUID           NOT NULL,
    trade_id                UUID           NOT NULL,
    trade_number            TEXT           NOT NULL,
    trade_version           INT            NOT NULL,
    leg_no                  INT            NOT NULL,
    book_id                 UUID           NOT NULL,
    book_type               TEXT           NOT NULL,
    counterparty_id         UUID           NOT NULL,
    product_family          TEXT           NOT NULL,
    product_key             TEXT,
    production_year         INT,
    delivery_date           DATE           NOT NULL,
    quantity                NUMERIC(18, 3) NOT NULL,
    contract_price          NUMERIC(18, 6),
    contract_currency       CHAR(3)        NOT NULL,
    market_price            NUMERIC(18, 6),
    market_currency         CHAR(3),
    price_origin            TEXT,
    curve_code              TEXT,
    snapshot_id             UUID,
    curve_date              DATE,
    -- The market price in the contract currency, and the contract currency's rate to base.
    market_price_contract   NUMERIC(18, 6),
    fx_to_base              NUMERIC(18, 10),
    mtm                     NUMERIC(18, 2),
    mtm_base                NUMERIC(18, 2),
    status                  TEXT           NOT NULL CHECK (status IN ('OK', 'MISSING_PRICE', 'MISSING_FX', 'MISSING_BUCKET', 'EXCLUDED')),
    note                    TEXT,
    PRIMARY KEY (run_id, schedule_id)
);
CREATE INDEX valuation_result_trade_idx ON valuation_result (trade_id);

CREATE TABLE pnl_explain (
    run_id       UUID           NOT NULL REFERENCES valuation_run (id),
    prior_run_id UUID           REFERENCES valuation_run (id),
    schedule_id  UUID           NOT NULL,
    trade_id     UUID           NOT NULL,
    book_id      UUID           NOT NULL,
    component    TEXT           NOT NULL CHECK (component IN ('NEW', 'CANCELLED', 'DELIVERED', 'AMENDED', 'PRICE', 'FX', 'UNEXPLAINED')),
    amount_base  NUMERIC(18, 2) NOT NULL,
    PRIMARY KEY (run_id, schedule_id, component)
);

CREATE OR REPLACE FUNCTION valuation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER valuation_result_immutable BEFORE UPDATE OR DELETE ON valuation_result FOR EACH ROW EXECUTE FUNCTION valuation_immutable();
CREATE TRIGGER pnl_explain_immutable BEFORE UPDATE OR DELETE ON pnl_explain FOR EACH ROW EXECUTE FUNCTION valuation_immutable();
