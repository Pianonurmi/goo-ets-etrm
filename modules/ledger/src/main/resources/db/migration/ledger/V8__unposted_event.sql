-- Events the ledger could not post (increment 14, review findings F4 and F5). A posting listener that fails runs in a
-- savepoint, so the business transaction still commits; an applicable posting rule whose amount is missing or
-- negative posts nothing. Both are recorded here and raise a LEDGER_ATTENTION task in the inbox, instead of failing
-- the delivery or registry confirmation, or vanishing. A job replays them once the cause is gone.
CREATE TABLE unposted_event (
    id              UUID        NOT NULL PRIMARY KEY,
    legal_entity_id UUID,
    event_id        UUID,
    event_type      TEXT        NOT NULL,
    rule_code       TEXT,
    kind            TEXT        NOT NULL CHECK (kind IN ('FAILED', 'MISSING_AMOUNT')),
    reason          TEXT        NOT NULL,
    event_class     TEXT,
    event_json      JSONB,
    facts_json      JSONB,
    replayable      BOOLEAN     NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'POSTED', 'DISMISSED')),
    attempts        INT         NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    last_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     TEXT,
    resolution      TEXT
);
-- One open record per event and rule (a failed listener has no rule): a retry updates it rather than adding another.
CREATE UNIQUE INDEX unposted_event_open_uq ON unposted_event (event_id, event_type, coalesce(rule_code, ''))
    WHERE status = 'OPEN' AND event_id IS NOT NULL;
CREATE INDEX unposted_event_status_idx ON unposted_event (status, legal_entity_id);
