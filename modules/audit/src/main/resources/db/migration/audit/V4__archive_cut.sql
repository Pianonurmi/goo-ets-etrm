-- Archiving old partitions (increment 15, review finding S7). The trail is one seal chain across its monthly
-- partitions, so a partition past its retention could not be dropped: its rows' seals would no longer verify. An
-- archive cut declares that rows which occurred before it may be gone. The seals stay, so the chain's links still
-- verify end to end, and every row from the cut on must still be there and match its seal.
CREATE TABLE audit_archive_cut (
    cut         TIMESTAMPTZ NOT NULL PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    recorded_by TEXT        NOT NULL,
    reason      TEXT        NOT NULL,
    CONSTRAINT audit_archive_cut_month_ck CHECK (cut = date_trunc('month', cut AT TIME ZONE 'UTC') AT TIME ZONE 'UTC')
);

CREATE OR REPLACE FUNCTION audit_archive_cut_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'archive cuts are append-only';
END $$;
CREATE TRIGGER audit_archive_cut_append_only BEFORE UPDATE OR DELETE ON audit_archive_cut
    FOR EACH ROW EXECUTE FUNCTION audit_archive_cut_append_only();

-- Seals whose link or digest does not verify, or whose audit row is gone although it occurred after the latest cut.
CREATE OR REPLACE FUNCTION audit_seal_broken() RETURNS TABLE (seal_position BIGINT, audit_seq BIGINT)
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT c.position, c.audit_seq FROM (
        SELECT s.position, s.audit_seq, s.occurred_at, s.prev_hash, s.hash,
               lag(s.hash) OVER (ORDER BY s.position) AS expected_prev,
               (SELECT audit_event_digest(s.prev_hash, e) FROM audit_event e
                 WHERE e.seq = s.audit_seq AND e.occurred_at = s.occurred_at) AS recomputed
        FROM audit_seal s
    ) c
    WHERE c.prev_hash IS DISTINCT FROM c.expected_prev
       OR (c.recomputed IS NULL AND c.occurred_at >= (SELECT coalesce(max(cut), '-infinity'::timestamptz) FROM audit_archive_cut))
       OR c.hash <> c.recomputed
    ORDER BY c.position
$$;
