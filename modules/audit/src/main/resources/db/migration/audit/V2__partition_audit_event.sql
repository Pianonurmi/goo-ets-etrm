-- The audit trail grows with every domain event, so it is partitioned by month of occurred_at (ADR 0030).
-- A partitioned table's unique keys must contain the partition key: the primary key is (seq, occurred_at), and the
-- idempotency key is (event_id, occurred_at). A redelivered event carries the same occurred_at, so a duplicate still
-- conflicts. Partitions are named audit_event_pYYYYMM. The default partition catches any row outside the
-- partitions created so far, and the partition maintenance job keeps three months ahead.

CREATE TABLE audit_event_unpartitioned AS SELECT * FROM audit_event;
DROP TABLE audit_event;

CREATE TABLE audit_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    event_id       UUID        NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    actor          TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    aggregate_type TEXT,
    aggregate_ref  TEXT,
    correlation_id TEXT,
    payload        JSONB       NOT NULL,
    PRIMARY KEY (seq, occurred_at),
    UNIQUE (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE INDEX audit_event_type_idx ON audit_event (event_type, occurred_at);
CREATE INDEX audit_event_aggregate_idx ON audit_event (aggregate_type, aggregate_ref);
CREATE INDEX audit_event_actor_idx ON audit_event (actor, occurred_at);
CREATE TABLE audit_event_pdefault PARTITION OF audit_event DEFAULT;

-- Row triggers on a partitioned table are cloned to every partition, present and future.
CREATE TRIGGER audit_event_immutable BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION audit_immutable();

DO $$
DECLARE
    v_month DATE := date_trunc('month', LEAST(now(), coalesce((SELECT min(occurred_at) FROM audit_event_unpartitioned), now())) AT TIME ZONE 'UTC');
    v_last  DATE := date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '3 months';
BEGIN
    WHILE v_month <= v_last LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF audit_event FOR VALUES FROM (%L) TO (%L)',
                       'audit_event_p' || to_char(v_month, 'YYYYMM'),
                       v_month::text || ' 00:00:00+00', (v_month + INTERVAL '1 month')::date::text || ' 00:00:00+00');
        v_month := v_month + INTERVAL '1 month';
    END LOOP;
END $$;

INSERT INTO audit_event OVERRIDING SYSTEM VALUE SELECT * FROM audit_event_unpartitioned;
SELECT setval(pg_get_serial_sequence('audit_event', 'seq'), coalesce(max(seq), 0) + 1, false) FROM audit_event;
DROP TABLE audit_event_unpartitioned;
