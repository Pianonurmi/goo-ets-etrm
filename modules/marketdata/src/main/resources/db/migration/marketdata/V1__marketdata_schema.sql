-- Market data: instruments with vendor aliases, quotes and FX rates (both bitemporal: a correction supersedes the
-- earlier row, which stays for as-of queries), import formats as data, import runs and price indices.

CREATE TABLE instrument (
    code                   TEXT        NOT NULL PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]+$'),
    kind                   TEXT        NOT NULL CHECK (kind IN ('EUA_SPOT', 'EUA_FUTURE', 'EUA_FORWARD', 'GOO_VINTAGE', 'INDEX', 'RATE')),
    description            TEXT        NOT NULL,
    allowance_product_code TEXT,
    futures_contract_code  TEXT,
    bucket_code            TEXT,
    production_year        INT,
    currency               CHAR(3)     NOT NULL,
    unit                   TEXT        NOT NULL,
    active                 BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT instrument_shape_ck CHECK (
        (kind = 'EUA_FUTURE' AND futures_contract_code IS NOT NULL AND allowance_product_code IS NOT NULL)
        OR (kind IN ('EUA_SPOT', 'EUA_FORWARD') AND allowance_product_code IS NOT NULL)
        OR (kind = 'GOO_VINTAGE' AND bucket_code IS NOT NULL AND production_year IS NOT NULL)
        OR kind IN ('INDEX', 'RATE')
    )
);
CREATE UNIQUE INDEX instrument_future_uq ON instrument (futures_contract_code) WHERE futures_contract_code IS NOT NULL;
CREATE UNIQUE INDEX instrument_goo_uq ON instrument (bucket_code, production_year) WHERE kind = 'GOO_VINTAGE';

-- Vendor and broker codes mapped to instruments, so that files can be imported without renaming.
CREATE TABLE instrument_alias (
    source          TEXT NOT NULL,
    external_code   TEXT NOT NULL,
    instrument_code TEXT NOT NULL REFERENCES instrument (code),
    PRIMARY KEY (source, external_code)
);

CREATE TABLE market_data_import (
    id          UUID        NOT NULL PRIMARY KEY,
    format_code TEXT        NOT NULL,
    file_name   TEXT        NOT NULL,
    content_hash TEXT       NOT NULL,
    rows_read   INT         NOT NULL,
    accepted    INT         NOT NULL,
    rejected    INT         NOT NULL,
    imported_by TEXT        NOT NULL,
    imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT market_data_import_once_uq UNIQUE (format_code, content_hash)
);

CREATE TABLE market_data_import_error (
    import_id UUID NOT NULL REFERENCES market_data_import (id),
    line_no   INT  NOT NULL,
    message   TEXT NOT NULL,
    PRIMARY KEY (import_id, line_no)
);

CREATE TABLE quote (
    id              UUID           NOT NULL PRIMARY KEY,
    instrument_code TEXT           NOT NULL REFERENCES instrument (code),
    quote_date      DATE           NOT NULL,
    quote_type      TEXT           NOT NULL CHECK (quote_type IN ('SETTLEMENT', 'MID', 'BID', 'ASK', 'CLOSE', 'FIXING')),
    price           NUMERIC(18, 6) NOT NULL CHECK (price > 0),
    currency        CHAR(3)        NOT NULL,
    source          TEXT           NOT NULL,
    import_id       UUID REFERENCES market_data_import (id),
    quality         TEXT           NOT NULL DEFAULT 'OK' CHECK (quality IN ('OK', 'SUSPECT', 'ACKNOWLEDGED')),
    quality_note    TEXT,
    recorded_by     TEXT           NOT NULL,
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    superseded_at   TIMESTAMPTZ,
    acknowledged_by TEXT,
    CONSTRAINT quote_ack_four_eyes_ck CHECK (acknowledged_by IS NULL OR acknowledged_by <> recorded_by)
);
CREATE UNIQUE INDEX quote_current_uq ON quote (instrument_code, quote_date, quote_type, source) WHERE superseded_at IS NULL;
CREATE INDEX quote_instrument_date_idx ON quote (instrument_code, quote_date);

