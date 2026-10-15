-- The instruction's history keeps the cancellation without the beneficiary's name and identifier (increment 15,
-- review finding S7): the history is append-only and could not forget them. The reference to the beneficiary stays.
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
                without_beneficiary(to_jsonb(NEW) - 'lease_until'));
    END IF;
    RETURN NULL;
END $$;
