-- Archiving old lot journal partitions (increment 15, review finding S7). Each lot's events form one hash chain, so
-- dropping a month's partition would leave the first remaining event pointing at a hash that is gone. Before the
-- partitions older than a cut are dropped, the hash of each lot's last event before the cut is kept here: the
-- verification accepts it as the start of the remaining chain.
CREATE TABLE lot_event_anchor (
    lot_id      UUID        NOT NULL,
    cut         TIMESTAMPTZ NOT NULL,
    hash        TEXT        NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (lot_id, cut)
);

CREATE OR REPLACE FUNCTION lot_event_anchor_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'lot event anchors are append-only';
END $$;
CREATE TRIGGER lot_event_anchor_append_only BEFORE UPDATE OR DELETE ON lot_event_anchor
    FOR EACH ROW EXECUTE FUNCTION lot_event_anchor_append_only();
