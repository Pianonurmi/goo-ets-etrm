-- Portfolio value (ADR 0039): a management view kept with each run, never booked.

-- Cheapest to deliver: what a sale's certificates are worth on the valuation-grade allocation, shown next to the
-- official MtM (which stays on the bucket curve).
ALTER TABLE valuation_result ADD COLUMN ctd_price_eur NUMERIC(18, 6);
ALTER TABLE valuation_result ADD COLUMN ctd_mtm_base NUMERIC(18, 2);

-- The bridge of one run, in EUR: inventory at the best price each certificate qualifies for, plus the GoO trades'
-- MtM, less give-away, transfer fees and expiry haircut on the allocation.
CREATE TABLE portfolio_value (
    run_id            UUID           PRIMARY KEY REFERENCES valuation_run (id),
    legal_entity_id   UUID           NOT NULL,
    valuation_date    DATE           NOT NULL,
    currency          TEXT           NOT NULL,
    held_mwh          BIGINT         NOT NULL,
    inventory_value   NUMERIC(18, 2) NOT NULL,
    trade_mtm         NUMERIC(18, 2) NOT NULL,
    give_away         NUMERIC(18, 2) NOT NULL,
    transfer_fees     NUMERIC(18, 2) NOT NULL,
    expiry_haircut    NUMERIC(18, 2) NOT NULL,
    portfolio_value   NUMERIC(18, 2) NOT NULL,
    allocated_mwh     BIGINT         NOT NULL,
    shortfall_mwh     BIGINT         NOT NULL,
    unpriced_mwh      BIGINT         NOT NULL,
    stamp             TEXT           NOT NULL,
    buckets           JSONB          NOT NULL,
    obligations       JSONB          NOT NULL,
    computed_at       TIMESTAMPTZ    NOT NULL,
    CHECK (portfolio_value = inventory_value + trade_mtm - give_away - transfer_fees - expiry_haircut)
);
CREATE INDEX portfolio_value_entity_idx ON portfolio_value (legal_entity_id, valuation_date);

-- What moved the portfolio value from the prior official run, kept at sign-off; the causes add up to the change.
CREATE TABLE portfolio_explain (
    run_id       UUID           NOT NULL REFERENCES valuation_run (id),
    prior_run_id UUID           NOT NULL REFERENCES valuation_run (id),
    cause        TEXT           NOT NULL CHECK (cause IN ('TRADE_MTM', 'INVENTORY_PRICE', 'INVENTORY_IN_OUT', 'GIVE_AWAY', 'REALLOCATION', 'EXPIRY', 'UNEXPLAINED')),
    amount       NUMERIC(18, 2) NOT NULL,
    PRIMARY KEY (run_id, cause)
);

CREATE TRIGGER portfolio_value_immutable BEFORE UPDATE OR DELETE ON portfolio_value FOR EACH ROW EXECUTE FUNCTION valuation_immutable();
CREATE TRIGGER portfolio_explain_immutable BEFORE UPDATE OR DELETE ON portfolio_explain FOR EACH ROW EXECUTE FUNCTION valuation_immutable();
