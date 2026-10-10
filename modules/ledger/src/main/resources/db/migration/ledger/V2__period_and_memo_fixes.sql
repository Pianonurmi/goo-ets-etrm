-- A hard-closed period is final: neither its status nor its closing data may change afterwards.
CREATE OR REPLACE FUNCTION accounting_period_closed_is_final() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'CLOSED' THEN
            RAISE EXCEPTION 'Accounting period % is closed and cannot be deleted', OLD.period;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.status = 'CLOSED' AND ROW(NEW.*) IS DISTINCT FROM ROW(OLD.*) THEN
        RAISE EXCEPTION 'Accounting period % is closed and cannot be changed', OLD.period;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounting_period_closed_is_final
    BEFORE UPDATE OR DELETE ON accounting_period
    FOR EACH ROW EXECUTE FUNCTION accounting_period_closed_is_final();

-- Commitment memos per delivery schedule, so that deliveries release what was actually posted and an amendment or a
-- cancellation reverses the remainder. Movements are append-only and keyed by event, which makes listeners idempotent.
CREATE TABLE commitment_memo (
    id                 UUID           NOT NULL PRIMARY KEY,
    legal_entity_id    UUID           NOT NULL,
    trade_id           UUID           NOT NULL,
    trade_number       TEXT           NOT NULL,
    trade_version      INT            NOT NULL,
    schedule_id        UUID           NOT NULL,
    side               TEXT           NOT NULL CHECK (side IN ('BUY', 'SELL')),
    product_family     TEXT           NOT NULL,
    currency           CHAR(3)        NOT NULL,
    quantity           NUMERIC(18, 3) NOT NULL CHECK (quantity > 0),
    amount             NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    confirmed_event_id UUID           NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT commitment_memo_event_uq UNIQUE (confirmed_event_id, schedule_id)
);
CREATE INDEX commitment_memo_trade_idx ON commitment_memo (trade_id);
CREATE INDEX commitment_memo_schedule_idx ON commitment_memo (schedule_id);

CREATE TABLE commitment_memo_movement (
    event_id    UUID           NOT NULL,
    memo_id     UUID           NOT NULL REFERENCES commitment_memo (id),
    kind        TEXT           NOT NULL CHECK (kind IN ('RELEASE', 'REVERSAL')),
    amount      NUMERIC(18, 2) NOT NULL CHECK (amount > 0),
    recorded_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, memo_id)
);

CREATE OR REPLACE FUNCTION commitment_memo_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER commitment_memo_append_only
    BEFORE UPDATE OR DELETE ON commitment_memo
    FOR EACH ROW EXECUTE FUNCTION commitment_memo_append_only();
CREATE TRIGGER commitment_memo_movement_append_only
    BEFORE UPDATE OR DELETE ON commitment_memo_movement
    FOR EACH ROW EXECUTE FUNCTION commitment_memo_append_only();

-- GL exports are numbered per legal entity.
ALTER TABLE gl_export ADD COLUMN export_seq INT;
UPDATE gl_export g
SET export_seq = n.seq
FROM (SELECT id, row_number() OVER (PARTITION BY legal_entity_id ORDER BY exported_at, id) AS seq FROM gl_export) n
WHERE g.id = n.id;
ALTER TABLE gl_export ALTER COLUMN export_seq SET NOT NULL;
ALTER TABLE gl_export ADD CONSTRAINT gl_export_seq_uq UNIQUE (legal_entity_id, export_seq);

-- Version of the default data (posting rules) last applied, so that corrected defaults reach existing databases.
CREATE TABLE ledger_seed (
    name       TEXT        NOT NULL PRIMARY KEY,
    version    INT         NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
