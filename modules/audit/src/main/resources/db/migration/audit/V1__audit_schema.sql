-- Append-only audit trail of every domain event and explicit command record, with actor and correlation id.

CREATE TABLE audit_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       UUID        NOT NULL UNIQUE,
    occurred_at    TIMESTAMPTZ NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor          TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    aggregate_type TEXT,
    aggregate_ref  TEXT,
    correlation_id TEXT,
    payload        JSONB       NOT NULL
);
CREATE INDEX audit_event_type_idx ON audit_event (event_type, occurred_at);
CREATE INDEX audit_event_aggregate_idx ON audit_event (aggregate_type, aggregate_ref);
CREATE INDEX audit_event_actor_idx ON audit_event (actor, occurred_at);

CREATE OR REPLACE FUNCTION audit_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END $$;
CREATE TRIGGER audit_event_immutable BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION audit_immutable();
