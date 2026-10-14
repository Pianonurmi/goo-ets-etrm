-- Reference data history is append-only (increment 14, review finding D9). The history trigger closes the system
-- period of the current row when the record changes; that is the only update allowed, and nothing is deleted.
CREATE OR REPLACE FUNCTION reference_history_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND upper_inf(OLD.sys_period) AND NOT upper_inf(NEW.sys_period)
        AND (isempty(NEW.sys_period) OR lower(NEW.sys_period) = lower(OLD.sys_period))
        AND (to_jsonb(NEW) - 'sys_period') = (to_jsonb(OLD) - 'sys_period')
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'reference data history is append-only';
END $$;
CREATE TRIGGER reference_history_append_only BEFORE UPDATE OR DELETE ON reference_history
    FOR EACH ROW EXECUTE FUNCTION reference_history_append_only();
