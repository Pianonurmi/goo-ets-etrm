-- The instruction row is the outbox: instructions for channels with external I/O (API, FILE) are committed first
-- and dispatched afterwards by a claim with a lease, so a registry call never runs inside a database transaction.
-- UNKNOWN marks an instruction whose outcome is not known (timeout, lost lease) and is resolved by a status
-- query or by an operator, never by a blind resend.

ALTER TABLE registry_instruction DROP CONSTRAINT registry_instruction_status_check;
ALTER TABLE registry_instruction ADD CONSTRAINT registry_instruction_status_check CHECK (status IN (
    'PENDING_APPROVAL', 'PENDING', 'DISPATCHING', 'SENT', 'UNKNOWN', 'ACKNOWLEDGED', 'CONFIRMED', 'FAILED', 'CANCELLED'));

-- The gateway channel is fixed when the instruction is created, so changing a registry's integration mode does not
-- strand in-flight instructions.
ALTER TABLE registry_instruction ADD COLUMN channel TEXT;
UPDATE registry_instruction SET channel = 'MANUAL' WHERE channel IS NULL;
ALTER TABLE registry_instruction ALTER COLUMN channel SET NOT NULL;
ALTER TABLE registry_instruction ADD COLUMN next_attempt_at TIMESTAMPTZ;
ALTER TABLE registry_instruction ADD COLUMN lease_until TIMESTAMPTZ;
ALTER TABLE registry_instruction ADD COLUMN approved_by TEXT;
ALTER TABLE registry_instruction ADD COLUMN approved_at TIMESTAMPTZ;
ALTER TABLE registry_instruction ADD COLUMN overdue_notified_at TIMESTAMPTZ;

DROP INDEX registry_instruction_open_idx;
CREATE INDEX registry_instruction_open_idx ON registry_instruction (registry_code, status)
    WHERE status IN ('PENDING_APPROVAL', 'PENDING', 'DISPATCHING', 'SENT', 'UNKNOWN', 'ACKNOWLEDGED');
CREATE INDEX registry_instruction_due_idx ON registry_instruction (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX registry_instruction_lease_idx ON registry_instruction (lease_until) WHERE status = 'DISPATCHING';

-- Registry statement formats as data: column mappings per registry and statement kind, so a new export layout is a
-- configuration change. Registry code '*' is the generic fallback.
CREATE TABLE statement_format (
    id             UUID        PRIMARY KEY,
    registry_code  TEXT        NOT NULL,
    profile_code   TEXT        NOT NULL CHECK (profile_code ~ '^[A-Z0-9_]{2,40}$'),
    kind           TEXT        NOT NULL CHECK (kind IN ('GOO_HOLDINGS', 'GOO_TRANSACTIONS', 'ALLOWANCE_HOLDINGS', 'ALLOWANCE_TRANSACTIONS')),
    delimiter      CHAR(1)     NOT NULL DEFAULT ',',
    skip_lines     INT         NOT NULL DEFAULT 0 CHECK (skip_lines >= 0),
    date_format    TEXT        NOT NULL DEFAULT 'yyyy-MM-dd',
    column_mapping JSONB       NOT NULL,
    constants      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    version        INT         NOT NULL DEFAULT 1,
    updated_by     TEXT        NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (registry_code, profile_code, kind)
);

INSERT INTO statement_format (id, registry_code, profile_code, kind, column_mapping, updated_by) VALUES
    ('01900000-0000-7000-8000-000000000001', '*', 'GENERIC', 'GOO_HOLDINGS',
     '{"issuanceRef":"issuance_ref","certStart":"cert_no_start","certEnd":"cert_no_end","deviceGsrn":"device_gsrn","productionStart":"production_start","productionEnd":"production_end"}',
     'migration'),
    ('01900000-0000-7000-8000-000000000002', '*', 'GENERIC', 'GOO_TRANSACTIONS',
     '{"externalRef":"transaction_id","transactionType":"type","issuanceRef":"issuance_ref","certStart":"cert_no_start","certEnd":"cert_no_end","status":"status","transactionDate":"date","instructionKey":"client_reference"}',
     'migration');
