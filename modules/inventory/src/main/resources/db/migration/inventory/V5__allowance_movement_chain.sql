-- Allowance movements are append-only and hash-chained per holding (increment 14, review finding D3).
--
-- The digest is computed in the database, by one function that both the insert trigger and the verification use, so
-- the two can never disagree. It covers every column as written; jsonb text output is canonical and dates and times
-- are rendered independently of the session's DateStyle and time zone.
ALTER TABLE allowance_movement ADD COLUMN prev_hash TEXT;
ALTER TABLE allowance_movement ADD COLUMN hash TEXT;

CREATE OR REPLACE FUNCTION allowance_movement_digest(prev TEXT, m allowance_movement) RETURNS TEXT
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT encode(sha256(convert_to(coalesce(prev, '') || '|' || jsonb_build_object(
        'seq', m.seq,
        'holding', m.holding_id,
        'type', m.movement_type,
        'quantity', m.quantity,
        'effective', to_char(m.effective_date, 'YYYY-MM-DD'),
        'reference', m.reference,
        'cost', m.cost_per_unit::text,
        'costCurrency', m.cost_currency,
        'year', m.compliance_year,
        'actor', m.actor,
        'correlation', m.correlation_id,
        'recordedMicros', (extract(epoch FROM m.recorded_at) * 1000000)::bigint,
        'payload', m.payload
    )::text, 'UTF8')), 'hex')
$$;

-- Existing movements are chained in sequence order before the trigger protects them.
DO $$
DECLARE
    r    allowance_movement;
    prev TEXT;
    last UUID;
BEGIN
    FOR r IN SELECT * FROM allowance_movement ORDER BY holding_id, seq LOOP
        IF last IS DISTINCT FROM r.holding_id THEN
            prev := NULL;
            last := r.holding_id;
        END IF;
        UPDATE allowance_movement SET prev_hash = prev, hash = allowance_movement_digest(prev, r) WHERE seq = r.seq;
        prev := allowance_movement_digest(prev, r);
    END LOOP;
END $$;

ALTER TABLE allowance_movement ALTER COLUMN hash SET NOT NULL;

-- Chains the new movement to the last one of its holding. The holding row is locked first, so two movements of one
-- holding cannot both link to the same predecessor.
CREATE OR REPLACE FUNCTION allowance_movement_chain() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    PERFORM 1 FROM allowance_holding WHERE id = NEW.holding_id FOR UPDATE;
    SELECT hash INTO NEW.prev_hash FROM allowance_movement WHERE holding_id = NEW.holding_id ORDER BY seq DESC LIMIT 1;
    NEW.hash := allowance_movement_digest(NEW.prev_hash, NEW);
    RETURN NEW;
END $$;
CREATE TRIGGER allowance_movement_chain BEFORE INSERT ON allowance_movement FOR EACH ROW EXECUTE FUNCTION allowance_movement_chain();

CREATE OR REPLACE FUNCTION allowance_movement_append_only() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    RAISE EXCEPTION 'allowance movements are append-only; record a correcting movement';
END $$;
CREATE TRIGGER allowance_movement_append_only BEFORE UPDATE OR DELETE ON allowance_movement
    FOR EACH ROW EXECUTE FUNCTION allowance_movement_append_only();
