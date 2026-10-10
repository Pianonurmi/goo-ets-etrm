-- Approval tasks (four-eyes). Tasks mirror pending approvals in trade and allocation; deciding a task executes the
-- owning module's approve/reject command with segregation of duties enforced here and in the module.

CREATE TABLE approval_task (
    id              UUID        NOT NULL PRIMARY KEY,
    task_type       TEXT        NOT NULL CHECK (task_type IN ('TRADE_APPROVAL', 'ALLOCATION_APPROVAL', 'GENERIC')),
    subject_type    TEXT        NOT NULL,
    subject_ref     UUID        NOT NULL,
    legal_entity_id UUID,
    summary         TEXT        NOT NULL,
    requested_by    TEXT        NOT NULL,
    requested_at    TIMESTAMPTZ NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by      TEXT,
    decided_at      TIMESTAMPTZ,
    reason          TEXT,
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    version         INT         NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX approval_task_open_subject_uq ON approval_task (subject_type, subject_ref) WHERE status = 'OPEN';
CREATE INDEX approval_task_inbox_idx ON approval_task (status, requested_at);
CREATE INDEX approval_task_entity_idx ON approval_task (legal_entity_id, status);
