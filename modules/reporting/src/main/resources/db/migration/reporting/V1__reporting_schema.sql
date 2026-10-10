-- Reporting (ADR 0023): a log of every report produced, and the EMIR trade event record with its field rules.

CREATE TABLE report_export (
    id               UUID        NOT NULL PRIMARY KEY,
    report_code      TEXT        NOT NULL,
    -- Null for reports across entities.
    legal_entity_id  UUID,
    seq              INT         NOT NULL,
    format           TEXT        NOT NULL CHECK (format IN ('CSV', 'JSON')),
    parameters       JSONB       NOT NULL,
    row_count        INT         NOT NULL,
    content_hash     TEXT        NOT NULL,
    content          TEXT        NOT NULL,
    exported_by      TEXT        NOT NULL,
    exported_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX report_export_seq_uq ON report_export (report_code, COALESCE(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid), seq);

-- EMIR lifecycle events of reportable trades, as captured; reported by the entity or its delegate outside this system.
CREATE TABLE emir_event (
    id               UUID        NOT NULL PRIMARY KEY,
    legal_entity_id  UUID        NOT NULL,
    trade_id         UUID        NOT NULL,
    trade_number     TEXT        NOT NULL,
    trade_version    INT         NOT NULL,
    uti              TEXT,
    action_type      TEXT        NOT NULL CHECK (action_type IN ('NEWT', 'MODI', 'TERM', 'EROR', 'VALU')),
    event_date       DATE        NOT NULL,
    -- What caused the event: the confirmation, cancellation or valuation run, or a manual correction.
    source_ref       TEXT        NOT NULL,
    fields           JSONB       NOT NULL,
    recorded_by      TEXT        NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT emir_event_uq UNIQUE (trade_id, action_type, source_ref)
);
CREATE INDEX emir_event_entity_idx ON emir_event (legal_entity_id, event_date);

CREATE OR REPLACE FUNCTION reporting_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;
CREATE TRIGGER emir_event_append_only BEFORE UPDATE OR DELETE ON emir_event FOR EACH ROW EXECUTE FUNCTION reporting_append_only();
CREATE TRIGGER report_export_append_only BEFORE UPDATE OR DELETE ON report_export FOR EACH ROW EXECUTE FUNCTION reporting_append_only();

-- Which fields an EMIR event must carry. Names follow the EMIR Refit report but are NOT verified against the
-- regulatory technical standards; `verified` stays false until compliance has checked them.
CREATE TABLE emir_field_rule (
    field_name   TEXT    NOT NULL PRIMARY KEY,
    applies_to   TEXT    NOT NULL CHECK (applies_to IN ('ALL', 'TRADE', 'VALUATION', 'CLEARED')),
    description  TEXT    NOT NULL,
    verified     BOOLEAN NOT NULL DEFAULT false
);

INSERT INTO emir_field_rule (field_name, applies_to, description) VALUES
    ('UTI', 'ALL', 'Unique transaction identifier (ISO 23897)'),
    ('REPORTING_COUNTERPARTY', 'ALL', 'LEI of the reporting counterparty'),
    ('OTHER_COUNTERPARTY', 'ALL', 'LEI of the other counterparty'),
    ('NATURE_OF_REPORTING_COUNTERPARTY', 'ALL', 'F for financial, N for non-financial counterparty'),
    ('ACTION_TYPE', 'ALL', 'NEWT, MODI, TERM, EROR or VALU'),
    ('EVENT_DATE', 'ALL', 'Date of the reported event'),
    ('COUNTERPARTY_SIDE', 'TRADE', 'BUYR or SLLR'),
    ('ASSET_CLASS', 'TRADE', 'COMM for emission allowance derivatives'),
    ('CONTRACT_TYPE', 'TRADE', 'FORW, FUTR, SWAP or OPTN'),
    ('UPI', 'TRADE', 'Unique product identifier (ISO 4914)'),
    ('VENUE', 'TRADE', 'MIC of the execution venue, XOFF or XXXX off venue'),
    ('EXECUTION_TIMESTAMP', 'TRADE', 'Date and time of execution (UTC)'),
    ('EFFECTIVE_DATE', 'TRADE', 'First delivery date'),
    ('EXPIRATION_DATE', 'TRADE', 'Last delivery date'),
    ('DELIVERY_TYPE', 'TRADE', 'PHYS for physical delivery'),
    ('PRICE', 'TRADE', 'Price per unit'),
    ('PRICE_CURRENCY', 'TRADE', 'Currency of the price'),
    ('NOTIONAL', 'TRADE', 'Notional amount'),
    ('NOTIONAL_CURRENCY', 'TRADE', 'Currency of the notional'),
    ('TOTAL_QUANTITY', 'TRADE', 'Total quantity'),
    ('QUANTITY_UNIT', 'TRADE', 'Unit of the quantity'),
    ('CLEARED', 'TRADE', 'Y or N'),
    ('INTRAGROUP', 'TRADE', 'Y or N'),
    ('CCP', 'CLEARED', 'LEI of the central counterparty'),
    ('VALUATION_AMOUNT', 'VALUATION', 'Mark-to-market value'),
    ('VALUATION_CURRENCY', 'VALUATION', 'Currency of the valuation'),
    ('VALUATION_TIMESTAMP', 'VALUATION', 'Date and time of the valuation'),
    ('VALUATION_METHOD', 'VALUATION', 'MTMA mark-to-market, MTMO mark-to-model');
