-- The life of every registry instruction, kept append-only (increment 14, review finding D9). The instruction row
-- holds the current state and is updated by the outbox dispatcher; this table records each state it passed through:
-- creation, every status change, attempt, external reference, error and registry response.
CREATE TABLE registry_instruction_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instruction_id UUID        NOT NULL,
    status         TEXT        NOT NULL,
    attempts       INT         NOT NULL,
    external_ref   TEXT,
    last_error     TEXT,
    actor          TEXT,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    data           JSONB       NOT NULL
);
CREATE INDEX registry_instruction_event_idx ON registry_instruction_event (instruction_id, seq);

CREATE OR REPLACE FUNCTION registry_instruction_event_record() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'INSERT'
        OR OLD.status IS DISTINCT FROM NEW.status
        OR OLD.attempts IS DISTINCT FROM NEW.attempts
        OR OLD.external_ref IS DISTINCT FROM NEW.external_ref
        OR OLD.last_error IS DISTINCT FROM NEW.last_error
        OR OLD.response_payload IS DISTINCT FROM NEW.response_payload
        OR OLD.approved_by IS DISTINCT FROM NEW.approved_by
        OR OLD.confirmed_by IS DISTINCT FROM NEW.confirmed_by
    THEN
        INSERT INTO registry_instruction_event (instruction_id, status, attempts, external_ref, last_error, actor, data)
        VALUES (NEW.id, NEW.status, NEW.attempts, NEW.external_ref, NEW.last_error, current_setting('etrm.actor', true),
                to_jsonb(NEW) - 'lease_until');
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER registry_instruction_event_record AFTER INSERT OR UPDATE ON registry_instruction
    FOR EACH ROW EXECUTE FUNCTION registry_instruction_event_record();

CREATE OR REPLACE FUNCTION registry_instruction_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'registry instruction events are append-only';
END $$;
CREATE TRIGGER registry_instruction_event_append_only BEFORE UPDATE OR DELETE ON registry_instruction_event
    FOR EACH ROW EXECUTE FUNCTION registry_instruction_event_append_only();

-- Instructions written before this version start their history with their current state.
INSERT INTO registry_instruction_event (instruction_id, status, attempts, external_ref, last_error, actor, recorded_at, data)
SELECT id, status, attempts, external_ref, last_error, submitted_by, created_at, to_jsonb(r) - 'lease_until'
FROM registry_instruction r
ORDER BY created_at;
