-- Four-eyes changes to sensitive master data (registry integration modes, counterparty registry and bank accounts):
-- a change is requested, approved by a second person through the approval inbox, and only then applied.

CREATE TABLE change_request (
    id               UUID        PRIMARY KEY,
    entity_type      TEXT        NOT NULL,
    entity_key       TEXT        NOT NULL,
    legal_entity_id  UUID,
    change           JSONB       NOT NULL,
    expected_version INT,
    summary          TEXT        NOT NULL,
    reason           TEXT        NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'CANCELLED')),
    requested_by     TEXT        NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL,
    decided_by       TEXT,
    decided_at       TIMESTAMPTZ,
    decision_note    TEXT,
    version          INT         NOT NULL DEFAULT 1
);
-- One pending change per record at a time keeps approvals unambiguous.
CREATE UNIQUE INDEX change_request_pending_uq ON change_request (entity_type, entity_key) WHERE status = 'PENDING';
CREATE INDEX change_request_status_idx ON change_request (status, requested_at);
