-- Reconciliation break workflow: one break per discrepancy across statements (fingerprint), a sighting per statement
-- that shows it, auto-clearing when a later statement no longer does, investigation and four-eyes acceptance.

ALTER TABLE reconciliation_break
    DROP CONSTRAINT reconciliation_break_status_check,
    ADD COLUMN registry_account_id     UUID,
    ADD COLUMN fingerprint             TEXT,
    ADD COLUMN first_seen_on           DATE,
    ADD COLUMN last_seen_on            DATE,
    ADD COLUMN assignee                TEXT,
    ADD COLUMN resolution_action       TEXT,
    ADD COLUMN acceptance_requested_by TEXT,
    ADD COLUMN acceptance_requested_at TIMESTAMPTZ;

UPDATE reconciliation_break b
SET registry_account_id = s.registry_account_id,
    first_seen_on       = s.statement_date,
    last_seen_on        = s.statement_date,
    fingerprint         = s.registry_account_id::text || '|' || b.break_type || '|' || coalesce(b.issuance_ref, b.product_code) || '|'
                          || coalesce(b.cert_no_start::text, '') || '-' || coalesce(b.cert_no_end::text, '')
                          || CASE WHEN b.break_type = 'BALANCE_MISMATCH' THEN '|' || (b.registry_quantity - b.internal_quantity)::text ELSE '' END
FROM registry_statement s
WHERE s.id = b.statement_id;

-- Before fingerprints, every reconciliation raised its own breaks; keep the earliest open one per discrepancy.
UPDATE reconciliation_break b
SET status = 'AUTO_CLEARED', resolution = 'duplicate of an earlier break (migration)', resolved_by = 'migration', resolved_at = now()
WHERE b.status = 'OPEN'
  AND EXISTS (SELECT 1 FROM reconciliation_break e
              WHERE e.fingerprint = b.fingerprint AND e.status = 'OPEN' AND (e.detected_at, e.id) < (b.detected_at, b.id));

-- Acceptances before four eyes were made by one person; mark them so the new rule can hold for every row.
UPDATE reconciliation_break SET acceptance_requested_by = 'migration' WHERE status = 'ACCEPTED';

ALTER TABLE reconciliation_break
    ALTER COLUMN registry_account_id SET NOT NULL,
    ALTER COLUMN fingerprint SET NOT NULL,
    ALTER COLUMN first_seen_on SET NOT NULL,
    ALTER COLUMN last_seen_on SET NOT NULL,
    ADD CONSTRAINT reconciliation_break_status_check
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'PENDING_ACCEPTANCE', 'RESOLVED', 'ACCEPTED', 'AUTO_CLEARED')),
    ADD CONSTRAINT reconciliation_break_four_eyes CHECK (
        status <> 'ACCEPTED'
        OR (acceptance_requested_by IS NOT NULL AND resolved_by IS NOT NULL AND resolved_by <> acceptance_requested_by)
    );

DROP INDEX reconciliation_break_open_idx;
CREATE UNIQUE INDEX reconciliation_break_open_fingerprint ON reconciliation_break (fingerprint)
    WHERE status IN ('OPEN', 'INVESTIGATING', 'PENDING_ACCEPTANCE');
CREATE INDEX reconciliation_break_account_idx ON reconciliation_break (registry_account_id, status);

CREATE TABLE reconciliation_break_sighting (
    break_id     UUID NOT NULL REFERENCES reconciliation_break (id) ON DELETE CASCADE,
    statement_id UUID NOT NULL REFERENCES registry_statement (id) ON DELETE CASCADE,
    PRIMARY KEY (break_id, statement_id)
);
CREATE INDEX reconciliation_break_sighting_statement_idx ON reconciliation_break_sighting (statement_id);
INSERT INTO reconciliation_break_sighting (break_id, statement_id) SELECT id, statement_id FROM reconciliation_break;
