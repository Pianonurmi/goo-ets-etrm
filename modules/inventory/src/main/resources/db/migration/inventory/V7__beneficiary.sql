-- Cancellation beneficiaries (increment 15, review finding S7). Who a cancellation was made for is kept here once and
-- referenced by id from what must never change (the lot journal, the lot's version history, the audit trail), so the
-- name and identifier can be erased when nothing requires them any more.
CREATE TABLE beneficiary (
    id            UUID        NOT NULL PRIMARY KEY,
    name          TEXT,
    identifier    TEXT,
    recorded_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    recorded_by   TEXT,
    -- Erasure needs a second person: it is requested with a reason, then approved in the inbox.
    erasure_requested_at TIMESTAMPTZ,
    erasure_requested_by TEXT,
    erasure_reason       TEXT,
    erased_at            TIMESTAMPTZ,
    erased_by            TEXT,
    CONSTRAINT beneficiary_request_ck CHECK (erasure_requested_at IS NULL OR (erasure_requested_by IS NOT NULL AND erasure_reason IS NOT NULL)),
    CONSTRAINT beneficiary_erased_ck CHECK (erased_at IS NULL OR (name IS NULL AND identifier IS NULL AND erasure_requested_at IS NOT NULL AND erased_by IS NOT NULL))
);

-- A document without a beneficiary's name and identifier, at any depth; the beneficiaryRef stays. The application
-- applies the same rule to what it chains (PersonalData.redact).
CREATE OR REPLACE FUNCTION without_beneficiary(data JSONB) RETURNS JSONB LANGUAGE plpgsql IMMUTABLE SET search_path FROM CURRENT AS $$
BEGIN
    RETURN CASE jsonb_typeof(data)
        WHEN 'object' THEN (
            SELECT coalesce(jsonb_object_agg(key, without_beneficiary(value)), '{}'::jsonb)
            FROM jsonb_each(data)
            WHERE key NOT IN ('beneficiaryName', 'beneficiaryId'))
        WHEN 'array' THEN (
            SELECT coalesce(jsonb_agg(without_beneficiary(value) ORDER BY ord), '[]'::jsonb)
            FROM jsonb_array_elements(data) WITH ORDINALITY AS element(value, ord))
        ELSE data
    END;
END $$;

-- Lot versions keep the cancellation without the beneficiary: the version history is append-only and could not forget it.
CREATE OR REPLACE FUNCTION certificate_lot_version_trigger() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    v_now TIMESTAMPTZ := clock_timestamp();
    v_effective TIMESTAMPTZ := NEW.effective_at;
    r RECORD;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        FOR r IN SELECT * FROM certificate_lot_version WHERE lot_id = OLD.id AND upper_inf(sys_period) LOOP
            UPDATE certificate_lot_version SET sys_period = tstzrange(lower(sys_period), v_now, '[)') WHERE id = r.id;
            -- The superseded version stays our knowledge for valid times before the change became effective.
            IF lower(r.valid_period) < v_effective AND (upper_inf(r.valid_period) OR upper(r.valid_period) > v_effective) THEN
                INSERT INTO certificate_lot_version (lot_id, version, sys_period, valid_period, event_seq, actor, data)
                VALUES (r.lot_id, r.version, tstzrange(v_now, NULL, '[)'), tstzrange(lower(r.valid_period), v_effective, '[)'), r.event_seq, r.actor, r.data);
            END IF;
        END LOOP;
    END IF;
    INSERT INTO certificate_lot_version (lot_id, version, sys_period, valid_period, event_seq, actor, data)
    VALUES (NEW.id, NEW.version, tstzrange(v_now, NULL, '[)'), tstzrange(v_effective, NULL, '[)'), NEW.updated_seq, NEW.updated_by,
            without_beneficiary(to_jsonb(NEW)));
    RETURN NEW;
END $$;
