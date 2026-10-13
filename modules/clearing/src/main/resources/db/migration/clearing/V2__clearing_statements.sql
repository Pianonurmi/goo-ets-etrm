-- Clearing member statements (ADR 0046): the member's own figures of a business day, versioned like margin runs and
-- reconciled against the computed margin; differences are breaks worked in the inbox.

-- Initial margin as last reported by a statement of the account's latest date; the ledger holds the same on 1970.
ALTER TABLE clearing_account ADD COLUMN initial_margin NUMERIC(18, 2) NOT NULL DEFAULT 0;

CREATE TABLE clearing_statement (
    id               UUID           NOT NULL PRIMARY KEY,
    account_id       UUID           NOT NULL REFERENCES clearing_account (id),
    legal_entity_id  UUID           NOT NULL,
    business_date    DATE           NOT NULL,
    version          INT            NOT NULL,
    currency         CHAR(3)        NOT NULL,
    variation_margin NUMERIC(18, 2) NOT NULL,
    initial_margin   NUMERIC(18, 2) NOT NULL CHECK (initial_margin >= 0),
    fees             NUMERIC(18, 2) NOT NULL,
    cash_balance     NUMERIC(18, 2),
    content_hash     TEXT           NOT NULL,
    file_name        TEXT,
    imported_by      TEXT           NOT NULL,
    imported_at      TIMESTAMPTZ    NOT NULL,
    superseded_at    TIMESTAMPTZ,
    CONSTRAINT clearing_statement_version_uq UNIQUE (account_id, business_date, version)
);
CREATE UNIQUE INDEX clearing_statement_current_uq ON clearing_statement (account_id, business_date) WHERE superseded_at IS NULL;

CREATE TABLE clearing_statement_line (
    statement_id     UUID           NOT NULL REFERENCES clearing_statement (id),
    contract_code    TEXT           NOT NULL,
    position         NUMERIC(18, 3) NOT NULL,
    settlement_price NUMERIC(18, 6),
    variation_margin NUMERIC(18, 2) NOT NULL,
    fees             NUMERIC(18, 2) NOT NULL,
    PRIMARY KEY (statement_id, contract_code)
);

CREATE TRIGGER clearing_statement_line_append_only
    BEFORE UPDATE OR DELETE ON clearing_statement_line
    FOR EACH ROW EXECUTE FUNCTION margin_line_append_only();

-- One break per account and day while open; it follows the current statement and run until they match or a second
-- person accepts the member's figures.
CREATE TABLE clearing_break (
    id              UUID        NOT NULL PRIMARY KEY,
    account_id      UUID        NOT NULL REFERENCES clearing_account (id),
    legal_entity_id UUID        NOT NULL,
    business_date   DATE        NOT NULL,
    statement_id    UUID        NOT NULL REFERENCES clearing_statement (id),
    run_id          UUID        REFERENCES margin_run (id),
    status          TEXT        NOT NULL CHECK (status IN ('OPEN', 'MATCHED', 'ACCEPTED')),
    differences     JSONB       NOT NULL,
    opened_by       TEXT        NOT NULL,
    opened_at       TIMESTAMPTZ NOT NULL,
    closed_by       TEXT,
    closed_at       TIMESTAMPTZ,
    resolution      TEXT,
    CONSTRAINT clearing_break_closed_ck CHECK ((status = 'OPEN') = (closed_at IS NULL)),
    CONSTRAINT clearing_break_four_eyes_ck CHECK (status <> 'ACCEPTED' OR closed_by <> opened_by)
);
CREATE UNIQUE INDEX clearing_break_open_uq ON clearing_break (account_id, business_date) WHERE status = 'OPEN';
CREATE INDEX clearing_break_entity_idx ON clearing_break (legal_entity_id, status);
