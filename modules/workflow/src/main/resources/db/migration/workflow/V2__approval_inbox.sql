-- Full approval inbox: task types are registered by the owning modules (not a fixed list), tasks can be claimed and
-- assigned, carry a priority and a due date, escalate when overdue, and keep comments and an append-only history.

ALTER TABLE approval_task DROP CONSTRAINT approval_task_task_type_check;
ALTER TABLE approval_task DROP CONSTRAINT approval_task_status_check;
ALTER TABLE approval_task ADD CONSTRAINT approval_task_status_check
    CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'ACKNOWLEDGED', 'COMPLETED', 'CANCELLED'));

ALTER TABLE approval_task ADD COLUMN assignee TEXT;
ALTER TABLE approval_task ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE approval_task ADD COLUMN priority INT NOT NULL DEFAULT 50 CHECK (priority BETWEEN 0 AND 100);
ALTER TABLE approval_task ADD COLUMN due_at TIMESTAMPTZ;
UPDATE approval_task SET due_at = requested_at + INTERVAL '1 day' WHERE due_at IS NULL;
ALTER TABLE approval_task ALTER COLUMN due_at SET NOT NULL;
ALTER TABLE approval_task ADD COLUMN escalation_level INT NOT NULL DEFAULT 0;
ALTER TABLE approval_task ADD COLUMN decision TEXT;
ALTER TABLE approval_task ADD COLUMN candidate_roles TEXT[] NOT NULL DEFAULT '{}';

-- One open task per type and subject, so a trade can have an approval and a confirmation break open together.
DROP INDEX approval_task_open_subject_uq;
CREATE UNIQUE INDEX approval_task_open_subject_uq ON approval_task (task_type, subject_type, subject_ref) WHERE status = 'OPEN';
CREATE INDEX approval_task_due_idx ON approval_task (status, due_at, id);
CREATE INDEX approval_task_assignee_idx ON approval_task (assignee, status) WHERE assignee IS NOT NULL;
CREATE INDEX approval_task_subject_idx ON approval_task (subject_type, subject_ref);

-- Registered task types (synchronised from the owning modules at start-up), for reporting and administration.
CREATE TABLE task_type_definition (
    task_type          TEXT        PRIMARY KEY,
    owner_module       TEXT        NOT NULL,
    display_name       TEXT        NOT NULL,
    candidate_roles    TEXT[]      NOT NULL,
    four_eyes          BOOLEAN     NOT NULL,
    allowed_decisions  TEXT[]      NOT NULL,
    reason_required    TEXT[]      NOT NULL,
    default_sla_minutes INT        NOT NULL CHECK (default_sla_minutes > 0),
    escalation_roles   TEXT[]      NOT NULL,
    default_priority   INT         NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE TABLE task_comment (
    id         UUID        PRIMARY KEY,
    task_id    UUID        NOT NULL REFERENCES approval_task (id),
    author     TEXT        NOT NULL,
    body       TEXT        NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX task_comment_task_idx ON task_comment (task_id, created_at);

-- Append-only history per task, hash-chained per task for tamper evidence.
CREATE TABLE task_event (
    seq        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id    UUID        NOT NULL REFERENCES approval_task (id),
    event_type TEXT        NOT NULL,
    actor      TEXT        NOT NULL,
    at         TIMESTAMPTZ NOT NULL,
    data       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    prev_hash  TEXT,
    hash       TEXT        NOT NULL
);
CREATE INDEX task_event_task_idx ON task_event (task_id, seq);

CREATE OR REPLACE FUNCTION task_event_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'task_event is append-only';
END $$;
CREATE TRIGGER task_event_immutable BEFORE UPDATE OR DELETE ON task_event FOR EACH ROW EXECUTE FUNCTION task_event_immutable();
