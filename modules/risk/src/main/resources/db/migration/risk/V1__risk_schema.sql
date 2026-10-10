-- Risk (ADR 0022): limits under four eyes, credit support, potential future exposure add-ons, current counterparty
-- exposure, limit utilisation and breaches.

CREATE TABLE risk_limit (
    id               UUID           NOT NULL PRIMARY KEY,
    -- Null: the limit applies across all legal entities (a group-wide credit limit).
    legal_entity_id  UUID,
    limit_type       TEXT           NOT NULL CHECK (limit_type IN ('CREDIT_EXPOSURE', 'POSITION_NET', 'TENOR_MAX_MONTHS', 'STOP_LOSS_MTD', 'COMPLIANCE_SHORT')),
    scope            JSONB          NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL CHECK (amount >= 0),
    -- Currency for amounts; null for quantities and months.
    currency         CHAR(3),
    warning_pct      INT            NOT NULL DEFAULT 80 CHECK (warning_pct BETWEEN 1 AND 100),
    valid_from       DATE           NOT NULL,
    valid_to         DATE,
    status           TEXT           NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'REJECTED', 'RETIRED')),
    replaces         UUID REFERENCES risk_limit (id),
    reason           TEXT           NOT NULL,
    requested_by     TEXT           NOT NULL,
    requested_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    approved_by      TEXT,
    approved_at      TIMESTAMPTZ,
    CONSTRAINT risk_limit_four_eyes_ck CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
CREATE INDEX risk_limit_entity_idx ON risk_limit (legal_entity_id, status);

-- Collateral and guarantees that reduce (received) or add to (posted) credit exposure.
CREATE TABLE credit_support (
    id               UUID           NOT NULL PRIMARY KEY,
    legal_entity_id  UUID,
    counterparty_id  UUID           NOT NULL,
    kind             TEXT           NOT NULL CHECK (kind IN ('CASH_RECEIVED', 'CASH_POSTED', 'GUARANTEE_RECEIVED', 'LETTER_OF_CREDIT_RECEIVED')),
    amount           NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)        NOT NULL,
    valid_from       DATE           NOT NULL,
    valid_to         DATE,
    reference        TEXT,
    status           TEXT           NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'REJECTED')),
    requested_by     TEXT           NOT NULL,
    requested_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    approved_by      TEXT,
    approved_at      TIMESTAMPTZ,
    CONSTRAINT credit_support_four_eyes_ck CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

-- Potential future exposure as a share of the remaining notional by product family and time to delivery. The seeded
-- percentages are placeholders until risk calibrates them.
CREATE TABLE pfe_addon (
    product_family    TEXT          NOT NULL,
    max_tenor_months  INT           NOT NULL,
    pct               NUMERIC(6, 3) NOT NULL CHECK (pct >= 0),
    calibrated        BOOLEAN       NOT NULL DEFAULT false,
    PRIMARY KEY (product_family, max_tenor_months)
);
INSERT INTO pfe_addon (product_family, max_tenor_months, pct) VALUES
    ('EMISSION_ALLOWANCE', 3, 6), ('EMISSION_ALLOWANCE', 12, 10), ('EMISSION_ALLOWANCE', 9999, 15),
    ('GUARANTEE_OF_ORIGIN', 3, 10), ('GUARANTEE_OF_ORIGIN', 12, 15), ('GUARANTEE_OF_ORIGIN', 9999, 20);

-- Current exposure of a legal entity to a counterparty, in the entity's base currency.
CREATE TABLE counterparty_exposure (
    legal_entity_id    UUID           NOT NULL,
    counterparty_id    UUID           NOT NULL,
    currency           CHAR(3)        NOT NULL,
    mtm                NUMERIC(18, 2) NOT NULL,
    receivables        NUMERIC(18, 2) NOT NULL,
    payables_netted    NUMERIC(18, 2) NOT NULL,
    pfe                NUMERIC(18, 2) NOT NULL,
    collateral         NUMERIC(18, 2) NOT NULL,
    exposure           NUMERIC(18, 2) NOT NULL,
    valuation_run_id   UUID,
    measured_at        TIMESTAMPTZ    NOT NULL,
    PRIMARY KEY (legal_entity_id, counterparty_id)
);

-- The latest measurement of each limit.
CREATE TABLE limit_utilisation (
    limit_id     UUID           NOT NULL PRIMARY KEY REFERENCES risk_limit (id),
    value        NUMERIC(18, 2) NOT NULL,
    utilisation  NUMERIC(9, 2)  NOT NULL,
    status       TEXT           NOT NULL CHECK (status IN ('OK', 'WARNING', 'BREACH')),
    detail       TEXT,
    measured_at  TIMESTAMPTZ    NOT NULL
);

CREATE TABLE limit_breach (
    id           UUID           NOT NULL PRIMARY KEY,
    limit_id     UUID           NOT NULL REFERENCES risk_limit (id),
    opened_at    TIMESTAMPTZ    NOT NULL,
    first_value  NUMERIC(18, 2) NOT NULL,
    peak_value   NUMERIC(18, 2) NOT NULL,
    closed_at    TIMESTAMPTZ,
    status       TEXT           NOT NULL CHECK (status IN ('OPEN', 'CLOSED'))
);
CREATE UNIQUE INDEX limit_breach_open_uq ON limit_breach (limit_id) WHERE status = 'OPEN';

CREATE TABLE risk_seed (
    name        TEXT        NOT NULL PRIMARY KEY,
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
