-- The lot journal grows with every lot operation, so it is partitioned by month of recorded_at (transaction time,
-- ADR 0030). The primary key becomes (seq, recorded_at); seq stays unique because it comes from one identity
-- sequence, and the per-lot hash chain is unchanged. Partitions are named lot_event_pYYYYMM; the default partition
-- catches rows outside the partitions created so far, and the partition maintenance job keeps three months ahead.

CREATE TABLE lot_event_unpartitioned AS SELECT * FROM lot_event;
DROP TABLE lot_event;

CREATE TABLE lot_event (
    seq            BIGINT      GENERATED ALWAYS AS IDENTITY,
    lot_id         UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    effective_at   TIMESTAMPTZ NOT NULL,
    actor          TEXT        NOT NULL,
    correlation_id TEXT,
    payload        JSONB       NOT NULL,
    prev_hash      TEXT,
    hash           TEXT        NOT NULL,
    PRIMARY KEY (seq, recorded_at)
) PARTITION BY RANGE (recorded_at);
CREATE INDEX lot_event_lot_idx ON lot_event (lot_id, seq);
CREATE INDEX lot_event_recorded_idx ON lot_event (recorded_at);
CREATE TABLE lot_event_pdefault PARTITION OF lot_event DEFAULT;

-- Row triggers on a partitioned table are cloned to every partition, present and future.
CREATE TRIGGER lot_event_no_update BEFORE UPDATE OR DELETE ON lot_event FOR EACH ROW EXECUTE FUNCTION lot_event_immutable();

DO $$
DECLARE
    v_month DATE := date_trunc('month', LEAST(now(), coalesce((SELECT min(recorded_at) FROM lot_event_unpartitioned), now())) AT TIME ZONE 'UTC');
    v_last  DATE := date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '3 months';
BEGIN
    WHILE v_month <= v_last LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF lot_event FOR VALUES FROM (%L) TO (%L)',
                       'lot_event_p' || to_char(v_month, 'YYYYMM'),
                       v_month::text || ' 00:00:00+00', (v_month + INTERVAL '1 month')::date::text || ' 00:00:00+00');
        v_month := v_month + INTERVAL '1 month';
    END LOOP;
END $$;

INSERT INTO lot_event OVERRIDING SYSTEM VALUE SELECT * FROM lot_event_unpartitioned;
SELECT setval(pg_get_serial_sequence('lot_event', 'seq'), coalesce(max(seq), 0) + 1, false) FROM lot_event;
DROP TABLE lot_event_unpartitioned;
