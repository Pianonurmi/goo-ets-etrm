-- Exchange futures on allowances, one row per listed contract (e.g. EEX EUA December 2026). The contract fixes the
-- delivery date, the lot size and the venue; trades and market data refer to it by code.
CREATE TABLE futures_contract (
    code                   TEXT           NOT NULL PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]+$'),
    allowance_product_code TEXT           NOT NULL REFERENCES allowance_product (code),
    venue_mic              CHAR(4)        NOT NULL CHECK (venue_mic ~ '^[A-Z0-9]{4}$'),
    delivery_month         CHAR(7)        NOT NULL CHECK (delivery_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    delivery_date          DATE           NOT NULL,
    last_trading_day       DATE           NOT NULL CHECK (last_trading_day <= delivery_date),
    contract_size          NUMERIC(18, 3) NOT NULL CHECK (contract_size > 0),
    currency               CHAR(3)        NOT NULL,
    active                 BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT futures_contract_venue_month_uq UNIQUE (venue_mic, allowance_product_code, delivery_month)
);
