-- Deal capture. `trade` holds the current header; `trade_version` is the immutable, bitemporal record of every
-- version (full JSON snapshot); `trade_leg` and `delivery_schedule` are current-state projections for querying.

CREATE SEQUENCE trade_number_seq START 1000;

CREATE TABLE trade (
    id                    UUID        NOT NULL PRIMARY KEY,
    trade_number          TEXT        NOT NULL UNIQUE,
    legal_entity_id       UUID        NOT NULL,
    book_id               UUID        NOT NULL,
    counterparty_id       UUID        NOT NULL,
    product_family        TEXT        NOT NULL CHECK (product_family IN ('GUARANTEE_OF_ORIGIN', 'EMISSION_ALLOWANCE')),
    instrument_type       TEXT        NOT NULL CHECK (instrument_type IN ('SPOT', 'FORWARD', 'FUTURE', 'OPTION', 'SWAP', 'AUCTION', 'INTERNAL_TRANSFER')),
    trade_date            DATE        NOT NULL,
    trader                TEXT        NOT NULL,
    broker_id             UUID,
    master_agreement_ref  TEXT,
    external_ref          TEXT,
    status                TEXT        NOT NULL CHECK (status IN (
                              'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONFIRMED', 'PARTIALLY_DELIVERED', 'DELIVERED', 'SETTLED', 'CLOSED', 'CANCELLED', 'DISPUTED')),
    current_version       INT         NOT NULL DEFAULT 1,
    intercompany_link_id  UUID,
    approved_by           TEXT,
    approved_at           TIMESTAMPTZ,
    confirmed_at          TIMESTAMPTZ,
    confirmation_ref      TEXT,
    created_by            TEXT        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX trade_entity_status_idx ON trade (legal_entity_id, status);
CREATE INDEX trade_counterparty_idx ON trade (counterparty_id);
CREATE INDEX trade_date_idx ON trade (trade_date);
CREATE INDEX trade_intercompany_idx ON trade (intercompany_link_id) WHERE intercompany_link_id IS NOT NULL;

CREATE TABLE trade_version (
    id            UUID        NOT NULL PRIMARY KEY,
    trade_id      UUID        NOT NULL REFERENCES trade (id),
    version       INT         NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    superseded_at TIMESTAMPTZ,
    recorded_by   TEXT        NOT NULL,
    reason        TEXT,
    status        TEXT        NOT NULL,
    data          JSONB       NOT NULL,
    CONSTRAINT trade_version_uq UNIQUE (trade_id, version)
);
CREATE INDEX trade_version_current_idx ON trade_version (trade_id) WHERE superseded_at IS NULL;

CREATE TABLE trade_leg (
    id                       UUID           NOT NULL PRIMARY KEY,
    trade_id                 UUID           NOT NULL REFERENCES trade (id) ON DELETE CASCADE,
    leg_no                   INT            NOT NULL,
    side                     TEXT           NOT NULL CHECK (side IN ('BUY', 'SELL')),
    energy_carrier           TEXT,
    allowance_product_code   TEXT,
    criteria_id              UUID,
    criteria_summary         TEXT,
    bucket_code              TEXT,
    quantity                 NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    unit                     TEXT           NOT NULL,
    price                    NUMERIC(18, 6) NOT NULL,
    price_currency           CHAR(3)        NOT NULL,
    price_type               TEXT           NOT NULL CHECK (price_type IN ('FIXED', 'INDEX', 'FORMULA')),
    price_index              TEXT,
    delivery_account_id      UUID,
    counterparty_account_ref TEXT,
    CONSTRAINT trade_leg_uq UNIQUE (trade_id, leg_no)
);

CREATE TABLE delivery_schedule (
    id                  UUID           NOT NULL PRIMARY KEY,
    trade_id            UUID           NOT NULL REFERENCES trade (id) ON DELETE CASCADE,
    leg_id              UUID           NOT NULL REFERENCES trade_leg (id) ON DELETE CASCADE,
    schedule_no         INT            NOT NULL,
    delivery_window     DATERANGE      NOT NULL,
    delivery_date       DATE           NOT NULL,
    quantity            NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    delivered_quantity  NUMERIC(18, 3) NOT NULL DEFAULT 0 CHECK (delivered_quantity >= 0 AND delivered_quantity <= quantity),
    payment_date        DATE,
    status              TEXT           NOT NULL CHECK (status IN ('PENDING', 'PARTIALLY_DELIVERED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT delivery_schedule_uq UNIQUE (leg_id, schedule_no)
);
CREATE INDEX delivery_schedule_open_idx ON delivery_schedule (delivery_date) WHERE status IN ('PENDING', 'PARTIALLY_DELIVERED');

CREATE TABLE delivery_record (
    seq           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    schedule_id   UUID        NOT NULL REFERENCES delivery_schedule (id),
    quantity      NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    delivered_on  DATE        NOT NULL,
    reference     TEXT,
    recorded_by   TEXT        NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
