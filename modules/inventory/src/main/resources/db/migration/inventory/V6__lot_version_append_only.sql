-- Lot versions are append-only (increment 14, review finding D9). The versioning trigger closes the system period of
-- the current version when the lot changes; that is the only update allowed, and nothing is deleted.
CREATE OR REPLACE FUNCTION certificate_lot_version_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND upper_inf(OLD.sys_period) AND NOT upper_inf(NEW.sys_period)
        AND (isempty(NEW.sys_period) OR lower(NEW.sys_period) = lower(OLD.sys_period))
        AND (to_jsonb(NEW) - 'sys_period') = (to_jsonb(OLD) - 'sys_period')
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'certificate lot versions are append-only';
END $$;
CREATE TRIGGER certificate_lot_version_append_only BEFORE UPDATE OR DELETE ON certificate_lot_version
    FOR EACH ROW EXECUTE FUNCTION certificate_lot_version_append_only();
