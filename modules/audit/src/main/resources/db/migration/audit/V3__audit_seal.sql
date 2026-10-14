-- Tamper evidence for the audit trail (increment 14, review finding S6).
--
-- Every business transaction writes audit rows, so chaining them at insert would serialise all writes on one lock.
-- Instead a sealing job chains committed rows into audit_seal, in the order it seals them: each seal holds the
-- digest of one audit row and of the previous seal. A row is sealed within a minute or so of its commit; a row
-- changed or removed after that breaks the chain. A row whose transaction commits late is simply sealed later,
-- because the job looks for rows without a seal rather than for sequence numbers above a watermark.
--
-- Audit records also take the request's correlation id from the transaction when they carry none of their own
-- (`etrm.correlation`, set by the application's transaction manager).

ALTER TABLE audit_event ALTER COLUMN correlation_id SET DEFAULT nullif(current_setting('etrm.correlation', true), '');
CREATE INDEX audit_event_recorded_idx ON audit_event (recorded_at);

CREATE TABLE audit_seal (
    position    BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    audit_seq   BIGINT      NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    prev_hash   TEXT,
    hash        TEXT        NOT NULL,
    sealed_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT audit_seal_row_uq UNIQUE (audit_seq, occurred_at)
);

CREATE OR REPLACE FUNCTION audit_seal_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit seals are append-only';
END $$;
CREATE TRIGGER audit_seal_append_only BEFORE UPDATE OR DELETE ON audit_seal FOR EACH ROW EXECUTE FUNCTION audit_seal_append_only();

-- The digest of one audit row after the previous seal; times as epoch microseconds, so neither the session's time
-- zone nor its DateStyle changes the result.
CREATE OR REPLACE FUNCTION audit_event_digest(prev TEXT, a audit_event) RETURNS TEXT
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT encode(sha256(convert_to(coalesce(prev, '') || '|' || jsonb_build_object(
        'seq', a.seq,
        'eventId', a.event_id,
        'occurredMicros', (extract(epoch FROM a.occurred_at) * 1000000)::bigint,
        'recordedMicros', (extract(epoch FROM a.recorded_at) * 1000000)::bigint,
        'actor', a.actor,
        'type', a.event_type,
        'aggregateType', a.aggregate_type,
        'aggregateRef', a.aggregate_ref,
        'correlation', a.correlation_id,
        'payload', a.payload
    )::text, 'UTF8')), 'hex')
$$;

-- Seals up to max_rows committed rows that have none, oldest first; returns how many it sealed. Runs under an
-- advisory lock, so two callers never extend the chain from the same head. Rows recorded more than `horizon` ago
-- are not looked for (they were sealed long ago, or are reported as unsealed by the verification).
CREATE OR REPLACE FUNCTION audit_seal_pending(max_rows INT, horizon INTERVAL DEFAULT INTERVAL '2 days') RETURNS INT
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    head  TEXT;
    a     audit_event;
    n     INT := 0;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('etrm_audit.audit_seal'));
    SELECT hash INTO head FROM audit_seal ORDER BY position DESC LIMIT 1;
    FOR a IN
        SELECT e.* FROM audit_event e
        WHERE e.recorded_at > clock_timestamp() - horizon
          AND NOT EXISTS (SELECT 1 FROM audit_seal s WHERE s.audit_seq = e.seq AND s.occurred_at = e.occurred_at)
        ORDER BY e.seq
        LIMIT max_rows
    LOOP
        INSERT INTO audit_seal (audit_seq, occurred_at, prev_hash, hash)
        VALUES (a.seq, a.occurred_at, head, audit_event_digest(head, a));
        head := audit_event_digest(head, a);
        n := n + 1;
    END LOOP;
    RETURN n;
END $$;

-- Seals whose link or digest does not verify, or whose audit row is gone.
CREATE OR REPLACE FUNCTION audit_seal_broken() RETURNS TABLE (seal_position BIGINT, audit_seq BIGINT)
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT c.position, c.audit_seq FROM (
        SELECT s.position, s.audit_seq, s.prev_hash, s.hash,
               lag(s.hash) OVER (ORDER BY s.position) AS expected_prev,
               (SELECT audit_event_digest(s.prev_hash, e) FROM audit_event e
                 WHERE e.seq = s.audit_seq AND e.occurred_at = s.occurred_at) AS recomputed
        FROM audit_seal s
    ) c
    WHERE c.prev_hash IS DISTINCT FROM c.expected_prev OR c.recomputed IS NULL OR c.hash <> c.recomputed
    ORDER BY c.position
$$;

-- Existing rows are sealed now, in sequence order.
SELECT audit_seal_pending(2147483647, INTERVAL '1000 years');
