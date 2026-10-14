-- Trade versions are append-only (increment 14, review finding D9): a version is superseded once, by setting
-- superseded_at, and otherwise never changes or disappears.
CREATE OR REPLACE FUNCTION trade_version_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.superseded_at IS NULL AND NEW.superseded_at IS NOT NULL
        AND (to_jsonb(NEW) - 'superseded_at') = (to_jsonb(OLD) - 'superseded_at')
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'trade versions are append-only';
END $$;
CREATE TRIGGER trade_version_append_only BEFORE UPDATE OR DELETE ON trade_version
    FOR EACH ROW EXECUTE FUNCTION trade_version_append_only();