CREATE TABLE fx_rate (
    id            UUID           NOT NULL PRIMARY KEY,
    rate_date     DATE           NOT NULL,
    base_currency CHAR(3)        NOT NULL,
    quote_currency CHAR(3)       NOT NULL CHECK (quote_currency <> base_currency),
    rate          NUMERIC(18, 8) NOT NULL CHECK (rate > 0),
    source        TEXT           NOT NULL,
    import_id     UUID REFERENCES market_data_import (id),
    recorded_by   TEXT           NOT NULL,
    recorded_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    superseded_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX fx_rate_current_uq ON fx_rate (rate_date, base_currency, quote_currency, source) WHERE superseded_at IS NULL;
CREATE INDEX fx_rate_pair_idx ON fx_rate (base_currency, quote_currency, rate_date);

-- How a file is read: delimiter, header lines, date and decimal format, and which column holds what.
CREATE TABLE import_format (
    code             TEXT        NOT NULL PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]+$'),
    kind             TEXT        NOT NULL CHECK (kind IN ('FX_WIDE_CSV', 'FX_ECB_XML', 'QUOTES_LONG_CSV')),
    description      TEXT        NOT NULL,
    source           TEXT        NOT NULL,
    delimiter        CHAR(1)     NOT NULL DEFAULT ',',
    skip_lines       INT         NOT NULL DEFAULT 0 CHECK (skip_lines >= 0),
    date_format      TEXT        NOT NULL DEFAULT 'yyyy-MM-dd',
    decimal_separator CHAR(1)    NOT NULL DEFAULT '.' CHECK (decimal_separator IN ('.', ',')),
    column_mapping   JSONB       NOT NULL DEFAULT '{}'::jsonb,
    constants        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    version          INT         NOT NULL DEFAULT 1,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO import_format (code, kind, description, source, column_mapping, constants) VALUES
    ('ECB_DAILY_XML', 'FX_ECB_XML', 'ECB euro foreign exchange reference rates, daily XML (eurofxref-daily.xml)', 'ECB', '{}', '{"base": "EUR"}'),
    ('ECB_EUROFXREF_HIST', 'FX_WIDE_CSV', 'ECB reference rates history, one column per currency (eurofxref-hist.csv)', 'ECB',
     '{"date": "Date"}', '{"base": "EUR"}'),
    ('GENERIC_SETTLEMENT_CSV', 'QUOTES_LONG_CSV', 'Exchange settlement prices: date, contract, settlement price', 'EXCHANGE',
     '{"date": "Date", "instrument": "Contract", "value": "Settlement", "currency": "Currency"}', '{"quoteType": "SETTLEMENT"}'),
    ('GENERIC_BROKER_GOO_CSV', 'QUOTES_LONG_CSV', 'Broker GoO quotes: date, product, mid price (EUR/MWh)', 'BROKER',
     '{"date": "Date", "instrument": "Product", "value": "Mid"}', '{"quoteType": "MID", "currency": "EUR"}');

-- A price index used by INDEX-priced trade legs, fixed from an instrument's quotes.
CREATE TABLE price_index (
    code            TEXT        NOT NULL PRIMARY KEY CHECK (code ~ '^[A-Z0-9_]+$'),
    description     TEXT        NOT NULL,
    instrument_code TEXT        NOT NULL REFERENCES instrument (code),
    quote_type      TEXT        NOT NULL CHECK (quote_type IN ('SETTLEMENT', 'MID', 'BID', 'ASK', 'CLOSE', 'FIXING')),
    fixing_rule     TEXT        NOT NULL CHECK (fixing_rule IN ('ON_DATE', 'PRIOR_DAY', 'MONTH_AVERAGE')),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
