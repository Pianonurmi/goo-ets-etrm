-- Margin calls (ADR 0046): the latest statement's cash at the member short of the initial margin required. A call is
-- paid from the bank once a second person approves the payment, or disputed with a reason.

CREATE TABLE margin_call (
    id              UUID           NOT NULL PRIMARY KEY,
    account_id      UUID           NOT NULL REFERENCES clearing_account (id),
    legal_entity_id UUID           NOT NULL,
    business_date   DATE           NOT NULL,
    statement_id    UUID           NOT NULL REFERENCES clearing_statement (id),
    amount          NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)        NOT NULL,
    status          TEXT           NOT NULL CHECK (status IN ('OPEN', 'PAID', 'DISPUTED', 'CANCELLED')),
    requested_by    TEXT           NOT NULL,
    requested_at    TIMESTAMPTZ    NOT NULL,
    decided_by      TEXT,
    decided_at      TIMESTAMPTZ,
    reason          TEXT,
    CONSTRAINT margin_call_decided_ck CHECK ((status = 'OPEN') = (decided_at IS NULL)),
    CONSTRAINT margin_call_four_eyes_ck CHECK (status NOT IN ('PAID', 'DISPUTED') OR decided_by <> requested_by)
);
CREATE UNIQUE INDEX margin_call_open_uq ON margin_call (account_id) WHERE status = 'OPEN';
CREATE INDEX margin_call_entity_idx ON margin_call (legal_entity_id, status);
