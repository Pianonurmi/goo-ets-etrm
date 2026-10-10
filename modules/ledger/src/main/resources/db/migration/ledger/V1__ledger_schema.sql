-- Sub-ledger: chart of accounts per legal entity, posting rules as data, immutable double-entry journal with
-- reversals, accounting periods, FX rates, cost layers for inventory costing and GL exports.

CREATE TABLE gl_account (
    id              UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID        NOT NULL,
    code            TEXT        NOT NULL CHECK (code ~ '^[0-9A-Z_.-]{2,30}$'),
    name            TEXT        NOT NULL,
    account_type    TEXT        NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE', 'MEMO')),
    gl_mapping      TEXT,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT gl_account_uq UNIQUE (legal_entity_id, code)
);

CREATE TABLE posting_rule (
    id              UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID,
    rule_code       TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    conditions      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    lines           JSONB       NOT NULL,
    description     TEXT,
    priority        INT         NOT NULL DEFAULT 100,
    effective_from  DATE        NOT NULL DEFAULT DATE '2000-01-01',
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    version         INT         NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX posting_rule_code_uq ON posting_rule (COALESCE(legal_entity_id, '00000000-0000-0000-0000-000000000000'::uuid), rule_code);
CREATE INDEX posting_rule_event_idx ON posting_rule (event_type) WHERE active;

CREATE TABLE accounting_period (
    legal_entity_id UUID        NOT NULL,
    period          CHAR(7)     NOT NULL CHECK (period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    status          TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'SOFT_CLOSED', 'CLOSED')),
    closed_by       TEXT,
    closed_at       TIMESTAMPTZ,
    PRIMARY KEY (legal_entity_id, period)
);

CREATE SEQUENCE journal_entry_seq START 100000;

CREATE TABLE journal_entry (
    id              UUID        NOT NULL PRIMARY KEY,
    entry_number    BIGINT      NOT NULL UNIQUE,
    legal_entity_id UUID        NOT NULL,
    period          CHAR(7)     NOT NULL,
    posting_date    DATE        NOT NULL,
    event_type      TEXT        NOT NULL,
    rule_code       TEXT,
    source_type     TEXT,
    source_ref      TEXT,
    event_id        UUID,
    description     TEXT        NOT NULL,
    currency        CHAR(3)     NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('POSTED', 'REVERSED', 'REVERSAL')),
    reversal_of     UUID        REFERENCES journal_entry (id),
    reversed_by     UUID,
    created_by      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    prev_hash       TEXT,
    hash            TEXT        NOT NULL
);
CREATE INDEX journal_entry_period_idx ON journal_entry (legal_entity_id, period);
CREATE INDEX journal_entry_source_idx ON journal_entry (source_type, source_ref);
CREATE UNIQUE INDEX journal_entry_event_uq ON journal_entry (event_id, rule_code) WHERE event_id IS NOT NULL;

CREATE TABLE journal_line (
    id            UUID           NOT NULL PRIMARY KEY,
    entry_id      UUID           NOT NULL REFERENCES journal_entry (id),
    line_no       INT            NOT NULL,
    account_id    UUID           NOT NULL REFERENCES gl_account (id),
    debit         NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit        NUMERIC(18, 2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    currency      CHAR(3)        NOT NULL,
    fx_rate       NUMERIC(18, 8) NOT NULL DEFAULT 1,
    base_debit    NUMERIC(18, 2) NOT NULL DEFAULT 0,
    base_credit   NUMERIC(18, 2) NOT NULL DEFAULT 0,
    dimensions    JSONB          NOT NULL DEFAULT '{}'::jsonb,
    memo          TEXT,
    CONSTRAINT journal_line_uq UNIQUE (entry_id, line_no),
    CONSTRAINT journal_line_one_side_ck CHECK (debit = 0 OR credit = 0)
);
CREATE INDEX journal_line_account_idx ON journal_line (account_id);

CREATE OR REPLACE FUNCTION journal_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal is append-only';
    END IF;
    IF TG_TABLE_NAME = 'journal_entry' AND (NEW.hash <> OLD.hash OR NEW.posting_date <> OLD.posting_date OR NEW.legal_entity_id <> OLD.legal_entity_id) THEN
        RAISE EXCEPTION 'journal entries cannot be edited; post a reversal';
    END IF;
    IF TG_TABLE_NAME = 'journal_line' THEN
        RAISE EXCEPTION 'journal lines cannot be edited; post a reversal';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER journal_entry_immutable BEFORE UPDATE OR DELETE ON journal_entry FOR EACH ROW EXECUTE FUNCTION journal_immutable();
CREATE TRIGGER journal_line_immutable BEFORE UPDATE OR DELETE ON journal_line FOR EACH ROW EXECUTE FUNCTION journal_immutable();

CREATE TABLE fx_rate (
    rate_date     DATE           NOT NULL,
    from_currency CHAR(3)        NOT NULL,
    to_currency   CHAR(3)        NOT NULL,
    rate          NUMERIC(18, 8) NOT NULL CHECK (rate > 0),
    source        TEXT           NOT NULL DEFAULT 'MANUAL',
    PRIMARY KEY (rate_date, from_currency, to_currency)
);

-- Cost layers for FIFO / weighted-average costing of fungible allowances and (optionally) GoO.
CREATE TABLE cost_layer (
    id                 UUID           NOT NULL PRIMARY KEY,
    legal_entity_id    UUID           NOT NULL,
    asset_key          TEXT           NOT NULL,
    acquired_on        DATE           NOT NULL,
    quantity           NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    remaining_quantity NUMERIC(18, 3) NOT NULL CHECK (remaining_quantity >= 0 AND remaining_quantity <= quantity),
    unit_cost          NUMERIC(18, 6) NOT NULL CHECK (unit_cost >= 0),
    currency           CHAR(3)        NOT NULL,
    source_ref         TEXT,
    seq                BIGINT         GENERATED ALWAYS AS IDENTITY
);
CREATE INDEX cost_layer_open_idx ON cost_layer (legal_entity_id, asset_key, acquired_on, seq) WHERE remaining_quantity > 0;

CREATE TABLE gl_export (
    id              UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID        NOT NULL,
    period          CHAR(7)     NOT NULL,
    exported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    exported_by     TEXT        NOT NULL,
    entry_count     INT         NOT NULL,
    content_hash    TEXT        NOT NULL,
    content         TEXT        NOT NULL
);
