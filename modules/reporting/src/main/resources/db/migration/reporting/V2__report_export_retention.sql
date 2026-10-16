-- Report export content is kept for the retention period (etrm.reporting.retention, 400 days by default); after it
-- the content is dropped while the export's number, parameters, row count and hash stay, so the export log still
-- proves what was handed out. The append-only trigger allows that one change and nothing else.
ALTER TABLE report_export ALTER COLUMN content DROP NOT NULL;
ALTER TABLE report_export ADD COLUMN content_expired_at TIMESTAMPTZ;
ALTER TABLE report_export ADD CONSTRAINT report_export_content_or_expired CHECK (content IS NOT NULL OR content_expired_at IS NOT NULL);

CREATE OR REPLACE FUNCTION report_export_retention_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE'
        AND OLD.content IS NOT NULL AND NEW.content IS NULL
        AND OLD.content_expired_at IS NULL AND NEW.content_expired_at IS NOT NULL
        AND (NEW.id, NEW.report_code, NEW.legal_entity_id, NEW.seq, NEW.format, NEW.parameters, NEW.row_count,
             NEW.content_hash, NEW.exported_by, NEW.exported_at)
            IS NOT DISTINCT FROM
            (OLD.id, OLD.report_code, OLD.legal_entity_id, OLD.seq, OLD.format, OLD.parameters, OLD.row_count,
             OLD.content_hash, OLD.exported_by, OLD.exported_at)
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'report_export is append-only: only the content of an export past its retention may be dropped';
END $$;

DROP TRIGGER report_export_append_only ON report_export;
CREATE TRIGGER report_export_append_only BEFORE UPDATE OR DELETE ON report_export
    FOR EACH ROW EXECUTE FUNCTION report_export_retention_only();
